package com.pasiflonet.mobile.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageRow(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val unixSeconds: Long,
    val typeLabel: String,
    val mediaKind: String?,
    val thumbLocalPath: String? = null,
    val miniThumbBase64: String? = null,
    val hasMedia: Boolean
)
