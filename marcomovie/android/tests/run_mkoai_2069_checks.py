#!/usr/bin/env python3
# mkoai 2.0.69 checks — real-code JVM checks + static regression checks.
# Runs Home2069Checks (top-bar + top-veil geometry on the measured POCO
# F5 numbers), plus static greps for the one item:
# (1) top bar moved down (statusBarHeight + 8dp -> + 20dp, badge follows
#     + 66dp -> + 78dp) AND the top gets the hero diffusion feel: the
#     root-level TopEdgeFadeView now uses the topFadeColors gradient and
#     is TALL on the home tab (statusBarHeight + 250dp), short on other
#     tabs (statusBarHeight + 24dp) — so the hero art emerges out of
#     the page tone at the top instead of starting at a hard cut, and
#     no longer reads as a poster pasted on the page. The in-card top
#     fade stays gone; the bottom scrim, bloom, grid, subtitles
#     removal and Maoki source selection are untouched.
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
CLASSES = "/tmp/home2069-classes"
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
         os.path.join(ROOT, "android/tests/Home2069Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.69 sources + Home2069Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2069Checks ----
    p, f = run_jvm_class("Home2069Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    hs = read("HomeScreen.java")

    # ---- Static (1a): the top bar moves down ----
    check("overlayLp.topMargin = statusBarHeight() + a.dp(20);" in ms,
          "(1a) overlay topMargin is statusBarHeight + 20dp (was + 8dp)")
    check("overlayLp.topMargin = statusBarHeight() + a.dp(8);" not in ms,
          "(1a) the old + 8dp overlay margin is gone")
    check("badgeLp.topMargin = statusBarHeight() + a.dp(78);" in ms,
          "(1a) badge topMargin is statusBarHeight + 78dp (was + 66dp, still 10dp below the bar)")
    check("badgeLp.topMargin = statusBarHeight() + a.dp(66);" not in ms,
          "(1a) the old + 66dp badge margin is gone")
    check("buildHomeTopHeaderRow()" in ms
          and ms.count("buildHomeTopHeaderRow()") == 2,
          "(1a) the top bar is built by the single buildHomeTopHeaderRow (definition + one call site)")
    check("Gravity.BOTTOM" in ms
          and "card.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))" in ms,
          "(1a) hero content stays Gravity.BOTTOM-anchored: the lowered bar cannot touch the title")

    # ---- Static (1b): the top veil carries the diffusion ----
    fade = ms[ms.index("private static final class TopEdgeFadeView"):]
    fade = fade[:fade.index("private static final class BottomEdgeFadeView")]
    check("topFadeColors(baseRgb)" in fade,
          "(1b) TopEdgeFadeView paints the topFadeColors gradient (opaque -> half -> transparent)")
    check("fadeColors(baseRgb)" not in fade,
          "(1b) the old 3-stop fadeColors veil is gone from TopEdgeFadeView")
    check("1f / 7" in fade and "6f / 7" in fade,
          "(1b) the veil gradient has the 8 evenly spaced topFadeColors stops")
    check("topFadeHeightPx()" in ms and "updateTopFadeHeight()" in ms,
          "(1b) the veil height helpers exist (topFadeHeightPx / updateTopFadeHeight)")
    check("statusBarHeight() + a.dp(activeTab == 0 ? 250 : 24)" in ms,
          "(1b) veil height is statusBarHeight + 250dp on home, + 24dp on other tabs")
    check(ms.count("updateTopFadeHeight()") >= 2,
          "(1b) updateTopFadeHeight is defined and called from switchTab (height follows the tab)")
    upd = ms[ms.index("private void updateTopFadeHeight()"):]
    upd = upd[:upd.index("}", upd.index("lp.height = next")) + 1]
    check("homeTopFade == null" in upd and "lp.height != next" in upd,
          "(1b) updateTopFadeHeight null-guards and is a no-op when the height is unchanged")
    check("hero-diffusion-topfade" not in ms and "heroTopFades" not in ms,
          "(1b) the in-card hero top fade stays gone (only the root-level veil carries the diffusion)")
    check("topBarTone" not in ms,
          "(1b) no solid dark top band is back (topBarTone stays deleted)")
    check("scrimColors(heroDiffusionTarget)" in ms,
          "(1b) the bottom scrim is untouched (still the only in-card fade)")

    # ---- Static: 2.0.68 items still hold ----
    check(grep_app("SubtitleClient") == 0 and grep_app("/api/subtitles") == 0
          and grep_app("搜尋字幕") == 0,
          "2.0.68 holds: subtitles still fully removed")
    check(grep_app("tmdb-list-count") == 0 and grep_app("僅顯示來源裡有的片") == 0,
          "2.0.68 holds: view-all count line still deleted")
    grid = ms[ms.index("private void fillTmdbListGrid"):]
    grid = grid[:grid.index("private void followTopSectionPoster")]
    check("i % 4 == 0" in grid and "a.dp(140)" in grid,
          "2.0.68 holds: view-all grid still 4 columns, 140dp art")
    check(os.path.exists(os.path.join(SRC, "SourceSpeed2068.java"))
          and "takeWarmed(" in read("AutoSourceFallback.java")
          and "AutoSourceFallback.stopWarm()" in read("PlaybackController.java"),
          "2.0.68 holds: Maoki source selection (warm / takeWarmed / race-stop) still wired")

    # ---- Static: TV untouched, anime filter untouched, DeepL untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs
          and "2.0.69" not in hs,
          "TV untouched: no diffusion, hero tone, list tone, list grid or 2.0.69 marker in HomeScreen.java")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")
    check("deepl_quota_exhausted" in read("QuotaState2066.java")
          and os.path.exists(os.path.join(SRC, "TranslateClient.java")),
          "DeepL untouched: deepl_quota_exhausted + TranslateClient still there (removal paused)")

    # ---- Static: no new threads ----
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")

    # ---- Static: secret audit (whole app tree) ----
    check(grep_app("phc_") == 0 and grep_app("os_v2_") == 0
          and grep_app("client_id=") == 0,
          "secret audit: no key literal in the app tree")

    # ---- Static: forbidden files carry no 2.0.69 marker ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.69" not in text,
              "forbidden file untouched: no 2.0.69 marker in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.70"' in ev and "20071" in ev, "edition-versions.json = 2.0.70 / 20071")
    import json as _json
    lite = _json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.70"' in man and 'android:versionCode="20071"' in man,
          "AndroidManifest.xml = 2.0.70 / 20071")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
