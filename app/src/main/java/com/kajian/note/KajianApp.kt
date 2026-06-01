package com.kajian.note

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.kajian.note.utils.PreferencesManager
import java.util.Locale

class KajianApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(applyLocale(base, PreferencesManager(base).getAppLanguage()))
    }

    companion object {
        fun applyLocale(context: Context, lang: String): Context {
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }
}
