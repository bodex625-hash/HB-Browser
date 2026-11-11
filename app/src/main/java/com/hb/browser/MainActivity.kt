package com.hb.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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

class MainActivity : Activity() {
    private val gold = Color.rgb(212, 175, 55)
    private val black = Color.rgb(9, 10, 12)
    private val panel = Color.rgb(23, 25, 29)
    private val muted = Color.rgb(155, 160, 168)

    private lateinit var address: EditText
    private lateinit var webContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var tabStrip: LinearLayout
    private lateinit var profile: CefriumProfile
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTab: BrowserTab? = null
    private var nextTabId = 1
    private val prefs by lazy { getSharedPreferences("hb_browser", Context.MODE_PRIVATE) }

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
            activeTab?.browser?.let { if (it.canGoForward()) it.goForward() }
        })
        nav.addView(makeAction("↻", 40, 18) { activeTab?.browser?.reload() })

        val engineLabel = TextView(this).apply {
            text = "CHROMIUM"
            setTextColor(gold)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        nav.addView(engineLabel)
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
        instance.setPinchToZoomEnabled(true)
        instance.setZoomLevel(zoomPercentToLevel(prefs.getInt("page_zoom", 80)))

        val tab = BrowserTab(nextTabId++, instance)
        instance.setOnUrlChangedListener { url ->
            runOnUiThread {
                tab.url = url
                if (activeTab?.id == tab.id && !isFinishing) address.setText(url)
                refreshTabStrip()
            }
        }
        instance.setOnTitleChangedListener { title ->
            runOnUiThread {
                if (title.isNotBlank()) tab.title = title
                refreshTabStrip()
            }
        }
        instance.setOnLoadingStateChangedListener { isLoading, _, _ ->
            runOnUiThread {
                if (activeTab?.id == tab.id && !isFinishing) {
                    progress.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }

        tabs.add(tab)
        webContainer.addView(
            instance.surfaceContainer,
            FrameLayout.LayoutParams(-1, -1)
        )
        selectTab(tab)
        instance.loadUrl(initialUrl)
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
            "تغيير زوم الصفحة",
            "حول HB Browser"
        )
        AlertDialog.Builder(this)
            .setTitle("HB Browser")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveBookmark()
                    1 -> showBookmarks()
                    2 -> confirmClearAllData()
                    3 -> showPageZoomDialog()
                    4 -> showAboutDialog()
                }
            }
            .show()
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

    private fun showPageZoomDialog() {
        val levels = intArrayOf(80, 90, 100, 110, 125)
        val current = prefs.getInt("page_zoom", 80)
        val selected = levels.indexOf(current).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle("زوم صفحات الإنترنت")
            .setSingleChoiceItems(levels.map { "$it%" }.toTypedArray(), selected) { dialog, which ->
                val percent = levels[which]
                prefs.edit().putInt("page_zoom", percent).apply()
                tabs.forEach { it.browser.setZoomLevel(zoomPercentToLevel(percent)) }
                Toast.makeText(this, "تم ضبط زوم الصفحة على $percent%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

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
        tabs.forEach { it.browser.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.browser.onResume() }
        activeTab?.browser?.setPageVisible(true)
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
        super.onDestroy()
    }
}
