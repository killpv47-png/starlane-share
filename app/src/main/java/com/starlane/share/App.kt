package com.starlane.share

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.starlane.share.engine.ShareManager
import java.util.Locale

/**
 * Application entry point. Deliberately does almost nothing on the main thread: the transfer
 * node, identity keys, cipher benchmark and transports are all created lazily the first time the
 * user scans, receives or connects (see [ShareManager]).
 */
class App : Application() {
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ShareManager.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/** In-app language selection that works on every API level without AppCompat. */
object Locales {
    fun selected(context: Context): Locale? {
        val lang = Prefs(context).language
        return if (lang.isEmpty()) null else Locale(lang)
    }

    fun wrap(base: Context): Context {
        val locale = selected(base) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList(locale)) else @Suppress("DEPRECATION") { config.locale = locale }
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun current(context: Context): Locale =
        if (Build.VERSION.SDK_INT >= 24) context.resources.configuration.locales[0]
        else @Suppress("DEPRECATION") context.resources.configuration.locale
}
