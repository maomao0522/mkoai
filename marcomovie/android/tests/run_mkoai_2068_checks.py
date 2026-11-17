#!/usr/bin/env python3
# mkoai 2.0.68 checks — real-code JVM checks + static regression checks.
# Runs Home2068Checks (quota 7 features, hero fill math, grid math,
# SourceSpeed2068 pure checks, compareRanked order pins), plus static
# greps for the five items:
# (a) subtitles removed (SubDL + OpenSubtitles): no SubtitleClient, no
#     /api/subtitles, no 搜尋字幕 in the app tree; local file import kept;
# (b) hero poster-first, backdrop CENTER_CROP fills (no band);
# (c) view-all count line deleted (view gone, not GONE);
# (d) view-all grid denser (4 columns, tighter spacing, 140dp art);
# (e) source selection learns from Maoki: speed EMA + probe cache 6h +
#     detail-page pre-resolve + race-stop on play + startup error grace
#     — ad-rule priority untouched, probe still ranking-only, tap-play
#     still immediate, no new threads.
# Plus the TV-untouched, anime-filter-untouched, DeepL-untouched,
# secret audit, forbidden-file and version-anchor checks.
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2068-classes"
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


def read_tree(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
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


def grep_app(pattern):
    """Count matches of a literal pattern across android/src/main/java (app code only)."""
    n = 0
    for base, _dirs, files in os.walk(os.path.join(ROOT, "android/src/main/java")):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            with open(os.path.join(base, fn), encoding="utf-8") as f:
                n += f.read().count(pattern)
    return n


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
         os.path.join(SRC, "HeroDiffusion2063.java"),
         os.path.join(SRC, "HeroSwipe2063.java"),
         os.path.join(SRC, "HomeRows2061.java"),
         os.path.join(SRC, "VideoKind2061.java"),
         os.path.join(SRC, "HomeAmbient.java"),
         os.path.join(SRC, "ImageLoader.java"),
         os.path.join(SRC, "RequestScope.java"),
         os.path.join(SRC, "Cms.java"),
         os.path.join(SRC, "Prefs.java"),
         os.path.join(SRC, "TitleTranslator.java"),
         os.path.join(SRC, "TmdbClient.java"),
         os.path.join(SRC, "AwwratedTitles.java"),
         os.path.join(SRC, "ChineseConvert.java"),
         os.path.join(SRC, "MobileIosSettings.java"),
         os.path.join(SRC, "MaterialSwitch2065.java"),
         os.path.join(SRC, "SettingsScreen.java"),
         os.path.join(SRC, "UpdateScreen.java"),
         os.path.join(SRC, "MainActivity.java"),
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "MobileShell.java"),
         os.path.join(SRC, "HomeTone2066.java"),
         os.path.join(SRC, "HomeYear2066.java"),
         os.path.join(SRC, "HeroRatings2066.java"),
         os.path.join(SRC, "QuotaState2066.java"),
         os.path.join(SRC, "WorkerApi.java"),
         os.path.join(SRC, "TranslateClient.java"),
         os.path.join(SRC, "OmdbClient.java"),
         os.path.join(SRC, "TraktHome.java"),
         os.path.join(SRC, "SimklHome.java"),
         os.path.join(SRC, "TvmazeHome.java"),
         os.path.join(SRC, "MobileDetailScreen.java"),
         os.path.join(SRC, "PlaybackControls.java"),
         os.path.join(SRC, "NotifyClient.java"),
         os.path.join(SRC, "AutoSourceFallback.java"),
         os.path.join(SRC, "SourcePreferences.java"),
         os.path.join(SRC, "SourceSpeed2068.java"),
         os.path.join(SRC, "PlaybackController.java"),
         os.path.join(SRC, "PostHogClient.java"),
         os.path.join(ROOT, "android/tests/Home2068Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.68 sources + Home2068Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2068Checks ----
    p, f = run_jvm_class("Home2068Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    pc = read("PlaybackControls.java")
    qs = read("QuotaState2066.java")
    af = read("AutoSourceFallback.java")
    ss = read("SourceSpeed2068.java")
    pbc = read("PlaybackController.java")
    mds = read("MobileDetailScreen.java")
    ph = read("PostHogClient.java")
    pf = read("Prefs.java")
    hs = read("HomeScreen.java")

    # ---- Static (a): subtitles removed ----
    check(not os.path.exists(os.path.join(SRC, "SubtitleClient.java")),
          "(a) SubtitleClient.java is deleted from the tree")
    check(grep_app("SubtitleClient") == 0,
          "(a) no SubtitleClient reference left in the app code")
    check(grep_app("/api/subtitles") == 0,
          "(a) no /api/subtitles route left in the app code")
    check(grep_app("搜尋字幕") == 0,
          "(a) no 搜尋字幕 entry left in the app code")
    check("searchSubtitlesOnline" not in pc
          and "downloadAndMountSubtitle" not in pc
          and "subtitleDownloadError" not in pc
          and "subtitleLanguageLabel" not in pc
          and "currentEpisodeNumber" not in pc,
          "(a) all five removed methods are gone from PlaybackControls")
    check("SUBTITLE" not in qs,
          "(a) QuotaState2066 has no SUBTITLE slot left")
    check("four events are sent" in ph
          and "app_open, video_play" in ph and "trailer_open" in ph
          and "subtitle_download is gone" in ph,
          "(a) PostHog lists four events, subtitle_download is gone")
    check("匯入字幕檔案" in pc and "pickSubtitle()" in pc,
          "(a) local subtitle file import is kept (匯入字幕檔案… -> pickSubtitle)")
    check("addExternalSubtitle" in read("MoviePlayer.java")
          and "addExternalSubtitle" in read("SourceController.java"),
          "(a) MoviePlayer.addExternalSubtitle + the local-import caller survive")
    check("sidecar" not in pc,
          "(a) no sidecar-tradeoff comment left in PlaybackControls")

    # ---- Static (b): hero poster-first, backdrop fills ----
    hero_art = ms[ms.index("private void bindHeroArtwork"):]
    hero_art = hero_art[:hero_art.index("private View buildHeroShowcaseCard")]
    check("featured.poster" in hero_art
          and hero_art.index("featured.poster") < hero_art.index("featured.backdrop"),
          "(b) bindHeroArtwork checks the poster before the backdrop (poster-first)")
    check("CENTER_CROP" in hero_art
          and "FIT_START" not in hero_art and "FIT_CENTER" not in hero_art,
          "(b) bindHeroArtwork uses CENTER_CROP for both poster and backdrop, no FIT band")
    key = ms[ms.index("private static String heroArtworkKey"):]
    key = key[:key.index("}")]
    check(key.index("v.poster") < key.index("v.backdrop"),
          "(b) heroArtworkKey is poster-first, so the tone cache keys the displayed art")
    resolve = ms[ms.index("private void resolveHeroBackdrop"):]
    resolve = resolve[:resolve.index("private void sampleHeroTone")]
    check("v.poster == null" in resolve,
          "(b) the late backdrop only swaps in for a poster-less video (never yanks a poster)")
    check("sampleHeroTone(page, backdrop, gen, 0)" in resolve
          and resolve.count("sampleHeroTone(page, backdrop, gen, 0)") == 1,
          "(b) the tone is re-sampled under the backdrop key only on the poster-less swap")

    # ---- Static (c): view-all count line deleted ----
    check(grep_app("tmdb-list-count") == 0,
          "(c) no tmdb-list-count tag left in the app code")
    check(grep_app("僅顯示來源裡有的片") == 0,
          "(c) no count-line text left in the app code")
    check(grep_app("來源確認中") == 0,
          "(c) no count-line fallback text left in the app code")

    # ---- Static (d): view-all grid denser ----
    grid = ms[ms.index("private void fillTmdbListGrid"):]
    grid = grid[:grid.index("private void followTopSectionPoster")]
    check("i % 4 == 0" in grid and "i % 3 == 0" not in grid,
          "(d) fillTmdbListGrid is 4 columns (was 3)")
    check("a.dp(140)" in grid and "a.dp(190)" not in grid,
          "(d) grid art height is 140dp (was 190dp)")
    check("lp.setMargins(a.dp(3), a.dp(3), a.dp(6), a.dp(12))" in grid,
          "(d) grid margins are (3,3,6,12) (were (5,5,14,19))")
    check("card.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(8))" in grid,
          "(d) grid card padding is (3,3,3,8) (was (5,5,5,10))")
    check("name.setMaxLines(2)" in grid,
          "(d) grid title stays 13sp bold, max 2 lines (readable)")
    check("fillTmdbListGrid" not in hs and "showTmdbListPage" not in hs
          and "openTmdbList" not in hs,
          "(d) TV grid untouched: HomeScreen has no view-all/grid builders")

    # ---- Static (e): source selection learns from Maoki ----
    check(os.path.exists(os.path.join(SRC, "SourceSpeed2068.java")),
          "(e) SourceSpeed2068.java exists (speed EMA + probe cache 6h + grace)")
    check("SOURCE_SPEED_2068" in pf and "SOURCE_URL_CACHE_2068" in pf,
          "(e) Prefs holds the two local-only keys (source_speed_2068 / source_url_cache_2068)")
    keys_block = pf[pf.index("static final String[] KEYS = {"):]
    keys_block = keys_block[:keys_block.index("};")]
    check("SOURCE_SPEED_2068" not in keys_block
          and "SOURCE_URL_CACHE_2068" not in keys_block,
          "(e) both keys are local-only, NOT in KEYS (never synced)")
    check("lookupCached(a, cacheKey)" in af and "finishProbe(" in af,
          "(e) probeEpisode looks the verdict cache up first and records through finishProbe")
    check("recordProbe(MainActivity a, String siteKey" in ss and "isInterrupted()" in ss,
          "(e) a cancelled probe is never recorded (interrupted threads skip)")
    check("takeWarmed(a, title, wantedEpisode, failedSiteKey)" in af,
          "(e) find() serves a warmed candidate immediately, without searching")
    check("warmDetail(a, s, v, playEp)" in mds
          and "AutoSourceFallback.warmDetail" in mds,
          "(e) the detail page warms the play episode's sources once the detail renders")
    check("static void warmDetail(" in af and "static void stopWarm()" in af
          and "takeWarmed(" in af,
          "(e) warmDetail / stopWarm / takeWarmed are all in AutoSourceFallback")
    check("PRERESOLVE_SLOTS = 3" in ss and "PRERESOLVE_SLOW_EXTRA = 2" in ss
          and "SLOW_MS = 3000" in ss,
          "(e) pre-resolve is 3 fast slots + 2 slow extras, slow threshold 3000ms (Maoki numbers)")
    check("AutoSourceFallback.stopWarm()" in pbc,
          "(e) play start stops the background warm (resolvePlayer — race-start stop)")
    check("shouldForgiveStartupError(" in pbc and '"_startupRetry"' in pbc,
          "(e) the error listener forgives one startup error with a same-URL retry (bounded once)")
    check("invalidateForUrl(" in pbc and "recordPlaybackFailure(" in pbc,
          "(e) a counted playback failure invalidates the cached verdict + records the failure")
    check("STARTUP_GRACE_MS = 20_000" in ss and "COOLDOWN_MS = 5 * 60_000" in ss
          and "CACHE_TTL_MS = 6L * 60L * 60L * 1000L" in ss,
          "(e) grace 20s / cooldown 5min / cache TTL 6h (Maoki numbers)")
    rank = af[af.index("static int compareRanked"):]
    rank = rank[:rank.index("}", rank.index("speedOfEma")) + 1]
    check(rank.index("hasEpisode") < rank.index("x.priority")
          and rank.index("x.priority") < rank.index("y.verified")
          and rank.index("y.verified") < rank.index("coolingDown")
          and rank.index("coolingDown") < rank.index("speedOfEma"),
          "(e) compareRanked order: episode > priority > verified > cooldown > speed (priority never loses to speed)")
    check("never rejects" in af and "ranking-only" in af,
          "(e) the probe stays ranking-only, never a rejection (2.0.44 rule kept)")
    check("preResolveBestForEpisode" not in af
          and "preResolveBestForEpisode" not in ss
          and "next-episode best-source" in ss,
          "(e) the next-episode best switch is deliberately NOT ported (background sniffer)")

    # ---- Static: TV untouched, anime filter untouched, DeepL untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs,
          "TV untouched: no diffusion, hero tone, list tone or list grid in HomeScreen.java")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")
    check("deepl_quota_exhausted" in qs and os.path.exists(os.path.join(SRC, "TranslateClient.java")),
          "DeepL untouched: deepl_quota_exhausted + TranslateClient still there (removal paused)")
    check("2.0.68" not in read("TranslateClient.java"),
          "DeepL untouched: no 2.0.68 marker in TranslateClient.java")

    # ---- Static: no new threads in the changed files ----
    # AutoSourceFallback already had 2x "new Thread" (autopick pool
    # factory + driver) + 1x newFixedThreadPool (the autopick pool);
    # PlaybackController already had 2x "new Thread" (PLAYER_RESOLVE +
    # PLAYER_INIT factories) + the cached/single executors. All pinned
    # at their pre-2.0.68 counts — the warm reuses the shared
    # SearchCoordinator pools, the grace is a same-URL retry.
    check(af.count("new Thread") == 2 and af.count("newFixedThreadPool") == 1,
          "no new thread in AutoSourceFallback.java (2x new Thread + 1 pool are the pre-existing autopick ones)")
    check(pbc.count("new Thread") == 2
          and pbc.count("newCachedThreadPool") == 1
          and pbc.count("newSingleThreadExecutor") == 1,
          "no new thread in PlaybackController.java (2x new Thread + 2 executors are the pre-existing resolve pools)")
    check("new Thread" not in ss and "ExecutorService" not in ss,
          "no new thread in SourceSpeed2068.java (pure store + prefs only)")
    check("new Thread" not in mds,
          "no new thread in MobileDetailScreen.java (warmDetail only kicks the shared coordinator)")
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")

    # ---- Static: secret audit (changed files) ----
    for rel in ("SourceSpeed2068.java", "AutoSourceFallback.java", "MobileShell.java",
                "PlaybackController.java", "MobileDetailScreen.java", "PlaybackControls.java",
                "QuotaState2066.java", "PostHogClient.java"):
        text = read(rel)
        check("phc_" not in text and "os_v2_" not in text and "client_id=" not in text,
              "secret audit: no key literal in " + rel)

    # ---- Static: forbidden files carry no 2.0.68 marker ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.68" not in text,
              "forbidden file untouched: no 2.0.68 marker in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.73"' in ev and "20074" in ev, "edition-versions.json = 2.0.73 / 20074")
    import json as _json
    lite = _json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.73"' in man and 'android:versionCode="20074"' in man,
          "AndroidManifest.xml = 2.0.73 / 20074")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
