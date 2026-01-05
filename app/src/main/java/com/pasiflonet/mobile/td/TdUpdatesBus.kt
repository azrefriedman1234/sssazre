package com.pasiflonet.mobile.td

import java.util.concurrent.CopyOnWriteArrayList
import org.drinkless.tdlib.TdApi

object TdUpdatesBus {
    private val listeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun addListener(l: (TdApi.Object) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (TdApi.Object) -> Unit) {
        listeners.remove(l)
    }

    fun dispatch(obj: TdApi.Object) {
        for (l in listeners) {
            try { l(obj) } catch (_: Throwable) {}
        }
    }
}
