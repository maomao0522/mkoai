#!/usr/bin/env python3
# mkoai 2.0.77 checks — JVM + static checks for Google TV Continue Watching.
#
# (feature, user 2026-11-17 ~20:07) the TV launcher's Continue Watching
#   row shows mkoai's in-progress titles: WatchNext2077 publishes a row
#   per in-progress history entry (WatchNextPrograms, no new dependency,
#   no Play publishing), tapping the card fires mkoai://watch-next?key=...
#   back into MainActivity, which looks the entry up by key and resumes it
#   through CatalogScreen.resumeHistory (the in-app Continue Watching
#   path). A finished title, or one removed from history, has its row
#   deleted; clearing the whole history deletes all of our rows. A
#   throttled reconcile (TV home, at most once per 10 min) heals
#   phone-synced progress onto the TV row.
# (design) publish points: PlaybackController.savePlaybackHistoryOnPrepared
#   (first publish when playback opens) + savePlaybackHistory(force=true)
#   (pause / stop / episode change); the 2s periodic saves leave the row
#   alone. Delete points: CatalogScreen single-remove + SupabaseSync
#   .clearHistory. Everything is TV-only (UiModeManager) and fail-soft
#   (any provider error is logged, playback never depends on it).
# (forbidden) NO exception this version — all 9 forbidden files must be
#   byte-identical to the 2.0.76 source zip. The 2.0.76 exception
#   (GlobalSearchScreen + HomeScreen) was one-time and does NOT carry over.
# The manifest / edition-versions bump to 2.0.77 / 20078 is done in this
# same work (unlike 2.0.76, where the runner left the bump to the parent),
# so this runner pins mobile+tv 2.0.77 / 20078.
# Also spot-checks that the 2.0.76 items still hold.
#
import glob
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/rec2077-classes"
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
         os.path.join(SRC, "WatchHistory.java"),
         os.path.join(SRC, "WatchNext2077.java"),
         os.path.join(SRC, "CatalogScreen.java"),
         os.path.join(SRC, "SupabaseSync.java"),
         os.path.join(ROOT, "android/tests/WatchNext2077Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.77 sources + WatchNext2077Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: WatchNext2077Checks ----
    p, f = run_jvm_class("WatchNext2077Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    wn = read("WatchNext2077.java")
    pc = read("PlaybackController.java")
    cs = read("CatalogScreen.java")
    ss = read("SupabaseSync.java")
    ma = read("MainActivity.java")

    # ---- Static: pure API surface ----
    check("static String contentIdFor(WatchHistory.Entry e)" in wn,
          "WatchNext2077 has contentIdFor()")
    check("static String intentUriFor(WatchHistory.Entry e)" in wn,
          "WatchNext2077 has intentUriFor()")
    check("static String keyFromIntentUri(String uri)" in wn,
          "WatchNext2077 has keyFromIntentUri()")
    check("static boolean shouldPublish(WatchHistory.Entry e)" in wn,
          "WatchNext2077 has shouldPublish()")
    check("static Map<String, Object> rowFor(WatchHistory.Entry e)" in wn,
          "WatchNext2077 has rowFor()")
    check("WATCH_NEXT_TYPE_CONTINUE" in wn, "row type is WATCH_NEXT_TYPE_CONTINUE")
    check("COLUMN_LAST_PLAYBACK_POSITION_MILLIS" in wn,
          "row carries the last playback position")
    check("COLUMN_POSTER_ART_URI" in wn, "row carries the poster art URI")
    check("MAX_ROWS = 10" in wn, "reconcile caps the launcher row at 10")

    # ---- Static: TV-only guard + fail-soft ----
    check("UI_MODE_TYPE_TELEVISION" in wn, "TV guard uses UiModeManager TELEVISION")
    check(wn.count("isTv(c)") >= 5,
          "all five Android entry points (publishOrRemove/upsert/remove/removeAll/reconcile) check isTv")
    check(wn.count("catch (Exception") >= 6,
          "every provider call is wrapped in try/catch (fail-soft)")
    check("new Thread" not in wn, "WatchNext2077 adds no thread")

    # ---- Static: publish hooks ----
    check(pc.count("WatchNext2077.publishOrRemove") == 2,
          "PlaybackController publishes at OnPrepared + the forced save")
    check("if (force) WatchNext2077.publishOrRemove(a, a.playingBookmark);" in pc,
          "the periodic save publishes only when forced (pause/stop), not every 2s")

    # ---- Static: delete hooks ----
    check("WatchNext2077.remove(a, e);" in cs,
          "single history remove deletes the launcher card")
    check("WatchNext2077.removeAll(c);" in ss,
          "clearHistory deletes all launcher cards")

    # ---- Static: tap-back handling ----
    check("void handleWatchNext(android.content.Intent intent)" in ma,
          "MainActivity has handleWatchNext()")
    check(ma.count("handleWatchNext(") == 3,
          "handleWatchNext is called from onCreate + onNewIntent (def + 2 calls)")
    check("resumeHistory(found);" in ma,
          "a found entry resumes through the in-app Continue Watching path")
    check("WatchNext2077.reconcileIfDue(this);" in ma,
          "onResume reconciles the launcher row from stored history")

    # ---- Static: manifest deep link + version ----
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"),
               encoding="utf-8").read()
    check('android:host="watch-next"' in man,
          "manifest has the mkoai://watch-next deep link")
    check('android:versionName="2.0.77"' in man and 'android:versionCode="20078"' in man,
          "manifest = 2.0.77 / 20078")
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    evj = json.loads(ev)
    check(evj["android-mobile"]["versionName"] == "2.0.77"
          and evj["android-mobile"]["versionCode"] == 20078,
          "edition-versions.json mobile = 2.0.77 / 20078")
    check(evj["android-tv"]["versionName"] == "2.0.77"
          and evj["android-tv"]["versionCode"] == 20078,
          "edition-versions.json tv = 2.0.77 / 20078")
    check(evj["android-lite"]["versionName"] == "0.5.3"
          and evj["android-lite"]["versionCode"] == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")

    # ---- Static: 2.0.76 holds (spot checks) ----
    gs = read("GlobalSearchScreen.java")
    check('"search-recommend:"' in gs, "2.0.76 holds: search-recommend: pills still there")
    check('"推薦設定"' in gs, "2.0.76 holds: 推薦設定 section still there")
    check("RecommendRegion2076.reorder(" in read("HomeScreen.java"),
          "2.0.76 holds: TV compose still applies the region reorder")
    check("RecommendRegion2076.reorder(" in read("MobileShell.java"),
          "2.0.76 holds: phone home still applies the region reorder")

    # ---- Static: forbidden files byte-identical vs the 2.0.76 source zip ----
    z = zipfile.ZipFile(os.path.expanduser("~/workspace/mkoai/mkoai-2.0.76-Source.zip"))
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        old = z.read("marcomovie/android/src/main/java/app/mkoai/player/" + rel)
        new = open(os.path.join(SRC, rel), "rb").read()
        check(old == new, "forbidden file byte-identical vs 2.0.76 zip: " + rel)

    # ---- Static: no secrets, no new threads in touched files, DeepL untouched ----
    secret = re.compile(r"AIza[0-9A-Za-z_-]{10,}|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{10,}")
    hits = 0
    for rel in ("WatchNext2077.java", "PlaybackController.java", "CatalogScreen.java",
                "SupabaseSync.java", "MainActivity.java"):
        hits += len(secret.findall(read(rel)))
    check(hits == 0, "secret grep = 0 in the 2.0.77 files")
    check(ma.count("new Thread") == 0, "MainActivity still has 0 new Thread")
    check("DeepL" not in wn, "DeepL untouched by 2.0.77")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
