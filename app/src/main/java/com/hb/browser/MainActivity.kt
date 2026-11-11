package com.hb.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.net.Uri

class MainActivity : Activity() {
    private val gold = Color.rgb(212, 175, 55)
    private val black = Color.rgb(9, 10, 12)
    private val panel = Color.rgb(23, 25, 29)
    private val muted = Color.rgb(155, 160, 168)
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var webContainer: LinearLayout
    private lateinit var tabLabel: TextView
    private lateinit var progress: ProgressBar
    private val tabs = mutableListOf<WebView>()
    private var activeTab = 0
    private val prefs by lazy { getSharedPreferences("hb_browser", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black
        window.navigationBarColor = black
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        buildInterface()
        addTab("https://www.google.com")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, strokeColor: Int? = null, radius: Int = 14): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
            setPadding(dp(8), dp(7), dp(8), dp(3))
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(7) }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    loadAddress(text.toString())
                    true
                } else false
            }
        }
        val brand = TextView(this).apply {
            text = "HB"
            textSize = 18f
            setTextColor(gold)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(panel, gold, 13)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(46)).apply { marginEnd = dp(7) }
            contentDescription = "HB Browser"
        }
        addressRow.addView(brand)
        addressRow.addView(address)
        addressRow.addView(makeAction("＋", 42) { addTab("https://www.google.com") })
        addressRow.addView(makeAction("⋮", 42) { showMenu() }.apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(5) }
        })
        root.addView(addressRow)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        nav.addView(makeAction("‹", 38, 20) { if (currentWeb()?.canGoBack() == true) currentWeb()?.goBack() })
        nav.addView(makeAction("›", 38, 20) { if (currentWeb()?.canGoForward() == true) currentWeb()?.goForward() })
        nav.addView(makeAction("↻", 38, 18) { currentWeb()?.reload() })
        nav.addView(makeAction("⌫", 38, 16) { clearAllHistory() })
        tabLabel = TextView(this).apply {
            setTextColor(gold)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "1 تبويب"
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        nav.addView(tabLabel)
        nav.addView(makeAction("▢", 38, 17) { showTabs() })
        root.addView(nav)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(panel)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(2))
        }
        root.addView(progress)

        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun makeAction(label: String, width: Int, textSize: Int = 19, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            this.textSize = textSize.toFloat()
            setTextColor(gold)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            background = rounded(panel, Color.rgb(48, 45, 36), 13)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(width), dp(38)).apply { marginEnd = dp(4) }
            setOnClickListener { action() }
        }
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 80
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.blockNetworkLoads = false
            settings.blockNetworkImage = false
            settings.setSupportZoom(true)
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            isFocusableInTouchMode = true
            isClickable = true
            settings.setGeolocationEnabled(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view === currentWeb()) {
                        this@MainActivity.progress.progress = newProgress
                        this@MainActivity.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    }
                }

                // Support legitimate pop-up windows opened by a direct user action.
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (!isUserGesture || resultMsg == null) return false
                    val popup = createWebView()
                    tabs.add(popup)
                    activeTab = tabs.lastIndex
                    webContainer.removeAllViews()
                    webContainer.addView(popup, LinearLayout.LayoutParams(-1, -1))
                    address.setText("")
                    tabLabel.text = "${tabs.size} تبويب"
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (view === currentWeb()) {
                        address.setText(url ?: "")
                        tabLabel.text = "${tabs.size} تبويب"
                    }

                }
            }
        }
    }

    private fun currentWeb(): WebView? = tabs.getOrNull(activeTab)

    private fun addTab(url: String) {
        val web = createWebView()
        tabs.add(web)
        activeTab = tabs.lastIndex
        webContainer.removeAllViews()
        webContainer.addView(web, LinearLayout.LayoutParams(-1, -1))
        tabLabel.text = "${tabs.size} تبويب"
        loadInto(web, url)
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        webContainer.removeAllViews()
        webContainer.addView(tabs[index], LinearLayout.LayoutParams(-1, -1))
        address.setText(tabs[index].url ?: "")
        tabLabel.text = "${tabs.size} تبويب"
    }

    private fun loadAddress(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        val url = if (value.contains(" ") || (!value.contains(".") && !value.startsWith("http"))) {
            "https://www.google.com/search?q=" + Uri.encode(value)
        } else if (value.startsWith("http://") || value.startsWith("https://")) value
        else "https://$value"
        currentWeb()?.let { loadInto(it, url) }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun loadInto(web: WebView, url: String) {
        web.loadUrl(url)
    }

    private fun showTabs() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { i, w -> "${i + 1}. ${w.title ?: w.url ?: "تبويب جديد"}" }.toMutableList()
        labels.add("＋ تبويب جديد")
        AlertDialog.Builder(this).setTitle("التبويبات")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == tabs.size) addTab("https://www.google.com") else switchTab(which)
            }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun clearAllHistory() {
        tabs.forEach { it.clearHistory() }
        Toast.makeText(this, "تم مسح سجل الرجوع والتقدم في جميع التبويبات", Toast.LENGTH_SHORT).show()
    }

    private fun showMenu() {
        val items = arrayOf("إضافة للمفضلة", "عرض المفضلة", "مسح سجل التصفح", "مسح الكاش", "مسح الكوكيز", "مسح بيانات المواقع", "حول HB Browser")
        AlertDialog.Builder(this).setTitle("HB Browser")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveBookmark()
                    1 -> showBookmarks()
                    2 -> clearAllHistory()
                    3 -> { tabs.forEach { it.clearCache(true) }; Toast.makeText(this, "تم مسح الكاش", Toast.LENGTH_SHORT).show() }
                    4 -> { CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); Toast.makeText(this, "تم مسح الكوكيز", Toast.LENGTH_SHORT).show() } }
                    5 -> clearSiteData()
                    6 -> AlertDialog.Builder(this).setMessage("HB Browser 1.0\nمتصفح مستقل بواجهة سوداء وذهبية.").setPositiveButton("حسناً", null).show()
                }
            }.show()
    }

    private fun saveBookmark() {
        val url = currentWeb()?.url ?: return
        val title = currentWeb()?.title ?: url
        val all = prefs.getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        all.add("$title|$url")
        prefs.edit().putStringSet("bookmarks", all).apply()
        Toast.makeText(this, "تمت الإضافة للمفضلة", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        val all = prefs.getStringSet("bookmarks", emptySet())?.toList() ?: emptyList()
        if (all.isEmpty()) {
            Toast.makeText(this, "لا توجد صفحات محفوظة", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("المفضلة")
            .setItems(all.map { it.substringBefore("|") }.toTypedArray()) { _, which ->
                val url = all[which].substringAfter("|", "")
                if (url.isNotEmpty()) currentWeb()?.loadUrl(url)
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun clearSiteData() {
        tabs.forEach { it.clearFormData(); it.clearHistory(); it.clearCache(true) }
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            prefs.edit().remove("bookmarks").apply()
            Toast.makeText(this, "تم مسح بيانات التصفح والمفضلة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        val web = currentWeb()
        if (web?.canGoBack() == true) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
