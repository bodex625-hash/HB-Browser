package com.hb.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumProfile
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val gold = Color.rgb(212, 175, 55)
    private val black = Color.rgb(9, 10, 12)
    private val panel = Color.rgb(23, 25, 29)
    private val muted = Color.rgb(155, 160, 168)
    private val fixedZoomPercent = 80

    private lateinit var address: EditText
    private lateinit var webContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var tabStrip: LinearLayout
    private lateinit var profile: CefriumProfile
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTab: BrowserTab? = null
    private var nextTabId = 1
    private val prefs by lazy { getSharedPreferences("hb_browser", Context.MODE_PRIVATE) }
    private val licenseExecutor = Executors.newSingleThreadExecutor()
    private val proxyExecutor = Executors.newSingleThreadExecutor()
    private val proxyPermissionRequest = 901
    private val licenseHandler = Handler(Looper.getMainLooper())
    private val licenseCheck = object : Runnable {
        override fun run() {
            licenseExecutor.execute {
                val state = ActivationManager.check(this@MainActivity)
                if (state != ActivationManager.State.ACTIVE &&
                    state != ActivationManager.State.OFFLINE_GRACE
                ) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            startActivity(android.content.Intent(this@MainActivity, ActivationActivity::class.java))
                            finish()
                        }
                    }
                }
            }
            licenseHandler.postDelayed(this, 6L * 60 * 60 * 1000)
        }
    }

    private data class BrowserTab(
        val id: Int,
        val browser: CefriumBrowser,
        var title: String = "تبويب جديد",
        var url: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black
        window.navigationBarColor = black
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        profile = CefriumProfile.getDefault()
        profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT)
        restoreSavedProxy()

        buildInterface()
        createNewTab()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, strokeColor: Int? = null, radius: Int = 14): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
            setPadding(dp(8), dp(7), dp(8), dp(3))
        }

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brand = TextView(this).apply {
            text = "HB"
            textSize = 18f
            setTextColor(gold)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(panel, gold, 13)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(46)).apply {
                marginEnd = dp(7)
            }
        }

        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            hint = "ابحث أو أدخل عنوان موقع"
            setSelectAllOnFocus(true)
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(panel, Color.rgb(75, 65, 39), 18)
            imeOptions = EditorInfo.IME_ACTION_GO
            textDirection = View.TEXT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(7)
            }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    loadAddress(text.toString())
                    true
                } else false
            }
        }

        addressRow.addView(brand)
        addressRow.addView(address)
        addressRow.addView(makeAction("⋮", 42) { showMenu() })
        root.addView(addressRow)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(43)).apply {
                topMargin = dp(5)
                bottomMargin = dp(2)
            }
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll.addView(tabStrip)
        tabRow.addView(tabScroll)
        tabRow.addView(makeAction("+", 42, 23) { createNewTab() })
        root.addView(tabRow)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(5))
        }
        nav.addView(makeAction("‹", 40, 20) {
            activeTab?.browser?.let { if (it.canGoBack()) it.goBack() }
        })
        nav.addView(makeAction("›", 40, 20) {
            activeTab?.browser?.goForward()
        })
        nav.addView(makeAction("↻", 40, 18) { activeTab?.browser?.reload() })

        root.addView(nav)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(gold)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(2))
        }
        root.addView(progress)

        webContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        root.addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun makeAction(
        label: String,
        width: Int,
        textSize: Int = 19,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            this.textSize = textSize.toFloat()
            setTextColor(gold)
            gravity = Gravity.CENTER
            background = rounded(panel, Color.rgb(48, 45, 36), 13)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(width), dp(38)).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { action() }
        }
    }

    private fun createNewTab(initialUrl: String = "https://www.google.com") {
        val instance = CefriumBrowser.createWithSurface(this, profile)
        instance.setJavaScriptEnabled(true)
        instance.setPinchToZoomEnabled(false)
        instance.setZoomLevel(zoomPercentToLevel(fixedZoomPercent))

        val tab = BrowserTab(nextTabId++, instance)
        configureTab(tab)

        tabs.add(tab)
        webContainer.addView(
            instance.surfaceContainer,
            FrameLayout.LayoutParams(-1, -1)
        )
        selectTab(tab)
        instance.loadUrl(initialUrl)
    }

    private fun configureTab(tab: BrowserTab) {
        val browser = tab.browser
        browser.setOnUrlChangedListener { url ->
            runOnUiThread {
                tab.url = url
                if (activeTab?.id == tab.id && !isFinishing) address.setText(url)
                refreshTabStrip()
            }
        }
        browser.setOnTitleChangedListener { title ->
            runOnUiThread {
                if (title.isNotBlank()) tab.title = title
                refreshTabStrip()
            }
        }
        browser.setOnLoadingStateChangedListener { isLoading, _, _ ->
            runOnUiThread {
                if (!isLoading && !isFinishing) {
                    // Re-apply the fixed zoom after every navigation, including survey pages.
                    browser.setZoomLevel(zoomPercentToLevel(fixedZoomPercent))
                }
                if (activeTab?.id == tab.id && !isFinishing) {
                    progress.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }
        browser.setPopupHandler(object : CefriumBrowser.PopupHandler {
            override fun onCreateWindow(
                popup: CefriumBrowser,
                targetUrl: String,
                userGesture: Boolean
            ): Boolean {
                runOnUiThread {
                    val popupTab = BrowserTab(nextTabId++, popup, url = targetUrl)
                    configureTab(popupTab)
                    tabs.add(popupTab)
                    webContainer.addView(
                        popup.surfaceContainer,
                        FrameLayout.LayoutParams(-1, -1)
                    )
                    selectTab(popupTab)
                }
                return true
            }

            override fun onCloseWindow(popup: CefriumBrowser) {
                runOnUiThread { removePopupTab(popup) }
            }

            override fun onOpenPopup(targetUrl: String, userGesture: Boolean): Boolean {
                // Keep unhandled popup navigation inside this browser, never launch Chrome.
                return false
            }
        })
    }

    private fun removePopupTab(popup: CefriumBrowser) {
        val tab = tabs.firstOrNull { it.browser === popup } ?: return
        val index = tabs.indexOf(tab)
        val wasActive = activeTab?.id == tab.id
        webContainer.removeView(popup.surfaceContainer)
        tabs.remove(tab)
        if (wasActive) {
            if (tabs.isEmpty()) {
                activeTab = null
                createNewTab()
            } else {
                selectTab(tabs[(index - 1).coerceAtLeast(0)])
            }
        } else {
            refreshTabStrip()
        }
    }

    private fun selectTab(tab: BrowserTab) {
        activeTab = tab
        tabs.forEach { item ->
            val selected = item.id == tab.id
            item.browser.surfaceContainer.visibility = if (selected) View.VISIBLE else View.GONE
            item.browser.setPageVisible(selected)
        }
        address.setText(tab.url)
        progress.visibility = View.GONE
        refreshTabStrip()
    }

    private fun refreshTabStrip() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        tabs.forEach { tab ->
            val selected = activeTab?.id == tab.id
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(
                    if (selected) Color.rgb(48, 42, 25) else panel,
                    if (selected) gold else Color.rgb(48, 45, 36),
                    11
                )
                setPadding(dp(9), 0, dp(5), 0)
                layoutParams = LinearLayout.LayoutParams(dp(142), dp(34)).apply {
                    marginEnd = dp(5)
                }
                isClickable = true
                setOnClickListener { selectTab(tab) }
            }
            val label = TextView(this).apply {
                text = tab.title.ifBlank { "تبويب جديد" }
                textSize = 11f
                setTextColor(if (selected) gold else Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                isClickable = false
            }
            val close = TextView(this).apply {
                text = "×"
                textSize = 19f
                setTextColor(muted)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(25), -1)
                setOnClickListener { closeTab(tab) }
            }
            chip.addView(label)
            chip.addView(close)
            tabStrip.addView(chip)
        }
    }

    private fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return
        val wasActive = activeTab?.id == tab.id
        tab.browser.flushCookies()
        tab.browser.close()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            activeTab = null
            createNewTab()
            return
        }
        if (wasActive) {
            val next = tabs[(index - 1).coerceAtLeast(0)]
            selectTab(next)
        } else {
            refreshTabStrip()
        }
    }

    private fun loadAddress(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        val url = when {
            value.contains(" ") || (!value.contains(".") && !value.startsWith("http")) ->
                "https://www.google.com/search?q=" + Uri.encode(value)
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> "https://$value"
        }
        activeTab?.browser?.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun showMenu() {
        val items = arrayOf(
            "إضافة للمفضلة",
            "عرض المفضلة",
            "مسح جميع بيانات التصفح",
            "إعداد بروكسي SOCKS5",
            "حول HB Browser"
        )
        AlertDialog.Builder(this)
            .setTitle("HB Browser")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveBookmark()
                    1 -> showBookmarks()
                    2 -> confirmClearAllData()
                    3 -> showProxyDialog()
                    4 -> showAboutDialog()
                }
            }
            .show()
    }


    private fun restoreSavedProxy() {
        profile.clearProxy()
        profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT)
        val saved = ProxyCredentialStore.load(this) ?: return
        if (saved.size >= 4 && HBProxyVpnController.permissionIntent(this) == null) {
            HBProxyVpnController.start(this)
        }
    }

    private fun showProxyDialog() {
        val current = ProxyCredentialStore.load(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hintText: String, value: String = "", password: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                setSingleLine(true)
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(panel, Color.rgb(75, 65, 39), 12)
                if (value.isNotEmpty()) setText(value)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
                    bottomMargin = dp(8)
                }
            }
        }
        val host = field("عنوان البروكسي أو IP", current?.getOrNull(0) ?: "")
        val port = field("Port", current?.getOrNull(1) ?: "", false).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val username = field("اسم المستخدم (اختياري)", current?.getOrNull(2) ?: "")
        val password = field("كلمة المرور (اختياري)", current?.getOrNull(3) ?: "", true)
        val status = TextView(this).apply {
            text = "يدعم SOCKS5 مع أو بدون مصادقة"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(4))
        }
        form.addView(host)
        form.addView(port)
        form.addView(username)
        form.addView(password)
        form.addView(status)

        val dialog = AlertDialog.Builder(this)
            .setTitle("إعداد بروكسي SOCKS5")
            .setView(form)
            .setNegativeButton("إغلاق", null)
            .setNeutralButton("اختبار الاتصال", null)
            .setPositiveButton("حفظ وتشغيل", null)
            .create()
        form.addView(makeAction("إيقاف البروكسي", 160, 14) {
            disableProxy()
            dialog.dismiss()
        })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull()
                val u = username.text.toString()
                val pw = password.text.toString()
                if (h.isBlank() || p == null || p !in 1..65535) {
                    status.text = "تأكد من العنوان والمنفذ"
                    status.setTextColor(Color.RED)
                    return@setOnClickListener
                }
                val ok = activateProxy(h, p, u, pw, true)
                status.text = if (ok) "تم حفظ البروكسي وتشغيله" else "تعذر تشغيل البروكسي"
                status.setTextColor(if (ok) gold else Color.RED)
                if (ok) dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull()
                val u = username.text.toString()
                val pw = password.text.toString()
                if (h.isBlank() || p == null || p !in 1..65535) {
                    status.text = "أدخل عنواناً ومنفذاً صحيحين أولاً"
                    status.setTextColor(Color.RED)
                    return@setOnClickListener
                }
                status.text = "جارٍ اختبار الاتصال..."
                status.setTextColor(gold)
                proxyExecutor.execute {
                    val testBridge = Socks5ProxyBridge(h, p, u, pw)
                    val result = try {
                        testBridge.start()
                        testBridge.testConnection()
                    } catch (_: Exception) { false } finally {
                        testBridge.stop()
                    }
                    runOnUiThread {
                        if (!isFinishing && dialog.isShowing) {
                            status.text = if (result) "نجح الاتصال بالبروكسي" else "فشل الاتصال؛ تحقق من البيانات أو نوع البروكسي"
                            status.setTextColor(if (result) gold else Color.RED)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun activateProxy(host: String, port: Int, user: String, pass: String, save: Boolean): Boolean {
        return try {
            profile.clearProxy()
            profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT)
            if (save) ProxyCredentialStore.save(this, host, port, user, pass)
            val permission = HBProxyVpnController.permissionIntent(this)
            if (permission != null) {
                startActivityForResult(permission, proxyPermissionRequest)
            } else {
                HBProxyVpnController.start(this)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun disableProxy() {
        profile.clearProxy()
        profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT)
        HBProxyVpnController.stop(this)
        ProxyCredentialStore.clear(this)
        Toast.makeText(this, "تم إيقاف البروكسي", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearAllData() {
        AlertDialog.Builder(this)
            .setTitle("مسح جميع بيانات التصفح؟")
            .setMessage("سيتم إغلاق كل علامات التبويب ومسح الكاش والكوكيز وبيانات المواقع وسجل التنقل المفتوح. لا يمكن التراجع عن هذه العملية.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("مسح الكل") { _, _ -> clearAllBrowsingData() }
            .show()
    }

    private fun clearAllBrowsingData() {
        progress.visibility = View.GONE
        tabs.toList().forEach { tab ->
            tab.browser.clearCache()
            tab.browser.flushCookies()
            tab.browser.close()
        }
        tabs.clear()
        activeTab = null
        webContainer.removeAllViews()
        refreshTabStrip()

        profile.listSiteData { sites ->
            clearSiteDataSequentially(sites, 0)
        }
    }

    private fun clearSiteDataSequentially(
        sites: List<CefriumProfile.SiteData>,
        index: Int
    ) {
        if (index >= sites.size) {
            createNewTab()
            Toast.makeText(
                this,
                "تم مسح الكاش والكوكيز وبيانات المواقع وإغلاق كل التبويبات",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        profile.clearSiteData(sites[index].site) {
            clearSiteDataSequentially(sites, index + 1)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("حول HB Browser")
            .setMessage(
                "HB Browser\n" +
                    "برمجة BODEX و HADI\n" +
                    "قيد التطوير"
            )
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun zoomPercentToLevel(percent: Int): Double =
        kotlin.math.ln(percent / 100.0) / kotlin.math.ln(1.2)

    private fun saveBookmark() {
        val url = activeTab?.browser?.url ?: return
        val all = prefs.getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        all.add("$url|$url")
        prefs.edit().putStringSet("bookmarks", all).apply()
        Toast.makeText(this, "تمت الإضافة للمفضلة", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        val all = prefs.getStringSet("bookmarks", emptySet())?.toList() ?: emptyList()
        if (all.isEmpty()) {
            Toast.makeText(this, "لا توجد صفحات محفوظة", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("المفضلة")
            .setItems(all.map { it.substringBefore("|") }.toTypedArray()) { _, which ->
                val url = all[which].substringAfter("|", "")
                if (url.isNotEmpty()) activeTab?.browser?.loadUrl(url)
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    override fun onPause() {
        licenseHandler.removeCallbacks(licenseCheck)
        tabs.forEach { it.browser.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        licenseHandler.removeCallbacks(licenseCheck)
        licenseCheck.run()
        tabs.forEach { tab ->
            tab.browser.onResume()
            tab.browser.setPageVisible(activeTab?.id == tab.id)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == proxyPermissionRequest) {
            if (resultCode == RESULT_OK) {
                HBProxyVpnController.start(this)
                Toast.makeText(this, "تم السماح بتشغيل نفق البروكسي", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "لم يتم منح إذن VPN؛ لم يبدأ النفق", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val current = activeTab?.browser
        if (current?.canGoBack() == true) current.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.toList().forEach {
            it.browser.flushCookies()
            it.browser.close()
        }
        tabs.clear()
        activeTab = null
        licenseHandler.removeCallbacks(licenseCheck)
        licenseExecutor.shutdownNow()
        proxyExecutor.shutdownNow()
        super.onDestroy()
    }
}
