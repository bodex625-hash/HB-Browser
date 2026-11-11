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
    private lateinit var profile: CefriumProfile
    private var browser: CefriumBrowser? = null
    private val prefs by lazy { getSharedPreferences("hb_browser", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black
        window.navigationBarColor = black
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        profile = CefriumProfile.getDefault()
        profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT)

        buildInterface()
        createChromiumBrowser()
        browser?.loadUrl("https://www.google.com")
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

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        nav.addView(makeAction("‹", 40, 20) {
            browser?.let { if (it.canGoBack()) it.goBack() }
        })
        nav.addView(makeAction("›", 40, 20) {
            browser?.goForward()
        })
        nav.addView(makeAction("↻", 40, 18) { browser?.reload() })

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

    private fun createChromiumBrowser() {
        val instance = CefriumBrowser.createWithSurface(this, profile)
        instance.setJavaScriptEnabled(true)
        instance.setPinchToZoomEnabled(true)
        // Persisted page zoom: 80% by default. Cefrium uses 0.0 for 100%
        // and a 1.2 multiplier per zoom level.
        instance.setZoomLevel(zoomPercentToLevel(prefs.getInt("page_zoom", 80)))
        instance.setOnUrlChangedListener { url ->
            runOnUiThread {
                if (!isFinishing) address.setText(url)
            }
        }
        instance.setOnTitleChangedListener { title ->
            runOnUiThread {
                if (!isFinishing && title.isNotBlank()) {
                    // Keep the address bar stable; the page title is available to the engine.
                }
            }
        }
        instance.setOnLoadingStateChangedListener { isLoading, _, _ ->
            runOnUiThread {
                if (!isFinishing) {
                    progress.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }
        browser = instance
        webContainer.addView(
            instance.surfaceContainer,
            FrameLayout.LayoutParams(-1, -1)
        )
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
        browser?.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun showMenu() {
        val items = arrayOf(
            "إضافة للمفضلة",
            "عرض المفضلة",
            "مسح الكاش",
            "مسح كوكيز الموقع الحالي",
            "مسح بيانات الموقع الحالي",
            "تغيير زوم الصفحة",
            "حول HB Chromium"
        )
        AlertDialog.Builder(this)
            .setTitle("HB Browser")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveBookmark()
                    1 -> showBookmarks()
                    2 -> {
                        browser?.clearCache()
                        Toast.makeText(this, "تم مسح الكاش", Toast.LENGTH_SHORT).show()
                    }
                    3 -> clearCurrentSiteCookies()
                    4 -> clearCurrentSiteData()
                    5 -> showPageZoomDialog()
                    6 -> AlertDialog.Builder(this)
                        .setMessage("HB Browser – Chromium Edition\nنسخة تجريبية بمحرك Chromium مدمج.")
                        .setPositiveButton("حسناً", null)
                        .show()
                }
            }
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
                browser?.setZoomLevel(zoomPercentToLevel(percent))
                Toast.makeText(this, "تم ضبط زوم الصفحة على $percent%", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun currentOrigin(): String? {
        val url = browser?.url ?: return null
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return "$scheme://$host"
    }

    private fun clearCurrentSiteCookies() {
        val origin = currentOrigin()
        if (origin == null) {
            Toast.makeText(this, "افتح موقعاً أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        browser?.deleteCookies(origin, null)
        browser?.flushCookies()
        Toast.makeText(this, "تم طلب حذف كوكيز الموقع الحالي", Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrentSiteData() {
        val origin = currentOrigin()
        if (origin == null) {
            Toast.makeText(this, "افتح موقعاً أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        profile.clearSiteData(origin) {
            Toast.makeText(this, "تم مسح بيانات الموقع الحالي", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBookmark() {
        val url = browser?.url ?: return
        val title = url
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
        AlertDialog.Builder(this)
            .setTitle("المفضلة")
            .setItems(all.map { it.substringBefore("|") }.toTypedArray()) { _, which ->
                val url = all[which].substringAfter("|", "")
                if (url.isNotEmpty()) browser?.loadUrl(url)
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    override fun onPause() {
        browser?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser?.onResume()
    }

    override fun onBackPressed() {
        val current = browser
        if (current?.canGoBack() == true) current.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        browser?.let {
            it.flushCookies()
            it.close()
        }
        browser = null
        super.onDestroy()
    }
}
