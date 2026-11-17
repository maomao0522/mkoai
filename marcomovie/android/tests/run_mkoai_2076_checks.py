#!/usr/bin/env python3
# mkoai 2.0.76 checks — JVM + static checks for the 推薦優先地區 setting.
#
# (feature, user 2026-11-17 ~19:46) two independent region picks — one
#   for dramas (劇）, one for movies (電影）; options 不限 (default) /
#   台灣 / 中國 / 韓國 / 日本 / 美國. The picks live on the search page
#   below 最近搜尋 (GlobalSearchScreen.showHistory, above 熱門搜尋 — one
#   UI for phone + TV). "Priority" = matching titles are boosted to the
#   front of the 推薦 grid (stable partition, never a filter); 不限 is the
#   current behaviour. Fail-open: unknown/empty area never drops a title.
# (design) region matching reuses AwwratedHome.regionOf (2.0.76 adds the
#   日本 → JP branch; JP behaves exactly like OTHER in the home filter's
#   isPresent / platformPasses / isAnyRegion, so the branch changes no
#   home-filter behaviour). The reorder is RecommendRegion2076.reorder
#   (pure Java). TV: HomeScreen.composeHomeVideos takes the two picks
#   (5 call sites pass them from Prefs). Phone: MobileShell
#   .renderHomeSections applies the reorder after the year filter.
#   The picks are Prefs keys (local per device, like HIDE_LUNLI — NOT in
#   KEYS or SYNC_SETTING_KEYS).
# (exception, user-approved 2026-11-17 ~20:01) this version touches two
#   forbidden files, once: GlobalSearchScreen.java (the 推薦設定 section)
#   and HomeScreen.java (composeHomeVideos signature + 5 call sites).
#   The other seven forbidden files must carry no 2.0.76 marker.
# The manifest / edition-versions bump to 2.0.76 / 20077 is the
# parent agent's step AFTER this work, so this runner does NOT pin
# the mobile/tv version — only that lite stays 0.5.3 / 53.
# Also spot-checks that the 2.0.75 items still hold.
#
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/rec2076-classes"
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
         os.path.join(SRC, "ImageDecodePolicy.java"),
         os.path.join(SRC, "RequestScope.java"),
         os.path.join(SRC, "Cms.java"),
         os.path.join(SRC, "Prefs.java"),
         os.path.join(SRC, "TitleTranslator.java"),
         os.path.join(SRC, "TmdbClient.java"),
         os.path.join(SRC, "AwwratedTitles.java"),
         os.path.join(SRC, "AwwratedHome.java"),
         os.path.join(SRC, "ChineseConvert.java"),
         os.path.join(SRC, "RecommendRegion2076.java"),
         os.path.join(SRC, "GlobalSearchScreen.java"),
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
         os.path.join(SRC, "SourcePicker.java"),
         os.path.join(ROOT, "android/tests/Recommend2076Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.76 sources + Recommend2076Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Recommend2076Checks ----
    p, f = run_jvm_class("Recommend2076Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    prefs = read("Prefs.java")
    aw = read("AwwratedHome.java")
    rr = read("RecommendRegion2076.java")
    gs = read("GlobalSearchScreen.java")
    hs = read("HomeScreen.java")
    ms = read("MobileShell.java")
    qr = read("QrScanActivity.java")

    # ---- Static: Prefs keys (local only) ----
    check('RECOMMEND_DRAMA_REGION = "recommend_drama_region"' in prefs,
          "Prefs has RECOMMEND_DRAMA_REGION")
    check('RECOMMEND_MOVIE_REGION = "recommend_movie_region"' in prefs,
          "Prefs has RECOMMEND_MOVIE_REGION")
    check("RECOMMEND_DRAMA_REGION" not in prefs.split("static final String[] KEYS = {")[1].split("};")[0],
          "Prefs: drama region key is NOT in KEYS (local only, like HIDE_LUNLI)")
    check("RECOMMEND_MOVIE_REGION" not in prefs.split("static final String[] KEYS = {")[1].split("};")[0],
          "Prefs: movie region key is NOT in KEYS (local only, like HIDE_LUNLI)")

    # ---- Static: regionOf JP branch ----
    check('REGION_JP = "JP"' in aw, "AwwratedHome has the REGION_JP constant")
    check('if (a.contains("日本")) return REGION_JP;' in aw,
          "regionOf maps 日本 → JP")
    check('if ("JP".equals(token)) return REGION_JP;' in aw,
          "regionOf maps the JP token → JP")

    # ---- Static: helper ----
    check("static List<Cms.Video> reorder(" in rr, "RecommendRegion2076 has reorder()")
    check("VideoKind2061.kindOf(v)" in rr, "reorder() classifies by VideoKind2061.kindOf")
    check("AwwratedHome.regionOf(v.area)" in rr, "reorder() reads the region via AwwratedHome.regionOf")
    check('"台灣", "中國", "韓國", "日本", "美國"' in rr or
          '"台灣"' in rr and '"日本"' in rr,
          "RecommendRegion2076 has the Traditional-Chinese labels")

    # ---- Static: search-page UI (approved exception file 1) ----
    check("void renderRecommendSettings() {" in gs,
          "GlobalSearchScreen has renderRecommendSettings()")
    check("void renderRecommendRow(String label, String prefKey) {" in gs,
          "GlobalSearchScreen has renderRecommendRow()")
    check(gs.count("renderRecommendSettings();") == 2,
          "renderRecommendSettings() is called in both showHistory branches")
    check('"推薦設定"' in gs, "the section header is 推薦設定")
    check('"劇優先地區"' in gs and '"電影優先地區"' in gs,
          "the two rows are 劇優先地區 / 電影優先地區")
    check("Prefs.RECOMMEND_DRAMA_REGION" in gs and "Prefs.RECOMMEND_MOVIE_REGION" in gs,
          "the rows save to the two Prefs keys")
    check('"search-recommend:"' in gs, "the pills carry search-recommend: tags")
    check("a.prefs.edit().putString(prefKey, code).apply();" in gs,
          "tapping a pill saves the pick and re-renders")
    check("styleRecommendPill" in gs, "the pills have selected/unselected styling")

    # ---- Static: TV compose (approved exception file 2) ----
    check("String dramaRegion," in hs and "String movieRegion)" in hs,
          "composeHomeVideos takes the two region picks")
    check("RecommendRegion2076.reorder(out, dramaRegion, movieRegion)" in hs,
          "composeHomeVideos applies the reorder at the end")
    check(hs.count("Prefs.RECOMMEND_DRAMA_REGION") == 5,
          "all 5 composeHomeVideos call sites pass the drama pick")
    check(hs.count("Prefs.RECOMMEND_MOVIE_REGION") == 5,
          "all 5 composeHomeVideos call sites pass the movie pick")

    # ---- Static: phone home sections ----
    check("RecommendRegion2076.reorder(" in ms,
          "MobileShell.renderHomeSections applies the reorder")
    check("Prefs.RECOMMEND_DRAMA_REGION" in ms and "Prefs.RECOMMEND_MOVIE_REGION" in ms,
          "the phone reorder reads both Prefs keys")

    # ---- Static: 2.0.75 holds (spot checks) ----
    check("preview.getHolder().getSurface() == null" in qr,
          "2.0.75 holds: startCamera() still guards on the holder surface")
    check("camera.setPreviewDisplay(holder);" in qr,
          "2.0.75 holds: surfaceCreated() still re-attaches the preview")

    # ---- Static: forbidden files (approved two carry 2.0.76, the rest must not) ----
    check("2.0.76" in gs, "approved exception: GlobalSearchScreen carries 2.0.76")
    check("2.0.76" in hs, "approved exception: HomeScreen carries 2.0.76")
    for rel in ("Cms.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java"):
        text = read(rel)
        check("2.0.76" not in text,
              "forbidden file untouched: no 2.0.76 marker in " + rel)

    # ---- Static: no new threads in the checked files ----
    check(ms.count("new Thread") == 1,
          "MobileShell still has exactly 1 new Thread (no new threads)")
    check(read("MainActivity.java").count("new Thread") == 0,
          "MainActivity still has 0 new Thread")
    check(read("SourcePicker.java").count("new Thread") == 0,
          "SourcePicker still has 0 new Thread")
    check(qr.count("new Thread") == 0,
          "QrScanActivity still has 0 new Thread")

    # ---- Version: lite stays 0.5.3 / 53 (mobile+tv bump is the parent's step) ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
