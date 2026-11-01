// Use an integer for version numbers
version = 1

cloudstream {
    description = "Anime from MegaPlay (sub/dub, selectable quality) with Tenrai (MAL) metadata"
    authors = listOf("floga")

    /**
     * Status int as one of the following:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    requiresResources = false
    language = "en"

    iconUrl = "https://megaplay.buzz/images/favicon.png"
}

android {
    namespace = "com.megaplay"
}
