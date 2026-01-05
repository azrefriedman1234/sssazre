package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.util.TempCleaner
import com.pasiflonet.mobile.td.TdUpdatesBus
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private val tdUpdateListener: (TdApi.Object) -> Unit = { obj ->
        if (obj is TdApi.UpdateNewMessage) {
            try {
                val msg = obj.message
                liveMsgs.add(0, msg)
                if (liveMsgs.size > 200) liveMsgs.removeAt(liveMsgs.size - 1)
                runOnUiThread { refreshList() }
            } catch (_: Throwable) {}
        }
    }


    private lateinit var adapter: MessagesAdapter
    private val liveMsgs = ArrayList<TdApi.Message>(200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = MessagesAdapter { msg ->
            val i = Intent(this, DetailsActivity::class.java).apply {
                putExtra("chat_id", msg.chatId)
                putExtra("message_id", msg.id) // TdApi.Message.id
            }
            startActivity(i)
        }

        // RecyclerView (מנסה כמה שמות ids נפוצים בלי להפיל קומפילציה)
        val rv = findByAnyId<RecyclerView>(
            "recycler", "recyclerView", "rvMessages", "messagesRecycler", "rv", "list"
        )
        if (rv != null) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = adapter
        } else {
            Toast.makeText(this, "RecyclerView לא נמצא בלייאאוט", Toast.LENGTH_SHORT).show()
        }

        hookSettingsExitClearButtons()

        // Live updates דרך TdUpdatesBus (חיבור אמיתי ייעשה במקום שבו TDLib Client מקבל updates)

        refreshList()
    }

    private fun refreshList() {
        adapter.submit(liveMsgs)
    }

    private fun hookSettingsExitClearButtons() {
        // ניקוי זמניים
        hookButton(listOf("btn_clear_temp", "btnClearTemp", "btn_temp_clear", "btnTempClear")) {
            try {
                val n = TempCleaner.clearTemp(applicationContext)
                Toast.makeText(this, "נוקו קבצים זמניים: $n", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "ניקוי נכשל: ${t.message ?: ""}", Toast.LENGTH_LONG).show()
            }
        }

        // הגדרות
        hookButton(listOf("btn_settings", "btnSettings", "btnSetting", "btn_settings_main")) {
            try {
                startActivity(Intent(this, SettingsActivity::class.java))
            } catch (_: Throwable) {
                Toast.makeText(this, "SettingsActivity לא נמצא", Toast.LENGTH_SHORT).show()
            }
        }

        // סגור/יציאה
        hookButton(listOf("btn_exit", "btnExit", "btn_close", "btnClose")) {
            try { finishAffinity() } catch (_: Throwable) { finish() }
        }
    }

    private fun <T : View> findByAnyId(vararg names: String): T? {
        for (n in names) {
            val id = resources.getIdentifier(n, "id", packageName)
            if (id != 0) {
                @Suppress("UNCHECKED_CAST")
                val v = findViewById<View>(id) as? T
                if (v != null) return v
            }
        }
        return null
    }

    private fun hookButton(names: List<String>, onClick: () -> Unit) {
        val v = findByAnyId<View>(*names.toTypedArray())
        if (v != null) v.setOnClickListener { onClick() }
    }

    /**
     * מתקין handler של עדכונים על TDLib בלי תלות בשמות/מחלקות ספציפיים.
     * מנסה כמה מועמדים נפוצים אצלך בפרויקט.
     */
        }

        // מועמדים נפוצים: נסה להפעיל מתודה שמקבלת Function1<TdApi.Object, Unit>
        val candidates = listOf(
            "com.pasiflonet.mobile.td.TdRepository",
            "com.pasiflonet.mobile.td.TdlibManager",
            "com.pasiflonet.mobile.TelegramTdLib",
            "com.pasiflonet.mobile.ui.TelegramTdLib",
            "com.pasiflonet.mobile.td.TelegramTdLib"
        )

        val methodNames = listOf(
            "setUpdatesHandler",
            "setUpdateHandler",
            "setUpdatesListener",
            "addUpdatesHandler",
            "addUpdateHandler"
        )

        for (cn in candidates) {
            try {
                val cls = Class.forName(cn)
                val inst = cls.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }?.invoke(null)
                    ?: cls.kotlin.objectInstance
                    ?: cls.constructors.firstOrNull { it.parameterTypes.isEmpty() }?.newInstance()

                if (inst != null) {
                    for (mn in methodNames) {
                        val m = inst.javaClass.methods.firstOrNull { it.name == mn && it.parameterTypes.size == 1 }
                        if (m != null) {
                            m.invoke(inst, handler)
                            Toast.makeText(this, "Live updates: ON", Toast.LENGTH_SHORT).show()
                            return
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // אם לא הצליח – לא שוברים. לפחות האפליקציה תעלה, ואת זה נסגור בשלב הבא עם שם המחלקה המדויק.
        Toast.makeText(this, "Live updates: handler not attached (need exact TD entrypoint)", Toast.LENGTH_SHORT).show()
    }
    override fun onStart() {
        super.onStart()
        TdUpdatesBus.addListener(tdUpdateListener)
    }

    override fun onStop() {
        TdUpdatesBus.removeListener(tdUpdateListener)
        super.onStop()
    }

}
