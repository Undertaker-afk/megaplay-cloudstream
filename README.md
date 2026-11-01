# MegaPlay – CloudStream 3 extension

Anime provider for CloudStream 3.

- **Metadata** (search, home page, posters, synopsis, episode list): Tenrai API (`api.tenrai.org/v1`, Jikan/MAL clone).
- **Streaming**: MegaPlay. The provider resolves `megaplay.buzz/stream/mal/{malId}/{ep}/{sub|dub}` to the player file id, calls `/stream/getSourcesNew`, AES-decrypts the `enc` field and returns the raw `master.m3u8`.
- **Sub / Dub**: every show exposes both episode lists (CloudStream's Sub/Dub toggle on the title page). Pick the language there.
- **Resolutions**: each quality in the HLS master playlist is returned as its own link, plus an "Auto" adaptive link. Switch in the player's source/quality menu. Subtitles from MegaPlay are offered too.

- **Home page**: Trending, Airing this season, Most popular, Top rated, Top movies, Fan favorites, Upcoming.
- **Title page**: poster, synopsis, genres, cast with voice actors, recommendations, and per-episode thumbnails and air dates.

- **Ratings & runtime**: show score, per-episode scores and run times.
- **Next airing**: countdown to the next episode of airing shows (from the weekly JST broadcast slot).
- **Trailers**: trailer button when MAL has one.
- **Settings**: extension settings (gear in the extension list) to choose Sub or Dub as the preferred language and to hide R+ / adult titles (off by default). If a dub is missing, playback falls back to the sub.
- **Skip intro/outro**: the MAL id is attached to every title, so CloudStream's built-in skip feature and MAL/AniList sync work. (Extensions can't pass their own skip timestamps in this API version.)

## Install

In CloudStream: Settings > Extensions > Add repository, then paste:

```
https://raw.githubusercontent.com/Undertaker-afk/megaplay-cloudstream/main/repo.json
```

## Legal

This project hosts no media. See [DISCLAIMER.md](DISCLAIMER.md) (includes the DMCA notice).

## Build

Requires JDK 17 and the Android SDK (`ANDROID_HOME`).

```bash
./gradlew MegaPlayProvider:make
```

The `.cs3` file lands in `MegaPlayProvider/build/`. Copy it to your phone and install it from CloudStream (Settings > Extensions), or push this repo to GitHub (the included workflow publishes a `builds` branch) and add the repo URL after editing `repo.json`.

## Notes

- MegaPlay's CDN is referer-gated; the provider sends the right `Referer`.
- Not every show is mapped on MegaPlay by MAL id; unmapped shows return no links.
- If playback stops working, MegaPlay probably changed the `enc` key/IV (see `aesKey` / `aesIv` in `MegaPlayProvider.kt`).
