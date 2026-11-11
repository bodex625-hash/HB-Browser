package com.hb.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.net.Uri

class MainActivity : Activity() {
    private val gold = Color.rgb(212, 175, 55)
    private val black = Color.rgb(9, 9, 9)
    private val panel = Color.rgb(24, 24, 24)
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var webContainer: LinearLayout
    private lateinit var tabLabel: TextView
    private val tabs = mutableListOf<WebView>()
    private var activeTab = 0
    private val prefs by lazy { getSharedPreferences("hb_browser", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black
        window.navigationBarColor = black
        buildInterface()
        addTab("https://www.google.com")
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
            setPadding(8, 8, 8, 4)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            hint = "ابحث أو أدخل عنوان موقع"
            setHintTextColor(0xFFAAAAAA.toInt())
            setSelectAllOnFocus(true)
            setPadding(14, 0, 14, 0)
            setBackgroundColor(panel)
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, 46, 1f).apply { marginEnd = 6 }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    loadAddress(text.toString()); true
                } else false
            }
        }
        top.addView(address)
        top.addView(makeButton("＋") { addTab("https://www.google.com") })
        top.addView(makeButton("⋮") { showMenu() })
        root.addView(top)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        nav.addView(makeButton("‹") { if (currentWeb()?.canGoBack() == true) currentWeb()?.goBack() })
        nav.addView(makeButton("›") { if (currentWeb()?.canGoForward() == true) currentWeb()?.goForward() })
        nav.addView(makeButton("↻") { currentWeb()?.reload() })
        tabLabel = TextView(this).apply {
            setTextColor(gold)
            textSize = 13f
            gravity = Gravity.CENTER
            text = "تبويبات: 1"
            setPadding(8, 8, 8, 8)
        }
        nav.addView(tabLabel, LinearLayout.LayoutParams(0, 42, 1f))
        nav.addView(makeButton("□") { showTabs() })
        root.addView(nav)

        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(webContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(gold)
            setBackgroundColor(panel)
            minWidth = 42
            minimumWidth = 42
            minHeight = 42
            setPadding(4, 0, 4, 0)
            setOnClickListener { action() }
        }
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 80
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setGeolocationEnabled(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (view === currentWeb()) {
                        address.setText(url ?: "")
                        tabLabel.text = "تبويبات: ${tabs.size}"
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
        tabLabel.text = "تبويبات: ${tabs.size}"
        loadInto(web, url)
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        webContainer.removeAllViews()
        webContainer.addView(tabs[index], LinearLayout.LayoutParams(-1, -1))
        address.setText(tabs[index].url ?: "")
        tabLabel.text = "تبويبات: ${tabs.size}"
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

    private fun showMenu() {
        val items = arrayOf("إضافة للمفضلة", "عرض المفضلة", "مسح سجل التصفح", "مسح الكاش", "مسح الكوكيز", "مسح بيانات المواقع", "حول HB Browser")
        AlertDialog.Builder(this).setTitle("HB Browser")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveBookmark()
                    1 -> showBookmarks()
                    2 -> { tabs.forEach { it.clearHistory() }; Toast.makeText(this, "تم مسح السجل الحالي", Toast.LENGTH_SHORT).show() }
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
