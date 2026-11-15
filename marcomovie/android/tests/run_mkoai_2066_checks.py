#!/usr/bin/env python3
# mkoai 2.0.66 checks — real-code JVM checks + static regression checks.
# Runs Home2066Checks (HeroDiffusion2063.pageTone / heroBottomFraction
# 3-arg / bloomColorsAnchored light-aware far colour, HomeTone2066
# palette + WCAG contrast at the empirical band extremes,
# HomeYear2066 year rules, HeroRatings2066 ratings line,
# QuotaState2066 quota-hide state), plus static greps for the phone
# home Emby restyle in MobileShell (floating top pill, page tone
# follows the poster, hero dissolves, dynamic text, tone-following
# dock, backdrop-first hero art, horizontal CW cards, type badge
# bottom-left without a pill), the quota wiring (detail buttons,
# OMDb ratings, push test, chart rows that stay hidden until reset),
# the year-rule call sites, the badge move (phone + TV), the anime
# filter untouched, the TV home untouched except the badge, the secret
# audit, and the version anchors.
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2066-classes"
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
         os.path.join(ROOT, "android/tests/Home2066Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.66 sources + Home2066Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2066Checks ----
    p, f = run_jvm_class("Home2066Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    hd = read("HeroDiffusion2063.java")
    mds = read("MobileDetailScreen.java")
    ss = read("SettingsScreen.java")
    pc = read("PlaybackControls.java")
    hs = read("HomeScreen.java")
    vk = read("VideoKind2061.java")
    tmdb = read("TmdbClient.java")
    prefs = read("Prefs.java")
    quota = read("QuotaState2066.java")

    # ---- Static: floating top pill, hero from the screen top ----
    check('"home-top-pill"' in ms and "contentContainer.addView(topOverlay" in ms,
          "floating pill: the top bar is a floating pill over the hero (tag home-top-pill)")
    check("body.setPadding(0, 0, 0, a.dp(106))" in ms,
          "floating pill: the scroll body starts at the screen top (no status-bar + bar pad)")
    check("heroContainer.setPadding(0, 0, 0, 0)" in ms,
          "floating pill: the hero container has no inset — the art starts at the top edge")
    check("retintTopOverlay" in ms and "topPillBg(" in ms,
          "floating pill: the pill is re-tinted with the page tone (not a fixed dark glass)")

    # ---- Static: the dark band is gone ----
    check("topBarTone" not in ms and "heroTopFades" not in ms
          and "topFadeColors" not in ms,
          "dark band gone: no topBarTone / heroTopFades / topFadeColors left in MobileShell")
    check("HOME_TOP_BAR_HEIGHT_DP" not in ms and "HOME_TOP_BAR_HEIGHT_DP" not in hd
          and "topBarTone" not in hd,
          "dark band gone: the 60dp bar height and topBarTone are deleted from HeroDiffusion2063")
    check("topFadeColors" in hd,
          "dark band gone: topFadeColors survives only as an unused pure helper in HeroDiffusion2063")

    # ---- Static: light background + dynamic text ----
    check("HeroDiffusion2063.pageTone(" in ms,
          "light bg: the home tone comes from pageTone (light-preserving), not backgroundTone")
    check("HomeTone2066" in ms and "registerHomeText" in ms
          and "retintHomeText" in ms and "homeTextDirty" in ms,
          "dynamic text: home text is registered and re-tinted on the light/dark flip")
    check("applyStatusBarIcons" in ms and "SYSTEM_UI_FLAG_LIGHT_STATUS_BAR" in ms,
          "dynamic text: the status-bar icons flip with the tone (white on dark, dark on light)")

    # ---- Static: hero dissolves, no dark band at the bottom ----
    check("scrimColors(" in ms,
          "hero dissolve: the hero bottom still uses the page-colour scrim (melts into the bg)")
    check("HeroDiffusion2063.heroBottomFraction(" in ms
          and "0f,\n                HeroDiffusion2063.heroHeightDp(" in ms,
          "hero dissolve: the bottom-edge fraction is computed from a hero that starts at 0")

    # ---- Static: hero ratings row + backdrop-first art ----
    check('"hero-ratings"' in ms and "HeroRatings2066.ratingsLine(" in ms,
          "hero ratings: a ratings row under the synopsis, from remarks + the cached TMDB vote")
    check("bindHeroArtwork" in ms and "FIT_CENTER" in ms,
          "hero crop: bindHeroArtwork shows a poster whole (FIT_CENTER), never hard-cropped")
    check("v.backdrop" in ms and "resolveHeroBackdrop" in ms,
          "hero crop: the hero prefers a landscape backdrop, resolved from the disk cache only")
    check("TmdbClient.cachedSearch" in ms and "static Item cachedSearch(" in tmdb,
          "hero crop: the backdrop comes from TmdbClient.cachedSearch (disk only, never network)")

    # ---- Static: Continue Watching — horizontal cards, visible progress ----
    check("buildHistoryCard(e, 260, 104)" in ms,
          "CW: history cards are horizontal 260x104 (thumb + text column), not a cropped poster")
    check("retintProgressTracks" in ms and "HomeTone2066.PROGRESS_FILL" in ms,
          "CW: the progress track is re-tinted with the page tone (visible on light and dark)")

    # ---- Static: tone-following dock ----
    check("retintDock" in ms and "dockBackground()" in ms,
          "dock: the floating pill dock is re-tinted with the page tone")
    check("dockLabel(" in ms and "DOCK_SELECTED_LIGHT" in read("HomeTone2066.java"),
          "dock: labels use the selected-aware colours (darker coral on a light pill)")
    check("setBaseColor" in ms,
          "dock: the bottom-edge veil follows the page tone (no dark band under a light page)")

    # ---- Static: year rules at the choke points ----
    check("HomeYear2066.preferRecentForHero(" in ms,
          "year rules: the hero prefers 2-year titles at the single carousel choke point")
    check("HomeYear2066.filterRecommendations(" in ms,
          "year rules: the phone home sections filter to the last 4 years")
    check("HomeYear2066.keepInRecommendations(" in ms,
          "year rules: the TMDB / chart / Douban row build filters to the last 4 years")
    check("HomeRows2061.isTvmazeRow(row.kind)" in ms,
          "year rules: the TVmaze tonight row is exempt (premiere year is not recency)")

    # ---- Static: quota wiring — detail buttons ----
    check(mds.count("QuotaState2066.record") == 3,
          "quota: the detail page records quota 3x (translate + trailer + OMDb)")
    check(mds.count("QuotaState2066.isHidden") == 3,
          "quota: the detail page checks hidden 3x before showing translate / trailer / OMDb")
    check("翻譯額度用完，24 小時後再試" in mds
          and "YouTube 額度用完，午夜太平洋時間重置" in mds,
          "quota: the detail toasts name the recovery (24h for DeepL, midnight Pacific for YouTube)")
    check("fetchByImdbIdOrThrow" in mds and "fetchByTitleOrThrow" in mds,
          "quota: the OMDb ratings area uses the throwing fetch (quota is visible, not swallowed)")

    # ---- Static: quota wiring — push test + chart rows ----
    check("QuotaState2066.isHidden" in ss and "QuotaState2066.record" in ss,
          "quota: the push-test link hides while the ntfy limit is out and records on 502")
    check("notify_failed" in ss and "e.retryAfterOrNull" in ss,
          "quota: the push test reads retry_after from the 502 BODY, not the status")
    check("fetchChartItems" in ms and "QuotaState2066.isHidden" in ms,
          "quota: chart rows skip the fetch entirely while hidden (no retry on every home open)")
    th = read("TraktHome.java")
    check(th.count("QuotaState2066.record") == 1,
          "quota: TraktHome records its quota on RateLimited (one record call)")
    check(th.count("WorkerApi.getJson(") == 1
          and '"&list=trending"' in th and '"&list=popular"' not in th,
          "quota: TraktHome makes exactly one fetch (trending only) — the popular retry on 429 is gone")
    check("throw limited;" in th,
          "quota: TraktHome rethrows the RateLimited after recording it (the caller hides the row)")
    check("QuotaState2066.record" in read("SimklHome.java")
          and "QuotaState2066.record" in read("TvmazeHome.java"),
          "quota: SimklHome + TvmazeHome record their quota on RateLimited")

    # ---- Static: subtitles — documented, not hidden ----
    check("needs a Worker-side quota marker" in pc,
          "quota: the subtitle entry is deliberately NOT hidden (no Worker quota signal yet)")

    # ---- Static: quota state shape ----
    check("quota_hide_" in quota and "quota_hide_" not in prefs,
          "quota: hideUntil lives under quota_hide_<feature> in QuotaState2066, never in Prefs KEYS")
    m = re.search(r"static final String\[\] KEYS = \{(.*?)\};", prefs, re.S)
    keys = [e.strip().rstrip(",") for e in m.group(1).strip().splitlines() if e.strip()]
    check(len(keys) == 41,
          "quota: Prefs KEYS still has exactly 41 entries (no quota key added)")
    settings_keys = [
        "SEARCH_THREADS", "SEARCH_TIMEOUT", "EXPERIMENTAL_CSP",
        "HIDE_NETDISK", "MERGE_SAME_TITLE", "YOUTH_MODE",
        "ANALYTICS_ENABLED", "AUTO_NEXT", "AUTO_DIRECT_PLAY",
        "FILTER_HLS_ADS", "DANMAKU_DEFAULT", "TUNNELED_PLAYBACK",
        "SOFTWARE_AUDIO", "PREFER_AAC", "REDUCED_TRANSPARENCY",
        "STARTUP_ANIMATION", "HOME_AMBIENT", "REDUCED",
        "SHOW_HERO_BANNER", "UI_SCALE_PERCENT", "NAV_SCALE_PERCENT",
        "HOME_COLUMNS", "WALLPAPER_DIM", "VIDEO_OUTPUT",
        "PLAYER_ENGINE", "DECODER_MODE", "TV_PLAYER_STYLE", "ASPECT",
        "BUFFER_SECONDS", "PLAY_SPEED", "REPEAT_MODE", "SKIP_INTRO",
        "SKIP_OUTRO", "SUBTITLE_SIZE", "SUBTITLE_BOTTOM",
        "SUBTITLE_STYLE", "PICTURE_EFFECT", "AUDIO_EFFECT",
        "TV_DOCK_POSITION", "WALLPAPER_ENABLED",
    ]
    missing = [k for k in settings_keys if ("Prefs." + k) not in ss]
    check(not missing,
          "quota: all " + str(len(settings_keys))
          + " Prefs keys still in SettingsScreen.java"
          + ("" if not missing else " (missing: " + ", ".join(missing) + ")"))

    # ---- Static: type badge — bottom-left, white + shadow, no pill ----
    check("addTypeBadge" not in vk and "addTypeBadge" not in ms
          and "addTypeBadge" not in hs,
          "badge: VideoKind2061.addTypeBadge is deleted (phone + TV)")
    check("addPosterOverlay(a, art, v.remarks, null, VideoKind2061.badgeLabel(v))" in ms,
          "badge: the phone home card passes the type label into the overlay row")
    check("MobileShell.addPosterOverlay(" in hs
          and "a, artWrap, video.remarks, video.year, VideoKind2061.badgeLabel(video)" in hs,
          "badge: the TV home card passes the type label into the overlay row")
    check("hasType" in ms and "0xfff5f7fa" in ms,
          "badge: the type label renders as white text in the bottom row (same as the rating)")

    # ---- Static: anime filter + swipe/rotation untouched ----
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")
    check("HeroSwipe2063" in ms,
          "swipe untouched: the carousel still uses HeroSwipe2063")

    # ---- Static: TV home untouched except the badge ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "bloomColors" not in hs and "MaterialSwitch2065" not in hs
          and "sectionTitle" not in hs,
          "TV home untouched: no diffusion, bloom, Material switch or section title in HomeScreen.java")

    # ---- Static: no new threads in the changed files ----
    # MobileShell already has one pre-existing thread (the
    # tmdb-availability worker, untouched by 2.0.66), so it is pinned
    # at 1 instead of 0.
    for rel in ("HomeTone2066.java", "HomeYear2066.java", "HeroRatings2066.java",
                "QuotaState2066.java", "HeroDiffusion2063.java", "TmdbClient.java",
                "WorkerApi.java", "TranslateClient.java", "OmdbClient.java",
                "TraktHome.java", "SimklHome.java", "TvmazeHome.java",
                "MobileDetailScreen.java", "SettingsScreen.java",
                "PlaybackControls.java", "NotifyClient.java"):
        text = read(rel)
        check("new Thread" not in text,
              "no new thread in " + rel)
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")

    # ---- Static: secret audit (changed files) ----
    for rel in ("HomeTone2066.java", "HomeYear2066.java", "HeroRatings2066.java",
                "QuotaState2066.java", "HeroDiffusion2063.java", "TmdbClient.java",
                "WorkerApi.java", "TranslateClient.java", "OmdbClient.java",
                "TraktHome.java", "SimklHome.java", "TvmazeHome.java",
                "MobileDetailScreen.java", "SettingsScreen.java",
                "PlaybackControls.java", "NotifyClient.java", "MobileShell.java"):
        text = read(rel)
        check("phc_" not in text and "os_v2_" not in text and "client_id=" not in text,
              "secret audit: no key literal in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.66"' in ev and "20067" in ev, "edition-versions.json = 2.0.66 / 20067")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.66"' in man and 'android:versionCode="20067"' in man,
          "AndroidManifest.xml = 2.0.66 / 20067")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
