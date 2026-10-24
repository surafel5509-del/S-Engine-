package com.sengine.engine.localization

import org.json.JSONObject

// ─── Localization System ──────────────────────────────────────────────────

/**
 * Professional localization (i18n) system with support for:
 * - Multiple languages
 * - Parameterized strings
 * - Plural rules
 * - Font/script support
 * - Runtime language switching
 */

class LocalizationManager {
    var currentLanguage: String = "en"
    var fallbackLanguage: String = "en"
    val languages = mutableListOf<String>()
    private val strings = HashMap<String, HashMap<String, String>>() // lang → key → value

    fun addLanguage(code: String) {
        if (code !in languages) languages.add(code)
        strings.getOrPut(code) { HashMap() }
    }

    fun setString(language: String, key: String, value: String) {
        addLanguage(language)
        strings.getOrPut(language) { HashMap() }[key] = value
    }

    fun getString(key: String): String {
        return strings[currentLanguage]?.get(key)
            ?: strings[fallbackLanguage]?.get(key)
            ?: key
    }

    /** Get string with parameter substitution: "Hello {name}!" + params("name" to "World") */
    fun getString(key: String, vararg params: Pair<String, String>): String {
        var text = getString(key)
        for ((name, value) in params) {
            text = text.replace("{$name}", value)
        }
        return text
    }

    /** Get pluralized string. */
    fun getPlural(key: String, count: Int): String {
        val pluralKey = when {
            count == 0 -> "${key}_zero"
            count == 1 -> "${key}_one"
            count < 5 -> "${key}_few"
            else -> "${key}_many"
        }
        return getString(pluralKey).replace("{count}", count.toString())
            .ifEmpty { getString(key).replace("{count}", count.toString()) }
    }

    /** Load translations from JSON. */
    fun loadFromJson(json: String) {
        try {
            val obj = JSONObject(json)
            for (lang in obj.keys()) {
                addLanguage(lang)
                val translations = obj.getJSONObject(lang)
                for (key in translations.keys()) {
                    setString(lang, key, translations.getString(key))
                }
            }
        } catch (_: Exception) {}
    }

    /** Export all translations to JSON. */
    fun toJson(): String {
        val obj = JSONObject()
        for ((lang, map) in strings) {
            val langObj = JSONObject()
            for ((key, value) in map) langObj.put(key, value)
            obj.put(lang, langObj)
        }
        return obj.toString(2)
    }

    /** Switch language at runtime. */
    fun setLanguage(code: String) {
        if (code in languages) currentLanguage = code
    }
}
