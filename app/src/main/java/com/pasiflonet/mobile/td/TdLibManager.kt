package com.pasiflonet.mobile.td

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object TdLibManager {

    private var appCtx: Context? = null
    private var client: Client? = null
    private val started = AtomicBoolean(false)

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState = _authState.asStateFlow()

    // ---- Update listeners (UI) ----
    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()
    fun addUpdateListener(l: (TdApi.Object) -> Unit) { updateListeners.add(l) }
    fun removeUpdateListener(l: (TdApi.Object) -> Unit) { updateListeners.remove(l) }
    private fun notifyUpdate(obj: TdApi.Object) {
        for (l in updateListeners) {
            try { l(obj) } catch (_: Throwable) {}
        }
    }
    // --------------------------------

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    fun ensureClient() {
        if (client != null) return
        // TDLib updates מגיעים *רק* לכאן – זה מה שחסר כש"יש רק ישן"
        client = Client.create(updatesHandler, null, null)
        started.set(true)
    }

    fun isReady(): Boolean {
        val st = _authState.value
        return st != null && st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR
    }

    fun setOnline(online: Boolean) {
        // online=true שומר על זרימת עדכונים בלייב
        // is_background=false עוזר שלא "יישן" בזמן שהמסך פתוח
        send(TdApi.SetOption("online", TdApi.OptionValueBoolean(online))) { }
        send(TdApi.SetOption("is_background", TdApi.OptionValueBoolean(!online))) { }
    }

    fun send(fn: TdApi.Function, cb: (TdApi.Object) -> Unit) {
        ensureClient()
        val c = client ?: return
        c.send(fn, Client.ResultHandler { obj ->
            cb(obj ?: TdApi.Error(500, "TDLib returned null"))
        })
    }

    private val updatesHandler = Client.ResultHandler { obj ->
        if (obj == null) return@ResultHandler

        // authState updates
        if (obj.constructor == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
            val up = obj as TdApi.UpdateAuthorizationState
            _authState.value = up.authorizationState
        }

        // 모든 updates (כולל UpdateNewMessage) -> listeners
        notifyUpdate(obj)
    }
}
