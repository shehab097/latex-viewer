package com.example.texviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: DocAdapter
    private lateinit var empty: View
    private lateinit var collapsing: CollapsingToolbarLayout
    private val stack = ArrayList<DocumentFile>()
    private val exts = setOf("tex", "ltx", "md", "markdown", "txt")
    private var query = ""

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { keep(it); open(Doc(it.toString(), nameOf(it))) } }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        u?.let { keep(it); DocumentFile.fromTreeUri(this, it)?.let { d -> stack.clear(); stack.add(d); show() } } }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { stack.removeAt(stack.lastIndex); show() } }

    override fun onCreate(s: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        empty = findViewById(R.id.empty)
        collapsing = findViewById(R.id.collapsing)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        (tb.menu.findItem(R.id.search).actionView as SearchView).apply {
            queryHint = "Search documents"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = false
                override fun onQueryTextChange(q: String?): Boolean { query = q.orEmpty(); show(); return true }
            })
        }
        adapter = DocAdapter(
            onClick = { d -> if (d.dir != null) { stack.add(d.dir); show() } else open(d) },
            onLong = { d -> if (stack.isEmpty()) { Recents.remove(this, d.uri); show() } })
        val cols = maxOf(2, resources.configuration.screenWidthDp / 190) // phones 2, tablets 4+
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = GridLayoutManager(this@MainActivity, cols); adapter = this@MainActivity.adapter }
        findViewById<ExtendedFloatingActionButton>(R.id.fabFile).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<FloatingActionButton>(R.id.fabFolder).setOnClickListener { pickTree.launch(null) }
        onBackPressedDispatcher.addCallback(this, back)
    }

    override fun onResume() { super.onResume(); show() }

    private fun show() {
        back.isEnabled = stack.isNotEmpty()
        collapsing.title = stack.lastOrNull()?.name ?: "Library"
        var list: List<Doc> = if (stack.isEmpty()) Recents.load(this) else stack.last().listFiles().toList()
            .filter { it.isDirectory || it.name?.substringAfterLast('.', "")?.lowercase() in exts }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
            .map { Doc(it.uri.toString(), it.name ?: "?", Recents.thumbFile(this, it.uri.toString()).path, if (it.isDirectory) it else null) }
        if (query.isNotBlank()) list = list.filter { it.name.contains(query.trim(), true) }
        adapter.set(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(d: Doc) = startActivity(Intent(this, ViewerActivity::class.java).putExtra("uri", d.uri).putExtra("name", d.name))
    private fun keep(u: Uri) { try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
    private fun nameOf(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) { val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return it.getString(i) } }
        return u.lastPathSegment ?: "Document"
    }
}
