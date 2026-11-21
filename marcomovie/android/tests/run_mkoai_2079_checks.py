#!/usr/bin/env python3
# mkoai 2.0.79 checks — JVM + static checks for picture enhancement (Media3 sharpen).
#
# (feature, user 2026-11-21) 畫質增強，像 Maoki 的超分辨率：
#   Maoki's super-resolution is MPV-only Anime4K GLSL shaders and cannot run in
#   Media3/ExoPlayer, so this version ships the feasible equivalent on the current
#   player: a separable sharpen convolution through ExoPlayer.setVideoEffects,
#   using Media3's own SeparableConvolution program (no hand-written GLSL).
#   Modes 關閉 (default) / 效率 / 質量， in the player 更多 menu (VOD + live),
#   stored in Prefs.VIDEO_ENHANCE and re-applied at player creation. Off keeps
#   the plain ExoPlayer path (no effects set). The real Anime4K comes when the
#   player unifies on media_kit after the merge.
# (design) VideoEnhance2079: 3-tap sharpen kernel [-a, 1+2a, -a] as three unit-area
#   triangles (integer taps sum to 1, integral is 1, brightness preserved);
#   applyTo() is fail-soft (never breaks playback); MoviePlayer.applyVideoEnhance()
#   is a no-op without an ExoPlayer (system backend). Dependency media3-effect
#   1.5.1 added to the fetcher resolve list + media-dependencies.json (35 entries).
# (forbidden) NO exception this version — all 9 forbidden files must be
#   byte-identical to the 2.0.78 source zip.
# The manifest / edition-versions bump to 2.0.79 / 20080 is done in this
# same work, so this runner pins mobile+tv 2.0.79 / 20080.
# Also spot-checks that the 2.0.78 and 2.0.77 items still hold.
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
CLASSES = "/tmp/rec2079-classes"
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
    # 2.0.79: the checks class loads Media3 classes (SeparableConvolution /
    # ConvolutionFunction1D), so the runtime classpath needs the AAR classes
    # too — the 2.0.78 runner only needed CLASSES + ORGJSON + ANDROID.
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
         os.path.join(ROOT, "android/tests/VideoEnhance2079Checks.java"),
         os.path.join(ROOT, "android/tests/QualityTier2078Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.79 sources + VideoEnhance2079Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: VideoEnhance2079Checks ----
    p, f = run_jvm_class("VideoEnhance2079Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- JVM: QualityTier2078Checks (regression) ----
    p, f = run_jvm_class("QualityTier2078Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ve = read("VideoEnhance2079.java")
    pr = read("Prefs.java")
    mp = read("MoviePlayer.java")
    pc = read("PlaybackControls.java")
    qt = read("QualityTier2078.java")
    sp = read("SourcePreferences.java")
    asf = read("AutoSourceFallback.java")
    ma = read("MainActivity.java")

    # ---- Static: VideoEnhance2079 pure API surface ----
    check("MODE_OFF = 0" in ve and "MODE_EFFICIENCY = 1" in ve and "MODE_QUALITY = 2" in ve,
          "VideoEnhance2079 modes are off 0 / efficiency 1 / quality 2")
    check('LABELS = {"關閉", "效率", "質量"}' in ve, "mode labels are 關閉/效率/質量")
    check("STRENGTH_EFFICIENCY = 0.35f" in ve and "STRENGTH_QUALITY = 0.6f" in ve,
          "strengths are 0.35 efficiency / 0.6 quality")
    check("static List<Effect> effectsFor(int mode)" in ve, "VideoEnhance2079 has effectsFor()")
    check("static void applyTo(ExoPlayer player, int mode)" in ve, "VideoEnhance2079 has applyTo()")
    check("class SharpenConvolution extends SeparableConvolution" in ve,
          "sharpen is a Media3 SeparableConvolution (tested program, no hand-written GLSL)")
    check("class SharpenFunction implements ConvolutionFunction1D" in ve,
          "kernel is a ConvolutionFunction1D")
    check("gl_FragColor" not in ve and "samplerExternalOES" not in ve and "createProgram" not in ve,
          "no hand-written GLSL in VideoEnhance2079 (uses Media3's own program)")
    check("Anime4K" in ve and "cannot run in" in ve,
          "the code states Anime4K cannot run in Media3 (no false claim)")
    check("new Thread" not in ve, "VideoEnhance2079 adds no thread")
    check("setVideoEffects(effectsFor(mode))" in ve, "applyTo calls setVideoEffects")
    check("catch (RuntimeException error)" in ve, "applyTo is fail-soft on graph failure")
    check('AppLog.w("VideoEnhance2079"' in ve, "applyTo failure is logged")

    # ---- Static: Prefs key ----
    check('VIDEO_ENHANCE = "video_enhance"' in pr, "Prefs has VIDEO_ENHANCE = video_enhance")
    check("VIDEO_ENHANCE," in pr, "VIDEO_ENHANCE is in the backed-up KEYS set")
    check("ranges.put(VIDEO_ENHANCE, new int[] {0, 2})" in pr, "VIDEO_ENHANCE range is 0-2")

    # ---- Static: MoviePlayer wiring ----
    check("applyVideoEnhance();" in mp, "MoviePlayer applies the mode at player creation")
    check(mp.index("applyVideoEnhance();") > mp.index("exo.setTrackSelectionParameters(trackParams.build());"),
          "apply call sits after the player is built")
    check("public void applyVideoEnhance()" in mp, "MoviePlayer has applyVideoEnhance()")
    check("if (exo == null) return;" in mp, "applyVideoEnhance is a no-op without an ExoPlayer")
    check("Prefs.VIDEO_ENHANCE" in mp, "applyVideoEnhance reads the stored mode")
    check("VideoEnhance2079.applyTo(exo, mode)" in mp, "applyVideoEnhance delegates to VideoEnhance2079")

    # ---- Static: PlaybackControls menu ----
    check("void videoEnhance()" in pc, "PlaybackControls has videoEnhance()")
    check('choose(\n                "畫質增強",' in pc, "videoEnhance opens a 畫質增強 picker")
    check("a.prefs.edit().putInt(Prefs.VIDEO_ENHANCE, i).apply();" in pc,
          "videoEnhance persists the picked mode")
    check("a.player.applyVideoEnhance();" in pc, "videoEnhance re-applies live, no reload")
    check(pc.count("畫質增強") == 3,
          "畫質增強 appears 3 times: live menu label + VOD menu label + picker title")
    check(pc.count("videoEnhance();") == 2,
          "videoEnhance is wired in both more() menus (live case 5 + VOD case 9)")
    check(pc.count("VideoEnhance2079.LABELS[VideoEnhance2079.clampMode") == 2,
          "both menus show the current mode in the label")

    # ---- Static: media3-effect dependency ----
    deps = json.loads(open(os.path.join(ROOT, "android/media-dependencies.json"),
                           encoding="utf-8").read())
    eff = [x for x in deps if x["name"] == "media3-effect"]
    check(len(eff) == 1 and eff[0]["version"] == "1.5.1",
          "media-dependencies.json pins media3-effect 1.5.1")
    check(len(eff) == 1 and eff[0]["sha256"]
          == "29ca94492cc7ae86b7817da3449e2c29504cd7fdd410a8603b74098260291955",
          "media3-effect SHA-256 matches Google Maven")
    check(len(deps) == 35, "media-dependencies.json has 35 entries (34 + effect)")
    fetch = open(os.path.join(ROOT, "scripts/fetch_media_dependencies.py"),
                 encoding="utf-8").read()
    check("'media3-effect'" in fetch, "fetcher resolve list includes media3-effect")

    # ---- Static: manifest + version ----
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"),
               encoding="utf-8").read()
    check('android:host="watch-next"' in man,
          "2.0.77 holds: manifest still has the mkoai://watch-next deep link")
    check('android:versionName="2.0.79"' in man and 'android:versionCode="20080"' in man,
          "manifest = 2.0.79 / 20080")
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    evj = json.loads(ev)
    check(evj["android-mobile"]["versionName"] == "2.0.79"
          and evj["android-mobile"]["versionCode"] == 20080,
          "edition-versions.json mobile = 2.0.79 / 20080")
    check(evj["android-tv"]["versionName"] == "2.0.79"
          and evj["android-tv"]["versionCode"] == 20080,
          "edition-versions.json tv = 2.0.79 / 20080")
    check(evj["android-lite"]["versionName"] == "0.5.3"
          and evj["android-lite"]["versionCode"] == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")

    # ---- Static: 2.0.78 holds (spot checks) ----
    check("QualityTier2078.isTc(name)" in sp,
          "2.0.78 holds: lineIndex scoring still calls QualityTier2078.isTc")
    check("Integer.compare(x.qualityTier, y.qualityTier)" in asf,
          "2.0.78 holds: compareRanked still orders by qualityTier")

    # ---- Static: 2.0.77 holds (spot checks) ----
    check("WatchNext2077.publishOrRemove" in read("PlaybackController.java"),
          "2.0.77 holds: PlaybackController still publishes to Watch Next")
    check("WatchNext2077.remove(a, e);" in read("CatalogScreen.java"),
          "2.0.77 holds: single history remove still deletes the launcher card")
    check("void handleWatchNext(android.content.Intent intent)" in ma,
          "2.0.77 holds: MainActivity still has handleWatchNext()")

    # ---- Static: forbidden files byte-identical vs the 2.0.78 source zip ----
    z = zipfile.ZipFile(os.path.expanduser("~/workspace/mkoai/mkoai-2.0.78/mkoai-2.0.78-Source.zip"))
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        old = z.read("marcomovie/android/src/main/java/app/mkoai/player/" + rel)
        new = open(os.path.join(SRC, rel), "rb").read()
        check(old == new, "forbidden file byte-identical vs 2.0.78 zip: " + rel)

    # ---- Static: no secrets, no new threads in touched files, DeepL untouched ----
    secret = re.compile(r"AIza[0-9A-Za-z_-]{10,}|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{10,}")
    hits = 0
    for rel in ("VideoEnhance2079.java", "Prefs.java", "MoviePlayer.java", "PlaybackControls.java"):
        hits += len(secret.findall(read(rel)))
    check(hits == 0, "secret grep = 0 in the 2.0.79 files")
    check(ma.count("new Thread") == 0, "MainActivity still has 0 new Thread")
    check("DeepL" not in ve and "DeepL" not in pc, "DeepL untouched by 2.0.79")


    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
