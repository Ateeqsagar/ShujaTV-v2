# ShujaTV v2

Android TV player with **no login**. On start it loads free, publicly available sources (Pakistan, India, Urdu / Hindi / Punjabi / Tamil / Telugu / Bengali channels, free movie channels, Pluto TV, public-domain films from the Internet Archive).

* Settings > Sources & repos: switch any built-in source on/off, or add your own M3U link, GitHub repo, `sources.json`, or `archive:<search words>`.
* Installs next to the original app (application id `com.shuja.tvv2`, name "ShujaTV v2").

## sources.json (your own repo for future additions)

```json
{ "sources": [
  { "name": "My channels", "url": "https://raw.githubusercontent.com/you/repo/main/my.m3u", "type": "live" },
  { "name": "My films",    "url": "https://example.com/films.m3u", "type": "movie" }
] }
```

type = live | movie | series | auto. Add the raw link of this file once in the app; later edits to the file appear after a refresh.

Build: push to GitHub, run the "Build APK" workflow, download the ShujaTV-v2-apk artifact.
