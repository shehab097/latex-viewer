package com.example.texviewer

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DocAdapter(private val onClick: (Doc) -> Unit, private val onLong: (Doc) -> Unit) : RecyclerView.Adapter<DocAdapter.VH>() {
    private var items = listOf<Doc>()
    @SuppressLint("NotifyDataSetChanged") fun set(l: List<Doc>) { items = l; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img); val tv: TextView = v.findViewById(R.id.title); val badge: TextView = v.findViewById(R.id.badge)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_doc, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val d = items[i]; h.tv.text = d.name
        val ext = d.name.substringAfterLast('.', "").uppercase()
        h.badge.text = ext; h.badge.visibility = if (d.dir == null && ext.isNotEmpty()) View.VISIBLE else View.GONE
        val f = d.thumb?.let { File(it) }
        if (f != null && f.exists()) { h.img.scaleType = ImageView.ScaleType.CENTER_CROP; h.img.setImageBitmap(BitmapFactory.decodeFile(f.path)) }
        else { h.img.scaleType = ImageView.ScaleType.CENTER; h.img.setImageResource(if (d.dir != null) R.drawable.ic_folder else R.drawable.ic_file) }
        h.itemView.setOnClickListener { onClick(d) }
        h.itemView.setOnLongClickListener { onLong(d); true }
    }
}
