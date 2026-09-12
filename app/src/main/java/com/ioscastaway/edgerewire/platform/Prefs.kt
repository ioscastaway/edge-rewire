package com.ioscastaway.edgerewire.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the debug screen can tune. Read by the service, written by the UI. */
data class Settings(
    /** Master switch. Off = the service stays bound but never attaches a strip. */
    val enabled: Boolean = true,
    /** Width of each edge strip. iOS uses roughly 20pt; Android's own back-gesture zone is similar. */
    val edgeWidthDp: Int = 20,
    /** Inward travel needed before release counts as a navigation. */
    val commitDistanceDp: Int = 56,
    /**
     * Ask the system to lift the 200dp-per-edge cap on gesture exclusion by requesting hidden
     * navigation bars with "show transient bars by swipe" on the strip window. This is the same
     * path fullscreen games use. Off = plain exclusion rects, capped by the system.
     */
    val unrestrictedExclusion: Boolean = true,
    /** Tint the strips so you can see where they are. */
    val debugTint: Boolean = false,
    /**
     * When the browser's Back button cannot be found at all, fall back to the system back action.
     * Off (default) = do nothing, which is the iOS behaviour we are after.
     */
    val fallbackToSystemBack: Boolean = false,
    /** Packages the strips are active for. */
    val targets: Set<String> = setOf(SAMSUNG_INTERNET),
) {
    companion object {
        const val SAMSUNG_INTERNET = "com.sec.android.app.sbrowser"
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("edge_rewire", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = read()
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        sp.edit()
            .putBoolean(K_ENABLED, s.enabled)
            .putInt(K_EDGE_WIDTH, s.edgeWidthDp)
            .putInt(K_COMMIT, s.commitDistanceDp)
            .putBoolean(K_UNRESTRICTED, s.unrestrictedExclusion)
            .putBoolean(K_TINT, s.debugTint)
            .putBoolean(K_FALLBACK, s.fallbackToSystemBack)
            .putStringSet(K_TARGETS, s.targets)
            .apply()
        _settings.value = s
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            enabled = sp.getBoolean(K_ENABLED, d.enabled),
            edgeWidthDp = sp.getInt(K_EDGE_WIDTH, d.edgeWidthDp),
            commitDistanceDp = sp.getInt(K_COMMIT, d.commitDistanceDp),
            unrestrictedExclusion = sp.getBoolean(K_UNRESTRICTED, d.unrestrictedExclusion),
            debugTint = sp.getBoolean(K_TINT, d.debugTint),
            fallbackToSystemBack = sp.getBoolean(K_FALLBACK, d.fallbackToSystemBack),
            targets = sp.getStringSet(K_TARGETS, null) ?: d.targets,
        )
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_EDGE_WIDTH = "edge_width_dp"
        const val K_COMMIT = "commit_distance_dp"
        const val K_UNRESTRICTED = "unrestricted_exclusion"
        const val K_TINT = "debug_tint"
        const val K_FALLBACK = "fallback_system_back"
        const val K_TARGETS = "targets"
    }
}
