package com.example.texviewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.window.embedding.EmbeddingRule
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: DocAdapter
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var toolbar: MaterialToolbar
    private val prefs by lazy { getSharedPreferences("p", 0) }
    private var splitEnabled = false
    private val isLargeScreen: Boolean
        get() {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val widthPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.width()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels
            }
            return widthPx / resources.displayMetrics.density >= 600f
        }
    private val canSplit: Boolean
        get() = isLargeScreen && runCatching {
            SplitController.getInstance(this).splitSupportStatus ==
                SplitController.SplitSupportStatus.SPLIT_AVAILABLE
        }.getOrDefault(false)
    private val stack = ArrayList<DocumentFile>()
    private val exts = setOf("tex", "ltx", "latex", "md", "markdown", "mdown", "mkd", "txt")
    private val supportedMimes = arrayOf(
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/x-markdown",
        "application/x-tex",
        "application/x-latex",
        "text/x-tex"
    )
    private var query = ""

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let {
            keep(it)
            val n = nameOf(it)
            if (isSupported(n)) open(Doc(it.toString(), n))
            else Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
        }
    }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        u?.let {
            keep(it)
            DocumentFile.fromTreeUri(this, it)?.let { d ->
                stack.clear()
                stack.add(d)
                show()
            }
        }
    }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        empty = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyText = findViewById(R.id.emptyText)
        toolbar = findViewById(R.id.toolbar)

        (toolbar.menu.findItem(R.id.search).actionView as SearchView).apply {
            queryHint = "Search documents"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = false
                override fun onQueryTextChange(q: String?): Boolean {
                    this@MainActivity.query = q.orEmpty()
                    show()
                    return true
                }
            })
        }

        splitEnabled = prefs.getBoolean("split_view", false)
        toolbar.menu.findItem(R.id.split_view)?.apply {
            isVisible = canSplit
            isChecked = splitEnabled
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.split_view -> {
                    splitEnabled = !item.isChecked
                    item.isChecked = splitEnabled
                    setSplitEnabled(splitEnabled)
                    true
                }
                else -> false
            }
        }

        adapter = DocAdapter(
            onClick = { d ->
                if (d.dir != null) {
                    stack.add(d.dir)
                    show()
                } else {
                    open(d)
                }
            },
            onLong = { d -> if (d.dir == null) showDocumentActions(d) }
        )
        // Keep cards comfortably sized on phones and use the extra room on
        // tablets, foldables, and desktop windows without an endless grid.
        val density = resources.displayMetrics.density
        // Use the activity window width, not the physical display width: in an
        // embedded split the library only occupies its own pane.
        val windowWidthPx = (resources.configuration.screenWidthDp * density).toInt()
        val maxGridWidth = (1400 * density).toInt()
        val effectiveWidth = minOf(windowWidthPx, maxGridWidth)
        val cardMin = resources.getDimensionPixelSize(R.dimen.card_min_width)
        val cols = (effectiveWidth / cardMin).toInt()
            .coerceIn(2, resources.getInteger(R.integer.library_max_columns))
        findViewById<RecyclerView>(R.id.list).apply {
            if (windowWidthPx > maxGridWidth) {
                (layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams).apply {
                    width = maxGridWidth
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                }
            }
            layoutManager = GridLayoutManager(this@MainActivity, cols)
            adapter = this@MainActivity.adapter
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fabFile).setOnClickListener {
            pickFile.launch(supportedMimes)
        }
        findViewById<FloatingActionButton>(R.id.fabFolder).setOnClickListener {
            pickTree.launch(null)
        }
        onBackPressedDispatcher.addCallback(this, back)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val u = i.data ?: return
        val n = nameOf(u)
        if (!isSupported(n)) {
            Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            return
        }
        keep(u)
        val doc = Doc(u.toString(), n)
        Recents.add(this, doc)
        open(doc)
    }

    private fun isSupported(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in exts

    override fun onResume() {
        super.onResume()
        toolbar.menu.findItem(R.id.split_view)?.apply {
            isVisible = canSplit
            isChecked = splitEnabled
        }
        adapter.invalidateThumbs()
        show()
    }

    private fun show() {
        back.isEnabled = stack.isNotEmpty()
        val inFolder = stack.isNotEmpty()
        toolbar.title = if (inFolder) stack.last().name ?: "Library" else "Library"

        var list: List<Doc> = if (!inFolder) {
            Recents.load(this)
        } else {
            stack.last().listFiles().toList()
                .filter { it.isDirectory || it.name?.substringAfterLast('.', "")?.lowercase() in exts }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
                .map {
                    Doc(
                        it.uri.toString(),
                        it.name ?: "?",
                        Recents.thumbFile(this, it.uri.toString()).path,
                        if (it.isDirectory) it else null
                    )
                }
        }

        if (query.isNotBlank()) list = list.filter { it.name.contains(query.trim(), true) }

        adapter.set(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        when {
            query.isNotBlank() -> {
                emptyTitle.text = "No matches"
                emptyText.text = "Nothing here matches \"${query.trim()}\""
            }
            inFolder -> {
                emptyTitle.text = "Nothing to show"
                emptyText.text = "This folder has no LaTeX, Markdown or text documents"
            }
            else -> {
                emptyTitle.text = "No recent documents"
                emptyText.text = "Open a LaTeX (.tex) or Markdown (.md) file or folder to get started"
            }
        }
    }

    private fun setSplitEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("split_view", enabled).apply()
        runCatching {
            val rules: Set<EmbeddingRule> = if (enabled) {
                RuleController.parseRules(this, R.xml.main_split_config)
            } else {
                emptySet()
            }
            RuleController.getInstance(this).setRules(rules)
        }
        Toast.makeText(
            this,
            if (enabled) "Split view enabled" else "Full-screen view enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDocumentActions(d: Doc) {
        val actions = mutableListOf("Open", "Rename", "Export as PDF", "Share")
        if (stack.isEmpty()) actions.add("Remove from recents")
        MaterialAlertDialogBuilder(this)
            .setTitle(d.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Open" -> open(d)
                    "Rename" -> showRenameDialog(d)
                    "Export as PDF" -> open(d, exportPdf = true)
                    "Share" -> Sharing.share(this, Uri.parse(d.uri), d.name)
                    "Remove from recents" -> confirmRemove(d)
                }
            }
            .show()
    }

    private fun showRenameDialog(d: Doc) {
        RenameDialog.show(this, Uri.parse(d.uri), d.name) { result ->
            Recents.rename(this, d.uri, Doc(result.uri.toString(), result.name))
            show()
        }
    }

    private fun confirmRemove(d: Doc) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove from recents?")
            .setMessage("\"${d.name}\" stays on your device — only this shortcut is removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                Recents.remove(this, d.uri)
                show()
            }
            .show()
    }

    private fun open(d: Doc, exportPdf: Boolean = false) = startActivity(
        Intent(this, ViewerActivity::class.java)
            .putExtra("uri", d.uri)
            .putExtra("name", d.name)
            .putExtra("exportPdf", exportPdf)
    )

    private fun keep(u: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(u, readWrite)
        } catch (_: Exception) {
            try {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun nameOf(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) it.getString(i)?.let { name -> return name }
            }
        }
        return u.lastPathSegment ?: "Document"
    }
}
