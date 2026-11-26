package com.framextv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** Registers the FrameXTV provider with CloudStream. */
@CloudstreamPlugin
class FrameXtvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FrameXtvProvider())
    }
}
