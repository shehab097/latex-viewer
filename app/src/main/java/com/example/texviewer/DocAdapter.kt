package com.example.texviewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DocAdapter(
    private val onClick: (Doc) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onStartDrag: (Doc, View) -> Unit,
    private val onDropOnFolder: (Doc, List<Doc>) -> Unit
) : RecyclerView.Adapter<DocAdapter.VH>() {
    companion object {
        const val MODE_GRID = 0
        const val MODE_LIST = 1
    }

    private var items = listOf<Doc>()
    private var mode = MODE_GRID
    private val selected = LinkedHashSet<String>()

    var selectionMode = false
        private set

    // thumbnails are tiny, but avoid decoding them again on every rebind
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun setViewMode(m: Int) {
        if (mode == m) return
        mode = m
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun set(l: List<Doc>) {
        items = l
        val valid = selected.filter { uri -> l.any { it.uri == uri } }
        selected.clear()
        selected.addAll(valid)
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    /** Drops cached bitmaps so regenerated thumbnails are reloaded from disk. */
    fun invalidateThumbs() {
        cache.evictAll()
    }

    fun isSelected(d: Doc) = selected.contains(d.uri)

    fun selectedDocs(): List<Doc> = items.filter { selected.contains(it.uri) }

    fun startSelection(d: Doc) {
        selectionMode = true
        selected.clear()
        selected.add(d.uri)
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun toggle(d: Doc) {
        if (selected.remove(d.uri)) {
            if (selected.isEmpty()) selectionMode = false
        } else {
            selected.add(d.uri)
        }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty() && !selectionMode) {
            onSelectionChanged(0)
            return
        }
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.uri) }
        selectionMode = selected.isNotEmpty()
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val tv: TextView = v.findViewById(R.id.title)
        val badge: TextView = v.findViewById(R.id.badge)
        val check: CheckBox = v.findViewById(R.id.check)
        val drag: ImageView = v.findViewById(R.id.drag)
    }

    override fun getItemViewType(position: Int) = mode

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val layout = if (t == MODE_LIST) R.layout.item_doc_list else R.layout.item_doc
        return VH(LayoutInflater.from(p.context).inflate(layout, p, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val d = items[i]
        h.tv.text = d.name

        val ext = d.name.substringAfterLast('.', "").uppercase()
        h.badge.visibility = if (d.dir == null && ext.isNotEmpty()) View.VISIBLE else View.GONE
        h.badge.text = when (ext) {
            "MARKDOWN", "MDOWN", "MKD" -> "MD"
            "LATEX" -> "TEX"
            else -> ext.take(4)
        }
        h.img.contentDescription =
            if (d.dir != null) "Folder ${d.name}" else "Preview of ${d.name}"

        val path = d.thumb
        val file = path?.let { File(it) }
        val bitmap = if (file != null && file.exists()) {
            cache[path] ?: decodeSampled(path, 720)?.also { cache.put(path, it) }
        } else null

        if (bitmap != null) {
            h.img.scaleType = ImageView.ScaleType.CENTER_CROP
            h.img.setImageBitmap(bitmap)
        } else {
            h.img.scaleType = ImageView.ScaleType.CENTER
            h.img.setImageResource(if (d.dir != null) R.drawable.ic_folder else R.drawable.ic_file)
        }

        h.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        h.check.isChecked = selected.contains(d.uri)
        h.drag.contentDescription = "Drag ${d.name}"
        h.itemView.alpha = 1f

        h.itemView.setOnClickListener {
            if (selectionMode) toggle(d) else onClick(d)
        }
        h.itemView.setOnLongClickListener {
            if (selectionMode) toggle(d) else startSelection(d)
            true
        }
        h.drag.setOnLongClickListener {
            onStartDrag(d, h.itemView)
            true
        }

        h.itemView.setOnDragListener(if (d.dir != null) folderDropListener(d, h) else null)
    }

    private fun folderDropListener(folder: Doc, h: VH) = View.OnDragListener { _, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> event.localState is List<*>
            DragEvent.ACTION_DRAG_ENTERED -> {
                h.itemView.alpha = 0.6f
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                h.itemView.alpha = 1f
                true
            }
            DragEvent.ACTION_DROP -> {
                h.itemView.alpha = 1f
                @Suppress("UNCHECKED_CAST")
                val docs = event.localState as? List<Doc> ?: emptyList()
                if (docs.isNotEmpty()) onDropOnFolder(folder, docs)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                h.itemView.alpha = 1f
                true
            }
            else -> false
        }
    }

    private fun decodeSampled(path: String, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > targetPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
