package com.megaplay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

/**
 * Extra English subtitle sources, used when MegaPlay has none (or always, see the settings).
 *   ARM (arm.haglund.dev)   MAL id -> AniList / IMDb ids, no key
 *   Stremio OpenSubtitles   by IMDb id + season + episode, no key
 *   Jimaku (jimaku.cc)      by AniList id + episode, needs an API key from the settings
 *   Wyzie (sub.wyzie.io)    by IMDb id + season + episode, needs an API key from the settings
 * Every source is optional and failures are swallowed so playback is never affected.
 */
class ExtraSubtitles {
    private val arm = "https://arm.haglund.dev/api/v2/ids"
    private val stremio = "https://opensubtitles-v3.strem.io/subtitles"
    private val jimaku = "https://jimaku.cc"
    private val wyzie = "https://sub.wyzie.io/search"
    private val timeout = 12L

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ArmIds(
        val anilist: Int? = null,
        val imdb: String? = null,
        @JsonProperty("themoviedb-season") val tmdbSeason: Int? = null,
        @JsonProperty("thetvdb-season") val tvdbSeason: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioSub(val url: String? = null, val lang: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioResp(val subtitles: List<StremioSub>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JimakuEntry(val id: Int? = null, val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JimakuFile(val name: String? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WyzieSub(val url: String? = null, val display: String? = null, val format: String? = null)

    private class Sub(val label: String, val url: String)

    private suspend fun ids(malId: String): ArmIds? =
        tryParseJson<ArmIds>(app.get("$arm?source=myanimelist&id=$malId", timeout = timeout, cacheTime = 60).text)

    private fun seasonOf(ids: ArmIds) = ids.tmdbSeason ?: ids.tvdbSeason ?: 1

    private suspend fun fromStremio(ids: ArmIds, ep: String): List<Sub> {
        val imdb = ids.imdb ?: return emptyList()
        var subs = tryParseJson<StremioResp>(
            app.get("$stremio/series/$imdb:${seasonOf(ids)}:$ep.json", timeout = timeout, cacheTime = 60).text
        )?.subtitles.orEmpty()
        // Movies live under a different path; only worth a second request for single-episode titles.
        if (subs.none { it.lang == "eng" } && ep == "1") {
            subs = tryParseJson<StremioResp>(
                app.get("$stremio/movie/$imdb.json", timeout = timeout, cacheTime = 60).text
            )?.subtitles.orEmpty()
        }
        return subs.filter { it.lang == "eng" && it.url != null }.take(3)
            .mapIndexed { i, s -> Sub("English (OpenSubtitles ${i + 1})", s.url!!) }
    }

    private suspend fun fromJimaku(ids: ArmIds, ep: String, key: String): List<Sub> {
        val anilist = ids.anilist ?: return emptyList()
        val headers = mapOf("Authorization" to key)
        val entry = tryParseJson<List<JimakuEntry>>(
            app.get("$jimaku/api/entries/search?anilist_id=$anilist", headers = headers, timeout = timeout, cacheTime = 0).text
        )?.firstOrNull()?.id ?: return emptyList()
        val files = tryParseJson<List<JimakuFile>>(
            app.get("$jimaku/api/entries/$entry/files?episode=$ep", headers = headers, timeout = timeout, cacheTime = 0).text
        ).orEmpty()
        return files.filter { it.url != null && Regex("""\.(srt|ass|vtt)$""", RegexOption.IGNORE_CASE).containsMatchIn(it.url!!) }
            .take(3).map { Sub("Jimaku: " + (it.name ?: "subtitle"), it.url!!) }
    }

    private suspend fun fromWyzie(ids: ArmIds, ep: String, key: String): List<Sub> {
        val imdb = ids.imdb ?: return emptyList()
        val url = "$wyzie?id=$imdb&season=${seasonOf(ids)}&episode=$ep&language=en&key=$key"
        return tryParseJson<List<WyzieSub>>(app.get(url, timeout = timeout, cacheTime = 0).text).orEmpty()
            .filter { it.url != null }.take(3).map { Sub("Wyzie: " + (it.display ?: "English"), it.url!!) }
    }

    /** Hands every subtitle the enabled sources find to [callback]; returns how many were delivered. */
    suspend fun fetch(malId: String, ep: String, callback: (SubtitleFile) -> Unit): Int {
        val ids = try { ids(malId) } catch (e: Exception) { null } ?: return 0
        val found = mutableListOf<Sub>()
        suspend fun source(block: suspend () -> List<Sub>) {
            try { found += block() } catch (e: Exception) { /* a failing source must not hide the others */ }
        }
        source { fromStremio(ids, ep) }
        MegaPlayPlugin.jimakuKey()?.let { key -> source { fromJimaku(ids, ep, key) } }
        MegaPlayPlugin.wyzieKey()?.let { key -> source { fromWyzie(ids, ep, key) } }
        found.distinctBy { it.url }.forEach { callback(newSubtitleFile(it.label, it.url)) }
        return found.size
    }

    /** One line per source for the settings "Connection test". */
    suspend fun diagnose(malId: String = "58567", ep: String = "1"): String {
        val out = StringBuilder("Extra subtitles (MAL $malId, episode $ep)")
        fun line(msg: String) { out.append("\n").append(msg) }
        val ids = try { ids(malId) } catch (e: Exception) { line("ARM: ERROR ${e.javaClass.simpleName}: ${e.message}"); return out.toString() }
        if (ids == null) { line("ARM: no ids"); return out.toString() }
        line("ARM: ok (imdb ${ids.imdb ?: "none"}, anilist ${ids.anilist ?: "none"}, season ${seasonOf(ids)})")
        suspend fun probe(name: String, key: String?, block: suspend (String) -> List<Sub>) {
            if (key == null && name != "OpenSubtitles") { line("$name: no API key set"); return }
            line("$name: " + try { "${block(key ?: "").size} track(s)" } catch (e: Exception) { "ERROR ${e.javaClass.simpleName}: ${e.message}" })
        }
        probe("OpenSubtitles", null) { fromStremio(ids, ep) }
        probe("Jimaku", MegaPlayPlugin.jimakuKey()) { fromJimaku(ids, ep, it) }
        probe("Wyzie", MegaPlayPlugin.wyzieKey()) { fromWyzie(ids, ep, it) }
        return out.toString()
    }
}
