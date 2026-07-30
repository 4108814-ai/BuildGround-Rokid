package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.res.Resources
import android.os.LocaleList

/**
 * Semantic Settings labels used by the self-arm automator.
 *
 * The primary source is the installed `com.android.settings` APK itself. That makes matching
 * follow the exact locale and wording shipped by each firmware instead of requiring Nexus to
 * maintain a translation table. Fallbacks cover vendor builds that rename or hide resources.
 */
internal enum class SelfArmSettingsLabel(
    val resourceNames: List<String>,
    val fallbacks: List<String>,
) {
    WIRELESS_DEBUGGING(
        resourceNames = listOf(
            "adb_wireless_settings",
            "enable_adb_wireless",
            "wireless_debugging_main_switch_title",
        ),
        fallbacks = listOf(
            "wireless debugging",
            "débogage sans fil",
            "debug sans fil",
            "depuración inalámbrica",
            "depuração sem fio",
            "debug wireless",
            "drahtloses debugging",
            "отладка по wi-fi",
        ),
    ),
    PAIR_WITH_CODE(
        resourceNames = listOf("adb_pair_method_code_title"),
        fallbacks = listOf(
            "pair device with pairing code",
            "associer l'appareil avec un code d'association",
            "code d'association",
            "pairing code",
            "código de emparejamiento",
            "código de vinculación",
            "código de pareamento",
            "codice di accoppiamento",
            "kopplungscode",
            "код подключения",
        ),
    ),
    PAIRING_DIALOG_TITLE(
        resourceNames = listOf("adb_pairing_device_dialog_title"),
        fallbacks = listOf(
            "pair with device",
            "associer un appareil",
            "associer l'appareil",
            "vincular con dispositivo",
            "mit gerät koppeln",
            "подключение устройства",
        ),
    ),
    PAIRING_CODE_LABEL(
        resourceNames = listOf("adb_pairing_device_dialog_pairing_code_label"),
        fallbacks = listOf(
            "wi-fi pairing code",
            "wifi pairing code",
            "code d'association wi-fi",
            "pairing code",
            "код подключения wi-fi",
        ),
    ),
    IP_ADDRESS_AND_PORT(
        resourceNames = listOf("adb_wireless_ip_addr_preference_title"),
        fallbacks = listOf(
            "ip address & port",
            "ip address and port",
            "adresse ip et port",
            "adresse ip & port",
            "ip-adresse & port",
            "ip-адрес и порт",
        ),
    ),
    DEVELOPER_OPTIONS(
        resourceNames = listOf("development_settings_title"),
        fallbacks = listOf(
            "developer options",
            "options pour les développeurs",
            "opciones de desarrollador",
            "opções do desenvolvedor",
            "entwickleroptionen",
            "параметры разработчика",
            "настройки разработчика",
        ),
    ),
    DEVELOPER_OPTIONS_DISABLED(
        resourceNames = listOf("dev_settings_disabled_warning"),
        fallbacks = listOf(
            "enable developer options first",
            "turn on developer options first",
            "activer les options pour les développeurs",
            "activar primero las opciones de desarrollador",
            "ative primeiro as opções do desenvolvedor",
            "entwickleroptionen zuerst aktivieren",
            "сначала включите параметры разработчика",
        ),
    ),
    BUILD_NUMBER(
        resourceNames = listOf("build_number"),
        fallbacks = listOf(
            "build number",
            "numéro de build",
            "numéro de version",
            "software version",
            "número de compilación",
            "número de compilação",
            "build-nummer",
            "номер сборки",
        ),
    ),
    WIFI_PRIMARY_SWITCH(
        resourceNames = listOf(
            "wifi_settings_primary_switch_title",
            "wifi_settings",
            "wifi_settings_title",
        ),
        fallbacks = listOf("wi-fi", "wifi", "wlan"),
    ),
}

internal class SelfArmSettingsStringResolver(context: Context) {
    private val appContext = context.applicationContext
    private var settingsContext: Context? = null
    private var localeToken = ""
    private var candidateCache = emptyMap<SelfArmSettingsLabel, List<String>>()
    private var normalizedCandidateCache = emptyMap<SelfArmSettingsLabel, List<String>>()

    fun refresh() {
        val packageContext = settingsContext ?: runCatching {
            appContext.createPackageContext(
                AccessibilityWindowRoots.SETTINGS_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrNull()?.also { settingsContext = it }
        val settingsLocales = packageContext
            ?.resources
            ?.configuration
            ?.locales
            ?.toLanguageTags()
            .orEmpty()
        val token = LocaleList.getDefault().toLanguageTags() + "|" + settingsLocales
        if (token == localeToken && candidateCache.isNotEmpty()) return
        localeToken = token
        candidateCache = SelfArmSettingsLabel.entries.associateWith { label ->
            val resolved = label.resourceNames.mapNotNull { resourceName ->
                packageContext?.resources?.resolveString(resourceName)
            }
            SelfArmSettingsLabelMatcher.candidates(label, resolved)
        }
        normalizedCandidateCache = candidateCache.mapValues { (_, candidates) ->
            candidates.map(SelfArmSettingsTextMatcher::normalize)
        }
    }

    fun candidates(label: SelfArmSettingsLabel): List<String> {
        refresh()
        return candidateCache[label]
            ?: SelfArmSettingsLabelMatcher.candidates(label, emptyList())
    }

    fun matches(value: String, label: SelfArmSettingsLabel): Boolean {
        refresh()
        return SelfArmSettingsTextMatcher.containsAnyNormalized(
            value,
            normalizedCandidateCache[label].orEmpty(),
        )
    }

    fun matchesExactly(value: String, label: SelfArmSettingsLabel): Boolean {
        refresh()
        val normalizedValue = SelfArmSettingsTextMatcher.normalize(value)
        return normalizedValue.isNotBlank() &&
            normalizedCandidateCache[label].orEmpty().any(normalizedValue::equals)
    }

    private fun Resources.resolveString(resourceName: String): String? {
        val resourceId = getIdentifier(
            resourceName,
            "string",
            AccessibilityWindowRoots.SETTINGS_PACKAGE,
        )
        if (resourceId == 0) return null
        return runCatching { getString(resourceId).trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }
}

internal object SelfArmSettingsLabelMatcher {
    fun candidates(
        label: SelfArmSettingsLabel,
        resolved: Iterable<String>,
    ): List<String> =
        buildList {
            resolved.filterTo(this) { it.isNotBlank() }
            label.fallbacks.filterTo(this) { it.isNotBlank() }
        }.distinctBy(SelfArmSettingsTextMatcher::normalize)

    fun matches(
        value: String,
        label: SelfArmSettingsLabel,
        resolved: Iterable<String>,
    ): Boolean {
        val candidates = candidates(label, resolved)
        return SelfArmSettingsTextMatcher.containsAny(value, *candidates.toTypedArray())
    }

    fun matchesExactly(
        value: String,
        label: SelfArmSettingsLabel,
        resolved: Iterable<String>,
    ): Boolean {
        val normalizedValue = SelfArmSettingsTextMatcher.normalize(value)
        if (normalizedValue.isBlank()) return false
        return candidates(label, resolved)
            .asSequence()
            .map(SelfArmSettingsTextMatcher::normalize)
            .any(normalizedValue::equals)
    }
}
