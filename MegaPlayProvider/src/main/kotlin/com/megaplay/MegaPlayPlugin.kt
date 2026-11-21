package com.megaplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

@CloudstreamPlugin
class MegaPlayPlugin : Plugin() {
    override fun load(context: Context) {
        prefs = context.getSharedPreferences("megaplay", Context.MODE_PRIVATE)
        cacheDir = context.cacheDir
        registerMainAPI(MegaPlayProvider())
        registerMainAPI(HanimeProvider())

        // Settings button for this extension in CloudStream's extension list.
        openSettings = { ctx -> showSettings(ctx) }
    }

    private fun showSettings(ctx: Context) {
        val items = arrayOf(
            "Preferred language: " + if (preferDub()) "Dub" else "Sub",
            "Hide R+ / adult titles: " + if (hideAdult()) "On" else "Off",
            "Data saver server URL: " + (recoderUrl() ?: "not set"),
            "Data saver API key: " + if (recoderKey() != null) "set" else "not set",
            "Data saver quality: ${recoderHeight()}p",
            "Extra subtitles: " + SUBS_MODES[subsModeIndex()].second,
            "Jimaku API key: " + if (jimakuKey() != null) "set" else "not set",
            "Wyzie API key: " + if (wyzieKey() != null) "set" else "not set",
            "Run connection test",
            "Hanime relay URL: " + (hanimeRelayUrl() ?: "not set (direct)"),
            "Hanime relay secret: " + if (hanimeRelaySecret() != null) "set" else "not set",
            "Test Hanime handshake",
            "Hanime sources in MegaPlay: " + if (hanimeEnabled()) "On" else "Off",
            "Preferred source: " + if (hanimeFirst()) "Hanime first" else "MegaPlay first",
        )
        AlertDialog.Builder(ctx)
            .setTitle("MegaPlay settings")
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                val editor = prefs?.edit()
                when (which) {
                    0 -> { editor?.putString(KEY_LANG, if (preferDub()) "sub" else "dub")?.apply(); showSettings(ctx) }
                    1 -> { editor?.putBoolean(KEY_HIDE_ADULT, !hideAdult())?.apply(); showSettings(ctx) }
                    2 -> askText(ctx, "Data saver server URL", "https://your-server:port", prefs?.getString(KEY_RECODE_URL, "") ?: "", false) {
                        prefs?.edit()?.putString(KEY_RECODE_URL, it.trim())?.apply()
                    }
                    3 -> askText(ctx, "Data saver API key", "API key", "", true) {
                        prefs?.edit()?.putString(KEY_RECODE_KEY, it.trim())?.apply()
                    }
                    4 -> {
                        val next = HEIGHTS[(HEIGHTS.indexOf(recoderHeight()) + 1) % HEIGHTS.size]
                        editor?.putInt(KEY_RECODE_HEIGHT, next)?.apply()
                        showSettings(ctx)
                    }
                    5 -> {
                        editor?.putString(KEY_SUBS_MODE, SUBS_MODES[(subsModeIndex() + 1) % SUBS_MODES.size].first)?.apply()
                        showSettings(ctx)
                    }
                    6 -> askText(ctx, "Jimaku API key", "API key from jimaku.cc", "", true) {
                        prefs?.edit()?.putString(KEY_JIMAKU, it.trim())?.apply()
                    }
                    7 -> askText(ctx, "Wyzie API key", "API key from sub.wyzie.io", "", true) {
                        prefs?.edit()?.putString(KEY_WYZIE, it.trim())?.apply()
                    }
                    8 -> runConnectionTest(ctx)
                    9 -> askText(ctx, "Hanime relay URL", "https://your-relay.example", hanimeRelayUrl() ?: "", false) {
                        prefs?.edit()?.putString(KEY_HANIME_RELAY, it.trim())?.apply()
                    }
                    10 -> askText(ctx, "Hanime relay secret", "Bearer secret (optional)", "", true) {
                        prefs?.edit()?.putString(KEY_HANIME_SECRET, it.trim())?.apply()
                    }
                    11 -> runHanimeTest(ctx)
                    12 -> { editor?.putBoolean(KEY_HANIME_ON, !hanimeEnabled())?.apply(); showSettings(ctx) }
                    13 -> { editor?.putBoolean(KEY_HANIME_FIRST, !hanimeFirst())?.apply(); showSettings(ctx) }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Text input dialog (no layout resources needed); reopens the settings menu afterwards. */
    private fun askText(
        ctx: Context, title: String, hint: String, current: String, secret: Boolean, onSave: (String) -> Unit
    ) {
        val input = EditText(ctx).apply {
            this.hint = hint
            setText(current)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage("Leave empty to clear. Stored only on this device.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()); showSettings(ctx) }
            .setNegativeButton("Cancel") { _, _ -> showSettings(ctx) }
            .show()
    }

    private fun runConnectionTest(ctx: Context) {
        val running = AlertDialog.Builder(ctx).setTitle("Connection test").setMessage("Running...")
            .setPositiveButton("Close", null).show()
        fun show(report: String) {
            val ui = Runnable {
                running.dismiss()
                AlertDialog.Builder(ctx).setTitle("Connection test").setMessage(report)
                    .setPositiveButton("Close", null).show()
            }
            (ctx as? Activity)?.runOnUiThread(ui) ?: ui.run()
        }
        Thread {
            suspend {
                val provider = MegaPlayProvider()
                provider.diagnose() + "\n\n" + ExtraSubtitles().diagnose() + "\n\n" + provider.diagnoseRecoder()
            }.startCoroutine(
                Continuation(EmptyCoroutineContext) { result ->
                    show(result.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" })
                }
            )
        }.start()
    }

    /** Same async dialog pattern as the MegaPlay connection test. */
    private fun runHanimeTest(ctx: Context) {
        val running = AlertDialog.Builder(ctx).setTitle("Hanime handshake test").setMessage("Running...")
            .setPositiveButton("Close", null).show()
        fun show(report: String) {
            val ui = Runnable {
                running.dismiss()
                AlertDialog.Builder(ctx).setTitle("Hanime handshake test").setMessage(report)
                    .setPositiveButton("Close", null).show()
            }
            (ctx as? Activity)?.runOnUiThread(ui) ?: ui.run()
        }
        Thread {
            suspend {
                HanimeProvider().diagnose()
            }.startCoroutine(
                Continuation(EmptyCoroutineContext) { result ->
                    show(result.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" })
                }
            )
        }.start()
    }

    companion object {
        private const val KEY_LANG = "preferred_lang"
        private const val KEY_HIDE_ADULT = "hide_adult"
        private const val KEY_RECODE_URL = "recode_url"
        private const val KEY_RECODE_KEY = "recode_key"
        private const val KEY_RECODE_HEIGHT = "recode_height"
        private const val KEY_SUBS_MODE = "subs_mode"
        private const val KEY_JIMAKU = "jimaku_key"
        private const val KEY_WYZIE = "wyzie_key"
        private const val KEY_HANIME_RELAY = "hanime_relay"
        private const val KEY_HANIME_SECRET = "hanime_relay_secret"
        private const val KEY_HANIME_ON = "hanime_enabled"
        private const val KEY_HANIME_FIRST = "hanime_first"
        private val HEIGHTS = listOf(240, 360, 480)

        /** Extra subtitle sources: off, only when MegaPlay has no captions, or always. */
        val SUBS_MODES = listOf("off" to "Off", "fallback" to "Only when MegaPlay has none", "always" to "Always")
        var prefs: SharedPreferences? = null

        fun preferDub(): Boolean = prefs?.getString(KEY_LANG, "sub") == "dub"

        /** Off by default so R+ titles (e.g. Cyberpunk: Edgerunners) show up in search. */
        fun hideAdult(): Boolean = prefs?.getBoolean(KEY_HIDE_ADULT, false) == true

        /** Data-saver server base URL entered by the user, or null when unset/invalid. */
        fun recoderUrl(): String? =
            prefs?.getString(KEY_RECODE_URL, null)?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        fun subsModeIndex(): Int =
            SUBS_MODES.indexOfFirst { it.first == prefs?.getString(KEY_SUBS_MODE, "fallback") }.takeIf { it >= 0 } ?: 1

        fun subsMode(): String = SUBS_MODES[subsModeIndex()].first

        fun jimakuKey(): String? = prefs?.getString(KEY_JIMAKU, null)?.trim()?.takeIf { it.isNotEmpty() }

        fun wyzieKey(): String? = prefs?.getString(KEY_WYZIE, null)?.trim()?.takeIf { it.isNotEmpty() }

        /** Where the Hanime catalogue is cached on disk (set when the plugin loads). */
        var cacheDir: java.io.File? = null

        /** Add Hanime links to MegaPlay titles it carries; independent of the adult-title filter. */
        fun hanimeEnabled(): Boolean = prefs?.getBoolean(KEY_HANIME_ON, true) != false

        /** List Hanime's sources before MegaPlay's. */
        fun hanimeFirst(): Boolean = prefs?.getBoolean(KEY_HANIME_FIRST, false) == true

        /** Optional relay for hanime's signed handshake (Cloudflare workaround). */
        fun hanimeRelayUrl(): String? =
            prefs?.getString(KEY_HANIME_RELAY, null)?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        fun hanimeRelaySecret(): String? =
            prefs?.getString(KEY_HANIME_SECRET, null)?.trim()?.takeIf { it.isNotEmpty() }

        fun recoderKey(): String? = prefs?.getString(KEY_RECODE_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

        /** Target height in pixels; the data saver only kicks in if MegaPlay has nothing at or below it. */
        fun recoderHeight(): Int = prefs?.getInt(KEY_RECODE_HEIGHT, 360)?.takeIf { it in HEIGHTS } ?: 360
    }
}
