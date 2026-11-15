#!/usr/bin/env python3
# mkoai 2.0.62 checks — real-code JVM checks + static regression checks.
# Runs Home2062Checks (WorkerApi, Trakt parse, Simkl parse, TVmaze airstamp, OMDb
# ratings, YouTube watch URL, Fanart pickers, subtitle parse, translate
# truncate/chunks, PostHog event body + distinct_id, toVideo tmdbId),
# plus static greps for the 7 items (Trakt rows, TVmaze, OMDb, YouTube,
# subtitles/translate/Fanart, ntfy push test, PostHog analytics), the
# phone/TV home wiring, the detail-page wiring, the secret audit, the
# Worker routes merge check (node --check on a /tmp merged copy — the
# routes are NOT deployed), and the version anchors.
import glob
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
WORKER_ROOT = os.path.expanduser("~/workspace/cloudflare-maoki-mkoai/src")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2062-classes"
TOTAL = 0
FAILED = []


def check(cond, name):
    global TOTAL
    TOTAL += 1
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        FAILED.append(name)


def read(rel):
    with open(os.path.join(SRC, rel), encoding="utf-8") as f:
        return f.read()


def run_jvm_class(classname, extra_args=None):
    """Run one JVM check class; return (passed, failed_names)."""
    cmd = [os.path.join(JDK, "bin/java"), "-Dsun.stdout.encoding=UTF-8"]
    if extra_args:
        cmd += extra_args
    cmd += ["-cp", ":".join([CLASSES, ORGJSON, ANDROID]),
            "app.mkoai.player." + classname]
    jr = subprocess.run(cmd, text=True, capture_output=True, timeout=180)
    passed = 0
    failed = []
    for line in jr.stdout.splitlines():
        if line.startswith("PASS "):
            passed += 1
            print(line)
        elif line.startswith("FAIL "):
            print(line)
            failed.append(line[5:])
    m = re.search(r"(\d+) " + classname.replace("Checks", "") + r" checks passed", jr.stdout)
    if not m:
        failed.append(classname + " did not complete (count line missing)")
        print(jr.stdout[-1500:])
        print(jr.stderr[-1500:])
    elif int(m.group(1)) != passed:
        failed.append(classname + " count mismatch")
    return passed, failed


ORGJSON = ("/home/hatch/.gradle-kazumi/caches/modules-2/files-2.1/org.json/"
           "json/20180813/8566b2b0391d9d4479ea225645c6ed47ef17fe41/"
           "json-20180813.jar")
ANDROID = os.path.join(ROOT, "tools/android-35/android.jar")


