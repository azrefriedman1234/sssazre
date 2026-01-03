package com.pasiflonet.mobile.td

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

object TdLibManager {

    private var appCtx: Context? = null
    private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState.asStateFlow()

    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    fun ensureClient() {
        if (client != null) return

        val updatesHandler = Client.ResultHandler { obj ->
            if (obj is TdApi.UpdateAuthorizationState) {
                _authState.value = obj.authorizationState
            }

            // מפיץ כל Update ל-UI
            if (obj.javaClass.simpleName.startsWith("Update")) {
                for (l in updateListeners) runCatching { l(obj) }
            }
        }

        val exceptionHandler = Client.ExceptionHandler { e -> e.printStackTrace() }

        // ✅ זה הפיקס: arg2+arg3 הם ExceptionHandler
        client = Client.create(updatesHandler, exceptionHandler, exceptionHandler)

        send(TdApi.SetLogVerbosityLevel(1)) { }
        send(TdApi.GetAuthorizationState()) { }
        send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) { }
    }

    fun addUpdateListener(l: (TdApi.Object) -> Unit) { updateListeners.add(l) }
    fun removeUpdateListener(l: (TdApi.Object) -> Unit) { updateListeners.remove(l) }

    // ✅ Function חייב generic
    fun send(f: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit = {}) {
        val c = client ?: return
        c.send(f, Client.ResultHandler { obj -> cb(obj) })
    }
}
