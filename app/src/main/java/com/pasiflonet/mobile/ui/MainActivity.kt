package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.util.TempCleaner
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: MessagesAdapter
    private val liveMsgs = ArrayList<TdApi.Message>(200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = MessagesAdapter { msg ->
            val i = Intent(this, DetailsActivity::class.java).apply {
                putExtra("chat_id", msg.chatId)
                putExtra("message_id", msg.id)
            }
            startActivity(i)
        }

        val rv = findFirstRecyclerView(window.decorView)
        if (rv != null) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = adapter
        } else {
            Toast.makeText(this, "RecyclerView לא נמצא בלייאאוט", Toast.LENGTH_SHORT).show()
        }

        hookButtons()

        // לייב הודעות מה-TDLib
        TdLibManager.addUpdatesHandler { obj ->
            if (obj is TdApi.UpdateNewMessage) {
                val msg = obj.message ?: return@addUpdatesHandler
                runOnUiThread {
                    liveMsgs.add(0, msg)
                    while (liveMsgs.size > 120) liveMsgs.removeAt(liveMsgs.size - 1)
                    adapter.submit(liveMsgs)
                }
            }
        }
    }

    private fun hookButtons() {
        // כפתור הגדרות
        findButtonByIdOrName("btn_settings", "btnSettings")?.setOnClickListener {
            try {
                startActivity(Intent(this, SettingsActivity::class.java))
            } catch (t: Throwable) {
                Toast.makeText(this, "SettingsActivity לא נמצא/שגיאה: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        // כפתור יציאה/סגירה
        findButtonByIdOrName("btn_exit", "btnExit", "btn_close", "btnClose")?.setOnClickListener {
            finishAffinity()
        }

        // כפתור ניקוי קבצים זמניים (רק cacheDir/pasiflonet_tmp)
        findButtonByIdOrName("btn_clear_temp", "btnClearTemp")?.setOnClickListener {
            val n = TempCleaner.clearTemp(this)
            Toast.makeText(this, "נמחקו $n קבצים זמניים", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findButtonByIdOrName(vararg names: String): Button? {
        for (name in names) {
            val id = resources.getIdentifier(name, "id", packageName)
            if (id != 0) {
                val v = findViewById<View>(id)
                if (v is Button) return v
            }
        }
        // fallback: הכפתור הראשון שנמצא בעץ – רק אם לא מצאנו ID
        return findFirstButton(window.decorView)
    }

    private fun findFirstRecyclerView(root: View?): RecyclerView? {
        if (root == null) return null
        if (root is RecyclerView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findFirstRecyclerView(root.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun findFirstButton(root: View?): Button? {
        if (root == null) return null
        if (root is Button) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val b = findFirstButton(root.getChildAt(i))
                if (b != null) return b
            }
        }
        return null
    }
}
