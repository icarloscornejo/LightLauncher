package com.prototype.lightlauncher.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.prototype.lightlauncher.core.Language
import java.util.Locale

/**
 * Puts the launcher's own text in the chosen language. It does two things, because Android reads the
 * language in two places: the resources of the context (strings), and the process default locale (the day, month and
 * AM/PM names a TextClock formats). Automatic puts the system's language back, so going from a pinned language to
 * Automatic needs no restart. The Application is deliberately left out: its context is made once per process and
 * would not follow a change made while the launcher runs.
 */
object LocalizedContext {

    /** Sets the locales of [configuration] (and the process default) for [language]. */
    fun configure(configuration: Configuration, language: Language) {
        val locales: LocaleList = localesOf(language)
        LocaleList.setDefault(locales)
        Locale.setDefault(locales[0])
        configuration.setLocales(locales)
    }

    /** [base] with [language] applied, for the places that read strings without being an activity of ours. */
    fun wrap(base: Context, language: Language): Context {
        val configuration = Configuration(base.resources.configuration)
        configure(configuration, language)
        return base.createConfigurationContext(configuration)
    }

    private fun localesOf(language: Language): LocaleList {
        val tag: String = language.tag ?: return Resources.getSystem().configuration.locales
        return LocaleList(Locale.forLanguageTag(tag))
    }
}
