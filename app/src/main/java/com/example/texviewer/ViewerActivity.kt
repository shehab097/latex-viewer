package com.example.texviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.PixelCopy
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
import java.io.File

class ViewerActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: View
    private lateinit var progress: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var uri: Uri
    private lateinit var name: String

    private var content: String? = null
    private var loadError: String? = null
    private var pageReady = false
    private var destroyed = false

    private val prefs by lazy { getSharedPreferences("p", 0) }

    private var theme: String
        get() = prefs.getString("theme", "light") ?: "light"
        set(v) = prefs.edit().putString("theme", v).apply()
    private var font: String
        get() = prefs.getString("font", "serif") ?: "serif"
        set(v) = prefs.edit().putString("font", v).apply()
    private var scale: Int
        get() = prefs.getInt("scale", 100)
        set(v) = prefs.edit().putInt("scale", v.coerceIn(60, 250)).apply()

    private val palette = mapOf(
        "light" to Pair(0xFFFFFFFF.toInt(), 0xFF1B1B1F.toInt()),
        "dark" to Pair(0xFF121212.toInt(), 0xFFE3E3E3.toInt()),
        "gray" to Pair(0xFFC4C4C4.toInt(), 0xFF1A1A1A.toInt()),
        "grayscale" to Pair(0xFFF2F2F2.toInt(), 0xFF111111.toInt()),
        "night" to Pair(0xFFF4E8D0.toInt(), 0xFF4A3B2A.toInt())
    )
    private val chipIds = mapOf(
        "light" to R.id.c_light,
        "dark" to R.id.c_dark,
        "gray" to R.id.c_gray,
        "grayscale" to R.id.c_grayscale,
        "night" to R.id.c_night
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        root = findViewById(R.id.root)
        progress = findViewById(R.id.progress)
        toolbar = findViewById(R.id.toolbar)
        web = findViewById(R.id.web)

        val incoming = intent?.data
            ?: intent?.getStringExtra("uri")?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (incoming == null) {
            finish()
            return
        }
        uri = incoming
        name = intent?.getStringExtra("name") ?: displayName(uri) ?: uri.lastPathSegment ?: "Document"

        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.viewer)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.display) showSettings()
            true
        }

        content = try {
            readText(uri)
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
            null
        }
        if (content != null) {
            // remember the document even if the user leaves before the thumbnail is ready
            Recents.add(this, Doc(uri.toString(), name))
        } else {
            // moved/deleted file or a revoked URI grant
            Recents.remove(this, uri.toString())
        }

        applyChrome()

        web.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (pageReady || destroyed) return
                pageReady = true
                progress.visibility = View.GONE
                view.evaluateJavascript(
                    "setup(${JSONObject.quote(theme)},${JSONObject.quote(font)},$scale)", null
                )
                val body = content
                if (body != null) {
                    view.evaluateJavascript(
                        "render(${JSONObject.quote(body)},${isMarkdown(name)})", null
                    )
                    view.postDelayed({ saveThumb() }, 1200)
                } else {
                    view.evaluateJavascript(
                        "showError(${JSONObject.quote("The file could not be read.")},${JSONObject.quote(loadError ?: "")})",
                        null
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }
        }
        web.loadUrl("file:///android_asset/viewer.html#theme=$theme&font=$font&scale=$scale")
    }

    private fun readText(u: Uri): String {
        if (u.scheme == "file" && u.path != null) return File(u.path!!).readText()
        val stream = contentResolver.openInputStream(u)
            ?: throw java.io.FileNotFoundException("Cannot open $u")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun displayName(u: Uri): String? = try {
        if (u.scheme == "content") {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } else null
    } catch (_: Exception) {
        null
    }

    private fun isMarkdown(n: String): Boolean {
        val e = n.substringAfterLast('.', "").lowercase()
        return e == "md" || e == "markdown" || e == "mdown" || e == "mkd"
    }

    private fun applyChrome() {
        val (bg, fg) = palette[theme] ?: palette["light"]!!
        root.setBackgroundColor(bg)
        toolbar.setBackgroundColor(bg)
        toolbar.setTitleTextColor(fg)
        toolbar.subtitle = null
        web.setBackgroundColor(bg)
        toolbar.navigationIcon?.mutate()?.setTint(fg)
        toolbar.menu.findItem(R.id.display)?.icon?.mutate()?.setTint(fg)
        WindowCompat.getInsetsController(window, root).apply {
            val light = ColorUtils.calculateLuminance(bg) > 0.5
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun update() {
        if (::web.isInitialized && pageReady) {
            web.evaluateJavascript(
                "setup(${JSONObject.quote(theme)},${JSONObject.quote(font)},$scale)", null
            )
        }
        applyChrome()
    }

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_display, null)
        dialog.setContentView(view)

        view.findViewById<ChipGroup>(R.id.themes).apply {
            check(chipIds[theme] ?: R.id.c_light)
            setOnCheckedStateChangeListener { _, ids ->
                ids.firstOrNull()?.let { id ->
                    chipIds.entries.firstOrNull { it.value == id }?.let {
                        theme = it.key
                        update()
                    }
                }
            }
        }
        view.findViewById<MaterialButtonToggleGroup>(R.id.fonts).apply {
            check(if (font == "serif") R.id.b_serif else R.id.b_sans)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    font = if (id == R.id.b_serif) "serif" else "sans"
                    update()
                }
            }
        }
        val slider = view.findViewById<Slider>(R.id.slider)
        slider.value = scale.toFloat().coerceIn(60f, 250f)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                scale = value.toInt()
                update()
            }
        }
        view.findViewById<View>(R.id.reset).setOnClickListener {
            scale = 100
            slider.value = 100f
            update()
        }
        dialog.show()
    }

    /* ---------- thumbnail ---------- */
    private fun saveThumb() {
        if (destroyed || content == null || !::web.isInitialized) return
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            web.getLocationInWindow(loc)
            val decor = window.decorView
            val left = loc[0].coerceIn(0, maxOf(0, decor.width - 1))
            val top = loc[1].coerceIn(0, maxOf(0, decor.height - 1))
            val right = (loc[0] + w).coerceAtMost(decor.width)
            val bottom = (loc[1] + h).coerceAtMost(decor.height)
            if (right <= left || bottom <= top) {
                drawFallback()
                return
            }
            try {
                PixelCopy.request(
                    window,
                    Rect(left, top, right, bottom),
                    bitmap,
                    { result ->
                        if (!destroyed) {
                            if (result == PixelCopy.SUCCESS) storeThumb(bitmap) else drawFallback()
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (_: Exception) {
                drawFallback()
            }
        } else {
            drawFallback()
        }
    }

    private fun drawFallback() {
        try {
            val w = web.width
            val h = web.height
            if (w <= 0 || h <= 0) return
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(b))
            storeThumb(b)
        } catch (_: Exception) {
        }
    }

    private fun storeThumb(bitmap: Bitmap) {
        try {
            val tw = 400
            val th = maxOf(1, (tw.toFloat() * bitmap.height / bitmap.width).toInt())
            val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
            Recents.thumbFile(this, uri.toString()).outputStream()
                .use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Recents.add(this, Doc(uri.toString(), name))
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
