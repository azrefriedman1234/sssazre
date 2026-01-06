package com.pasiflonet.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.drinkless.tdlib.TdApi

class MessagesAdapter(
    private val onClick: (TdApi.Message) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = ArrayList<TdApi.Message>(200)

    fun submit(list: List<TdApi.Message>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.bind(m, onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val t1: TextView = v.findViewById(android.R.id.text1)
        private val t2: TextView = v.findViewById(android.R.id.text2)

        fun bind(msg: TdApi.Message, onClick: (TdApi.Message) -> Unit) {
            t1.text = "chat=${msg.chatId}  id=${msg.id}"
            t2.text = extractText(msg)
            itemView.setOnClickListener { onClick(msg) }
        }

        private fun extractText(msg: TdApi.Message): String {
            val c = msg.content ?: return ""
            return if (c is TdApi.MessageText) {
                c.text?.text ?: ""
            } else {
                c.javaClass.simpleName
            }
        }
    }
}