def main():
    global TOTAL
    jars = ":".join(glob.glob(os.path.join(ROOT, "tools/java-dependencies/*.jar")))
    aars = ":".join(glob.glob(os.path.join(ROOT, "android/build/aar/*/classes.jar")))
    os.makedirs(CLASSES, exist_ok=True)
    cp = ":".join([ORGJSON, ANDROID, jars, aars])
    sp = ":".join([os.path.join(ROOT, "android/src/main/java"),
                   os.path.join(ROOT, "shared/java"),
                   os.path.join(ROOT, "android/build/generated")])
    jc = subprocess.run(
        [os.path.join(JDK, "bin/javac"), "-encoding", "UTF-8",
         "-source", "8", "-target", "8", "-Xlint:-options",
         "-cp", cp, "-sourcepath", sp, "-d", CLASSES,
         os.path.join(SRC, "WorkerApi.java"),
         os.path.join(SRC, "TraktHome.java"),
         os.path.join(SRC, "TvmazeHome.java"),
         os.path.join(SRC, "SimklHome.java"),
         os.path.join(SRC, "OmdbClient.java"),
         os.path.join(SRC, "YoutubeClient.java"),
         os.path.join(SRC, "FanartClient.java"),
         os.path.join(SRC, "TranslateClient.java"),
         os.path.join(SRC, "SubtitleClient.java"),
         os.path.join(SRC, "PostHogClient.java"),
         os.path.join(SRC, "NotifyClient.java"),
         os.path.join(SRC, "TmdbClient.java"),
         os.path.join(SRC, "Cms.java"),
         os.path.join(SRC, "Prefs.java"),
         os.path.join(SRC, "HomeRows2061.java"),
         os.path.join(SRC, "VideoKind2061.java"),
         os.path.join(SRC, "MobileShell.java"),
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "MobileDetailScreen.java"),
         os.path.join(SRC, "PlaybackControls.java"),
         os.path.join(SRC, "PlaybackController.java"),
         os.path.join(SRC, "MobileIosSettings.java"),
         os.path.join(SRC, "SettingsScreen.java"),
         os.path.join(SRC, "MainActivity.java"),
         os.path.join(SRC, "GlobalSearchScreen.java"),
         os.path.join(ROOT, "android/tests/Home2062Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.62 sources + Home2062Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2062Checks ----
    p, f = run_jvm_class("Home2062Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- Static: (1) WorkerApi shared helper ----
    wa = read("WorkerApi.java")
    check("maoki-mkoai-api.maotai0522.workers.dev" in wa, "(1) WorkerApi.BASE is the live Worker")
    check("class RateLimited extends IOException" in wa and "retryAfter" in wa,
          "(1) WorkerApi surfaces 429 as RateLimited with retry_after")
    check('replace("+", "%20")' in wa, "(1) WorkerApi.encode uses %20, not +")
    check("getJson" in wa and "getText" in wa and "postJson" in wa,
          "(1) WorkerApi has getJson / getText / postJson")
    check("HTTP \" + status" in wa, "(1) WorkerApi error message carries the HTTP status")

    # ---- Static: (1) Trakt rows ----
    tr = read("TraktHome.java")
    check("fetchMoviesItems" in tr and "fetchShowsItems" in tr,
          "(1) TraktHome fetches trending movies + shows")
    check("/api/trakt?type=\" + type + \"&list=trending" in tr,
          "(1) TraktHome calls /api/trakt trending")
    check('"&list=popular"' not in tr and "catch (WorkerApi.RateLimited" in tr
          and "QuotaState2066.record" in tr,
          "(1) trending 429 records the quota and rethrows — no popular fallback (2.0.66)")
    check("MAX_ITEMS = 20" in tr, "(1) Trakt rows cap at 20 items")
    check("fetchMoviesVideos" in tr and "fetchShowsVideos" in tr,
          "(1) TraktHome has the TV Video variants")
    check('v.tag = "trakt-" + kind' in tr and 'toVideos(entries, "電影")' in tr
          and 'toVideos(entries, "影劇")' in tr, "(1) Trakt videos carry the trakt tag")
    check("BoundedBatch.run" in tr and "3000" in tr,
          "(1) Trakt Fanart poster fill has a hard 3s budget")
    check("optInt(\"tvdb\"" in tr, "(1) Trakt keeps the TVDB id for show posters")
    check(tr.count("WorkerApi.getJson(\"/api/trakt") == 1,
          "(1) Trakt calls the Worker exactly once (trending only), never loops, no fallback")

    # ---- Static: (2) TVmaze ----
    tv = read("TvmazeHome.java")
    check("fetchTonightItems" in tv and "list=schedule&country=US&date=" in tv,
          "(2) TVmaze tonight schedule: US, Taipei today")
    check("optString(\"airstamp\"" in tv, "(2) TVmaze sorts/converts on airstamp")
    check('"airdate"' not in tv, "(2) TVmaze never reads airdate (the day-lag trap)")
    check("fetchShow" in tv and "HTTP 404" in tv and "return null" in tv,
          "(2) TVmaze fetchShow: 404 -> null, not an exception")
    check("fetchEpisode" in tv and "https://api.tvmaze.com" not in tv,
          "(2) TVmaze fetchEpisode takes the absolute _links URL (direct, no key)")
    check("searchShow" in tv and "/api/tvmaze?q=" in tv,
          "(2) TVmaze searchShow goes through the Worker")
    check("今晚" in tv and "明天" in tv, "(2) TVmaze airInfo labels 今晚 / 明天 (Taipei day)")

    # ---- Static: (3) OMDb ratings ----
    om = read("OmdbClient.java")
    check("fetchByImdbId" in om and "/api/omdb?id=" in om,
          "(3) OMDb fetches by IMDb id first")
    check("fetchByTitle" in om and "/api/omdb?title=" in om,
          "(3) OMDb falls back to title lookup")
    check('"Internet Movie Database"' in om and '"Rotten Tomatoes"' in om
          and '"Metacritic"' in om, "(3) OMDb reads all three rating sources")
    check('"N/A"' in om, "(3) OMDb treats N/A as missing")
    md = read("MobileDetailScreen.java")
    check("detail-imdb-rating" in md and "detail-mc-rating" in md,
          "(3) detail page has IMDb + Metacritic badges (rt badge already existed)")
    check("OmdbClient.ratingValue" in md,
          "(3) detail page fills badges from OMDb (regex only as fallback)")
    check("updateRatingBadges" in md and "sOmdbData" in md,
          "(3) OMDb result is cached so Douban refill cannot wash it away")

    # ---- Static: (4) YouTube trailers ----
    yt = read("YoutubeClient.java")
    check('"/api/youtube?q=" + WorkerApi.encode(query + " trailer")' in yt,
          "(4) YouTube search appends ' trailer' to the title")
    check("maxResults=5" in yt, "(4) YouTube maxResults=5 (quota: ~100 searches/day)")
    check("CACHE" in yt and "toLowerCase" in yt,
          "(4) YouTube caches one search per title per session")
    check("watchUrl" in yt and "https://www.youtube.com/watch?v=" in yt,
          "(4) YouTube watchUrl is the canonical watch link")
    check("detail-trailer" in md, "(4) detail page has the 預告片 button")
    check("ACTION_VIEW" in md and "YoutubeClient.watchUrl" in md,
          "(4) tapping a trailer opens it with ACTION_VIEW (no embedded player)")
    check("trailer_open" in md, "(4) trailer_open event is captured")

    # ---- Static: (5a) subtitles ----
    sc = read("SubtitleClient.java")
    check("languages=ZH_BG,EN" in sc, "(5a) subtitle search defaults to ZH_BG,EN")
    check("is_pack" in sc and "unpack_files" in sc,
          "(5a) SubDL packs expand to their unpack_files")
    check("file_id" in sc, "(5a) OpenSubtitles downloads by file_id")
    check("/api/subtitles/download?provider=subdl&url=" in sc,
          "(5a) SubDL download passes the search-returned url straight through")
    check("subtitle_is_archive" in sc and "subtitle_format_unsupported" in sc,
          "(5a) subtitle download errors are surfaced by error name")
    pc = read("PlaybackControls.java")
    check("搜尋字幕…" in pc, "(5a) player subtitle menu has 搜尋字幕…")
    check("searchSubtitlesOnline" in pc and "downloadAndMountSubtitle" in pc,
          "(5a) player has the subtitle search + mount path")
    check("movie-subtitle-" in pc and ".vtt" in pc,
          "(5a) downloaded subtitles are cached as .vtt temp files")
    check("addExternalSubtitle" in pc and "2 * 1024 * 1024" in pc,
          "(5a) subtitles mount via addExternalSubtitle with a 2MB guard")

    # ---- Static: (5b) translation ----
    tc = read("TranslateClient.java")
    check('target_lang", TARGET_LANG' in tc or 'TARGET_LANG = "ZH-HANT"' in tc,
          "(5b) translation targets ZH-HANT")
    check("DEEPL_MAX_CHARS = 5000" in tc, "(5b) translation caps at 5000 chars (DeepL limit)")
    check("translateViaMyMemory" in tc and "catch (WorkerApi.RateLimited" in tc,
          "(5b) DeepL 429 falls back to MyMemory (chunked <=500)")
    check("mymemory_empty_response" in tc and "translate_empty_response" in tc,
          "(5b) translation never returns untranslated text as if it translated")
    check("detail-translate-btn" in md and "detail-synopsis-translated" in md,
          "(5b) detail page shows a 翻譯 button + the translation under the original")
    check("containsChinese" in md, "(5b) the 翻譯 button only shows for non-Chinese synopses")

    # ---- Static: (5c) Fanart ----
    fa = read("FanartClient.java")
    check("data.length() == 0" in fa, "(5c) Fanart treats empty data:{} as no artwork")
    check("pickBackground" in fa and "moviebackground" in fa and "tvbanner" in fa,
          "(5c) Fanart background priority: moviebackground -> showbackground -> tvbanner")
    check("pickLogo" in fa and "hdmovielogo" in fa and "clearart" in fa,
          "(5c) Fanart logo priority: hdmovielogo -> hdtvlogo -> clearart")
    check("pickPoster" in fa and "movieposter" in fa and "tvposter" in fa,
          "(5c) Fanart poster priority: movieposter -> tvposter")
    check('"en".equals' in fa, "(5c) Fanart prefers lang=en artwork")
    check("detail-fanart-logo" in md and "enrichDetailExtras" in md,
          "(5c) detail page loads Fanart logo + backdrop in the background")
    check("externalTvdbId" in md or "TvmazeHome.searchShow" in md,
          "(5c) TV Fanart looks up the TVDB id (TMDB external_ids or TVmaze)")

    # ---- Static: (6) ntfy push test ----
    nc = read("NotifyClient.java")
    check('TOPIC = "maoki-mkoai-gq6wmzc2"' in nc, "(6) NotifyClient.TOPIC is the default topic")
    check('body.put("topic"' not in nc, "(6) the push POST never sends a topic (server default)")
    check("sendTest" in nc and "/api/notify" in nc, "(6) NotifyClient.sendTest posts to /api/notify")
    check("retry_after" in nc, "(6) NotifyClient surfaces retry_after from failures")
    mis = read("MobileIosSettings.java")
    ss = read("SettingsScreen.java")
    check("推播測試" in mis and "NotifyClient.TOPIC" in mis,
          "(6) phone settings has the push test + topic name")
    check("推播測試" in ss and "NotifyClient.TOPIC" in ss,
          "(6) TV settings has the push test + topic name")
    check("推播測試失敗" in mis and "推播測試失敗" in ss,
          "(6) push test failure is shown, never silently retried")

    # ---- Static: (7) PostHog analytics ----
    ph = read("PostHogClient.java")
    # The api_key check needs the real body: it must never be put into the JSON.
    check('body.put("api_key"' not in ph, "(7) the app NEVER sends api_key (Worker injects it)")
    check("distinctId" in ph and "UUID.randomUUID" in ph,
          "(7) distinct_id is a random UUID, never a personal identifier")
    check("posthog_distinct_id" in ph, "(7) distinct_id persists in Prefs")
    check("isAnalyticsEnabled" in ph and "analytics_enabled" in ph,
          "(7) the analytics switch gates every capture")
    check("newSingleThreadExecutor" in ph and "setDaemon(true)" in ph,
          "(7) captures run on a background daemon thread, never the UI thread")
    check("SessionReplay" not in ph and "session_replay" not in ph and "回放" not in ph,
          "(7) Session Replay is never enabled")
    pr = read("Prefs.java")
    check("ANALYTICS_ENABLED" in pr, "(7) Prefs has ANALYTICS_ENABLED")
    check("POSTHOG_DISTINCT_ID" in pr, "(7) Prefs has POSTHOG_DISTINCT_ID")
    check("analytics_enabled" in pr and "posthog_distinct_id" in pr,
          "(7) both Prefs keys use snake_case values")
    # ANALYTICS_ENABLED must be user-visible (in KEYS + BOOLEAN_KEYS);
    # POSTHOG_DISTINCT_ID is local-only (must NOT be in KEYS).
    keys_block = pr.split("KEYS = {")[1].split("};")[0] if "KEYS = {" in pr else ""
    check("ANALYTICS_ENABLED" in keys_block,
          "(7) ANALYTICS_ENABLED is user-visible (in KEYS)")
    check("POSTHOG_DISTINCT_ID" not in keys_block,
          "(7) POSTHOG_DISTINCT_ID is local-only (not in KEYS)")
    check("ANALYTICS_ENABLED" in pr.split("BOOLEAN_KEYS")[1].split(")));")[0],
          "(7) ANALYTICS_ENABLED is a boolean pref (in BOOLEAN_KEYS)")
    ma = read("MainActivity.java")
    check("PostHogClient.init(this)" in ma, "(7) MainActivity inits PostHogClient on launch")
    check("sAppOpenCaptured" in ma and 'capture("app_open"' in ma,
          "(7) app_open is sent once per process, not on every onCreate")
    events = []
    for f in ("MainActivity.java", "PlaybackController.java", "GlobalSearchScreen.java",
              "MobileDetailScreen.java", "PlaybackControls.java"):
        events += re.findall(r'PostHogClient\.capture\("([a-z_]+)"', read(f))
    check(sorted(events) == ["app_open", "subtitle_download", "trailer_open",
                             "video_play", "video_search"],
          "(7) exactly the 5 allowed events are captured, nothing else: " + ",".join(sorted(events)))
    check("video_play" in read("PlaybackController.java")
          and 'props.put("title"' in read("PlaybackController.java"),
          "(7) video_play sends only the title (no URL or source)")

    # ---- Static: (7) Simkl via Worker lookup + phone/TV rows ----
    sh = read("SimklHome.java")
    check("fetchMoviesItems" in sh and "fetchTvItems" in sh,
          "(7) SimklHome fetches trending movies + TV as items")
    check("fetchMoviesVideos" in sh and "fetchTvVideos" in sh,
          "(7) SimklHome has the TV Video variants")
    check("/api/simkl?type=movies&list=trending" in sh
          and "/api/simkl?type=tv&list=trending" in sh,
          "(7) Simkl charts go through the Worker /api/simkl (movies + tv)")
    check("https://api.simkl.com" not in sh and "extended=posters" not in sh,
          "(7) SimklHome never calls api.simkl.com directly (direct = 412, live verified)")
    check("Cms.fetch" not in sh and "WorkerApi.getJson" in sh,
          "(7) Simkl chart fetch uses WorkerApi.getJson, not Cms.fetch")
    check("trendingData" in sh and 'optJSONArray("data")' in sh,
          "(7) Simkl parses the Worker envelope {data:[...]}")
    check("static TmdbClient.Item parseItem" in sh,
          "(7) Simkl has a pure parseItem (JVM-tested, no network)")
    check("No API key needed" not in sh,
          "(7) the stale 'no API key needed' claim is gone from SimklHome")
    check("/api/simkl?id=" in sh, "(7) a missing Simkl title is looked up via /api/simkl?id=")
    check("lookupSimklTitle" in sh, "(7) Simkl title lookup is a named helper")
    hr = read("HomeRows2061.java")
    check("KIND_TRAKT_MOVIES" in hr and "KIND_TRAKT_SHOWS" in hr
          and "KIND_SIMKL_MOVIES" in hr and "KIND_SIMKL_SHOWS" in hr
          and "KIND_TVMAZE_TONIGHT" in hr, "(rows) the 5 new row kinds exist")
    check("Trakt 热门电影" in hr and "Trakt 热门剧集" in hr
          and "Simkl 热门电影" in hr and "Simkl 热门剧集" in hr
          and "今晚播出" in hr, "(rows) the 5 new row labels exist")
    check(hr.count("KIND_TRAKT_MOVIES") >= 2 and "isExternalRow" in hr,
          "(rows) the external-row helpers exist")
    check(hr.split("TMDB_ROW_DEFS")[1].split("};")[0].count("},") == 30,
          "(rows) TMDB_ROW_DEFS has 30 rows (25 old + 5 new)")
    ms = read("MobileShell.java")
    check("TraktHome.fetchMoviesItems" in ms and "TraktHome.fetchShowsItems" in ms
          and "SimklHome.fetchMoviesItems" in ms and "SimklHome.fetchTvItems" in ms
          and "TvmazeHome.fetchTonightItems" in ms,
          "(rows) phone home dispatches all 5 new rows in the shared BoundedBatch")
    check("airInfo" in ms, "(rows) phone home copies TVmaze airInfo into the card remarks")
    hs = read("HomeScreen.java")
    check("fetchExternalSections" in hs and "startExternalSections" in hs,
          "(rows) TV home has the external-sections fetch path")
    check("TraktHome.fetchMoviesVideos" in hs and "TraktHome.fetchShowsVideos" in hs
          and "SimklHome.fetchMoviesVideos" in hs and "SimklHome.fetchTvVideos" in hs,
          "(rows) TV home fetches all 4 ranking sections in parallel")
    check("sectionRows" in hs and hs.count("sectionRows") >= 5,
          "(rows) TV sections live in their own sectionRows (not the cleared rows)")

    # ---- Static: detail page TVmaze line ----
    check("detail-tvmaze-info" in md, "(detail) detail page has the TVmaze info line")
    check("buildTvmazeLine" in md and "airstamp" in md,
          "(detail) the next-episode time comes from airstamp")
    check('"airdate"' not in md, "(detail) the detail page never reads airdate")
    check("連載中" in md and "已完結" in md, "(detail) TVmaze status is translated (連載中/已完結)")
    check("tmdbId" in read("Cms.java"), "(detail) Cms.Video carries tmdbId for OMDb/Fanart")
    check("v.tmdbId = it.id" in read("TmdbClient.java"),
          "(detail) TmdbClient.toVideo fills tmdbId from the item")
    check("externalImdbId" in read("TmdbClient.java")
          and "externalTvdbId" in read("TmdbClient.java"),
          "(detail) TmdbClient has the external-ids lookups (IMDb/TVDB)")

    # ---- Static: secret audit (no key values in the app) ----
    src_files = []
    for base, _, files in os.walk(SRC):
        for fn in files:
            if fn.endswith(".java"):
                src_files.append(os.path.join(base, fn))
    secret_hits = []
    for path in src_files:
        with open(path, encoding="utf-8", errors="replace") as f:
            txt = f.read()
        if re.search(r"phc_[A-Za-z0-9]", txt):
            secret_hits.append(os.path.basename(path) + ": phc_")
        if "os_v2_" in txt:
            secret_hits.append(os.path.basename(path) + ": os_v2_")
        if "client_id=" in txt:
            secret_hits.append(os.path.basename(path) + ": client_id=")
    check(not secret_hits, "(secrets) no PostHog/Simkl/OneSignal key values in the app: "
          + ",".join(secret_hits))
    key_assigns = [os.path.basename(p) for p in src_files
                   if re.search(r'API_KEY\s*=\s*"', open(p, encoding="utf-8", errors="replace").read())]
    check(key_assigns == ["TmdbClient.java"],
          "(secrets) the only API_KEY assignment is TmdbClient's pre-existing TMDB key: "
          + ",".join(key_assigns))

    # ---- Static: Worker routes file (new, NOT deployed) + merge check ----
    routes_path = os.path.join(WORKER_ROOT, "routes-2062-simkl-posthog.js")
    check(os.path.exists(routes_path), "(worker) routes-2062-simkl-posthog.js exists")
    routes = ""
    if os.path.exists(routes_path):
        with open(routes_path, encoding="utf-8") as f:
            routes = f.read()
    check('if (path === "/api/simkl")' in routes, "(worker) /api/simkl handler exists")
    check('if (path === "/api/posthog")' in routes, "(worker) /api/posthog handler exists")
    check("4328" in routes and "/api/notify" in routes,
          "(worker) merge anchor is documented (before /api/notify, line 4328)")
    check("BEGIN PASTE" in routes and "END PASTE" in routes,
          "(worker) the paste block is delimited")
    check("env.SIMKL_CLIENT_ID" in routes and "env.POSTHOG_API_KEY" in routes,
          "(worker) both keys come from env secrets, never hardcoded")
    check(not re.search(r"phc_[A-Za-z0-9]{8,}", routes),
          "(worker) no PostHog key value in the routes file")
    check("us.i.posthog.com/capture/" in routes, "(worker) PostHog forwards to /capture/")
    check("api.simkl.com/" in routes, "(worker) Simkl proxies api.simkl.com")
    check("simkl:trending:" in routes, "(worker) Simkl trending uses a KV cache key")
    # Merge into a /tmp copy of index.js and node --check it (no deploy).
    merged_ok = False
    node = shutil.which("node")
    index_path = os.path.join(WORKER_ROOT, "index.js")
    if node and os.path.exists(index_path) and "BEGIN PASTE" in routes and "END PASTE" in routes:
        try:
            with open(index_path, encoding="utf-8") as f:
                index_src = f.read()
            # Paste block = the lines between the full marker lines
            # (the header comment also mentions BEGIN/END PASTE, so match
            # the marker lines themselves, not the bare words).
            lines = routes.splitlines()
            begin = next(i for i, ln in enumerate(lines) if "BEGIN PASTE:" in ln)
            end = next(i for i, ln in enumerate(lines) if "END PASTE:" in ln)
            paste = "\n".join(lines[begin + 1:end])
            anchor = 'if (path === "/api/notify") {'
            if anchor in index_src:
                merged = index_src.replace(anchor, paste + "\n" + anchor, 1)
                os.makedirs("/tmp/home2062-merge", exist_ok=True)
                merged_path = "/tmp/home2062-merge/index.js"
                with open(merged_path, "w", encoding="utf-8") as f:
                    f.write(merged)
                nc = subprocess.run([node, "--check", merged_path],
                                    text=True, capture_output=True, timeout=60)
                merged_ok = nc.returncode == 0
                if nc.returncode != 0:
                    print(nc.stderr[-1000:])
        except Exception as e:  # noqa: BLE001 - merge check must not crash the runner
            print("merge check error: " + str(e))
    check(merged_ok, "(worker) merged index.js passes node --check (copy in /tmp, no deploy)")

    # ---- Static: 2.0.61 markers still present ----
    check("accountSyncLinkKeys" in ss, "2.0.61 marker: QR login link keys")
    check("filter_hls_ads" in pr or "FILTER_HLS_ADS" in pr, "2.0.61 marker: HLS ad filter pref")
    check("builtinHlsAdRules" in read("Cms.java"), "2.0.61 marker: built-in ad rules")

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.67"' in ev and "20068" in ev, "edition-versions.json = 2.0.67 / 20068")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.67"' in man and 'android:versionCode="20068"' in man,
          "AndroidManifest.xml = 2.0.67 / 20068")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
