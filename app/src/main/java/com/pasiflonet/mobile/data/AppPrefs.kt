package com.pasiflonet.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pasiflonet_prefs")

class AppPrefs(private val ctx: Context) {

    companion object {
        private val KEY_API_ID = stringPreferencesKey("api_id")
        private val KEY_API_HASH = stringPreferencesKey("api_hash")
        private val KEY_PHONE = stringPreferencesKey("phone")
        private val KEY_WATERMARK_URI = stringPreferencesKey("watermark_uri")

        // יעד כ-username (בלי @)
        private val KEY_TARGET_USERNAME = stringPreferencesKey("target_username")

        // cache של chatId אחרי resolve (לא חובה, אבל שימושי)
        private val KEY_TARGET_CHAT_ID_CACHE = longPreferencesKey("target_chat_id_cache")

        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
    }

    val apiIdFlow: Flow<String> = ctx.dataStore.data.map { it[KEY_API_ID].orEmpty() }
    val apiHashFlow: Flow<String> = ctx.dataStore.data.map { it[KEY_API_HASH].orEmpty() }
    val phoneFlow: Flow<String> = ctx.dataStore.data.map { it[KEY_PHONE].orEmpty() }
    val watermarkUriFlow: Flow<String> = ctx.dataStore.data.map { it[KEY_WATERMARK_URI].orEmpty() }

    val targetUsernameFlow: Flow<String> = ctx.dataStore.data.map { it[KEY_TARGET_USERNAME].orEmpty() }
    val targetChatIdCacheFlow: Flow<Long> = ctx.dataStore.data.map { it[KEY_TARGET_CHAT_ID_CACHE] ?: 0L }

    val loggedInFlow: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_LOGGED_IN] ?: false }

    suspend fun saveApiId(v: String) = ctx.dataStore.edit { it[KEY_API_ID] = v }
    suspend fun saveApiHash(v: String) = ctx.dataStore.edit { it[KEY_API_HASH] = v }
    suspend fun savePhone(v: String) = ctx.dataStore.edit { it[KEY_PHONE] = v }
    suspend fun saveWatermarkUri(v: String) = ctx.dataStore.edit { it[KEY_WATERMARK_URI] = v }
    suspend fun saveTargetUsername(v: String) = ctx.dataStore.edit { it[KEY_TARGET_USERNAME] = v }
    suspend fun saveTargetChatIdCache(v: Long) = ctx.dataStore.edit { it[KEY_TARGET_CHAT_ID_CACHE] = v }
    suspend fun setLoggedIn(v: Boolean) = ctx.dataStore.edit { it[KEY_LOGGED_IN] = v }

    // -------- תאימות אחורה (כדי שלא יישבר קוד ישן) --------
    val targetChatIdFlow: Flow<Long> = targetChatIdCacheFlow

    suspend fun saveApi(apiId: String, apiHash: String) {
        saveApiId(apiId)
        saveApiHash(apiHash)
    }

    suspend fun saveTargetChatId(chatId: Long) {
        saveTargetChatIdCache(chatId)
    }

    suspend fun saveWatermark(uri: String) {
        saveWatermarkUri(uri)
    }
}
