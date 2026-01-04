package com.pasiflonet.mobile.td

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object TdLibManager {

    private val inited = AtomicBoolean(false)

    @Volatile
    private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun init(ctx: Context) {
        if (inited.compareAndSet(false, true)) {
            ctx.applicationContext // keep
            TdApi.setLogVerbosityLevel(1)
        }
    }

    @Synchronized
    fun ensureClient() {
        if (client != null) return

        val updatesHandler = Client.ResultHandler { obj ->
            handleUpdate(obj)
        }

        val exceptionHandler = Client.ExceptionHandler { e ->
            e.printStackTrace()
        }

        client = Client.create(updatesHandler, exceptionHandler, exceptionHandler)
    }

    fun addUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.add(l)
    }

    fun removeUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.remove(l)
    }

    @Suppress("UNCHECKED_CAST")
    fun send(function: TdApi.Function<*>, cb: (TdApi.Object) -> Unit = {}) {
        val c = client ?: return
        c.send(function as TdApi.Function<TdApi.Object>, Client.ResultHandler { obj ->
            cb(obj)
        })
    }

    private fun handleUpdate(obj: TdApi.Object) {
        // auth state
        if (obj.constructor == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
            val up = obj as TdApi.UpdateAuthorizationState
            _authState.value = up.authorizationState
        }

        // notify listeners for updates
        val name = obj.javaClass.simpleName
        if (name.startsWith("Update")) {
            for (l in updateListeners) {
                try { l(obj) } catch (_: Throwable) {}
            }
        }
    }
}
