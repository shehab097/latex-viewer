package com.example.texviewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DocAdapter(
    private val onClick: (Doc) -> Unit,
    private val onLong: (Doc) -> Unit
) : RecyclerView.Adapter<DocAdapter.VH>() {
    private var items = listOf<Doc>()

    // thumbnails are tiny, but avoid decoding them again on every rebind
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @SuppressLint("NotifyDataSetChanged")
    fun set(l: List<Doc>) {
        items = l
        notifyDataSetChanged()
    }

    /** Drops cached bitmaps so regenerated thumbnails are reloaded from disk. */
    fun invalidateThumbs() {
        cache.evictAll()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val tv: TextView = v.findViewById(R.id.title)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_doc, p, false))

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

        h.itemView.setOnClickListener { onClick(d) }
        h.itemView.setOnLongClickListener {
            onLong(d)
            true
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
