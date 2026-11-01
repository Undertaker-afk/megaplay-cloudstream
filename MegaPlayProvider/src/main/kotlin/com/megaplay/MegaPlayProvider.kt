package com.megaplay

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Metadata: Tenrai (Jikan v4 clone, MAL ids).
 * Streams:  MegaPlay /stream/mal/{malId}/{ep}/{sub|dub} -> player file id
 *           -> /stream/getSourcesNew (AES encrypted) -> master.m3u8.
 */
class MegaPlayProvider : MainAPI() {
    override var mainUrl = "https://tenrai.net"
    override var name = "MegaPlay"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val api = "https://api.tenrai.org/v1"
    private val mega = "https://megaplay.buzz"
    private val refAnikoto = "https://anikotoapi.site/"
    private val refPlayer = "https://megaplay.buzz/"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0 Safari/537.36"

    // Key/IV are zero-padded to 32/16 bytes by the player (AES-256-CBC).
    private val aesKey = "i?LMTAx0Q6,:}50U"
    private val aesIv = "W0;27ToaUpl_P%'c"

    override val mainPage = mainPageOf(
        "$api/top/anime?filter=airing" to "Trending now",
        "$api/seasons/now" to "Airing this season",
        "$api/top/anime?filter=bypopularity" to "Most popular",
        "$api/top/anime" to "Top rated",
        "$api/top/anime?type=movie" to "Top movies",
        "$api/top/anime?filter=favorite" to "Fan favorites",
        "$api/seasons/upcoming" to "Upcoming",
    )

