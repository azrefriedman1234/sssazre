package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.td.TdMessageMapper
import com.pasiflonet.mobile.td.TdMediaDownloader
import com.pasiflonet.mobile.util.TempCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MessagesAdapter
    private val chatIsChannel = ConcurrentHashMap<Long, Boolean>()
    private val started = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = MessagesAdapter(
            onDetails = { row ->
                val i = Intent(this, DetailsActivity::class.java)
                i.putExtra("row_json", com.pasiflonet.mobile.util.JsonUtil.toJson(row))
                startActivity(i)
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnExit.setOnClickListener { finishAffinity() }

        // ניקוי זמניים (לא מוחק TDLib session)
        b.btnClearTemp.setOnClickListener {
            val n = TempCleaner.clean(applicationContext)
            Snackbar.make(b.root, "✅ נמחקו קבצים זמניים: $n", Snackbar.LENGTH_SHORT).show()
        }

        // קריטי: לוודא client קיים + לבקש auth state
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        // מחכים למצב הרשאה אמיתי. אם READY -> מתחילים לטעון/להאזין להודעות
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest

                if (st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    if (started.compareAndSet(false, true)) {
                        seedRecentMessages()
                        observeTdUpdates()
                    }
                } else {
                    // לא READY => הולכים ל-Login (רק פעם אחת)
                    if (started.compareAndSet(false, true)) {
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    private fun seedRecentMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            TdLibManager.send(TdApi.GetChats(TdApi.ChatListMain(), 100)) { obj ->
                if (obj.constructor != TdApi.Chats.CONSTRUCTOR) return@send
                val chats = (obj as TdApi.Chats).chatIds ?: return@send
                chats.forEach { chatId ->
                    TdLibManager.send(TdApi.GetChat(chatId)) { chatObj ->
                        if (chatObj.constructor != TdApi.Chat.CONSTRUCTOR) return@send
                        val chat = chatObj as TdApi.Chat
                        val isChannel = TdMessageMapper.isChannelChat(chat)
                        chatIsChannel[chatId] = isChannel
                        if (!isChannel) return@send

                        TdLibManager.send(TdApi.GetChatHistory(chatId, 0, 0, 1, false)) { histObj ->
                            if (histObj.constructor != TdApi.Messages.CONSTRUCTOR) return@send
                            val msgs = (histObj as TdApi.Messages).messages ?: return@send
                            if (msgs.isEmpty()) return@send
                            addMessageRow(chatId, msgs[0])
                        }
                    }
                }
            }
        }
    }

    private fun observeTdUpdates() {
        lifecycleScope.launch {
            TdLibManager.updates.collectLatest { obj ->
                if (obj == null) return@collectLatest
                if (obj.constructor == TdApi.UpdateNewMessage.CONSTRUCTOR) {
                    val up = obj as TdApi.UpdateNewMessage
                    val msg = up.message ?: return@collectLatest
                    val chatId = msg.chatId

                    val isChannel = chatIsChannel[chatId]
                    if (isChannel == null) {
                        TdLibManager.send(TdApi.GetChat(chatId)) { chatObj ->
                            if (chatObj.constructor != TdApi.Chat.CONSTRUCTOR) return@send
                            val chat = chatObj as TdApi.Chat
                            val ch = TdMessageMapper.isChannelChat(chat)
                            chatIsChannel[chatId] = ch
                            if (ch) addMessageRow(chatId, msg)
                        }
                    } else if (isChannel) {
                        addMessageRow(chatId, msg)
                    }
                }
            }
        }
    }

    private fun addMessageRow(chatId: Long, msg: TdApi.Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            val thumbId = TdMessageMapper.getThumbFileId(msg.content)
            val thumbPath = if (thumbId != null) {
                // מוריד רק thumbnail (קטן ומהיר) — זה בדיוק מה שרצית
                TdMediaDownloader.downloadFile(thumbId, priority = 8, synchronous = true)
            } else null

            val row = TdMessageMapper.mapToRow(chatId, msg, thumbPath, null)
            runOnUiThread {
                adapter.prepend(row)
                adapter.trimTo(100)
                b.recycler.scrollToPosition(0)
            }
        }
    }
}
