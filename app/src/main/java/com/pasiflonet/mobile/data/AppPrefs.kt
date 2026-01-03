package com.pasiflonet.mobile.data

import android.content.Context

object AppPrefs {
    private const val PREF = "pasiflonet_prefs"

    private const val K_API_ID = "api_id"
    private const val K_API_HASH = "api_hash"
    private const val K_PHONE = "phone"
    private const val K_TARGET_USERNAME = "target_username"
    private const val K_WATERMARK = "watermark_path"
    private const val K_LOGGED_IN = "logged_in"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getApiId(ctx: Context): Int = sp(ctx).getInt(K_API_ID, 0)
    fun setApiId(ctx: Context, v: Int) { sp(ctx).edit().putInt(K_API_ID, v).apply() }

    fun getApiHash(ctx: Context): String = sp(ctx).getString(K_API_HASH, "") ?: ""
    fun setApiHash(ctx: Context, v: String) { sp(ctx).edit().putString(K_API_HASH, v).apply() }

    fun getPhone(ctx: Context): String = sp(ctx).getString(K_PHONE, "") ?: ""
    fun setPhone(ctx: Context, v: String) { sp(ctx).edit().putString(K_PHONE, v).apply() }

    fun getTargetUsername(ctx: Context): String = sp(ctx).getString(K_TARGET_USERNAME, "") ?: ""
    fun setTargetUsername(ctx: Context, v: String) {
        val t = v.trim()
        sp(ctx).edit().putString(K_TARGET_USERNAME, if (t.startsWith("@")) t else "@$t").apply()
    }

    fun getWatermark(ctx: Context): String = sp(ctx).getString(K_WATERMARK, "") ?: ""
    fun setWatermark(ctx: Context, path: String) { sp(ctx).edit().putString(K_WATERMARK, path).apply() }

    fun isLoggedIn(ctx: Context): Boolean = sp(ctx).getBoolean(K_LOGGED_IN, false)
    fun setLoggedIn(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(K_LOGGED_IN, v).apply() }
}