    // ---- Tenrai models ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Pagination(@JsonProperty("has_next_page") val hasNext: Boolean? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Jpg(
        @JsonProperty("large_image_url") val large: String? = null,
        @JsonProperty("image_url") val image: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(val jpg: Jpg? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Named(val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Trailer(val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Broadcast(val day: String? = null, val time: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Anime(
        val duration: String? = null,
        val broadcast: Broadcast? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        val title: String? = null,
        @JsonProperty("title_english") val titleEnglish: String? = null,
        val type: String? = null,
        val episodes: Int? = null,
        val year: Int? = null,
        val score: Double? = null,
        val synopsis: String? = null,
        val status: String? = null,
        val images: Images? = null,
        val genres: List<Named>? = null,
        val trailer: Trailer? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ListResp(val data: List<Anime>? = null, val pagination: Pagination? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OneResp(val data: Anime? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Ep(
        @JsonProperty("mal_id") val number: Int? = null,
        val title: String? = null,
        val aired: String? = null,
        val score: Double? = null,
        val duration: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Person(val name: String? = null, val images: Images? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VoiceActor(val person: Person? = null, val language: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Character(val name: String? = null, val images: Images? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CharEntry(
        val character: Character? = null,
        val role: String? = null,
        @JsonProperty("voice_actors") val voiceActors: List<VoiceActor>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CharResp(val data: List<CharEntry>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecEntry(val entry: Anime? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecResp(val data: List<RecEntry>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpVideo(@JsonProperty("mal_id") val number: Int? = null, val images: Images? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpVideoResp(val data: List<EpVideo>? = null, val pagination: Pagination? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpResp(val data: List<Ep>? = null, val pagination: Pagination? = null)

    // ---- MegaPlay models ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(val file: String? = null, val label: String? = null, val kind: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Sources(val enc: String? = null, val tracks: List<Track>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceFile(val file: String? = null)

    // ---- Helpers ----

    private fun Anime.toSearch(): SearchResponse? {
        val id = malId ?: return null
        return newAnimeSearchResponse(title ?: titleEnglish ?: "?", "$id", TvType.Anime) {
            posterUrl = images?.jpg?.large ?: images?.jpg?.image
        }
    }

    /** Adds paging, plus Tenrai's safe-for-work filter when enabled in the extension settings. */
    private fun withPage(url: String, page: Int): String {
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "page=$page" + if (MegaPlayPlugin.hideAdult()) "&sfw" else ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get(withPage(request.data, page), referer = refAnikoto).parsedSafe<ListResp>()
        val items = resp?.data.orEmpty().mapNotNull { it.toSearch() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = resp?.pagination?.hasNext == true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$api/anime?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=25" +
            if (MegaPlayPlugin.hideAdult()) "&sfw" else ""
        return app.get(url, referer = refAnikoto).parsedSafe<ListResp>()?.data.orEmpty()
            .mapNotNull { it.toSearch() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val malId = url.substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("Bad anime id: $url")
        val info = app.get("$api/anime/$malId/full", referer = refAnikoto).parsedSafe<OneResp>()?.data
            ?: throw ErrorLoadingException("Tenrai returned no data for $malId")

        // Episode list (paginated).
        val eps = mutableListOf<Ep>()
        var page = 1
        while (page <= 30) {
            val r = app.get("$api/anime/$malId/episodes?page=$page", referer = refAnikoto)
                .parsedSafe<EpResp>() ?: break
            eps += r.data.orEmpty()
            if (r.pagination?.hasNext != true) break
            page++
        }
        // Movies / not-yet-listed shows: fall back to episode numbers 1..N.
        val numbered: List<Ep> =
            if (eps.isNotEmpty()) eps.mapIndexed { i, e -> e.copy(number = e.number ?: (i + 1)) }
            else (1..maxOf(info.episodes ?: 1, 1)).map { Ep(number = it) }

        // Per-episode thumbnails.
        val thumbs = HashMap<Int, String>()
        page = 1
        while (page <= 30) {
            val r = app.get("$api/anime/$malId/videos/episodes?page=$page", referer = refAnikoto)
                .parsedSafe<EpVideoResp>() ?: break
            r.data.orEmpty().forEach { v ->
                val n = v.number
                val img = v.images?.jpg?.image
                if (n != null && img != null) thumbs[n] = img
            }
            if (r.pagination?.hasNext != true) break
            page++
        }

        // Cast: characters with their (Japanese-first) voice actors.
        val cast = app.get("$api/anime/$malId/characters", referer = refAnikoto)
            .parsedSafe<CharResp>()?.data.orEmpty().mapNotNull { c ->
                val ch = c.character?.name ?: return@mapNotNull null
                val va = c.voiceActors.orEmpty().let { l ->
                    l.firstOrNull { it.language == "Japanese" } ?: l.firstOrNull()
                }?.person
                ActorData(
                    Actor(ch, c.character.images?.jpg?.image),
                    roleString = c.role,
                    voiceActor = va?.name?.let { Actor(it, va.images?.jpg?.image) },
                )
            }.take(40)

        val recs = app.get("$api/anime/$malId/recommendations", referer = refAnikoto)
            .parsedSafe<RecResp>()?.data.orEmpty().mapNotNull { it.entry?.toSearch() }.take(20)

        fun airedMs(s: String?): Long? = try {
            s?.take(10)?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it)?.time }
        } catch (e: Exception) {
            null
        }

        // Next episode time from the weekly JST broadcast slot (airing shows only).
        fun nextAiringOf(): NextAiring? {
            if (info.status?.contains("Currently Airing", true) != true) return null
            val day = info.broadcast?.day?.removeSuffix("s")?.take(3)?.lowercase() ?: return null
            val time = info.broadcast.time ?: return null
            val dow = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat").indexOf(day) + 1
            if (dow == 0) return null
            val (h, m) = time.split(":").map { it.toIntOrNull() ?: return null }.let { it[0] to it[1] }
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, m)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) != dow ||
                cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val nextEp = (numbered.maxOfOrNull { it.number ?: 0 } ?: 0) + 1
            if (info.episodes != null && nextEp > info.episodes) return null
            return NextAiring(nextEp, cal.timeInMillis / 1000)
        }

        fun episodes(lang: String) = numbered.map { e ->
            val n = e.number!!
            newEpisode("$malId|$n|$lang") {
                this.name = e.title ?: "Episode $n"
                this.episode = n
                this.posterUrl = thumbs[n]
                this.date = airedMs(e.aired)
                // Tenrai episode scores are out of 5.
                e.score?.takeIf { it > 0 }?.let { this.score = Score.from10(it * 2) }
                e.duration?.takeIf { it > 0 }?.let { this.runTime = it / 60 }
            }
        }

        val tvType = when {
            info.type.equals("Movie", true) -> TvType.AnimeMovie
            info.type.equals("OVA", true) || info.type.equals("ONA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(info.title ?: info.titleEnglish ?: "?", url, tvType) {
            posterUrl = info.images?.jpg?.large ?: info.images?.jpg?.image
            backgroundPosterUrl = posterUrl
            plot = info.synopsis
            actors = cast
            recommendations = recs
            nextAiring = nextAiringOf()
            addMalId(malId)
            addDuration(info.duration)
            year = info.year
            tags = info.genres?.mapNotNull { it.name }
            score = Score.from10(info.score)
            showStatus =
                if (info.status?.contains("Currently Airing", true) == true) ShowStatus.Ongoing
                else ShowStatus.Completed
            addTrailer(info.trailer?.url)
            // Sub/Dub switch in the CloudStream UI; the preferred language is listed first.
            if (MegaPlayPlugin.preferDub()) {
                addEpisodes(DubStatus.Dubbed, episodes("dub"))
                addEpisodes(DubStatus.Subbed, episodes("sub"))
            } else {
                addEpisodes(DubStatus.Subbed, episodes("sub"))
                addEpisodes(DubStatus.Dubbed, episodes("dub"))
            }
        }
    }

    // ---- Streaming ----

    private fun decrypt(enc: String): String? = try {
        val key = SecretKeySpec(aesKey.toByteArray().copyOf(32), "AES")
        val iv = IvParameterSpec(aesIv.toByteArray().copyOf(16))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, key, iv) }
        val padded = enc.padEnd((enc.length + 3) / 4 * 4, '=')
        String(cipher.doFinal(Base64.decode(padded, Base64.URL_SAFE)))
    } catch (e: Exception) {
        null
    }

    /** The embed page intermittently serves an anti-bot page without a file id, so retry. */
    private suspend fun findFileId(malId: String, ep: String, lang: String): String? {
        repeat(3) {
            // cacheTime = 0: a cached anti-bot page would make every retry fail.
            val html = app.get("$mega/stream/mal/$malId/$ep/$lang", referer = refAnikoto, cacheTime = 0).text
            Regex("""data-id="(\d+)"""").find(html)?.let { return it.groupValues[1] }
            Thread.sleep(400L * (it + 1))
        }
        return null
    }

    /**
     * Runs the whole stream-resolution chain with the app's own HTTP client and
     * reports every stage (used by the "Connection test" in the settings menu).
     */
    suspend fun diagnose(malId: String = "61990", ep: String = "1", lang: String = "sub"): String {
        val out = StringBuilder("Test: MAL $malId, episode $ep, $lang\n")
        fun step(msg: String) { out.append("\n").append(msg) }
        try {
            val page = app.get("$mega/stream/mal/$malId/$ep/$lang", referer = refAnikoto, cacheTime = 0)
            val id = Regex("""data-id="(\d+)"""").find(page.text)?.groupValues?.get(1)
            step("1. Embed page: HTTP ${page.code}, ${page.text.length} bytes, file id: ${id ?: "NONE"}")
            if (id == null) {
                step("   Page starts with: " + page.text.replace(Regex("\\s+"), " ").take(160))
                return out.toString()
            }
            val srcText = app.get("$mega/stream/getSourcesNew?id=$id", referer = refPlayer, cacheTime = 0).text
            val enc = Regex(""""enc"\s*:\s*"([^"]+)"""").find(srcText)?.groupValues?.get(1)
            step("2. Sources: ${srcText.length} bytes, encrypted field: ${if (enc != null) "found" else "MISSING"}")
            if (enc == null) {
                step("   Response starts with: " + srcText.replace(Regex("\\s+"), " ").take(160))
                return out.toString()
            }
            val json = decrypt(enc)
            step("3. Decrypt: " + if (json != null) "ok" else "FAILED")
            val master = json?.let { Regex(""""file"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
                ?.replace("\\/", "/")
            if (master == null) return out.toString()
            step("   Master: $master")
            val pl = app.get(master, referer = refPlayer, headers = mapOf("User-Agent" to ua), cacheTime = 0)
            step("4. Master playlist: HTTP ${pl.code}, ${pl.text.length} bytes")
            val names = Regex("""RESOLUTION=(\d+x\d+)""").findAll(pl.text).map { it.groupValues[1] }.toList()
            step("   Qualities: " + if (names.isEmpty()) "none listed" else names.joinToString(", "))
            if (pl.code != 200) step("   Body: " + pl.text.replace(Regex("\\s+"), " ").take(160))
            step("\nAll stages passed — links should work.")
        } catch (e: Exception) {
            step("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
        return out.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size != 3) return false
        // CloudStream prefixes episode data with mainUrl ("https://tenrai.net/61990|1|sub").
        val malId = parts[0].substringAfterLast("/")
        val (ep, lang0) = parts[1] to parts[2]
        // Fall back to the sub if the dub isn't available on MegaPlay.
        var lang = lang0
        var fileId = findFileId(malId, ep, lang)
        if (fileId == null && lang == "dub") {
            lang = "sub"
            fileId = findFileId(malId, ep, lang)
        }
        if (fileId == null) return false

        val srcText = app.get("$mega/stream/getSourcesNew?id=$fileId", referer = refPlayer, cacheTime = 0).text
        val src = tryParseJson<Sources>(srcText)
        val enc = src?.enc ?: Regex(""""enc"\s*:\s*"([^"]+)"""").find(srcText)?.groupValues?.get(1)
        val decrypted = enc?.let { decrypt(it) } ?: return false
        val master = (tryParseJson<SourceFile>(decrypted)?.file
            ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(decrypted)?.groupValues?.get(1))
            ?.replace("\\/", "/") ?: return false

        src?.tracks.orEmpty().filter { it.kind == "captions" && it.file != null }.forEach {
            subtitleCallback(newSubtitleFile(it.label ?: "Subtitle", it.file!!))
        }

        // The CDN is referer-gated.
        val headers = mapOf("Referer" to refPlayer, "User-Agent" to ua)
        val label = if (lang == "dub") "$name Dub" else "$name Sub"

        // One entry per quality in the master playlist -> the player's quality picker.
        try {
            M3u8Helper.generateM3u8(label, master, refPlayer, headers = headers).forEach(callback)
        } catch (e: Exception) {
            // fall through to the Auto link below
        }

        // Plus the raw master for adaptive "Auto" playback.
        callback(
            newExtractorLink(label, "$label Auto", master, ExtractorLinkType.M3U8) {
                this.referer = refPlayer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return true
    }
}
