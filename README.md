# MovieBox Android (Native UI + Official MovieBox-Tui Rust Engine)

A fast, native Android streaming app built with **Kotlin + Jetpack Compose (Material 3) + Media3 (ExoPlayer)** powered directly by the official **[MovieBox-Tui](https://github.com/mesamirh/MovieBox-Tui)** Rust core via JNI (`libmoviebox_ffi.so`).

## Features
- **All Original Providers:** `MovieBox`, `4KHDHub`, `Dramachi`, and `Stremio Addons` — using the exact Rust scrapers, signed CloudFront cookies, `/resource-position/v2` failover, HubCloud mirror resolution, and public DNS fallback from `MovieBox-Tui`.
- **Clean Native Android UI:** Home (Trending + Continue Watching + Favorites), Search with Provider Switcher, Season & Episode Picker, Resolution & Mirror Picker, Live TV (M3U), and Offline Downloads.
- **Playback:** Built-in **Media3 (ExoPlayer)** (MP4 / HLS / DASH with custom `Cookie`, `Referer`, and `User-Agent` headers) + External Player handoff (`VLC`, `mpv`, `MX Player`, `Just Player`).
