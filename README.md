# Nova TV

A lightweight Android TV app (Android 8+, built for low-RAM boxes) with an Apple TV–style interface for browsing movies, series and anime.

## Features
- Apple TV–style UI: floating glass tab bar, focus-following backdrop, pinned rows, details panel
- Movies & series from TMDB, anime from AniList (Hindi, Urdu and international rows)
- Family-safe: adult titles filtered at the API level plus a local blocklist
- Instant search with a built-in remote keyboard, recent searches and voice search
- Player: Apple-style controls, resume prompt, next episode, skip intro (AniSkip), audio/subtitle/quality picker, volume boost, cleaned subtitles, 1080p cap
- Continue Watching, My List, Settings (audio language, subtitles, autoplay, frame-rate matching)
- ~2 MB release APK, ~40 MB RAM while browsing

## Build
1. Get a free TMDB API key: https://www.themoviedb.org/settings/api
2. Add it to `local.properties` (not committed):
   ```
   tmdb.key=YOUR_KEY
   ```
3. Build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
4. Install: `adb install -r app/build/outputs/apk/release/app-release.apk`

## Note
This app does not host any content. It only shows metadata from TMDB/AniList and plays streams from third-party embed providers; availability and legality of those streams depends on your region.

## Account sync (Trakt, AniList, MyAnimeList)
Each service needs a free app registration; put the IDs in `local.properties` and rebuild.

| Service | Register at | Redirect / notes | local.properties |
|---|---|---|---|
| Trakt | https://trakt.tv/oauth/applications/new | Redirect URI: `urn:ietf:wg:oauth:2.0:oob` (device login) | `trakt.clientId=…` and `trakt.clientSecret=…` |
| AniList | https://anilist.co/settings/developer | Redirect URL: `novamobile://anilist` | `anilist.clientId=…` |
| MyAnimeList | https://myanimelist.net/apiconfig | App type "other", Redirect URL: `novamobile://mal` | `mal.clientId=…` |

Then Settings → Accounts → Connect. Movies/series sync with Trakt; anime with AniList / MAL.
