package com.example.texviewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class ViewerActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: View
    private lateinit var progress: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appbar: AppBarLayout
    private lateinit var uri: Uri
    private lateinit var name: String

    private var content: String? = null
    private var loadError: String? = null
    private var pageReady = false
    private var destroyed = false
    private var scaleUpdate: Runnable? = null
    private var toolbarHidden = false
    private var appBarAnimating = false

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
        appbar = findViewById(R.id.appbar)
        web = findViewById(R.id.web)
        appbar.addOnOffsetChangedListener { _, offset ->
            if (appBarAnimating && (offset == 0 || offset == -appbar.totalScrollRange)) {
                appBarAnimating = false
            }
        }

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
            when (it.itemId) {
                R.id.display -> showSettings()
                R.id.export_pdf -> exportPdf()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        // The WebView scrolls its content internally, so a native scroll listener
        // never fires. The page reports scroll deltas through this bridge instead.
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onScroll(dy: Int, y: Int) {
                runOnUiThread {
                    if (destroyed || !pageReady || appBarAnimating) return@runOnUiThread
                    when {
                        y <= 0 -> showToolbar()
                        dy > 12 -> hideToolbar()
                        dy < -12 -> showToolbar()
                    }
                }
            }
        }, "ViewerHost")

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
            allowFileAccess = true
            allowContentAccess = true
            domStorageEnabled = true
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
                    // TikZ pictures are compiled asynchronously; give the engine
                    // enough time to finish before capturing the recents thumbnail.
                    val hasTikz = body.contains("\\begin{tikzpicture}") ||
                        body.contains("\\begin{pgfpicture}") ||
                        body.contains("\\begin{circuitikz}")
                    view.postDelayed({ saveThumb() }, if (hasTikz) 6000L else 1200L)
                } else {
                    view.evaluateJavascript(
                        "showError(${JSONObject.quote("The file could not be read.")},${JSONObject.quote(loadError ?: "")})",
                        null
                    )
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return super.shouldInterceptRequest(view, request)
                val urlString = url.toString()

                // TikZJax loads its WASM/TeX resources through fetch(); serve them
                // from the bundled assets so diagram rendering works offline.
                if (scheme == "https" && url.host == "tikzjax.local") {
                    return serveTikzAsset(url)
                }

                if (scheme == "http" || scheme == "https" || scheme == "data") {
                    return super.shouldInterceptRequest(view, request)
                }

                // Images referenced by an absolute content:// URI.
                if (scheme == "content") {
                    return try {
                        contentResolver.openInputStream(url)?.let {
                            WebResourceResponse(
                                contentResolver.getType(url) ?: "application/octet-stream",
                                null,
                                it
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                // Bundled assets (KaTeX, marked) are served by the WebView itself.
                if (urlString.startsWith("file:///android_asset/")) {
                    val assetPath = urlString.removePrefix("file:///android_asset/")
                        .substringBefore('?').substringBefore('#')
                    try {
                        assets.open(assetPath).close()
                        return super.shouldInterceptRequest(view, request)
                    } catch (_: Exception) {
                        // Not an asset: try resolving it next to the document instead.
                    }
                } else if (scheme == "file") {
                    // An absolute file:// path can be loaded directly.
                    return super.shouldInterceptRequest(view, request)
                }

                // Relative paths are resolved against file:///android_asset/, so strip that
                // prefix and look for the file in the document's own folder.
                val relativePath = url.path?.removePrefix("/android_asset/")?.removePrefix("/")
                if (!relativePath.isNullOrEmpty()) {
                    val stream = resolveDocumentImageStream(relativePath)
                    if (stream != null) {
                        return WebResourceResponse(mimeTypeOf(relativePath), null, stream)
                    }
                }

                return super.shouldInterceptRequest(view, request)
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

    /**
     * Opens an image that is referenced relative to the currently viewed document.
     * Works for both plain file:// documents and Storage Access Framework URIs
     * (single documents and documents inside a tree).
     */
    private fun resolveDocumentImageStream(relativePath: String): InputStream? {
        val rel = safeRelativePath(relativePath) ?: return null
        return try {
            when (uri.scheme?.lowercase()) {
                "file" -> openFileRelative(rel)
                "content" -> openContentRelative(rel)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeRelativePath(path: String): String? {
        val parts = path.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun openFileRelative(rel: String): InputStream? {
        val document = uri.path?.let { File(it) } ?: return null
        val base = document.parentFile ?: return null
        val target = File(base, rel)
        val basePath = base.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(basePath)) return null
        return if (target.isFile) FileInputStream(target) else null
    }

    private fun openContentRelative(rel: String): InputStream? {
        val authority = uri.authority ?: return null
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            null
        }

        if (documentId != null) {
            val parentId = parentDocumentId(documentId)
            val targetId = when {
                parentId.isEmpty() -> rel
                parentId.endsWith(':') -> parentId + rel
                else -> "$parentId/$rel"
            }

            // Preferred route: ask the DocumentsProvider for the sibling document.
            if (DocumentsContract.isTreeUri(uri)) {
                try {
                    val sibling = DocumentsContract.buildDocumentUriUsingTree(uri, targetId)
                    openStreamQuietly(sibling)?.let { return it }
                } catch (_: Exception) {
                }
            }
            try {
                val sibling = DocumentsContract.buildDocumentUri(authority, targetId)
                openStreamQuietly(sibling)?.let { return it }
            } catch (_: Exception) {
            }

            // Fallback for providers whose document IDs are not path-like: walk the tree.
            if (DocumentsContract.isTreeUri(uri)) {
                try {
                    val treeId = DocumentsContract.getTreeDocumentId(uri)
                    if (documentId.startsWith(treeId)) {
                        val relativeToRoot = documentId.removePrefix(treeId).trimStart('/')
                        val parentPath = relativeToRoot.substringBeforeLast('/', "")
                        val targetPath = listOf(parentPath, rel)
                            .filter { it.isNotEmpty() }
                            .joinToString("/")
                        val root = DocumentFile.fromTreeUri(this, uri)
                        findDocument(root, targetPath)?.let { found ->
                            openStreamQuietly(found.uri)?.let { return it }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return null
    }

    private fun parentDocumentId(documentId: String): String {
        val slash = documentId.lastIndexOf('/')
        if (slash >= 0) return documentId.substring(0, slash)
        val colon = documentId.lastIndexOf(':')
        if (colon >= 0) return documentId.substring(0, colon + 1)
        return ""
    }

    private fun findDocument(root: DocumentFile?, path: String): DocumentFile? {
        var current = root ?: return null
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun openStreamQuietly(uri: Uri): InputStream? = try {
        contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        null
    }

    private fun mimeTypeOf(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }

    /** Serves TikZJax's WASM, TeX and font resources from the APK assets. */
    private fun serveTikzAsset(url: Uri): WebResourceResponse? {
        val path = url.path?.removePrefix("/") ?: return null
        if (path.isEmpty() || path.contains("..")) return null
        // Android's asset merger decompresses files ending in .gz, so the
        // pre-compressed TeX resources are stored with a .bin suffix.
        val assetPath = when (path) {
            "tex/core.dump.gz" -> "tikzjax/tex/core.dump.gz.bin"
            "tex/tex.wasm.gz" -> "tikzjax/tex/tex.wasm.gz.bin"
            "tex/tex_files.tar.gz" -> "tikzjax/tex/tex_files.tar.gz.bin"
            else -> "tikzjax/$path"
        }
        return try {
            val stream = assets.open(assetPath)
            val mime = when {
                path.endsWith(".gz") -> "application/gzip"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".ttf") -> "font/ttf"
                path.endsWith(".js") -> "application/javascript"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, stream).apply {
                responseHeaders = mutableMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "public, max-age=31536000"
                )
            }
        } catch (e: Exception) {
            Log.e("TikzAsset", "failed tikzjax/$path", e)
            null
        }
    }

    private fun applyChrome() {
        val (bg, fg) = palette[theme] ?: palette["light"]!!
        root.setBackgroundColor(bg)
        appbar.setBackgroundColor(bg)
        toolbar.setBackgroundColor(bg)
        toolbar.setTitleTextColor(fg)
        toolbar.subtitle = null
        web.setBackgroundColor(bg)
        toolbar.navigationIcon?.mutate()?.setTint(fg)
        toolbar.overflowIcon?.mutate()?.setTint(fg)
        toolbar.menu.findItem(R.id.display)?.icon?.mutate()?.setTint(fg)
        // Keep the status bar opaque so a collapsing toolbar cannot show through it.
        window.statusBarColor = bg
        window.navigationBarColor = bg
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

    /** Text-size changes only need a font-size update, not a full page re-theme. */
    private fun updateScale() {
        if (!destroyed && ::web.isInitialized && pageReady) {
            web.evaluateJavascript("setScale($scale)", null)
        }
    }

    private fun hideToolbar() {
        if (toolbarHidden || !::appbar.isInitialized) return
        toolbarHidden = true
        appBarAnimating = true
        appbar.setExpanded(false, true)
        appbar.postDelayed({ appBarAnimating = false }, 400)
    }

    private fun showToolbar() {
        if (!toolbarHidden || !::appbar.isInitialized) return
        toolbarHidden = false
        appBarAnimating = true
        appbar.setExpanded(true, true)
        appbar.postDelayed({ appBarAnimating = false }, 400)
    }

    private fun exportPdf(attempt: Int = 0) {
        if (destroyed) return
        if (!pageReady) {
            Toast.makeText(this, "Please wait for the document to finish loading.", Toast.LENGTH_SHORT).show()
            return
        }
        web.evaluateJavascript("document.querySelectorAll('.tikz-status').length") { result ->
            val pending = result?.trim()?.toIntOrNull() ?: 0
            if (pending > 0) {
                if (attempt >= 20) {
                    Toast.makeText(this, "Diagrams are still rendering. Try again in a moment.", Toast.LENGTH_LONG).show()
                } else {
                    if (attempt == 0) {
                        Toast.makeText(this, "Preparing diagrams…", Toast.LENGTH_SHORT).show()
                    }
                    web.postDelayed({ exportPdf(attempt + 1) }, 750)
                }
            } else {
                startPrint()
            }
        }
    }

    private fun startPrint() {
        if (destroyed) return
        try {
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = name.substringBeforeLast('.').ifBlank { name }
            val adapter = web.createPrintDocumentAdapter(jobName)
            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "PDF", 600, 600))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            printManager.print(jobName, adapter, attrs)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not start PDF export.", Toast.LENGTH_SHORT).show()
        }
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
        val sizeValue = view.findViewById<TextView>(R.id.sizeValue)
        val slider = view.findViewById<Slider>(R.id.slider)
        slider.value = scale.toFloat().coerceIn(60f, 250f)
        sizeValue.text = "${scale.coerceIn(60, 250)}%"
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                scale = value.toInt()
                sizeValue.text = "$scale%"
                // Coalesce rapid slider movements into one update per frame.
                scaleUpdate?.let { web.removeCallbacks(it) }
                val r = Runnable { updateScale() }
                scaleUpdate = r
                web.postOnAnimation(r)
            }
        }
        view.findViewById<View>(R.id.reset).setOnClickListener {
            scale = 100
            slider.value = 100f
            sizeValue.text = "100%"
            updateScale()
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
