package com.framextv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * FrameXTV: movies, TV series and anime.
 *
 * One backend, three catalogues:
 *  - `/api/...`        TMDB-backed movies and TV (details, cast, trailers, seasons, episodes)
 *  - `/anime/api/...`  AniList/Kitsu-backed anime (details, seasons, sub/dub streams)
 *  - `/api/stream`     one provider per request plus an `alternates` list; every provider is
 *                      requested so each server shows up as its own link.
 *
 * Items travel through CloudStream as `<mainUrl>/<movie|tv|anime>/<id>`; episode/link payloads are
 * `movie|id`, `tv|id|season|episode` and `anime|id|season|episode|sub|dub`.
 */
class FrameXtvProvider : MainAPI() {
    override var mainUrl = "https://api.framextv.tech"
    override var name = "FrameXTV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA,
    )

    // The discover endpoints only distinguish trending / popular / now_playing; anything else
    // silently falls back to "popular", so the other rows use the (working) genre filter.
    override val mainPage = mainPageOf(
        "$mainUrl/api/discover?type=movie&category=trending&page=" to "Trending Movies",
        "$mainUrl/api/discover?type=tv&category=trending&page=" to "Trending TV Shows",
        "$mainUrl/anime/api/discover?category=trending&perPage=20&page=" to "Trending Anime",
        "$mainUrl/api/discover?type=movie&category=now_playing&page=" to "In Theaters",
        "$mainUrl/api/discover?type=tv&category=now_playing&page=" to "On The Air",
        "$mainUrl/anime/api/discover?category=airing&perPage=20&page=" to "Airing Anime",
        "$mainUrl/api/discover?type=movie&category=popular&page=" to "Popular Movies",
        "$mainUrl/api/discover?type=tv&category=popular&page=" to "Popular TV Shows",
        "$mainUrl/anime/api/discover?category=popular&perPage=20&page=" to "Popular Anime",
        "$mainUrl/anime/api/discover?category=top_rated&perPage=20&page=" to "Top Rated Anime",
        "$mainUrl/anime/api/discover?category=new_releases&perPage=20&page=" to "New Anime Releases",
        "$mainUrl/api/discover?type=movie&genre=28&page=" to "Action Movies",
        "$mainUrl/api/discover?type=movie&genre=35&page=" to "Comedy Movies",
        "$mainUrl/api/discover?type=movie&genre=27&page=" to "Horror Movies",
        "$mainUrl/api/discover?type=movie&genre=878&page=" to "Sci-Fi Movies",
        "$mainUrl/api/discover?type=movie&genre=16&page=" to "Animated Movies",
        "$mainUrl/api/discover?type=tv&genre=18&page=" to "Drama Series",
        "$mainUrl/api/discover?type=tv&genre=80&page=" to "Crime Series",
        "$mainUrl/api/discover?type=tv&genre=10765&page=" to "Sci-Fi & Fantasy Series",
        "$mainUrl/api/discover?type=tv&genre=99&page=" to "Documentary Series",
    )

    // ---- Models -----------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DiscoverResponse(
        val results: List<MediaItem>? = null,
        val page: Int? = null,
        val totalPages: Int? = null,
    )

    /** A catalogue card; also used for similar / recommended / related entries. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaItem(
        val id: String? = null,
        val anilistId: String? = null,
        val title: String? = null,
        val type: String? = null,
        val poster: String? = null,
        val image: String? = null,
        val year: String? = null,
        val rating: String? = null,
    )

    /** Details for all three catalogues; every field is optional because they differ per type. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Details(
        val id: String? = null,
        val title: String? = null,
        val originalTitle: String? = null,
        val tagline: String? = null,
        val overview: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val genres: List<String>? = null,
        val rating: String? = null,
        val year: String? = null,
        @JsonProperty("release_date") val releaseDateTmdb: String? = null,
        val releaseDate: String? = null,
        val runtime: Int? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        val status: String? = null,
        val format: String? = null,
        val seasonYear: Int? = null,
        val studios: List<String>? = null,
        val episodes: Int? = null,
        val numberOfEpisodes: Int? = null,
        val seasons: List<SeasonInfo>? = null,
        val cast: List<CastEntry>? = null,
        val trailer: VideoEntry? = null,
        val trailers: List<VideoEntry>? = null,
        val videos: List<VideoEntry>? = null,
        val similar: List<MediaItem>? = null,
        val recommendations: List<MediaItem>? = null,
        val relatedAnime: List<MediaItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonInfo(
        val id: String? = null,
        val number: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        val name: String? = null,
        @JsonProperty("episode_count") val episodeCountTmdb: Int? = null,
        val episodeCount: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CastEntry(
        val name: String? = null,
        val character: String? = null,
        val profile: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoEntry(
        val key: String? = null,
        val name: String? = null,
        val site: String? = null,
        val type: String? = null,
        val official: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonResponse(val episodes: List<EpisodeInfo>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeInfo(
        val number: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        val runtime: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResponse(
        val success: Boolean? = null,
        val provider: String? = null,
        val server: String? = null,
        val audio: String? = null,
        val sources: List<Source>? = null,
        val subtitles: List<Sub>? = null,
        val headers: Map<String, String>? = null,
        val alternates: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        val url: String? = null,
        val quality: String? = null,
        val type: String? = null,
        val server: String? = null,
        val audio: String? = null,
        val headers: Map<String, String>? = null,
        val subtitles: List<Sub>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Sub(
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
        val language: String? = null,
    )

    // ---- Helpers ----------------------------------------------------------------------------

    private fun urlOf(kind: String, id: String) = "$mainUrl/$kind/$id"

    /** GET with a couple of retries: the backend sporadically answers 502 or an empty body. */
    private suspend fun fetchJsonText(url: String): String? {
        val tries = 3
        for (attempt in 1..tries) {
            try {
                val res = app.get(url, timeout = 25)
                val text = res.text.trim()
                if (res.code in 200..299 && (text.startsWith("{") || text.startsWith("["))) return text
                if (res.code in 400..499 && res.code != 429) return null
            } catch (e: Exception) {
                // fall through to the retry
            }
            if (attempt < tries) Thread.sleep(500L * attempt)
        }
        return null
    }

    private suspend inline fun <reified T : Any> getJson(url: String): T? =
        fetchJsonText(url)?.let { tryParseJson<T>(it) }

    private fun scoreOf(rating: String?): Score? =
        rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }

    private fun dateMs(s: String?): Long? = try {
        s?.take(10)?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it)?.time
        }
    } catch (e: Exception) {
        null
    }

    /** Strips the HTML (`<br>`, `<i>`, ...) AniList/Kitsu synopses carry. */
    private fun cleanText(s: String?): String? = s
        ?.replace(Regex("(?i)<br\\s*/?>"), "\n")
        ?.replace(Regex("<[^>]+>"), "")
        ?.replace("&quot;", "\"")?.replace("&#039;", "'")?.replace("&amp;", "&")
        ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&nbsp;", " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun toSearch(item: MediaItem): SearchResponse? {
        val id = (item.id ?: item.anilistId) ?: return null
        val title = item.title ?: return null
        val poster = item.poster ?: item.image
        val year = item.year?.take(4)?.toIntOrNull()
        val score = scoreOf(item.rating)
        return when (item.type?.lowercase()) {
            "tv" -> newTvSeriesSearchResponse(title, urlOf("tv", id), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
            "anime" -> newAnimeSearchResponse(title, urlOf("anime", id), TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.score = score
                addDubStatus(DubStatus.Subbed)
            }
            else -> newMovieSearchResponse(title, urlOf("movie", id), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    // ---- Home page and search ---------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = getJson<DiscoverResponse>(request.data + page)
        val items = res?.results.orEmpty().mapNotNull { toSearch(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && page < (res?.totalPages ?: 1))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = URLEncoder.encode(query, "UTF-8")
        // Movies + TV and anime live in separate catalogues; query both and merge.
        val responses = listOf(
            "$mainUrl/api/search?q=$q&page=$page",
            "$mainUrl/anime/api/search?q=$q&page=$page",
        ).amap { getJson<DiscoverResponse>(it) }
        val items = responses.flatMap { it?.results.orEmpty() }.mapNotNull { toSearch(it) }.distinctBy { it.url }
        val hasNext = responses.any { it != null && page < (it.totalPages ?: 1) }
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items.orEmpty()

    // ---- Title pages ------------------------------------------------------------------------

    private fun trailerUrls(d: Details): List<String> {
        fun rank(v: VideoEntry) = when {
            v === d.trailer -> 0
            v.type.equals("Trailer", true) -> 1
            else -> 2
        }
        return (listOfNotNull(d.trailer) + d.trailers.orEmpty() + d.videos.orEmpty())
            .filter { it === d.trailer || it.type.equals("Trailer", true) || it.type.equals("Teaser", true) }
            .sortedBy { rank(it) } // stable: the API's own order is kept within a rank
            .mapNotNull { v ->
                val key = v.key?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                when (v.site?.lowercase()) {
                    null, "youtube" -> "https://www.youtube.com/watch?v=$key"
                    "vimeo" -> "https://vimeo.com/$key"
                    "dailymotion" -> "https://www.dailymotion.com/video/$key"
                    else -> null
                }
            }
            .distinct()
            .take(5)
    }

    private fun plotOf(d: Details): String? {
        val body = cleanText(d.overview)
        val tag = d.tagline?.trim()?.takeIf { it.isNotEmpty() }
        val studio = d.studios.orEmpty().filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotEmpty() }
        return buildString {
            if (tag != null) append("“").append(tag).append("”\n\n")
            if (body != null) append(body)
            if (studio != null) append("\n\nStudio: ").append(studio)
        }.trim().takeIf { it.isNotEmpty() }
    }

    /** Poster, backdrop, logo, synopsis, genres, rating, cast, trailers and recommendations. */
    private suspend fun LoadResponse.applyDetails(d: Details) {
        posterUrl = d.poster
        backgroundPosterUrl = d.backdrop ?: d.poster
        logoUrl = d.logo
        plot = plotOf(d)
        year = d.year?.take(4)?.toIntOrNull() ?: (d.releaseDateTmdb ?: d.releaseDate)?.take(4)?.toIntOrNull() ?: d.seasonYear
        tags = d.genres?.filter { it.isNotBlank() }
        score = scoreOf(d.rating)
        duration = d.runtime?.takeIf { it > 0 }
        comingSoon = (dateMs(d.releaseDateTmdb ?: d.releaseDate)?.let { it > System.currentTimeMillis() } == true) ||
            d.status.equals("NOT_YET_RELEASED", true)
        actors = d.cast.orEmpty().mapNotNull { c ->
            val n = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(Actor(n, c.profile ?: c.profilePath), roleString = c.character?.takeIf { it.isNotBlank() })
        }.takeIf { it.isNotEmpty() }
        // Related titles (sequels/prequels) first, then recommendations, then "similar".
        recommendations = (d.relatedAnime.orEmpty() + d.recommendations.orEmpty() + d.similar.orEmpty())
            .mapNotNull { toSearch(it) }
            .distinctBy { it.url }
            .take(30)
        addTrailer(trailerUrls(d))
    }

    override suspend fun load(url: String): LoadResponse {
        val m = Regex("/(movie|tv|anime)/([^/?#]+)").find(url)
            ?: throw ErrorLoadingException("Not a FrameXTV title: $url")
        val id = m.groupValues[2]
        return when (m.groupValues[1]) {
            "movie" -> loadMovie(id)
            "tv" -> loadTv(id)
            else -> loadAnime(id)
        }
    }

    private suspend fun loadMovie(id: String): LoadResponse {
        val d = getJson<Details>("$mainUrl/api/details?id=$id&type=movie")
            ?.takeIf { it.title != null }
            ?: throw ErrorLoadingException("FrameXTV has no details for movie $id")
        val type = if (isAnimation(d)) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(d.title!!, urlOf("movie", id), type, "movie|$id|${d.originalLanguage.orEmpty()}") {
            applyDetails(d)
            addTMDbId(id)
        }
    }

    private fun isAnimation(d: Details) =
        d.originalLanguage == "ja" && d.genres.orEmpty().any { it.equals("Animation", true) }

    private suspend fun loadTv(id: String): LoadResponse {
        val d = getJson<Details>("$mainUrl/api/tv/$id")
            ?.takeIf { it.title != null }
            ?: throw ErrorLoadingException("FrameXTV has no details for series $id")
        val origLang = d.originalLanguage.orEmpty()
        val seasons = d.seasons.orEmpty().filter {
            (it.seasonNumber ?: it.number) != null && (it.episodeCountTmdb ?: it.episodeCount ?: 0) > 0
        }
        // A handful of requests at a time; long-running shows have dozens of seasons.
        val seasonData = seasons.chunked(4).flatMap { chunk ->
            chunk.amap { s -> s to getJson<SeasonResponse>("$mainUrl/api/tv/$id/season/${s.seasonNumber ?: s.number}") }
        }
        val episodes = seasonData.flatMap { (s, resp) ->
            val seasonNo = (s.seasonNumber ?: s.number)!!
            val listed = resp?.episodes.orEmpty()
            if (listed.isEmpty()) {
                // The season request failed: still offer numbered episodes so playback works.
                (1..(s.episodeCountTmdb ?: s.episodeCount ?: 0)).map { n ->
                    newEpisode("tv|$id|$seasonNo|$n|$origLang") {
                        this.name = "Episode $n"
                        this.season = seasonNo
                        this.episode = n
                    }
                }
            } else {
                listed.mapNotNull { e ->
                    val n = e.episodeNumber ?: e.number ?: return@mapNotNull null
                    newEpisode("tv|$id|$seasonNo|$n|$origLang") {
                        this.name = e.name?.takeIf { it.isNotBlank() } ?: "Episode $n"
                        this.season = seasonNo
                        this.episode = n
                        this.posterUrl = e.still
                        this.description = cleanText(e.overview)
                        this.date = dateMs(e.airDate)
                        this.score = e.voteAverage?.takeIf { it > 0 }?.let { Score.from10(it) }
                        this.runTime = e.runtime?.takeIf { it > 0 }
                    }
                }
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        val type = if (isAnimation(d)) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(d.title!!, urlOf("tv", id), type, episodes) {
            applyDetails(d)
            addTMDbId(id)
        }
    }

    /**
     * The anime backend sometimes answers with a bare Kitsu record (no cast/seasons) and sometimes
     * with the full AniList one; ask twice and keep the richer answer.
     */
    private suspend fun bestAnimeDetails(id: String): Details? {
        fun richness(d: Details) = d.seasons.orEmpty().size * 10 + d.cast.orEmpty().size +
            d.recommendations.orEmpty().size + d.relatedAnime.orEmpty().size + d.videos.orEmpty().size * 5
        var best: Details? = null
        for (attempt in 1..2) {
            val d = getJson<Details>("$mainUrl/anime/api/details?id=$id")?.takeIf { it.title != null } ?: continue
            if (best == null || richness(d) > richness(best)) best = d
            if (richness(best) >= 20) break
        }
        return best
    }

    private suspend fun loadAnime(id: String): LoadResponse {
        val d = bestAnimeDetails(id) ?: throw ErrorLoadingException("FrameXTV has no details for anime $id")
        val tvType = when (d.format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        // Every anime season is its own AniList entry (own id); `id` + season=1 selects it.
        val seasons = d.seasons.orEmpty().filter { it.id != null }.ifEmpty {
            listOf(SeasonInfo(id = id, number = 1, episodeCount = d.numberOfEpisodes ?: d.episodes))
        }
        val multiSeason = seasons.size > 1

        fun episodes(audio: String) = seasons.flatMap { s ->
            val count = (s.episodeCount ?: 0).takeIf { it > 0 } ?: 1
            (1..count).map { n ->
                newEpisode("anime|${s.id}|1|$n|$audio") {
                    this.name = if (tvType == TvType.AnimeMovie) d.title else "Episode $n"
                    this.episode = n
                    if (multiSeason) this.season = s.number
                }
            }
        }

        return newAnimeLoadResponse(d.title!!, urlOf("anime", id), tvType) {
            applyDetails(d)
            engName = d.title
            japName = d.originalTitle
            showStatus = when (d.status?.uppercase()) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            addAniListId(id.toIntOrNull())
            addEpisodes(DubStatus.Subbed, episodes("sub"))
            addEpisodes(DubStatus.Dubbed, episodes("dub"))
        }
    }

    // ---- Playback ---------------------------------------------------------------------------

    private fun qualityOf(q: String?): Int =
        Regex("(\\d{3,4})\\s*[pP]").find(q ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

    private fun headerOf(h: Map<String, String>?, key: String): String? =
        h?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = data.split("|")
        val id = p.getOrNull(1) ?: return false
        val isAnime = p[0] == "anime"
        val base = when (p[0]) {
            "movie" -> "$mainUrl/api/stream?id=$id&type=movie"
            "tv" -> "$mainUrl/api/stream?id=$id&type=tv&season=${p.getOrNull(2) ?: 1}&episode=${p.getOrNull(3) ?: 1}"
            "anime" -> "$mainUrl/anime/api/stream?id=$id&season=${p.getOrNull(2) ?: 1}" +
                "&episode=${p.getOrNull(3) ?: 1}&sub_type=${p.getOrNull(4) ?: "sub"}"
            else -> return false
        }

        // The default request picks the best provider; `alternates` lists the rest, and each is
        // requested with &provider= so every server becomes its own link.
        val first = getJson<StreamResponse>(base)
        val others = first?.alternates.orEmpty().filter { it != first?.provider }.distinct()
            .amap { alt -> getJson<StreamResponse>("$base&provider=$alt") }
        val responses = listOfNotNull(first) + others.filterNotNull()

        // Collect every server first; the list is then cleaned up before anything is shown.
        class Candidate(
            val label: String, val url: String, val isMp4: Boolean,
            val quality: Int, val headers: Map<String, String>?, val language: String?,
        )

        val seenLinks = HashSet<String>()
        val seenSubs = HashSet<String>()
        val candidates = ArrayList<Candidate>()

        responses.forEach { res ->
            res.sources.orEmpty().forEach sourceLoop@{ s ->
                val url = s.url?.takeIf { it.isNotBlank() } ?: return@sourceLoop
                if (!seenLinks.add(url)) return@sourceLoop

                var label = s.server ?: res.server ?: res.provider ?: name
                val audio = (s.audio ?: res.audio)?.lowercase()
                if (isAnime && audio == "dub" && !label.contains("dub", ignoreCase = true)) label += " (Dub)"

                candidates += Candidate(
                    label = label,
                    url = url,
                    isMp4 = s.type.equals("mp4", true) || Regex("\\.mp4(\\?|$)").containsMatchIn(url),
                    quality = qualityOf(s.quality),
                    // Anime streams need MegaPlay's Referer/Origin; the relay URLs already embed theirs.
                    headers = (s.headers ?: res.headers)?.takeIf { it.isNotEmpty() },
                    language = audioLanguageOf(label),
                )
            }

            (res.subtitles.orEmpty() + res.sources.orEmpty().flatMap { it.subtitles.orEmpty() }).forEach subLoop@{ sub ->
                val url = sub.url?.takeIf { it.isNotBlank() } ?: return@subLoop
                val label = (sub.label ?: sub.language ?: sub.lang ?: "Subtitle")
                    .removePrefix("Subtitles • ").trim().ifEmpty { "Subtitle" }
                if (!seenSubs.add("$label|$url")) return@subLoop
                subtitleCallback(newSubtitleFile(label, url))
            }
        }

        var links: List<Candidate> = candidates
        if (!isAnime) {
            // Some servers are dubbed into another language (e.g. "Server 3 (Hindi)"): keep English
            // and the title's own language, and only fall back to the dubs if nothing else exists.
            val orig = when (p[0]) {
                "movie" -> p.getOrNull(2)
                else -> p.getOrNull(4)
            }?.lowercase()?.takeIf { it.isNotEmpty() }
            links = links.filter { it.language == null || it.language == "en" || it.language == orig }
                .ifEmpty { candidates }

            // Some servers only carry a 2-3 minute clip. Compare run times and drop anything under
            // half of the longest one (a failed probe never removes a link).
            if (links.size > 1) {
                val minutes = links.amap { if (it.isMp4) null else playlistMinutes(it.url, it.headers) }
                val longest = minutes.filterNotNull().maxOrNull() ?: 0.0
                if (longest >= 10.0) {
                    links = links.filterIndexed { i, _ -> minutes[i]?.let { it >= longest * 0.5 } ?: true }
                }
            }
        }

        links.forEach { c ->
            callback(
                newExtractorLink(
                    c.label, c.label, c.url,
                    if (c.isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                ) {
                    this.referer = headerOf(c.headers, "Referer") ?: ""
                    this.quality = c.quality
                    if (c.headers != null) this.headers = c.headers
                }
            )
        }
        return links.isNotEmpty()
    }

    // Language names the servers put in their labels, e.g. "Executioner I · Server 3 (Hindi)".
    private val languageCodes = mapOf(
        "english" to "en", "hindi" to "hi", "tamil" to "ta", "telugu" to "te", "malayalam" to "ml",
        "kannada" to "kn", "bengali" to "bn", "punjabi" to "pa", "marathi" to "mr", "urdu" to "ur",
        "spanish" to "es", "latino" to "es", "french" to "fr", "german" to "de", "italian" to "it",
        "portuguese" to "pt", "russian" to "ru", "turkish" to "tr", "arabic" to "ar", "japanese" to "ja",
        "korean" to "ko", "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "thai" to "th",
        "indonesian" to "id", "vietnamese" to "vi", "polish" to "pl", "dutch" to "nl", "persian" to "fa",
        "hebrew" to "he", "greek" to "el", "ukrainian" to "uk", "czech" to "cs", "hungarian" to "hu",
        "romanian" to "ro", "swedish" to "sv",
    )

    private fun audioLanguageOf(label: String): String? =
        Regex("\\(([A-Za-z ]+)\\)").findAll(label)
            .mapNotNull { languageCodes[it.groupValues[1].trim().lowercase()] }
            .firstOrNull()

    /** Total length of an HLS stream in minutes (follows a master playlist to its first variant). */
    private suspend fun playlistMinutes(url: String, headers: Map<String, String>?): Double? {
        try {
            var text = app.get(url, headers = headers.orEmpty(), timeout = 8).text
            if (!text.startsWith("#EXTM3U")) return null
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variant = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
                text = app.get(java.net.URI(url).resolve(variant.trim()).toString(), headers = headers.orEmpty(), timeout = 8).text
                if (!text.startsWith("#EXTM3U")) return null
            }
            val seconds = Regex("#EXTINF:([\\d.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            return if (seconds > 0) seconds / 60.0 else null
        } catch (e: Exception) {
            return null
        }
    }
}
