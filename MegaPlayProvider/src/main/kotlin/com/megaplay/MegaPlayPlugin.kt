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
        registerMainAPI(MegaPlayProvider())

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
            "Run connection test",
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
                    5 -> runConnectionTest(ctx)
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
            .setMessage(if (secret) "Leave empty to clear. Stored only on this device." else "Leave empty to disable the data saver.")
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
                provider.diagnose() + "\n\n" + provider.diagnoseRecoder()
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
        private val HEIGHTS = listOf(240, 360, 480)
        var prefs: SharedPreferences? = null

        fun preferDub(): Boolean = prefs?.getString(KEY_LANG, "sub") == "dub"

        /** Off by default so R+ titles (e.g. Cyberpunk: Edgerunners) show up in search. */
        fun hideAdult(): Boolean = prefs?.getBoolean(KEY_HIDE_ADULT, false) == true

        /** Data-saver server base URL entered by the user, or null when unset/invalid. */
        fun recoderUrl(): String? =
            prefs?.getString(KEY_RECODE_URL, null)?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        fun recoderKey(): String? = prefs?.getString(KEY_RECODE_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

        /** Target height in pixels; the data saver only kicks in if MegaPlay has nothing at or below it. */
        fun recoderHeight(): Int = prefs?.getInt(KEY_RECODE_HEIGHT, 360)?.takeIf { it in HEIGHTS } ?: 360
    }
}
