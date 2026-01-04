package com.pasiflonet.mobile.td

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.drinkless.tdlib.TdApi

object TdUpdateBus {
    private val _updates = MutableSharedFlow<TdApi.Object>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val updates: SharedFlow<TdApi.Object> = _updates

    fun emit(obj: TdApi.Object) {
        _updates.tryEmit(obj)
    }
}
