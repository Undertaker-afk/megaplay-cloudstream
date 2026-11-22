package com.megaplay

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * hanime.tv provider.
 *
 * Everything listed here comes from hanime's own catalogue: the public
 * `search_hvs` dataset is used for search / home / title metadata, and the
 * player's `POST /api/v11/handshake` is used to mint the HLS source URLs.
 *
 * The handshake requires two things the browser normally provides:
 *  - the "web2" signature: SHA-256("[time],Xkdi29,https://hanime.tv,mn2,[time]")
 *  - an AES-256-GCM sealed token (key = SHA-256("htv-insecure-handshake-v1"),
 *    additional data = "htv-insecure-v1"), with the answer returned in the
 *    response's x-token header.
 * Both are reproduced natively here; no WebView / WASM is needed.
 */
class HanimeProvider : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val guestApi = "https://guest.freeanimehentai.net"
    private val cachedApi = "https://cached.freeanimehentai.net"
    private val authApi = "https://auth.hanime.tv"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    // Salt / origin / key material used by the site's signature WASM and player.
    private val salt1 = "Xkdi29"
    private val salt2 = "mn2"
    private val origin = "https://hanime.tv"
    private val keyBytes = sha256("htv-insecure-handshake-v1".toByteArray())
    private val aad = "htv-insecure-v1".toByteArray()

    // The search dataset is ~4.5 MB and contains every video, so keep it around.
    private var cachedHits: List<Hv>? = null
    private var cachedAt = 0L
    private val cacheTtl = 30 * 60 * 1000L
    private val pageSize = 24

    // ---- Models ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Hv(
        val id: Long? = null,
        val name: String? = null,
        @JsonProperty("search_titles") val searchTitles: String? = null,
        val slug: String? = null,
        val description: String? = null,
        @JsonProperty("cover_url") val coverUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        val brand: String? = null,
        val views: Long? = null,
        val likes: Long? = null,
        val dislikes: Long? = null,
        val downloads: Long? = null,
        val tags: List<String>? = null,
        @JsonProperty("created_at_unix") val createdAtUnix: Long? = null,
        @JsonProperty("released_at_unix") val releasedAtUnix: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HitsResp(val data: List<Hv>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HlsSource(
        val src: String? = null,
        val type: String? = null,
        val height: Int? = null,
        val width: Int? = null,
        val label: String? = null,
        val kind: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HandshakeData(val sources: List<HlsSource>? = null)

    // ---- Crypto / signature helpers ----

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)

    private fun unb64url(s: String): ByteArray {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE)
    }

    /** The `x-signature` / `x-time` pair the site's WASM would normally supply. */
    private fun web2Signature(): Pair<String, String> {
        val time = (System.currentTimeMillis() / 1000L).toString()
        val input = "$time,$salt1,$origin,$salt2,$time"
        val hex = sha256(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return hex to time
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    /** Seal a player payload the way hanime's `htv-insecure` helper does. */
    private fun seal(plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val envelope = "{\"v\":1,\"alg\":\"AES-256-GCM\",\"iv\":\"${b64url(iv)}\"," +
            "\"tag\":\"${b64url(enc.copyOfRange(enc.size - 16, enc.size))}\"," +
            "\"data\":\"${b64url(enc.copyOfRange(0, enc.size - 16))}\"}"
        return b64url(envelope.toByteArray(Charsets.UTF_8))
    }

    /** Open the sealed `x-token` answer from the handshake. */
    private fun open(token: String): String? = try {
        val env = String(unb64url(token), Charsets.UTF_8)
        fun field(name: String): String =
            Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(env)?.groupValues?.get(1) ?: ""
        val iv = unb64url(field("iv"))
        val tag = unb64url(field("tag"))
        val data = unb64url(field("data"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        String(cipher.doFinal(data + tag), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    // ---- Catalogue ----

    private fun parseHits(text: String): List<Hv>? = tryParseJson<HitsResp>(text)?.data ?: tryParseJson<List<Hv>>(text)

    // The 4.5 MB dataset is also kept on disk so a cold start does not re-download it.
    private val diskTtl = 6 * 60 * 60 * 1000L
    private fun cacheFile() = MegaPlayPlugin.cacheDir?.let { java.io.File(it, "hanime_search_hvs.json") }

    private suspend fun fetchHits(force: Boolean = false): List<Hv> {
        if (!force && cachedHits != null && System.currentTimeMillis() - cachedAt < cacheTtl) {
            return cachedHits!!
        }
        val file = cacheFile()
        if (!force && file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < diskTtl) {
            val onDisk = try { parseHits(file.readText()) } catch (e: Exception) { null }
            if (!onDisk.isNullOrEmpty()) {
                cachedHits = onDisk
                cachedAt = System.currentTimeMillis()
                return onDisk
            }
        }
        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/")
        var hits: List<Hv>? = null
        var raw: String? = null
        try {
            val text = app.get("$guestApi/api/v11/search_hvs", headers = headers, cacheTime = 0).text
            hits = parseHits(text)
            raw = text
        } catch (e: Exception) {
            // fall through to the signed v10 endpoint
        }
        if (hits.isNullOrEmpty()) {
            // Some installs need the signed v10 endpoint instead.
            val (sig, time) = web2Signature()
            val signed = headers + mapOf(
                "x-signature-version" to "web2",
                "x-signature" to sig,
                "x-time" to time,
            )
            val text = app.get("$cachedApi/api/v10/search_hvs", headers = signed, cacheTime = 0).text
            hits = parseHits(text)
            raw = text
        }
        if (hits.isNullOrEmpty()) throw ErrorLoadingException("Hanime catalogue unavailable")
        try { if (file != null && raw != null) file.writeText(raw) } catch (e: Exception) { /* cache is optional */ }
        cachedHits = hits
        cachedAt = System.currentTimeMillis()
        return hits
    }

    private fun stripHtml(s: String?): String? = s
        ?.replace(Regex("<[^>]*>"), " ")
        ?.replace("&nbsp;", " ")
        ?.replace("&amp;", "&")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** "Some Series Ep 3" / "Some Series 3" -> "Some Series". */
    private fun baseTitle(name: String): String {
        val n = name.trim()
        val ep = Regex("""(?:\s+[Ee]p(?:isode)?\s*|\s+)(\d{1,4})$""").find(n) ?: return n
        return n.substring(0, ep.range.first).trim()
    }

    private fun episodeNumber(name: String?): Int? {
        if (name == null) return null
        return Regex("""(?:[Ee]p(?:isode)?\s*|\s)(\d{1,4})$""").find(name.trim())
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun yearOf(h: Hv): Int? = (h.releasedAtUnix ?: h.createdAtUnix)?.let {
        Calendar.getInstance().apply { timeInMillis = it * 1000 }.get(Calendar.YEAR)
    }

    private fun toSearch(h: Hv): SearchResponse? {
        val slug = h.slug ?: return null
        return newAnimeSearchResponse(h.name ?: slug, slug, TvType.Anime) {
            posterUrl = h.coverUrl ?: h.posterUrl
        }
    }

    /** Wilson lower bound of the like ratio: a 5/5 title must not outrank a 900/1000 one. */
    private fun likeScore(h: Hv): Double {
        val up = (h.likes ?: 0).toDouble()
        val n = up + (h.dislikes ?: 0)
        if (n < 20) return 0.0
        val p = up / n
        val z = 1.96
        return (p + z * z / (2 * n) - z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n)) / (1 + z * z / n)
    }

    /**
     * Home rows built from the catalogue's own numbers; one entry per series (episodes of the
     * same series share a base title and open as one page).
     */
    private fun derivedRow(catalogue: List<Hv>, kind: String): List<Hv> {
        val now = System.currentTimeMillis() / 1000
        fun ageDays(h: Hv) = maxOf(1.0, (now - (h.createdAtUnix ?: now)) / 86400.0)
        val sorted = when (kind) {
            // Views per day since it was added, damped so a fresh upload does not dominate.
            "trending" -> catalogue.sortedByDescending { (it.views ?: 0) / (ageDays(it) + 30.0) }
            "toprated" -> catalogue.filter { likeScore(it) > 0 }.sortedByDescending { likeScore(it) }
            "week" -> catalogue.filter { ageDays(it) <= 7 }.sortedByDescending { it.createdAtUnix ?: 0 }
            "gems" -> {
                val median = catalogue.mapNotNull { it.views }.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
                catalogue.filter { (it.views ?: 0) < median && likeScore(it) > 0.8 }.sortedByDescending { likeScore(it) }
            }
            // Same shuffle all day, so paging through the row stays consistent.
            "random" -> catalogue.shuffled(java.util.Random(now / 86400))
            else -> catalogue
        }
        return sorted.distinctBy { baseTitle(it.name ?: it.slug ?: "") }
    }

    private fun sortedByKey(hits: List<Hv>, key: String): List<Hv> = when (key) {
        "likes" -> hits.sortedByDescending { it.likes ?: 0 }
        "created_at_unix" -> hits.sortedByDescending { it.createdAtUnix ?: 0 }
        "released_at_unix" -> hits.sortedByDescending { it.releasedAtUnix ?: 0 }
        "downloads" -> hits.sortedByDescending { it.downloads ?: 0 }
        else -> hits.sortedByDescending { it.views ?: 0 }
    }

    override val mainPage = mainPageOf(
        // Rows computed from the catalogue itself (views, likes, dates); see derivedRow().
        "d:trending" to "Trending now",
        "d:toprated" to "Top rated",
        "d:week" to "New this week",
        "d:gems" to "Hidden gems",
        "d:random" to "Surprise me",
        "views" to "Most viewed",
        "likes" to "Most liked",
        "created_at_unix" to "Recently added",
        "released_at_unix" to "Recently released",
        "downloads" to "Most downloaded",
        // Tag / brand rows ("tag:<name>", "brand:<name>"); a name the catalogue does not have shows an empty row.
        "tag:uncensored" to "Uncensored",
        "tag:romance" to "Romance",
        "tag:comedy" to "Comedy",
        "tag:fantasy" to "Fantasy",
        "tag:harem" to "Harem",
        "tag:drama" to "Drama",
        "tag:vanilla" to "Vanilla",
        "tag:3d" to "3D",
        "brand:Pink Pineapple" to "Pink Pineapple",
        "brand:Mary Jane" to "Mary Jane",
        "brand:Bunny Walker" to "Bunny Walker",
        "brand:Queen Bee" to "Queen Bee",
        "brand:Lune Pictures" to "Lune Pictures",
        "brand:Pashmina" to "Pashmina",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catalogue = fetchHits().distinctBy { it.slug }
        val key = request.data
        val all = when {
            key.startsWith("tag:") -> key.removePrefix("tag:").let { tag ->
                catalogue.filter { h -> h.tags.orEmpty().any { it.equals(tag, ignoreCase = true) } }
            }.sortedByDescending { it.views ?: 0 }
            key.startsWith("brand:") -> key.removePrefix("brand:").let { brand ->
                catalogue.filter { it.brand?.contains(brand, ignoreCase = true) == true }
            }.sortedByDescending { it.views ?: 0 }
            key.startsWith("d:") -> derivedRow(catalogue, key.removePrefix("d:"))
            else -> sortedByKey(catalogue, key)
        }
        val from = (page - 1) * pageSize
        val items = if (from < all.size) all.subList(from, minOf(from + pageSize, all.size)) else emptyList()
        return newHomePageResponse(request.name, items.mapNotNull { toSearch(it) }, hasNext = from + pageSize < all.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return fetchHits().asSequence().filter { h ->
            listOfNotNull(h.name, h.searchTitles, h.brand).any { it.lowercase().contains(q) } ||
                h.tags.orEmpty().any { it.lowercase().contains(q) }
        }.sortedByDescending { it.views ?: 0 }.distinctBy { it.slug }
            .take(80).mapNotNull { toSearch(it) }.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/").substringBefore("?")
        val all = fetchHits()
        val item = all.firstOrNull { it.slug == slug }
            ?: throw ErrorLoadingException("Hanime video not found: $slug")
        val title = baseTitle(item.name ?: slug)

        // Group the videos hanime actually has into series/episodes where possible.
        val episodes = all.filter { baseTitle(it.name ?: "") == title && !it.slug.isNullOrBlank() }
            .sortedWith(compareBy(nullsLast()) { episodeNumber(it.name) })
            .map { v ->
                newEpisode(v.slug!!) {
                    this.name = v.name ?: title
                    episodeNumber(v.name)?.let { this.episode = it }
                    this.posterUrl = v.coverUrl ?: v.posterUrl
                    this.date = (v.releasedAtUnix ?: v.createdAtUnix)?.times(1000)
                }
            }

        // Recommended: other series ranked by shared tags, with a bonus for the same studio
        // (these open in this provider).
        val myTags = item.tags.orEmpty().map { it.lowercase() }.toSet()
        val related = all.asSequence()
            .filter { !it.slug.isNullOrBlank() && baseTitle(it.name ?: "") != title }
            .map { h ->
                val shared = h.tags.orEmpty().count { it.lowercase() in myTags }
                h to (shared + if (item.brand != null && h.brand == item.brand) 3 else 0)
            }
            .filter { it.second >= 3 }
            .sortedWith(compareByDescending<Pair<Hv, Int>> { it.second }.thenByDescending { it.first.views ?: 0 })
            .map { it.first }
            .distinctBy { baseTitle(it.name ?: "") }
            .take(20).mapNotNull { toSearch(it) }.toList()

        // Tenrai (MAL) metadata when the show is known there; never blocks the page.
        val extra = try { tenraiFor(title) } catch (e: Exception) { null }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = item.coverUrl ?: item.posterUrl
            backgroundPosterUrl = item.coverUrl
            plot = stripHtml(item.description) ?: extra?.info?.synopsis
            tags = item.tags
            year = yearOf(item) ?: extra?.info?.year
            addEpisodes(DubStatus.Subbed, episodes)
            if (extra != null) {
                actors = extra.cast
                recommendations = related + extra.recs
                extra.info.malId?.let { addMalId(it) }
                addDuration(extra.info.duration)
                score = Score.from10(extra.info.score)
                addTrailer(extra.info.trailer?.url)
            } else {
                recommendations = related
            }
        }
    }

    // ---- Tenrai enrichment ----

    private val tenrai = "https://api.tenrai.org/v1"
    private val tenraiReferer = "https://anikotoapi.site/"
    private val megaPlay by lazy { MegaPlayProvider() }

    private class Enrich(val info: MegaPlayProvider.Anime, val cast: List<ActorData>, val recs: List<SearchResponse>)

    /** Looks the title up on Tenrai (exact normalised-title match) for rating, cast, trailer and recommendations. */
    private suspend fun tenraiFor(title: String): Enrich? {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return null
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val found = app.get("$tenrai/anime?q=$q&limit=10", referer = tenraiReferer, timeout = 15)
            .parsedSafe<MegaPlayProvider.ListResp>()?.data.orEmpty()
            .firstOrNull { a -> listOfNotNull(a.title, a.titleEnglish).any { normalize(it) == wanted } } ?: return null
        val malId = found.malId ?: return null
        val info = app.get("$tenrai/anime/$malId/full", referer = tenraiReferer, timeout = 15)
            .parsedSafe<MegaPlayProvider.OneResp>()?.data ?: found

        val cast = app.get("$tenrai/anime/$malId/characters", referer = tenraiReferer, timeout = 15)
            .parsedSafe<MegaPlayProvider.CharResp>()?.data.orEmpty().mapNotNull { c ->
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

        // Tenrai's recommendations are MAL ids, which MegaPlay opens (not this provider).
        val recs = app.get("$tenrai/anime/$malId/recommendations", referer = tenraiReferer, timeout = 15)
            .parsedSafe<MegaPlayProvider.RecResp>()?.data.orEmpty().mapNotNull { r ->
                val e = r.entry ?: return@mapNotNull null
                val id = e.malId ?: return@mapNotNull null
                // Built through the MegaPlay provider so the entry carries its name and opens there.
                megaPlay.newAnimeSearchResponse(e.title ?: e.titleEnglish ?: "?", "$id", TvType.Anime) {
                    posterUrl = e.images?.jpg?.large ?: e.images?.jpg?.image
                }
            }.take(20)
        return Enrich(info, cast, recs)
    }

    // ---- Streaming ----

    /**
     * Ask the player endpoint for fresh HLS URLs. Can be pointed at a relay via
     * the extension settings (useful when the site's Cloudflare check blocks
     * direct requests from a data-centre / VPN connection).
     */
    private suspend fun handshake(slug: String): List<HlsSource>? {
        val (sig, time) = web2Signature()
        val token = seal("{\"timestamp_unix\":$time,\"directive\":\"htv_player_handshake\",\"slug\":${jsonString(slug)}}")
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Origin" to origin,
            "Referer" to "$origin/",
            "User-Agent" to ua,
            "x-signature-version" to "web2",
            "x-signature" to sig,
            "x-time" to time,
            "x-csrf-token" to "null",
        )
        val url = MegaPlayPlugin.hanimeRelayUrl()?.let {
            MegaPlayPlugin.hanimeRelaySecret()?.let { secret -> headers["Authorization"] = "Bearer $secret" }
            "$it/api/v11/handshake"
        } ?: "$authApi/api/v11/handshake"

        fun fail(why: String): List<HlsSource>? {
            MegaPlayPlugin.lastHanimeSlug = slug
            MegaPlayPlugin.lastHanimeError = "$slug: $why"
            return null
        }

        // One retry: the first request after idle time is sometimes answered with a Cloudflare 403.
        var response = app.post(url, headers = headers, json = mapOf("token" to token), timeout = 30, cacheTime = 0)
        if (response.code != 200) {
            Thread.sleep(1500)
            response = app.post(url, headers = headers, json = mapOf("token" to token), timeout = 30, cacheTime = 0)
        }
        if (response.code != 200) {
            val mitigated = response.headers["cf-mitigated"]?.let { " (cf-mitigated: $it)" } ?: ""
            return fail("handshake HTTP ${response.code}$mitigated")
        }
        // OkHttp Headers lookup is case-insensitive.
        val sealedToken = response.headers["x-token"] ?: return fail("HTTP 200 but no x-token header")
        val opened = open(sealedToken) ?: return fail("could not open the x-token answer")
        val sources = tryParseJson<HandshakeData>(opened)?.sources ?: return fail("answer has no sources list")
        MegaPlayPlugin.lastHanimeError = null
        return sources
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfterLast("/").substringBefore("?")
        if (slug.isBlank()) return false
        return emitLinks(slug, callback)
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Used by MegaPlay as an alternative source: finds the hanime video for [ep] of a show known
     * under [titles] (matched by name + trailing episode number) and emits its HLS links.
     */
    suspend fun addLinksFor(titles: List<String>, ep: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val wanted = titles.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return false
        val matches = fetchHits().filter { h ->
            val n = h.name ?: return@filter false
            !h.slug.isNullOrBlank() && (normalize(n) in wanted || normalize(baseTitle(n)) in wanted)
        }
        val pick = matches.firstOrNull { episodeNumber(it.name) == ep }
            ?: matches.singleOrNull()?.takeIf { ep == 1 && episodeNumber(it.name) == null }
            ?: return false
        return emitLinks(pick.slug!!, callback)
    }

    private suspend fun emitLinks(slug: String, callback: (ExtractorLink) -> Unit): Boolean {
        val sources = handshake(slug) ?: return false
        // Prefer "normal" streams; otherwise take anything playable except the "promotion" (ad) entries.
        val playable = sources.filter { it.kind == "normal" && !it.src.isNullOrBlank() }
            .ifEmpty { sources.filter { it.kind != "promotion" && !it.src.isNullOrBlank() } }
            .sortedByDescending { it.height ?: 0 }
        if (playable.isEmpty()) {
            MegaPlayPlugin.lastHanimeSlug = slug
            MegaPlayPlugin.lastHanimeError = "$slug: ${sources.size} source(s), none playable " +
                "(kinds: ${sources.map { it.kind ?: "?" }.distinct().joinToString()}; " +
                "${sources.count { it.src.isNullOrBlank() }} without a URL)"
            return false
        }

        val headers = mapOf("Referer" to "$origin/", "User-Agent" to ua)
        for (source in playable) {
            val src = source.src!!
            val fullUrl = if (src.startsWith("http")) src else origin + src
            val height = source.height ?: 0
            val label = source.label?.takeIf { it.isNotBlank() }
                ?: if (height > 0) "${height}p" else "HLS"
            callback(newExtractorLink(name, "$name $label", fullUrl, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = if (height > 0) height else Qualities.Unknown.value
                this.headers = headers
            })
        }
        return true
    }

    /** Used by the settings "Test Hanime handshake" action. */
    suspend fun diagnose(): String {
        val out = StringBuilder("Hanime.tv connection test")
        try {
            val (sig, time) = web2Signature()
            out.append("\n1. Signature: ${sig.take(16)}… (x-time $time)")
            val hits = fetchHits(force = true)
            out.append("\n2. Catalogue: ${hits.size} videos")
            // Real tag / brand names, so the home rows can be matched to what the site actually uses.
            out.append("\n   Top tags: " + hits.flatMap { it.tags.orEmpty() }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(15).joinToString(", ") { it.key })
            out.append("\n   Top brands: " + hits.mapNotNull { it.brand }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(10).joinToString(", ") { it.key })
            MegaPlayPlugin.lastHanimeError?.let { out.append("\n   Last failed link lookup: $it") }
            // Test the episode that last failed to play, if there is one; otherwise the first catalogue entry.
            val slug = MegaPlayPlugin.lastHanimeSlug?.takeIf { s -> hits.any { it.slug == s } }
                ?: hits.firstOrNull { !it.slug.isNullOrBlank() }?.slug
                ?: throw ErrorLoadingException("No slugs in catalogue")
            val sources = handshake(slug)
            out.append("\n3. Handshake for $slug: ")
            if (sources == null) {
                out.append("FAILED: ${MegaPlayPlugin.lastHanimeError ?: "no details"}")
                return out.toString()
            }
            val usable = sources.filter { it.kind == "normal" && !it.src.isNullOrBlank() }
            out.append("${usable.size} playable of ${sources.size} sources (kinds: " +
                sources.map { it.kind ?: "?" }.distinct().joinToString() + ")")
            usable.take(4).forEach { out.append("\n   ${it.label ?: "${it.height}p"}: ${it.src}") }
        } catch (e: Exception) {
            out.append("\nERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
        return out.toString()
    }
}
