package com.megaplay

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
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
    override var name = "Hanime.tv"
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

    private suspend fun fetchHits(force: Boolean = false): List<Hv> {
        if (!force && cachedHits != null && System.currentTimeMillis() - cachedAt < cacheTtl) {
            return cachedHits!!
        }
        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/")
        var hits: List<Hv>? = null
        try {
            val text = app.get("$guestApi/api/v11/search_hvs", headers = headers, cacheTime = 0).text
            hits = tryParseJson<HitsResp>(text)?.data ?: tryParseJson<List<Hv>>(text)
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
            hits = tryParseJson<HitsResp>(text)?.data ?: tryParseJson<List<Hv>>(text)
        }
        if (hits.isNullOrEmpty()) throw ErrorLoadingException("Hanime catalogue unavailable")
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

    private fun sortedByKey(hits: List<Hv>, key: String): List<Hv> = when (key) {
        "likes" -> hits.sortedByDescending { it.likes ?: 0 }
        "created_at_unix" -> hits.sortedByDescending { it.createdAtUnix ?: 0 }
        "released_at_unix" -> hits.sortedByDescending { it.releasedAtUnix ?: 0 }
        "downloads" -> hits.sortedByDescending { it.downloads ?: 0 }
        else -> hits.sortedByDescending { it.views ?: 0 }
    }

    override val mainPage = mainPageOf(
        "views" to "Most viewed",
        "likes" to "Most liked",
        "created_at_unix" to "Recently added",
        "released_at_unix" to "Recently released",
        "downloads" to "Most downloaded",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = sortedByKey(fetchHits(), request.data).distinctBy { it.slug }
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

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = item.coverUrl ?: item.posterUrl
            backgroundPosterUrl = item.coverUrl
            plot = stripHtml(item.description)
            tags = item.tags
            year = yearOf(item)
            addEpisodes(DubStatus.Subbed, episodes)
        }
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

        val response = app.post(url, headers = headers, json = mapOf("token" to token), timeout = 30, cacheTime = 0)
        if (response.code != 200) return null
        // OkHttp Headers lookup is case-insensitive.
        val sealedToken = response.headers["x-token"] ?: return null
        return tryParseJson<HandshakeData>(open(sealedToken) ?: return null)?.sources
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfterLast("/").substringBefore("?")
        if (slug.isBlank()) return false
        val sources = handshake(slug) ?: return false
        val playable = sources.filter { it.kind == "normal" && !it.src.isNullOrBlank() }
            .sortedByDescending { it.height ?: 0 }
        if (playable.isEmpty()) return false

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
            val slug = hits.firstOrNull { !it.slug.isNullOrBlank() }?.slug
                ?: throw ErrorLoadingException("No slugs in catalogue")
            val sources = handshake(slug)
            out.append("\n3. Handshake for $slug: ")
            if (sources == null) {
                out.append("FAILED (check the relay settings / Cloudflare)")
                return out.toString()
            }
            val usable = sources.filter { it.kind == "normal" && !it.src.isNullOrBlank() }
            out.append("${usable.size} playable sources")
            usable.take(4).forEach { out.append("\n   ${it.label ?: "${it.height}p"}: ${it.src}") }
        } catch (e: Exception) {
            out.append("\nERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
        return out.toString()
    }
}
