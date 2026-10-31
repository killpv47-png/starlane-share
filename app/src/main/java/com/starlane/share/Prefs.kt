package com.starlane.share

import android.content.Context
import android.content.SharedPreferences
import com.starlane.core.engine.ConflictPolicy

/** All user settings. Every setting here is wired to real behaviour. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("starlane", Context.MODE_PRIVATE)

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v.trim().take(MAX_NAME)).apply()

    var nickname: String
        get() = sp.getString("nickname", "") ?: ""
        set(v) = sp.edit().putString("nickname", v.trim().take(MAX_NAME)).apply()

    var icon: Int
        get() = sp.getInt("icon", 0)
        set(v) = sp.edit().putInt("icon", v).apply()

    val onboarded: Boolean get() = username.length >= 2

    var turbo: Boolean
        get() = sp.getBoolean("turbo", true)
        set(v) = sp.edit().putBoolean("turbo", v).apply()

    /** "" = follow system, "en", "fa". */
    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) = sp.edit().putString("language", v).commit().let { }

    /** 0 = system, 1 = light, 2 = dark. */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    var reduceAnimations: Boolean
        get() = sp.getBoolean("reduce_anim", false)
        set(v) = sp.edit().putBoolean("reduce_anim", v).apply()

    var conflictPolicy: ConflictPolicy
        get() = runCatching { ConflictPolicy.valueOf(sp.getString("conflict", null) ?: "") }.getOrDefault(ConflictPolicy.RENAME)
        set(v) = sp.edit().putString("conflict", v.name).apply()

    /** Storage Access Framework tree URI chosen by the user, or null for Download/Starlane. */
    var saveTreeUri: String?
        get() = sp.getString("save_tree", null)
        set(v) = sp.edit().putString("save_tree", v).apply()

    /** Cached cipher benchmark "id:mbps,..." with the app version it was measured on. */
    var cipherBench: String
        get() = sp.getString("cipher_bench", "") ?: ""
        set(v) = sp.edit().putString("cipher_bench", v).apply()

    companion object {
        const val MAX_NAME = 24
        val ICONS = 6
    }
}
