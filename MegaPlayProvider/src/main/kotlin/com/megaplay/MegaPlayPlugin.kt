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
        openSettings = { ctx ->
            val options = arrayOf("Sub (Japanese audio)", "Dub (English audio)")
            AlertDialog.Builder(ctx)
                .setTitle("Preferred language")
                .setSingleChoiceItems(options, if (preferDub()) 1 else 0) { dialog, which ->
                    prefs?.edit()?.putString(KEY_LANG, if (which == 1) "dub" else "sub")?.apply()
                    dialog.dismiss()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    companion object {
        private const val KEY_LANG = "preferred_lang"
        var prefs: SharedPreferences? = null

        fun preferDub(): Boolean = prefs?.getString(KEY_LANG, "sub") == "dub"
    }
}
