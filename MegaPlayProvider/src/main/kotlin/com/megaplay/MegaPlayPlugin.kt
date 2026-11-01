package com.megaplay

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

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
        )
        AlertDialog.Builder(ctx)
            .setTitle("MegaPlay settings")
            .setItems(items) { dialog, which ->
                val editor = prefs?.edit()
                when (which) {
                    0 -> editor?.putString(KEY_LANG, if (preferDub()) "sub" else "dub")
                    1 -> editor?.putBoolean(KEY_HIDE_ADULT, !hideAdult())
                }
                editor?.apply()
                dialog.dismiss()
                showSettings(ctx) // reopen so the new value is visible
            }
            .setNegativeButton("Close", null)
            .show()
    }

    companion object {
        private const val KEY_LANG = "preferred_lang"
        private const val KEY_HIDE_ADULT = "hide_adult"
        var prefs: SharedPreferences? = null

        fun preferDub(): Boolean = prefs?.getString(KEY_LANG, "sub") == "dub"

        /** Off by default so R+ titles (e.g. Cyberpunk: Edgerunners) show up in search. */
        fun hideAdult(): Boolean = prefs?.getBoolean(KEY_HIDE_ADULT, false) == true
    }
}
