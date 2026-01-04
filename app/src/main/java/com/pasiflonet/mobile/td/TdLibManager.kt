package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object TdLibManager {

    private const val TAG = "TdLibManager"
    private var appCtx: Context? = null
    private var client: Client? = null
    private val creating = AtomicBoolean(false)

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    // optional UI listeners (if you already use them somewhere)
    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    fun ensureClient() {
        if (client != null) return
        if (!creating.compareAndSet(false, true)) return

        try {
            // ✅ correct way (avoid unresolved setLogVerbosityLevel)
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }

            val exceptionHandler = Client.ExceptionHandler { e ->
                Log.e(TAG, "TDLib exception", e)
            }

            val updatesHandler = Client.ResultHandler { obj ->
                try {
                    if (obj is TdApi.UpdateAuthorizationState) {
                        _authState.tryEmit(obj.authorizationState)
                    }
                    // ✅ broadcast to UI
                    TdUpdateBus.emit(obj)
                    // ✅ optional legacy listeners
                    notifyListeners(obj)
                } catch (t: Throwable) {
                    Log.e(TAG, "Update handler crash", t)
                }
            }

            // ✅ signature: (updatesHandler, exceptionHandler, exceptionHandler)
            client = Client.create(updatesHandler, exceptionHandler, exceptionHandler)
        } finally {
            creating.set(false)
        }
    }

    fun send(f: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit = {}) {
        ensureClient()
        val c = client ?: return
        c.send(f, Client.ResultHandler { obj ->
            try { cb(obj) } catch (t: Throwable) { Log.e(TAG, "Callback crash", t) }
        })
    }

    fun addUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.add(l)
    }

    fun removeUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.remove(l)
    }

    private fun notifyListeners(obj: TdApi.Object) {
        val n = obj.javaClass.simpleName
        if (!n.startsWith("Update")) return
        for (l in updateListeners) {
            try { l(obj) } catch (_: Throwable) {}
        }
    }
}
