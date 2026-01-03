package com.pasiflonet.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiMsg(val chatId: Long,
    val msgId: Long,
    val dateSec: Int,
    val from: String,
    val text: String
    val mediaUri: String? = null,
    val mediaMime: String? = null,
    val miniThumbB64: String? = null,
)

class MessagesAdapter(
    private val onDetails: (UiMsg) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = ArrayList<UiMsg>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun prepend(m: UiMsg) {
        items.add(0, m)
        if (items.size > 120) items.removeAt(items.size - 1)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_message, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvDate.text = fmt.format(Date(m.dateSec.toLong() * 1000L))
        holder.tvFrom.text = m.from
        holder.tvMsg.text = m.text
        holder.btnDetails.setOnClickListener { onDetails(m) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvDate)
        val tvFrom: TextView = v.findViewById(R.id.tvFrom)
        val tvMsg: TextView = v.findViewById(R.id.tvMsg)
        val btnDetails: Button = v.findViewById(R.id.btnDetails)
    }
}
