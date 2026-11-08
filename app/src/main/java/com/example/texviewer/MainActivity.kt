package com.example.texviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: DocAdapter
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var collapsing: CollapsingToolbarLayout
    private val stack = ArrayList<DocumentFile>()
    private val exts = setOf("tex", "ltx", "latex", "md", "markdown", "mdown", "mkd", "txt")
    private var query = ""

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { keep(it); open(Doc(it.toString(), nameOf(it))) }
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
        collapsing = findViewById(R.id.collapsing)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
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

        adapter = DocAdapter(
            onClick = { d ->
                if (d.dir != null) {
                    stack.add(d.dir)
                    show()
                } else {
                    open(d)
                }
            },
            onLong = { d -> if (stack.isEmpty()) confirmRemove(d) }
        )
        val cols = maxOf(2, resources.configuration.screenWidthDp / 190) // phones 2, tablets 4+
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = GridLayoutManager(this@MainActivity, cols)
            adapter = this@MainActivity.adapter
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fabFile).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
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
        keep(u)
        val doc = Doc(u.toString(), nameOf(u))
        Recents.add(this, doc)
        open(doc)
    }

    override fun onResume() {
        super.onResume()
        adapter.invalidateThumbs()
        show()
    }

    private fun show() {
        back.isEnabled = stack.isNotEmpty()
        val inFolder = stack.isNotEmpty()
        collapsing.title = if (inFolder) stack.last().name ?: "Library" else "Library"

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

    private fun open(d: Doc) = startActivity(
        Intent(this, ViewerActivity::class.java)
            .putExtra("uri", d.uri)
            .putExtra("name", d.name)
    )

    private fun keep(u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
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
