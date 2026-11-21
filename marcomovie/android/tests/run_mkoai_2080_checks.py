#!/usr/bin/env python3
# mkoai 2.0.80 checks — JVM + static checks for the first-frame watchdog.
#
# (bug, user 2026-11-21) 巨量《蜘蛛俠：嶄新之日國語》 sat on 「正在載入 HD...」
#   for 118s: never played, no error, no auto-switch. Its only line was a dead
#   m3u8 (403 access_denied from here). The fallback chain in PlaybackController
#   (next quality → 2.0.68 startup-grace retry → next line → next source) only
#   runs after onPlayerError fires, and a stalled line never fires one.
# (design) FirstFrameWatchdog2080: pure timing state (arm/disarm/shouldFire,
#   TIMEOUT_MS 20000, clock injected, no threads, no Android classes).
#   PlaybackController arms it after ready.prepareAsync() (VOD only, never for
#   live or the system backend) and posts one delayed check on the existing UI
#   handler; 20s with no first frame and no prepared state drives the existing
#   error path via MoviePlayer.fireFirstFrameWatchdog() (error code 2008,
#   network-connection timeout — never a decoder error), so the existing chain
#   runs unchanged. First frame or STATE_READY disarms it. Each hop re-enters
#   playerPage and re-arms a fresh window; the existing hop counters bound it.
# (forbidden) NO exception this version — all 9 forbidden files must be
#   byte-identical to the 2.0.79 source zip.
# The manifest / edition-versions bump to 2.0.80 / 20081 is done in this
# same work, so this runner pins mobile+tv 2.0.80 / 20081.
# Also spot-checks that the 2.0.79, 2.0.78 and 2.0.77 items still hold.
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
CLASSES = "/tmp/rec2080-classes"
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
    rt = ":".join([CLASSES, ORGJSON, ANDROID]
                  + glob.glob(os.path.join(ROOT, "android/build/aar/*/classes.jar"))
                  + glob.glob(os.path.join(ROOT, "tools/java-dependencies/*.jar")))
    cmd += ["-cp", rt,
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
         os.path.join(SRC, "VideoEnhance2079.java"),
         os.path.join(SRC, "FirstFrameWatchdog2080.java"),
         os.path.join(ROOT, "android/tests/VideoEnhance2079Checks.java"),
         os.path.join(ROOT, "android/tests/QualityTier2078Checks.java"),
         os.path.join(ROOT, "android/tests/FirstFrameWatchdog2080Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.80 sources + FirstFrameWatchdog2080Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: FirstFrameWatchdog2080Checks ----
    p, f = run_jvm_class("FirstFrameWatchdog2080Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- JVM: VideoEnhance2079Checks (regression) ----
    p, f = run_jvm_class("VideoEnhance2079Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- JVM: QualityTier2078Checks (regression) ----
    p, f = run_jvm_class("QualityTier2078Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    fw = read("FirstFrameWatchdog2080.java")
    mp = read("MoviePlayer.java")
    pc = read("PlaybackController.java")
    pr = read("Prefs.java")
    sp = read("SourcePreferences.java")
    asf = read("AutoSourceFallback.java")
    ma = read("MainActivity.java")

    # ---- Static: FirstFrameWatchdog2080 pure timing state ----
    check("TIMEOUT_MS = 20_000L" in fw, "watchdog window is 20000ms (user-approved 20s)")
    check("void arm(long nowMs)" in fw, "watchdog has arm(nowMs)")
    check("void disarm()" in fw, "watchdog has disarm()")
    check("boolean isArmed()" in fw, "watchdog has isArmed()")
    check("boolean shouldFire(long nowMs)" in fw, "watchdog has shouldFire(nowMs)")
    check("long remainingMs(long nowMs)" in fw, "watchdog has remainingMs(nowMs)")
    check("nowMs - armedAtMs >= TIMEOUT_MS" in fw, "shouldFire compares elapsed time to the window")
    check("new Thread" not in fw, "watchdog adds no thread")
    check("System.currentTimeMillis" not in fw, "watchdog takes an injected clock (no wall clock)")
    check("import android" not in fw and "android.os" not in fw,
          "watchdog is pure Java (no Android classes)")
    check("DeepL" not in fw, "DeepL untouched by 2.0.80")

    # ---- Static: MoviePlayer first-frame signal + error driver ----
    check("public boolean hasRenderedFirstFrame()" in mp,
          "MoviePlayer has hasRenderedFirstFrame()")
    check("firstFrameMs >= 0" in mp, "hasRenderedFirstFrame reads the first-frame timestamp")
    check("public void fireFirstFrameWatchdog()" in mp,
          "MoviePlayer has fireFirstFrameWatchdog()")
    check("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" in mp,
          "watchdog drives the error path with the network-timeout code (2008)")
    check("errorListener.onError(" in mp, "fireFirstFrameWatchdog calls the error listener")
    check("if (released || errorListener == null) return null;" in mp,
          "fireFirstFrameWatchdog is a no-op when released or unlistened")
    check("first frame watchdog fired" in mp, "watchdog fire is logged")
    fwb = mp[mp.index("public void fireFirstFrameWatchdog()"):mp.index("public String playbackInfo()")]
    check("decoderFallback" not in fwb,
          "watchdog fire never takes the decoder-fallback branch")

    # ---- Static: PlaybackController arming + check ----
    check('if (!useSystemBackend && !"live".equals(s.key)) {' in pc,
          "watchdog is armed for VOD only (never live, never the system backend)")
    check("new FirstFrameWatchdog2080()" in pc, "PlaybackController creates the watchdog")
    check("watchdog.arm(" in pc, "watchdog is armed at prepare time")
    check("watchdogCheck" in pc, "a delayed watchdog check runnable is posted")
    check("FirstFrameWatchdog2080.TIMEOUT_MS" in pc,
          "the check is posted with the watchdog window")
    check("ready.hasRenderedFirstFrame()" in pc,
          "the check disarms on a rendered first frame")
    check("|| a.prepared) return;" in pc,
          "the check disarms on STATE_READY too (audio-only has no frame)")
    check("watchdog.shouldFire(now)" in pc, "the check asks the watchdog whether to fire")
    check("watchdog.remainingMs(" in pc, "an early check re-posts for the remaining time")
    check("線路逾時，正在換線…" in pc, "the watchdog sets the switch reason in the status text")
    check("ready.fireFirstFrameWatchdog();" in pc,
          "the check drives the existing error path (no parallel fallback)")
    widx = pc.index("final Runnable watchdogCheck")
    check("ticket != a.generation" in pc[widx:widx + 1200],
          "the check is guarded by the generation ticket")
    check("a.isFinishing()" in pc[widx:widx + 1200],
          "the check retires when the activity is finishing")
    check(pc.index("ready.prepareAsync();") < pc.index("new FirstFrameWatchdog2080()"),
          "the watchdog is armed right after prepareAsync")
    # The two pre-existing player executors (PLAYER_RESOLVE / PLAYER_INIT,
    # "player-resolve" / "player-init" threads) are the only new Thread uses.
    check(pc.count("new Thread") == 2,
          "PlaybackController adds no thread (still only the 2 pre-existing player executors)")

    # ---- Static: the existing fallback chain is reused, not duplicated ----
    check("showPlaybackDeadEnd(a, mp, what);" in pc,
          "the chain still ends at the dead end when everything fails")
    check("SourceSpeed2068.shouldForgiveStartupError(" in pc,
          "the chain still offers the 2.0.68 startup-grace retry")
    check("當前線路異常，正在切換至 " in pc, "the chain still hops to the next line")
    check("當前片源異常，正在尋找其他片源…" in pc, "the chain still hops to the next source")

    # ---- Static: manifest + version ----
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"),
               encoding="utf-8").read()
    check('android:host="watch-next"' in man,
          "2.0.77 holds: manifest still has the mkoai://watch-next deep link")
    check('android:versionName="2.0.80"' in man and 'android:versionCode="20081"' in man,
          "manifest = 2.0.80 / 20081")
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    evj = json.loads(ev)
    check(evj["android-mobile"]["versionName"] == "2.0.80"
          and evj["android-mobile"]["versionCode"] == 20081,
          "edition-versions.json mobile = 2.0.80 / 20081")
    check(evj["android-tv"]["versionName"] == "2.0.80"
          and evj["android-tv"]["versionCode"] == 20081,
          "edition-versions.json tv = 2.0.80 / 20081")
    check(evj["android-lite"]["versionName"] == "0.5.3"
          and evj["android-lite"]["versionCode"] == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")

    # ---- Static: 2.0.79 holds (spot checks) ----
    check('VIDEO_ENHANCE = "video_enhance"' in pr, "2.0.79 holds: Prefs still has VIDEO_ENHANCE")
    check("applyVideoEnhance();" in mp, "2.0.79 holds: MoviePlayer still applies the mode")
    check("VideoEnhance2079.applyTo(exo, mode)" in mp, "2.0.79 holds: enhance still delegates")
    check("畫質增強" in read("PlaybackControls.java"),
          "2.0.79 holds: the 畫質增強 menu is still there")

    # ---- Static: 2.0.78 holds (spot checks) ----
    check("QualityTier2078.isTc(name)" in sp,
          "2.0.78 holds: lineIndex scoring still calls QualityTier2078.isTc")
    check("Integer.compare(x.qualityTier, y.qualityTier)" in asf,
          "2.0.78 holds: compareRanked still orders by qualityTier")

    # ---- Static: 2.0.77 holds (spot checks) ----
    check("WatchNext2077.publishOrRemove" in pc,
          "2.0.77 holds: PlaybackController still publishes to Watch Next")
    check("WatchNext2077.remove(a, e);" in read("CatalogScreen.java"),
          "2.0.77 holds: single history remove still deletes the launcher card")
    check("void handleWatchNext(android.content.Intent intent)" in ma,
          "2.0.77 holds: MainActivity still has handleWatchNext()")

    # ---- Static: forbidden files byte-identical vs the 2.0.79 source zip ----
    z = zipfile.ZipFile(os.path.expanduser("~/workspace/mkoai/mkoai-2.0.79/mkoai-2.0.79-Source.zip"))
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        old = z.read("marcomovie/android/src/main/java/app/mkoai/player/" + rel)
        new = open(os.path.join(SRC, rel), "rb").read()
        check(old == new, "forbidden file byte-identical vs 2.0.79 zip: " + rel)

    # ---- Static: no secrets, no new threads in touched files, DeepL untouched ----
    secret = re.compile(r"AIza[0-9A-Za-z_-]{10,}|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{10,}")
    hits = 0
    for rel in ("FirstFrameWatchdog2080.java", "MoviePlayer.java", "PlaybackController.java"):
        hits += len(secret.findall(read(rel)))
    check(hits == 0, "secret grep = 0 in the 2.0.80 files")
    check(ma.count("new Thread") == 0, "MainActivity still has 0 new Thread")


    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
