# MegaPlay – CloudStream 3 extension

Two CloudStream 3 providers: **MegaPlay** (mainstream anime) and **Hanime.tv** (adult/hentai).

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

## Hanime.tv provider

A second provider, **Hanime.tv**, uses hanime's own catalogue and player APIs. It only lists videos hanime actually hosts:

- **Browse / search / title pages** come from `guest.freeanimehentai.net/api/v11/search_hvs` (the same dataset the site's own search worker uses). Results are sorted/filtered locally and cached for 30 minutes. Series episodes are grouped from the videos that exist in that dataset.
- **Playback** uses the player's `POST auth.hanime.tv/api/v11/handshake`. The extension reproduces the site's `web2` signature (`SHA-256` of the timestamp with its two salts) and seals the request with AES-256-GCM, then decrypts the `x-token` response header to get fresh HLS URLs for every available quality. No WebView or WASM is involved.

The HLS segments are AES-128 encrypted with the key published by the site (`ct.htv-services.com/sign.bin`); CloudStream's player handles that automatically.

Hanime's API is guarded by Cloudflare. If direct handshakes return 403 (common from VPN / data-centre IPs), set an optional **Hanime relay URL** in the extension settings — requests are then sent to `<relay>/api/v11/handshake` (with an optional Bearer secret). The "Test Hanime handshake" settings action reports each step.

## Data saver (optional)

Some titles only offer 1080p on MegaPlay. If you run your own re-encoding server, the extension can add a lower-resolution
source for those titles to save mobile data. **Nothing about a server is included in this repository**: in the extension settings
(gear next to MegaPlay) enter your own *Data saver server URL* and *API key*, and pick the target quality (240p / 360p / 480p).
It only kicks in when MegaPlay offers nothing at or below the chosen quality, and adds an extra source named
"... 360p (data saver)". "Run connection test" in the same menu checks the server and shows the IP it exits from.

The server is any HTTP service that implements this small contract:

| Request | Purpose |
|---|---|
| `POST /jobs` with header `X-API-Key` and JSON `{"url": "<hls url>", "height": 360, "headers": {"Referer": "...", "User-Agent": "..."}}` | Start (or reuse) a re-encode. Returns `{"id": "...", "playlist": "/path/to/index.m3u8"}` once playback can start. |
| `GET <playlist>` and the segments it references | Standard HLS, no API key needed (the path is the capability). |
| `GET /health`, `GET /ip` (with `X-API-Key`) | Used by the connection test. |

`tools/Check.java` can verify such a server: `RECODER_URL=... RECODER_KEY=... java tools/Check.java`.

## Install

In CloudStream: Settings > Extensions > Add repository, then paste:

```
https://raw.githack.com/Undertaker-afk/megaplay-cloudstream/main/repo.json
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

## Extra subtitles

MegaPlay's dub streams have no captions, so the dub reuses the sub's tracks (the "Dubtitle" track first).
In the extension settings, **Extra subtitles** can also pull English subtitles from other sources
(off / only when MegaPlay has none / always; default: only when MegaPlay has none):

| Source | Key needed | Lookup |
|---|---|---|
| OpenSubtitles (via Stremio) | no | MAL id -> IMDb (ARM), season + episode |
| Jimaku | yes, set in settings | MAL id -> AniList (ARM), episode |
| Wyzie | yes, set in settings | MAL id -> IMDb (ARM), season + episode |

Sources that need a key are skipped until you enter one. "Run connection test" reports each source.
