#!/usr/bin/env python3
# mkoai 2.0.78 checks — JVM + static checks for HD-first / TC-last.
#
# (feature, user 2026-11-21) auto-pick prefers HD and puts TC last:
#   a TC (cam / 槍版 / 搶先) line loses to any non-TC line even when it
#   is the fastest; an HD line wins over a normal line at the same
#   ad-rule priority; a TC line still plays when it is the only playable
#   line. The signal is the LINE NAME (TC/TS/CAM/槍版/搶先 vs 高清/超清
#   /4K/藍光/1080/720P); episode names are not read.
# (design) two layers: SourcePreferences.lineIndex takes a -100,000 TC
#   penalty (decisive vs any episode-count gap, still above the
#   unplayable player-line -1,000,000); AutoSourceFallback.compareRanked
#   adds a quality-tier step after the ad-rule priority and before
#   probe-verified / cooldown / speed, with the tier captured at
#   candidate collection (find + warmDetail) and carried through
#   takeWarmed. The ad-rule priority still wins first.
# (forbidden) NO exception this version — all 9 forbidden files must be
#   byte-identical to the 2.0.77 source zip.
# The manifest / edition-versions bump to 2.0.78 / 20079 is done in this
# same work, so this runner pins mobile+tv 2.0.78 / 20079.
# Also spot-checks that the 2.0.77 items still hold.
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
CLASSES = "/tmp/rec2078-classes"
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
         os.path.join(SRC, "QualityTier2078.java"),
         os.path.join(SRC, "CatalogScreen.java"),
         os.path.join(SRC, "SupabaseSync.java"),
         os.path.join(ROOT, "android/tests/WatchNext2077Checks.java"),
         os.path.join(ROOT, "android/tests/QualityTier2078Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.78 sources + QualityTier2078Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: QualityTier2078Checks ----
    p, f = run_jvm_class("QualityTier2078Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    qt = read("QualityTier2078.java")
    sp = read("SourcePreferences.java")
    asf = read("AutoSourceFallback.java")
    ma = read("MainActivity.java")

    # ---- Static: pure API surface ----
    check("static boolean isTc(String name)" in qt, "QualityTier2078 has isTc()")
    check("static boolean isHd(String name)" in qt, "QualityTier2078 has isHd()")
    check("static int tierOf(String lineName)" in qt, "QualityTier2078 has tierOf()")
    check("static int tierForDetail(Cms.Video detail, String preferredLine, boolean hideNetdisk)" in qt,
          "QualityTier2078 has tierForDetail()")
    check("TIER_HD = 0" in qt and "TIER_NORMAL = 1" in qt and "TIER_TC = 2" in qt,
          "tiers are HD 0 / NORMAL 1 / TC 2")
    check("TC_SCORE_PENALTY = 100_000" in qt, "TC score penalty is 100,000")
    check('name.contains("枪版")' in qt and 'name.contains("搶先")' in qt,
          "TC markers include 枪版/搶先")
    check("new Thread" not in qt, "QualityTier2078 adds no thread")

    # ---- Static: lineIndex TC penalty ----
    check("QualityTier2078.isTc(name)" in sp,
          "lineIndex scoring calls QualityTier2078.isTc")
    check("QualityTier2078.TC_SCORE_PENALTY" in sp,
          "lineIndex applies the TC score penalty")

    # ---- Static: compareRanked quality tier ----
    check("final int qualityTier;" in asf, "RankedCandidate carries qualityTier")
    check("Integer.compare(x.qualityTier, y.qualityTier)" in asf,
          "compareRanked orders by qualityTier")
    check(asf.index("Integer.compare(x.qualityTier, y.qualityTier)")
          > asf.index("Integer.compare(x.priority, y.priority)"),
          "qualityTier step sits after the ad-rule priority")
    check(asf.index("Integer.compare(x.qualityTier, y.qualityTier)")
          < asf.index("Boolean.compare(y.verified, x.verified)"),
          "qualityTier step sits before probe-verified")
    check(asf.count("QualityTier2078.tierForDetail(") == 2,
          "tier is captured at both collection sites (find + warmDetail)")
    check("e.candidate.qualityTier" in asf,
          "takeWarmed carries the tier through to the fresh candidate")

    # ---- Static: manifest deep link + version ----
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"),
               encoding="utf-8").read()
    check('android:host="watch-next"' in man,
          "2.0.77 holds: manifest still has the mkoai://watch-next deep link")
    check('android:versionName="2.0.78"' in man and 'android:versionCode="20079"' in man,
          "manifest = 2.0.78 / 20079")
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    evj = json.loads(ev)
    check(evj["android-mobile"]["versionName"] == "2.0.78"
          and evj["android-mobile"]["versionCode"] == 20079,
          "edition-versions.json mobile = 2.0.78 / 20079")
    check(evj["android-tv"]["versionName"] == "2.0.78"
          and evj["android-tv"]["versionCode"] == 20079,
          "edition-versions.json tv = 2.0.78 / 20079")
    check(evj["android-lite"]["versionName"] == "0.5.3"
          and evj["android-lite"]["versionCode"] == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")

    # ---- Static: 2.0.77 holds (spot checks) ----
    check("WatchNext2077.publishOrRemove" in read("PlaybackController.java"),
          "2.0.77 holds: PlaybackController still publishes to Watch Next")
    check("WatchNext2077.remove(a, e);" in read("CatalogScreen.java"),
          "2.0.77 holds: single history remove still deletes the launcher card")
    check("void handleWatchNext(android.content.Intent intent)" in ma,
          "2.0.77 holds: MainActivity still has handleWatchNext()")

    # ---- Static: forbidden files byte-identical vs the 2.0.76 source zip ----
    z = zipfile.ZipFile(os.path.expanduser("~/workspace/mkoai/mkoai-2.0.77-Source.zip"))
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        old = z.read("marcomovie/android/src/main/java/app/mkoai/player/" + rel)
        new = open(os.path.join(SRC, rel), "rb").read()
        check(old == new, "forbidden file byte-identical vs 2.0.77 zip: " + rel)

    # ---- Static: no secrets, no new threads in touched files, DeepL untouched ----
    secret = re.compile(r"AIza[0-9A-Za-z_-]{10,}|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{10,}")
    hits = 0
    for rel in ("QualityTier2078.java", "SourcePreferences.java", "AutoSourceFallback.java"):
        hits += len(secret.findall(read(rel)))
    check(hits == 0, "secret grep = 0 in the 2.0.78 files")
    check(ma.count("new Thread") == 0, "MainActivity still has 0 new Thread")
    check("DeepL" not in qt and "DeepL" not in asf, "DeepL untouched by 2.0.78")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
