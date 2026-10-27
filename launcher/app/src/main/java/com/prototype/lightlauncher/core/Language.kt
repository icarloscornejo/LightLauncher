package com.prototype.lightlauncher.core

/**
 * The language the launcher shows its own text in. AUTO follows the TV's system language (the launcher
 * has twelve translations); EN and ES pin it. [tag] is the BCP 47 language tag, null for AUTO.
 */
enum class Language(val tag: String?) {
    AUTO(null),
    EN("en"),
    ES("es"),
    ;

    companion object {
        /** The stored name back to a value; anything unknown (or nothing) is AUTO, so a bad file never pins a language. */
        fun parse(text: String?): Language = entries.firstOrNull { it.name == text } ?: AUTO
    }
}
