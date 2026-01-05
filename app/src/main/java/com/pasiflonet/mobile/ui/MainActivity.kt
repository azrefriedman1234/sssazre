package com.pasiflonet.mobile.ui

import com.pasiflonet.mobile.R
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.util.TempCleaner
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: MessagesAdapter
    private val liveMsgs = ArrayList<TdApi.Message>(200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutId())

        adapter = MessagesAdapter { msg ->
            // פותח DetailsActivity (מעביר מזהים בסיסיים – לא שוברים קומפילציה גם אם לא משתמשים שם)
            val i = Intent(this, DetailsActivity::class.java).apply {
                putExtra("chat_id", msg.chatId)
                putExtra("message_id", msg.id)
            }
            startActivity(i)
        }

        // RecyclerView (מאתרים ID בצורה דינמית כדי לא ליפול על R.id לא קיים)
        val rv = findRecyclerView()
        if (rv != null) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = adapter
        } else {
            Toast.makeText(this, "RecyclerView לא נמצא בלייאאוט", Toast.LENGTH_SHORT).show()
        }

        // כפתור ניקוי זמניים (מנקה רק cacheDir/pasiflonet_tmp)
        hookClearTempButton()

        // רענון ראשוני
        refreshList()
    }

    private fun refreshList() {
        // אם ב־MessagesAdapter יש submit(list) – נשתמש בו, אחרת ננסה setItems/setMessages/submitList, ואם כלום – notify
        try {
            val m = adapter.javaClass.methods.firstOrNull { it.name == "submit" && it.parameterTypes.size == 1 }
            if (m != null) {
                m.invoke(adapter, liveMsgs)
                return
            }
        } catch (_: Throwable) {}

        try {
            val m = adapter.javaClass.methods.firstOrNull { it.name in listOf("setItems","setMessages","submitList","update","setData") && it.parameterTypes.size == 1 }
            if (m != null) {
                m.invoke(adapter, liveMsgs)
                return
            }
        } catch (_: Throwable) {}

        adapter.notifyDataSetChanged()
    }

    private fun hookClearTempButton() {
        val id = firstExistingId(
            "btn_clear_temp",
            "btnClearTemp",
            "btn_clear",
            "btnClear",
            "btn_temp_clear",
            "btnTempClear"
        )
        if (id == 0) return

        val v = findViewById<View>(id) ?: return
        v.setOnClickListener {
            val (files, bytes) = TempCleaner.clearTemp(this)
            Toast.makeText(this, "נוקו $files קבצים (" + (bytes/1024) + "KB)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findRecyclerView(): RecyclerView? {
        val id = firstExistingId(
            "recycler",
            "recyclerView",
            "rvMessages",
            "messagesRecycler",
            "rv",
            "list"
        )
        if (id == 0) return null
        return findViewById(id)
    }

    private fun getLayoutId(): Int {
        // activity_main הוא הדיפולט
        val id = resources.getIdentifier("activity_main", "layout", packageName)
        return if (id != 0) id else android.R.layout.list_content
    }

    private fun firstExistingId(vararg names: String): Int {
        for (n in names) {
            val id = resources.getIdentifier(n, "id", packageName)
            if (id != 0) return id
        }
        return 0
    }

    // נקודת כניסה עתידית: כשתרצה לחבר לייב מה־TDLib, נקרא כאן לעדכון הליסט ואז refreshList()
    fun addIncomingMessage(m: TdApi.Message) {
        liveMsgs.add(0, m)
        if (liveMsgs.size > 200) liveMsgs.removeAt(liveMsgs.size - 1)
        runOnUiThread { refreshList() }
    }
}
