package com.example.texviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import org.json.JSONObject

class ViewerActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: View
    private lateinit var progress: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var uri: Uri
    private lateinit var name: String
    private val prefs by lazy { getSharedPreferences("p", 0) }

    private var theme: String
        get() = prefs.getString("theme", "light")!!
        set(v) { prefs.edit().putString("theme", v).apply() }
    private var font: String
        get() = prefs.getString("font", "serif")!!
        set(v) { prefs.edit().putString("font", v).apply() }
    private var scale: Int
        get() = prefs.getInt("scale", 100)
        set(v) { prefs.edit().putInt("scale", v.coerceIn(60, 250)).apply() }

    private val palette = mapOf(
        "light" to Pair(0xFFFFFFFF.toInt(), 0xFF1B1B1F.toInt()), "dark" to Pair(0xFF121212.toInt(), 0xFFE3E3E3.toInt()),
        "gray" to Pair(0xFFC4C4C4.toInt(), 0xFF1A1A1A.toInt()), "grayscale" to Pair(0xFFF2F2F2.toInt(), 0xFF111111.toInt()),
        "night" to Pair(0xFFF4E8D0.toInt(), 0xFF4A3B2A.toInt()))
    private val chipIds = mapOf("light" to R.id.c_light, "dark" to R.id.c_dark, "gray" to R.id.c_gray, "grayscale" to R.id.c_grayscale, "night" to R.id.c_night)

    override fun onCreate(s: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(s)
        setContentView(R.layout.activity_viewer)
        root = findViewById(R.id.root); progress = findViewById(R.id.progress); toolbar = findViewById(R.id.toolbar); web = findViewById(R.id.web)
        uri = Uri.parse(intent.getStringExtra("uri")); name = intent.getStringExtra("name") ?: "Document"
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.viewer)
        toolbar.setOnMenuItemClickListener { if (it.itemId == R.id.display) showSettings(); true }
        applyChrome()

        val text = try { contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } } catch (e: Exception) { "Cannot open file: ${e.message}" }
        val md = name.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }
        web.settings.apply {
            javaScriptEnabled = true; builtInZoomControls = true; displayZoomControls = false   // pinch zoom + pan
            setSupportZoom(true); useWideViewPort = true; loadWithOverviewMode = true
            allowFileAccess = true; allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String) {
                v.evaluateJavascript("setup('$theme','$font',$scale);render(${JSONObject.quote(text)},$md)") { progress.visibility = View.GONE }
                v.postDelayed({ saveThumb() }, 1500)
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.scheme == "http" || r.url.scheme == "https") { try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}; return true }
                return false
            }
        }
        web.loadUrl("file:///android_asset/viewer.html")
    }

    private fun applyChrome() {
        val (bg, fg) = palette[theme] ?: palette["light"]!!
        root.setBackgroundColor(bg); toolbar.setBackgroundColor(bg); toolbar.setTitleTextColor(fg); web.setBackgroundColor(bg)
        toolbar.navigationIcon?.mutate()?.setTint(fg)
        toolbar.menu.findItem(R.id.display)?.icon?.mutate()?.setTint(fg)
        WindowCompat.getInsetsController(window, root).apply {
            val light = ColorUtils.calculateLuminance(bg) > 0.5
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
    }

    private fun update() { web.evaluateJavascript("setup('$theme','$font',$scale)", null); applyChrome() }

    private fun showSettings() {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_display, null); d.setContentView(v)
        v.findViewById<ChipGroup>(R.id.themes).apply {
            check(chipIds[theme] ?: R.id.c_light)
            setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { id -> chipIds.entries.firstOrNull { it.value == id }?.let { theme = it.key; update() } } }
        }
        v.findViewById<MaterialButtonToggleGroup>(R.id.fonts).apply {
            check(if (font == "serif") R.id.b_serif else R.id.b_sans)
            addOnButtonCheckedListener { _, id, ok -> if (ok) { font = if (id == R.id.b_serif) "serif" else "sans"; update() } }
        }
        val sl = v.findViewById<Slider>(R.id.slider)
        sl.value = scale.toFloat().coerceIn(60f, 250f)
        sl.addOnChangeListener { _, x, user -> if (user) { scale = x.toInt(); update() } }
        v.findViewById<View>(R.id.reset).setOnClickListener { scale = 100; sl.value = 100f; update() }
        d.show()
    }

    private fun saveThumb() {
        val w = web.width; if (w == 0 || web.height == 0) return
        val h = minOf(web.height, (w * 1.3).toInt())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); web.draw(Canvas(b))
        val s = Bitmap.createScaledBitmap(b, 360, (360f * h / w).toInt(), true)
        Recents.thumbFile(this, uri.toString()).outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        Recents.add(this, Doc(uri.toString(), name))
    }
}
