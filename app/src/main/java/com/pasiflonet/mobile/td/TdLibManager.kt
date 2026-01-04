package com.pasiflonet.mobile.td
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

import android.content.Context
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

object TdLibManager {
    private val _updates = MutableSharedFlow<org.drinkless.tdlib.TdApi.Object>(extraBufferCapacity = 256)
    val updates = _updates.asSharedFlow()
    private val _authState = MutableStateFlow<org.drinkless.tdlib.TdApi.AuthorizationState?>(null)
    val authState = _authState.asStateFlow()

    private const val TAG = "TdLibManager"

    private var appCtx: Context? = null
    private var client: Client? = null

    private val listeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun ensureClient() {
        if (client != null) return

        try {
        } catch (_: Throwable) {}

        val updatesHandler = Client.ResultHandler { obj ->
        _updates.tryEmit(obj)
        if (obj is org.drinkless.tdlib.TdApi.UpdateAuthorizationState) _authState.value = obj.authorizationState

            try {
                for (l in listeners) l(obj)
            } catch (t: Throwable) {
                Log.e(TAG, "listener crash", t)
            }
        }
        val exceptionHandler = Client.ExceptionHandler { e ->
            Log.e(TAG, "TDLib exception", e)
        }

        client = Client.create(updatesHandler, exceptionHandler, exceptionHandler)
        Log.d(TAG, "TDLib client created")
    }

    fun addUpdateListener(l: (TdApi.Object) -> Unit) {
        listeners.add(l)
    }

    fun removeUpdateListener(l: (TdApi.Object) -> Unit) {
        listeners.remove(l)
    }

    fun send(f: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit) {
        val c = client ?: run {
            ensureClient()
            client
        } ?: return

        c.send(f, Client.ResultHandler { obj -> cb(obj) })
    }
}
