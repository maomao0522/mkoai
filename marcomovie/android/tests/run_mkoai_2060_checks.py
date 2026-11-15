#!/usr/bin/env python3
# mkoai 2.0.60 checks — real-code JVM checks + static regression checks.
# Runs AdMarksChecks (manual ad-mark store + one-tap skip math) and
# Home2060Checks (TV all-sources merge/dedupe/cap, parallel aggregate
# budget + progressive painting, built-in ad-rule defaults, bundled
# 19-source default config), plus static greps for the new features, the
# 2.0.58/2.0.59 markers, and the version anchors.
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2060-classes"
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
         os.path.join(SRC, "AdMarks.java"),
         os.path.join(SRC, "Cms.java"),
         os.path.join(SRC, "Prefs.java"),
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "DoubanHome.java"),
         os.path.join(SRC, "SimklHome.java"),
         os.path.join(ROOT, "android/tests/AdMarksChecks.java"),
         os.path.join(ROOT, "android/tests/Home2060Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.60 sources + AdMarksChecks + Home2060Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: AdMarksChecks ----
    p, f = run_jvm_class("AdMarksChecks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- JVM: Home2060Checks (bundled config path via system property) ----
    asset = os.path.join(ROOT, "android/src/main/assets/default-config.json")
    p, f = run_jvm_class("Home2060Checks", ["-Dmkoai.defaultConfig=" + asset])
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- Static: new 2.0.60 features ----
    pc = read("PlaybackControls.java")
    check("skip-ad" in pc and "mark-ad" in pc, "player has 跳過廣告 + 標記廣告 buttons")
    check("ICON_SKIP_AD" in pc and "ICON_MARK_AD" in pc, "player has ICON_SKIP_AD + ICON_MARK_AD")
    check("adSkipArmed" in pc and "AdMarks.inRange" in pc,
          "tick() auto-skips a saved ad range once per entry")
    check("adMarkForCurrent" in pc and "AdMarks.skipTarget" in pc,
          "one-tap skip uses the saved mark end, else +30s")
    check("Prefs.AD_MARKS" in pc, "ad marks are stored under Prefs.AD_MARKS")

    hs = read("HomeScreen.java")
    check("ALL_SOURCES_KEY" in hs and "allSourcesSite" in hs,
          "TV home has the all-sources sentinel + factory")
    check("mergeAllSources" in hs and "fetchAggregated" in hs and "loadAllSources" in hs,
          "TV home aggregates all sources (merge + parallel fetch + loader)")
    check("ExecutorCompletionService" in hs, "aggregate streams results via CompletionService")
    check("ALL_SOURCES_BUDGET_MS" in hs and "ALL_SOURCES_CAP" in hs,
          "aggregate has a shared budget + the TV 40-card cap")

    cms = read("Cms.java")
    check("builtinHlsAdRules" in cms, "Cms has built-in ad-rule defaults")
    check("api.juliang.live" in cms and "admedia.mujiziyuan99.com" in cms,
          "built-in: juliang -> admedia host")
    check("360zyzz.com" in cms and "vod.360zyx.vip" in cms,
          "built-in: 360zyzz -> 360zyx host")
    check("mdzyapi.com" in cms and "play.modujx17.com" in cms,
          "built-in: mdzyapi.com -> play.modujx17.com host")

    pr = read("Prefs.java")
    check('AD_MARKS = "ad_marks"' in pr, "Prefs.AD_MARKS key exists")
    check("AD_MARKS" not in pr.split("static final String[] KEYS")[1].split("};")[0]
          if "static final String[] KEYS" in pr else True,
          "AD_MARKS is not a user-facing backup setting")
    ss = read("SupabaseSync.java")
    sync_keys = ss.split("SYNC_SETTING_KEYS")[1].split("};")[0] if "SYNC_SETTING_KEYS" in ss else ""
    check("AD_MARKS" not in sync_keys, "ad marks are NOT in the Supabase sync key list (local only)")

    sp_picker = read("SourcePicker.java")
    check("全部片源" in sp_picker and "selectAllSources" in sp_picker,
          "source picker has a 全部片源 row that returns to the merged home")

    ma = read("MainActivity.java")
    check("default-config.json" in ma and "getAssets().open" in ma,
          "first run loads the bundled default config from assets")
    check("allSourcesSite" in ma, "home() defaults to the all-sources home when no source is pinned")

    # Bundled asset is the 19-source file (not the old 22).
    bundled_path = os.path.join(ROOT, "android/src/main/assets/default-config.json")
    check(os.path.exists(bundled_path), "bundled default-config.json exists in assets")
    if os.path.exists(bundled_path):
        with open(bundled_path, encoding="utf-8") as f:
            bundled = json.load(f)
        names = [s.get("name") for s in bundled["sites"]]
        check(len(names) == 19, "bundled default has 19 sites")
        check(not any(n in names for n in ["wujin", "电影天堂", "Mkoai1", "yzzy影視", "uku優酷", "niuniu"]),
              "bundled default does not contain the 6 removed sources")
        check(all(n in names for n in ["豪華資源", "魔都動漫", "暴風資源"]),
              "bundled default contains the 3 new sources")

    # ---- Static: TV lightweight caps are NOT raised ----
    il = read("ImageLoader.java")
    check("w342" in il, "TV card posters still load at w342")
    check("w1280" in il, "detail posters still load at w1280")

    # ---- Static: 2.0.58 markers still present ----
    check("composeHomeVideos" in hs, "2.0.58 marker: composeHomeVideos")
    dh = read("DoubanHome.java")
    check("fetchNew" in dh, "2.0.58 marker: DoubanHome.fetchNew")
    tt = read("TmdbClient.java")
    check("translateTitle" in tt, "2.0.58 marker: TmdbClient.translateTitle")
    up = read("UpdatePolicy.java")
    check("drive.usercontent" in up, "2.0.58 marker: updater accepts drive.usercontent confirm form")

    # ---- Static: 2.0.59 markers still present ----
    hf = read("HlsAdFilter.java")
    check("class HlsAdFilter" in hf, "2.0.59 marker: HlsAdFilter")
    af = read("AdAwareHlsPlaylistParserFactory.java")
    check("AdAwareHlsPlaylistParserFactory" in af, "2.0.59 marker: AdAwareHlsPlaylistParserFactory")
    check("FILTER_HLS_ADS" in pr, "2.0.59 marker: filter_hls_ads pref")

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.63"' in ev and "20064" in ev, "edition-versions.json = 2.0.63 / 20064")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.63"' in man and 'android:versionCode="20064"' in man,
          "AndroidManifest.xml = 2.0.63 / 20064")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
