package com.example.texviewer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
    private lateinit var fabContainer: View
    private val prefs by lazy { getSharedPreferences("p", 0) }

    private var splitEnabled = false
    private var viewMode = DocAdapter.MODE_GRID
    private var managedRoot: DocumentFile? = null
    private var externalTree: DocumentFile? = null
    private var afterSetup: (() -> Unit)? = null
    private var pendingImportUri: Uri? = null
    private val clipboard = mutableListOf<Doc>()
    private var clipboardCut = false

    private val deviceSize: Pair<Int, Int>
        get() {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
        }
    private val isLargeScreen: Boolean
        get() {
            val (w, h) = deviceSize
            return minOf(w, h) / resources.displayMetrics.density >= 600f
        }
    private val isLandscape: Boolean
        get() {
            val (w, h) = deviceSize
            return w > h
        }
    private val canSplit: Boolean
        get() = isLargeScreen && isLandscape && runCatching {
            SplitController.getInstance(this).splitSupportStatus ==
                SplitController.SplitSupportStatus.SPLIT_AVAILABLE
        }.getOrDefault(false)

    private val stack = ArrayList<DocumentFile>()
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

    // Documents is the default location in the system picker.  The user can
    // still choose any other folder; the app folder is created inside it.
    private val documentsUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments")

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let {
            keep(it)
            val n = nameOf(it)
            if (Library.isSupported(n)) importAndOpen(it, n)
            else Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
        }
    }

    /** Browse an external folder without changing the managed library root. */
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        u?.let {
            keep(it)
            DocumentFile.fromTreeUri(this, it)?.let { d ->
                externalTree = d
                stack.clear()
                stack.add(d)
                adapter.clearSelection()
                show()
            }
        }
    }

    private val setupLibrary = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u == null) {
            afterSetup = null
            pendingImportUri = null
            Toast.makeText(this, "Library folder not set", Toast.LENGTH_SHORT).show()
        } else {
            val root = Library.setupRoot(this, u)
            if (root == null) {
                afterSetup = null
                pendingImportUri = null
                Toast.makeText(this, "Could not create the library folder", Toast.LENGTH_LONG).show()
            } else {
                managedRoot = root
                show()
                migrateExistingRecents(root, pendingImportUri)
                pendingImportUri = null
                val pending = afterSetup
                afterSetup = null
                pending?.invoke()
            }
        }
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (adapter.selectionMode) {
                adapter.clearSelection()
                return
            }
            if (stack.isNotEmpty()) {
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) externalTree = null
                show()
            }
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
        fabContainer = findViewById(R.id.fabContainer)

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
        viewMode = prefs.getInt("library_view", DocAdapter.MODE_GRID)
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
                R.id.view_mode -> {
                    toggleViewMode()
                    true
                }
                R.id.paste -> {
                    paste()
                    true
                }
                R.id.open_folder -> {
                    pickTree.launch(null)
                    true
                }
                R.id.sel_copy -> {
                    copySelection(false)
                    true
                }
                R.id.sel_cut -> {
                    copySelection(true)
                    true
                }
                R.id.sel_move -> {
                    moveSelection()
                    true
                }
                R.id.sel_delete -> {
                    confirmDelete(adapter.selectedDocs())
                    true
                }
                R.id.sel_more -> {
                    adapter.selectedDocs().singleOrNull()?.let { showMoreActions(it) }
                    true
                }
                R.id.sel_select_all -> {
                    adapter.selectAll()
                    true
                }
                else -> false
            }
        }

        adapter = DocAdapter(
            onClick = { d ->
                if (d.dir != null) {
                    stack.add(d.dir)
                    adapter.clearSelection()
                    show()
                } else if (d.managed) {
                    open(d)
                } else {
                    // Opening a document from Recents or an external folder
                    // imports a copy into the managed library first.
                    importAndOpen(Uri.parse(d.uri), d.name)
                }
            },
            onSelectionChanged = { count -> onSelectionChanged(count) },
            onStartDrag = { d, v -> startDrag(d, v) },
            onDropOnFolder = { folder, docs -> folder.dir?.let { moveDocs(docs, it) } }
        )

        val density = resources.displayMetrics.density
        val windowWidthPx = (resources.configuration.screenWidthDp * density).toInt()
        val maxGridWidth = (1400 * density).toInt()
        findViewById<RecyclerView>(R.id.list).apply {
            if (windowWidthPx > maxGridWidth) {
                (layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams).apply {
                    width = maxGridWidth
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                }
            }
            adapter = this@MainActivity.adapter
        }
        applyLayoutManager()

        findViewById<ExtendedFloatingActionButton>(R.id.fabFile).setOnClickListener {
            pickFile.launch(supportedMimes)
        }
        findViewById<FloatingActionButton>(R.id.fabFolder).setOnClickListener {
            newFolder()
        }

        // The root no longer fits system windows so the library and the page
        // can draw behind the gesture bar. Keep the floating buttons above the
        // bar (and give their shadows room on the right) with the real insets.
        val fabBaseMargin = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(fabContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            (v.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = fabBaseMargin + bars.bottom
                rightMargin = fabBaseMargin + bars.right
                v.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(fabContainer)
        onBackPressedDispatcher.addCallback(this, back)

        managedRoot = Library.loadRoot(this)
        updateMenuVisibility()

        if (intent?.action == Intent.ACTION_VIEW) handleIntent(intent) else maybePromptSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val loaded = Library.loadRoot(this)
        managedRoot = loaded ?: managedRoot?.takeIf {
            runCatching { it.exists() }.getOrDefault(false)
        }
        adapter.invalidateThumbs()
        show()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val u = i.data ?: return
        val n = nameOf(u)
        if (!Library.isSupported(n)) {
            Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            return
        }
        keep(u)
        importAndOpen(u, n)
    }

    private fun currentFolder(): DocumentFile? = stack.lastOrNull() ?: managedRoot

    private fun currentTitle(): String =
        if (stack.isNotEmpty()) stack.last().name ?: "Library" else "Library"

    private fun gridColumns(): Int = when {
        isLargeScreen && isLandscape -> 5
        isLargeScreen -> 3
        else -> 2
    }

    private fun applyLayoutManager() {
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = if (viewMode == DocAdapter.MODE_LIST) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, gridColumns())
        }
        adapter.setViewMode(viewMode)
    }

    private fun toggleViewMode() {
        viewMode = if (viewMode == DocAdapter.MODE_GRID) DocAdapter.MODE_LIST else DocAdapter.MODE_GRID
        prefs.edit().putInt("library_view", viewMode).apply()
        applyLayoutManager()
        updateMenuVisibility()
    }

    private fun show() {
        back.isEnabled = stack.isNotEmpty() || adapter.selectionMode
        val current = currentFolder()
        val managed = stack.isEmpty() || stack.first() != externalTree

        var list: List<Doc> = if (current == null) {
            Recents.load(this)
        } else {
            Library.list(this, current, managed)
        }
        if (query.isNotBlank()) list = list.filter { it.name.contains(query.trim(), true) }

        adapter.set(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        when {
            query.isNotBlank() -> {
                emptyTitle.text = "No matches"
                emptyText.text = "Nothing here matches \"${query.trim()}\""
            }
            current == null -> {
                emptyTitle.text = "Set up your library"
                emptyText.text =
                    "Tap Open file to import a document. The app will ask for a Documents folder and keep a copy of every file you open."
            }
            current.uri == managedRoot?.uri && list.isEmpty() -> {
                emptyTitle.text = "Library is empty"
                emptyText.text =
                    "Tap Open file to import a document. Copies are saved in Documents/${Library.FOLDER_NAME}."
            }
            else -> {
                emptyTitle.text = "Nothing to show"
                emptyText.text = "This folder has no LaTeX, Markdown or text documents"
            }
        }
        updateTitle()
        updateMenuVisibility()
    }

    private fun updateTitle() {
        toolbar.title = if (adapter.selectionMode) {
            "${adapter.selectedDocs().size} selected"
        } else {
            currentTitle()
        }
    }

    private fun updateMenuVisibility() {
        val menu = toolbar.menu
        val sel = adapter.selectionMode
        val selected = if (sel) adapter.selectedDocs() else emptyList()
        val hasDocuments = selected.any { it.document != null }

        menu.findItem(R.id.search)?.isVisible = !sel
        menu.findItem(R.id.view_mode)?.apply {
            isVisible = !sel
            setIcon(if (viewMode == DocAdapter.MODE_GRID) R.drawable.ic_list else R.drawable.ic_grid)
            title = if (viewMode == DocAdapter.MODE_GRID) "List view" else "Grid view"
        }
        menu.findItem(R.id.paste)?.isVisible = !sel && clipboard.isNotEmpty()
        menu.findItem(R.id.split_view)?.apply {
            isVisible = !sel && canSplit
            isChecked = splitEnabled
        }
        menu.findItem(R.id.open_folder)?.isVisible = !sel

        menu.findItem(R.id.sel_copy)?.isVisible = sel && hasDocuments
        menu.findItem(R.id.sel_cut)?.isVisible = sel && hasDocuments
        menu.findItem(R.id.sel_move)?.isVisible = sel && hasDocuments
        menu.findItem(R.id.sel_delete)?.isVisible = sel
        menu.findItem(R.id.sel_more)?.isVisible = sel && selected.size == 1
        menu.findItem(R.id.sel_select_all)?.isVisible = sel && selected.size < adapter.itemCount
    }

    private fun onSelectionChanged(count: Int) {
        back.isEnabled = stack.isNotEmpty() || count > 0
        if (count == 0) {
            fabContainer.visibility = View.VISIBLE
            toolbar.navigationIcon = null
            toolbar.navigationContentDescription = null
            toolbar.setNavigationOnClickListener(null)
            toolbar.title = currentTitle()
        } else {
            fabContainer.visibility = View.GONE
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = "Cancel selection"
            toolbar.setNavigationOnClickListener { adapter.clearSelection() }
            toolbar.title = "$count selected"
        }
        updateMenuVisibility()
    }

    private fun importAndOpen(source: Uri, name: String) {
        if (!Library.isSupported(name)) {
            Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            return
        }
        val root = managedRoot
        if (root == null) {
            pendingImportUri = source
            afterSetup = { importAndOpen(source, name) }
            explainLibrarySetup()
            return
        }
        val dest = if (stack.isNotEmpty() && stack.first() != externalTree) stack.last() else root
        Toast.makeText(this, "Saving a copy in the library…", Toast.LENGTH_SHORT).show()
        Thread {
            val copy = runCatching { Library.import(this, source, name, dest) }.getOrNull()
            runOnUiThread {
                if (copy == null) {
                    Toast.makeText(this, "Could not copy the document", Toast.LENGTH_LONG).show()
                } else {
                    val n = copy.name ?: name
                    val d = Doc(
                        uri = copy.uri.toString(),
                        name = n,
                        thumb = Recents.thumbFile(this, copy.uri.toString()).path,
                        dir = null,
                        document = copy,
                        managed = true
                    )
                    Recents.add(this, d)
                    show()
                    open(d)
                }
            }
        }.start()
    }

    private fun maybePromptSetup() {
        if (managedRoot != null || prefs.getBoolean("setup_prompted", false)) return
        prefs.edit().putBoolean("setup_prompted", true).apply()
        explainLibrarySetup()
    }

    private fun explainLibrarySetup() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Set up your library")
            .setMessage(
                "Choose the Documents folder. A \"${Library.FOLDER_NAME}\" folder will be " +
                    "created inside it, and a copy of every document you open will be saved there. " +
                    "Your original files are never changed."
            )
            .setNegativeButton("Cancel") { _, _ ->
                afterSetup = null
                pendingImportUri = null
            }
            .setPositiveButton("Choose folder") { _, _ -> requestLibrarySetup() }
            .show()
    }

    private fun requestLibrarySetup() {
        runCatching { setupLibrary.launch(documentsUri) }.onFailure {
            afterSetup = null
            pendingImportUri = null
            Toast.makeText(this, "Could not open the folder picker", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A library is introduced after the first version of the app already had
     * a Recents list. Copy those previously opened files into the new managed
     * folder so the user does not lose easy access to them.
     */
    private fun migrateExistingRecents(root: DocumentFile, skipUri: Uri?) {
        if (prefs.getBoolean("recents_migrated", false)) return
        prefs.edit().putBoolean("recents_migrated", true).apply()
        val skip = skipUri?.toString()
        val recents = Recents.load(this).filter { it.uri != skip }
        if (recents.isEmpty()) return
        Toast.makeText(
            this,
            "Copying ${recents.size} recent document(s) into the library…",
            Toast.LENGTH_LONG
        ).show()
        Thread {
            var imported = 0
            recents.forEach { d ->
                val source = runCatching { Uri.parse(d.uri) }.getOrNull() ?: return@forEach
                val exists = runCatching {
                    DocumentFile.fromSingleUri(this, source)?.exists() == true
                }.getOrDefault(false)
                if (!exists) return@forEach
                if (runCatching {
                        Library.import(this, source, d.name, root)
                    }.getOrNull() != null
                ) imported++
            }
            runOnUiThread {
                if (imported > 0) {
                    adapter.invalidateThumbs()
                    show()
                    Toast.makeText(
                        this,
                        "Imported $imported recent document(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun newFolder() {
        val current = currentFolder()
        if (current == null) {
            pendingImportUri = null
            afterSetup = { newFolder() }
            explainLibrarySetup()
            return
        }
        val input = EditText(this).apply {
            hint = "Folder name"
            setSingleLine()
            setText("New folder")
            selectAll()
        }
        val container = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 8, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New folder")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "New folder" }
                val created = Library.createFolder(current, name)
                if (created == null) {
                    Toast.makeText(this, "Could not create the folder", Toast.LENGTH_SHORT).show()
                } else {
                    show()
                }
            }
            .show()
    }

    private fun startDrag(d: Doc, anchor: View) {
        val docs = if (adapter.isSelected(d)) adapter.selectedDocs() else listOf(d)
        if (docs.isEmpty()) return
        val clip = ClipData.newPlainText("Library items", "${docs.size} item(s)")
        runCatching {
            anchor.startDragAndDrop(clip, View.DragShadowBuilder(anchor), docs, 0)
        }
    }

    private fun moveSelection() {
        val docs = adapter.selectedDocs()
        if (docs.isEmpty()) return
        val root = managedRoot
        if (root == null) {
            Toast.makeText(this, "Set up the library first", Toast.LENGTH_SHORT).show()
            return
        }
        val folders = Library.collectFolders(root).filter { f ->
            docs.none { it.dir != null && Library.isInSubtree(it.dir, f.dir) }
        }
        if (folders.isEmpty()) {
            Toast.makeText(this, "No destination folder", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = folders.map { if (it.path.isEmpty()) "Library root" else it.path }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Move ${docs.size} item(s) to")
            .setItems(labels) { _, which -> moveDocs(docs, folders[which].dir) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveDocs(docs: List<Doc>, dest: DocumentFile) {
        val filtered = docs.filter { it.document != null }
        if (filtered.isEmpty()) {
            Toast.makeText(this, "These shortcuts have no file to move", Toast.LENGTH_SHORT).show()
            return
        }
        if (filtered.any { it.dir != null && Library.isInSubtree(it.dir, dest) }) {
            Toast.makeText(this, "Cannot move a folder into itself", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Moving…", Toast.LENGTH_SHORT).show()
        Thread {
            var moved = 0
            filtered.forEach { d ->
                val src = d.document ?: return@forEach
                if (runCatching { src.parentFile?.uri }.getOrNull() == dest.uri) {
                    moved++
                    return@forEach
                }
                if (Library.move(this, src, dest)) {
                    moved++
                    Recents.remove(this, d.uri)
                }
            }
            runOnUiThread {
                adapter.clearSelection()
                show()
                Toast.makeText(
                    this,
                    if (moved > 0) "Moved $moved item(s)" else "Could not move",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun copySelection(cut: Boolean) {
        val docs = adapter.selectedDocs().filter { it.document != null }
        if (docs.isEmpty()) {
            Toast.makeText(this, "These shortcuts have no file to copy", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.clear()
        clipboard.addAll(docs)
        clipboardCut = cut
        adapter.clearSelection()
        Toast.makeText(
            this,
            if (cut) "Cut ${docs.size} item(s). Open a folder and tap Paste."
            else "Copied ${docs.size} item(s). Open a folder and tap Paste.",
            Toast.LENGTH_LONG
        ).show()
        updateMenuVisibility()
    }

    private fun paste() {
        if (clipboard.isEmpty()) return
        val dest = currentFolder()
        if (dest == null) {
            Toast.makeText(this, "Set up the library first", Toast.LENGTH_SHORT).show()
            return
        }
        val docs = clipboard.toList()
        val cut = clipboardCut
        val filtered = docs.filter { it.document != null }
        if (filtered.isEmpty()) {
            Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show()
            return
        }
        if (filtered.any { it.dir != null && Library.isInSubtree(it.dir, dest) }) {
            Toast.makeText(this, "Cannot paste a folder into itself", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, if (cut) "Moving…" else "Copying…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            filtered.forEach { d ->
                val src = d.document ?: return@forEach
                if (cut && runCatching { src.parentFile?.uri }.getOrNull() == dest.uri) {
                    ok++
                    return@forEach
                }
                val done = if (cut) {
                    Library.move(this, src, dest)
                } else {
                    Library.copy(this, src, dest) != null
                }
                if (done) {
                    ok++
                    if (cut) Recents.remove(this, d.uri)
                }
            }
            runOnUiThread {
                if (cut) {
                    clipboard.clear()
                    clipboardCut = false
                }
                show()
                Toast.makeText(
                    this,
                    if (ok > 0) "Pasted $ok item(s)" else "Could not paste",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun confirmDelete(docs: List<Doc>) {
        if (docs.isEmpty()) return
        val recentsOnly = docs.all { it.document == null }
        val external = docs.any { !it.managed && it.document != null }
        val title = if (recentsOnly) "Remove from recents?" else "Delete ${docs.size} item(s)?"
        val message = when {
            recentsOnly -> "The shortcuts are removed. The files stay on your device."
            external -> "Files outside the library are deleted permanently."
            else -> "The library copies are deleted. The original files are not affected."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (recentsOnly) "Remove" else "Delete") { _, _ ->
                deleteDocs(docs)
            }
            .show()
    }

    private fun deleteDocs(docs: List<Doc>) {
        if (docs.isEmpty()) return
        Toast.makeText(this, "Deleting…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            docs.forEach { d ->
                val doc = d.document
                val deleted = doc == null || Library.delete(doc)
                if (deleted) ok++
                Recents.remove(this, d.uri)
            }
            runOnUiThread {
                adapter.clearSelection()
                show()
                Toast.makeText(
                    this,
                    if (ok > 0) "Removed $ok item(s)" else "Could not delete",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun showMoreActions(d: Doc) {
        val actions = mutableListOf<String>()
        if (d.dir != null) {
            actions += "Open"
            actions += "Rename"
            actions += "Delete"
        } else if (d.managed) {
            actions += "Open"
            actions += "Rename"
            actions += "Export as PDF"
            actions += "Share"
            actions += "Delete"
        } else {
            // Browsing an external folder or the legacy recents list.  The
            // normal tap still imports a copy, but keep a direct-open path so
            // documents with relative images keep working from their folder.
            actions += "Open original"
            actions += "Open and import a copy"
            actions += "Rename"
            actions += "Export as PDF"
            actions += "Share"
            if (d.document != null) actions += "Delete"
            else actions += "Remove from recents"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(d.name)
            .setItems(actions.toTypedArray()) { _, which ->
                adapter.clearSelection()
                when (actions[which]) {
                    "Open" -> if (d.dir != null) {
                        stack.add(d.dir)
                        show()
                    } else {
                        open(d)
                    }
                    "Open original" -> open(d)
                    "Open and import a copy" -> importAndOpen(Uri.parse(d.uri), d.name)
                    "Rename" -> showRenameDialog(d)
                    "Export as PDF" -> open(d, exportPdf = true)
                    "Share" -> Sharing.share(this, Uri.parse(d.uri), d.name)
                    "Delete", "Remove from recents" -> confirmDelete(listOf(d))
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
