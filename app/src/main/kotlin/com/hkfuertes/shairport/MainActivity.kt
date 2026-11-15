package com.hkfuertes.shairport

import android.Manifest
import android.preference.PreferenceActivity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceCategory
import android.preference.SwitchPreference

@Suppress("DEPRECATION") // Classic XML preferences are deliberate for this background app.
class MainActivity : PreferenceActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var statusCategory: PreferenceCategory
    private val statusListener: () -> Unit = { runOnUiThread(::refreshStatus) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Prefs.get(this) // stores defaults (device name, ...) before the screen reads them
        addPreferencesFromResource(R.xml.preferences)
        preferences.registerOnSharedPreferenceChangeListener(this)
        // Started from the tap itself: Android forbids foreground services started from the
        // background, which a preference listener may be (e.g. adb changes it while we're paused).
        findPreference(Prefs.RECEIVER_ENABLED).setOnPreferenceChangeListener { _, value ->
            if (value == true) ReceiverService.start(this) // turning off: the service stops itself
            true
        }
        statusCategory = findPreference(PREF_STATUS) as PreferenceCategory
        refreshModel()
        refreshLowLatency()

        requestNotificationPermission()
        if (preferences.getBoolean(Prefs.RECEIVER_ENABLED, true)) ReceiverService.start(this)
    }

    override fun onResume() {
        super.onResume()
        EngineStatus.addListener(statusListener)
        AudioTapService.addListener(statusListener)
        refreshAirPlay2()
        refreshStatus()
    }

    override fun onPause() {
        EngineStatus.removeListener(statusListener)
        AudioTapService.removeListener(statusListener)
        super.onPause()
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        refreshLowLatency() // an audio tap turns it off
        fun summary(key: String, text: String) { statusCategory.findPreference(key).summary = text }
        summary(
            "status_shairport",
            getString(
                when {
                    !EngineStatus.shairport -> R.string.status_stopped
                    EngineStatus.airplay2 -> R.string.status_running_airplay2
                    else -> R.string.status_running_classic
                },
            ),
        )
        summary(
            "status_nqptp",
            getString(
                when {
                    !preferences.getBoolean(Prefs.AIRPLAY_2, false) -> R.string.status_nqptp_off
                    EngineStatus.nqptp -> R.string.status_running
                    else -> R.string.status_stopped
                },
            ),
        )
        summary(
            "status_mdns",
            if (EngineStatus.shairport && EngineStatus.advertising) {
                val name = preferences.getString(Prefs.SERVER_NAME, null)?.takeIf { it.isNotBlank() }
                    ?: Prefs.deviceName(this)
                getString(R.string.status_advertising, name, EngineStatus.address ?: getString(R.string.status_no_wifi))
            } else {
                getString(R.string.status_not_advertising)
            },
        )
        summary(
            "status_playback",
            EngineStatus.source?.let { getString(R.string.status_playing, it) } ?: getString(R.string.status_idle),
        )
        summary(
            "status_tap",
            AudioTapService.tap?.let { getString(if (it.silent) R.string.status_tap_silent else R.string.status_tap, it.app) }
                ?: getString(R.string.status_tap_off),
        )
    }

    // Configuration changes are applied by the running service itself (it listens too).
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == Prefs.AIRPLAY_2) {
            (findPreference(key) as SwitchPreference).isChecked = sharedPreferences.getBoolean(key, false)
        }
        when (key) {
            Prefs.AIRPLAY_2 -> {
                refreshModel()
                refreshStatus()
            }
            Prefs.LOW_LATENCY -> {
                (findPreference(key) as SwitchPreference).isChecked = sharedPreferences.getBoolean(key, true)
                refreshLowLatency()
            }
            Prefs.MODEL -> refreshModel()
            // The tile, the notification's "Stop" or adb may change it while this screen is open.
            Prefs.RECEIVER_ENABLED -> (findPreference(Prefs.RECEIVER_ENABLED) as SwitchPreference).isChecked =
                sharedPreferences.getBoolean(Prefs.RECEIVER_ENABLED, true)
        }
    }

    /** AirPlay 2 needs PTP's ports (Nqptp.kt): where apps can't have them it stays off, and says why. */
    private fun refreshAirPlay2() {
        val open = Nqptp.portsOpen
        findPreference(Prefs.AIRPLAY_2).apply {
            isEnabled = open
            setSummary(if (open) R.string.airplay_2_summary else R.string.airplay_2_ports_closed)
        }
        if (!open) Prefs.disableAirPlay2(preferences)
    }

    /**
     * Classic AirPlay advertises the generic model (ReceiverService); the choice is AirPlay 2's.
     * The Home app's hub removes any accessory whose model is a HomePod, so say so next to it.
     */
    private fun refreshModel() {
        val airplay2 = preferences.getBoolean(Prefs.AIRPLAY_2, false)
        val homePod = preferences.getString(Prefs.MODEL, null).orEmpty().startsWith("AudioAccessory")
        findPreference(Prefs.MODEL).apply {
            isEnabled = airplay2
            summary = when {
                !airplay2 -> getString(R.string.model_classic_summary)
                homePod -> getString(R.string.model_homepod_summary)
                else -> "%s"
            }
        }
    }

    /** AirPlay 2 only, and off while an app taps the audio (ReceiverService): say which applies. */
    private fun refreshLowLatency() {
        val airplay2 = preferences.getBoolean(Prefs.AIRPLAY_2, false)
        findPreference(Prefs.LOW_LATENCY).apply {
            isEnabled = airplay2
            summary = getString(
                when {
                    !airplay2 -> R.string.low_latency_airplay2_summary
                    AudioTapService.tap != null && preferences.getBoolean(Prefs.LOW_LATENCY, true) ->
                        R.string.low_latency_tap_summary
                    else -> R.string.low_latency_summary
                },
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    companion object {
        private const val PREF_STATUS = "status"
    }
}
