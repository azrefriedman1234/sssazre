package com.pasiflonet.mobile.td

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

object TdLibManager {

    @Volatile private var inited = false
    @Volatile private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun init(ctx: Context) {
        // ctx not needed right now, but keep signature stable
        inited = true
    }

    fun ensureClient() {
        if (!inited) return
        if (client != null) return

        synchronized(this) {
            if (client != null) return

            val updatesHandler = Client.ResultHandler { obj ->
                // Track auth state
                if (obj is TdApi.UpdateAuthorizationState) {
                    _authState.tryEmit(obj.authorizationState)
                }
                // Notify UI listeners for TDLib updates
                notifyUpdate(obj)
            }

            val exceptionHandler = Client.ExceptionHandler { e ->
                e.printStackTrace()
            }

            client = Client.create(updatesHandler, exceptionHandler, exceptionHandler)
        }
    }

    fun addUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.add(l)
    }

    fun removeUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.remove(l)
    }

    private fun notifyUpdate(obj: TdApi.Object) {
        val n = obj.javaClass.simpleName
        if (!n.startsWith("Update")) return
        for (l in updateListeners) {
            try { l(obj) } catch (_: Throwable) {}
        }
    }

    fun send(fn: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit = {}) {
        ensureClient()
        val c = client ?: return
        c.send(fn) { obj -> cb(obj) }
    }
}
