package com.pasiflonet.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.model.MessageRow

class MessagesAdapter(
    private val onDetails: (MessageRow) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = ArrayList<MessageRow>()

    fun prepend(row: MessageRow) {
        items.add(0, row)           // חדש למעלה
        notifyItemInserted(0)
    }

    fun trimTo(max: Int) {
        if (items.size <= max) return
        while (items.size > max) items.removeAt(items.size - 1) // ישן נמחק
        notifyDataSetChanged()
    }

    fun clearAll() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_message, parent, false)
        return VH(v, onDetails)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View, private val onDetails: (MessageRow) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvMsg: TextView = v.findViewById(R.id.tvMsg)
        private val tvDate: TextView = v.findViewById(R.id.tvDate)
        private val tvType: TextView = v.findViewById(R.id.tvType)
        private val btnDetails: TextView = v.findViewById(R.id.btnDetails)

        fun bind(row: MessageRow) {
            tvMsg.text = (row.text).toString().ifBlank { "(ללא טקסט)" }
            tvDate.text = ""
            tvType.text = row.typeLabel
            btnDetails.setOnClickListener { onDetails(row) }
        }
    }
}
