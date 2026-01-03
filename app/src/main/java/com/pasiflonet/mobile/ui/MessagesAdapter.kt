package com.pasiflonet.mobile.ui

import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pasiflonet.mobile.databinding.RowMessageBinding
import com.pasiflonet.mobile.model.MessageRow
import com.pasiflonet.mobile.util.TimeFmt

class MessagesAdapter(
    private val onDetails: (MessageRow) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = ArrayList<MessageRow>()

    class VH(val b: RowMessageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.tvType.text = item.typeLabel
        holder.b.tvDate.text = TimeFmt.formatUnixSeconds(item.unixSeconds)
        holder.b.tvText.text = item.text.ifBlank { "—" }
        holder.b.btnDetails.setOnClickListener { onDetails(item) }

        when {
            item.thumbLocalPath != null -> {
                holder.b.ivThumb.load("file://${item.thumbLocalPath}") { crossfade(true) }
            }
            !item.miniThumbBase64.isNullOrBlank() -> {
                val bytes = Base64.decode(item.miniThumbBase64, Base64.DEFAULT)
                holder.b.ivThumb.load(bytes) { crossfade(true) }
            }
            else -> holder.b.ivThumb.setImageDrawable(null)
        }
    }

    override fun getItemCount(): Int = items.size

    fun prepend(row: MessageRow) {
        items.add(0, row)
        notifyItemInserted(0)
    }

    fun trimTo(max: Int) {
        if (items.size <= max) return
        val removeCount = items.size - max
        repeat(removeCount) { items.removeAt(items.size - 1) }
        notifyDataSetChanged()
    }
}
