// Use an integer for version numbers.
version = 3

cloudstream {
    description = "FrameXTV: movies, TV series and anime with multi-server streams, cast, trailers and subtitles"
    authors = listOf("floga")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "OVA")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.framextv"
}
