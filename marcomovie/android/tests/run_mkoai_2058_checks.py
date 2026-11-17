#!/usr/bin/env python3
# mkoai 2.0.58 checks — real-code JVM checks + static regression checks.
# Runs Home2058Checks (Douban 新片 parse + Worker-404 fallback, Douban
# suggest parse, TMDB zh-TW pick, home composition, updater apkUrl)
# plus static greps for the TV home restructure, the source-picker TV
# focus fix, the TitleTranslator order, and the 2.0.51-2.0.57 anchors.
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2058-classes"
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


def main():
    # ---- JVM checks (Home2058Checks) ----
    orgjson = ("/home/hatch/.gradle-kazumi/caches/modules-2/files-2.1/org.json/"
               "json/20180813/8566b2b0391d9d4479ea225645c6ed47ef17fe41/"
               "json-20180813.jar")
    android = os.path.join(ROOT, "tools/android-35/android.jar")
    jars = ":".join(glob.glob(os.path.join(ROOT, "tools/java-dependencies/*.jar")))
    aars = ":".join(glob.glob(os.path.join(ROOT, "android/build/aar/*/classes.jar")))
    os.makedirs(CLASSES, exist_ok=True)
    cp = ":".join([orgjson, android, jars, aars])
    sp = ":".join([os.path.join(ROOT, "android/src/main/java"),
                   os.path.join(ROOT, "shared/java"),
                   os.path.join(ROOT, "android/build/generated")])
    jc = subprocess.run(
        [os.path.join(JDK, "bin/javac"), "-encoding", "UTF-8",
         "-source", "8", "-target", "8", "-Xlint:-options",
         "-cp", cp, "-sourcepath", sp, "-d", CLASSES,
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "DoubanHome.java"),
         os.path.join(SRC, "TitleTranslator.java"),
         os.path.join(SRC, "TmdbClient.java"),
         os.path.join(SRC, "UpdatePolicy.java"),
         os.path.join(SRC, "SourcePicker.java"),
         os.path.join(ROOT, "android/tests/Home2058Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.58 sources + Home2058Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1
    jr = subprocess.run(
        [os.path.join(JDK, "bin/java"), "-Dsun.stdout.encoding=UTF-8",
         "-cp", ":".join([CLASSES, orgjson, android]),
         "app.mkoai.player.Home2058Checks"],
        text=True, capture_output=True, timeout=120)
    jvm_pass = 0
    for line in jr.stdout.splitlines():
        if line.startswith("PASS "):
            jvm_pass += 1
            print(line)
        elif line.startswith("FAIL "):
            print(line)
            FAILED.append(line[5:])
    m = re.search(r"(\d+) Home2058 checks passed", jr.stdout)
    if m:
        check(int(m.group(1)) == jvm_pass, "Home2058Checks count matches PASS lines")
    else:
        check(False, "Home2058Checks completed (count line found)")
        print(jr.stdout[-1500:])
        print(jr.stderr[-1500:])

    # ---- Static checks: Douban 新片 ----
    dh = read("DoubanHome.java")
    check("fetchNew" in dh and "sort=T" in dh, "DoubanHome.fetchNew uses sort=T (newest first)")
    check("WORKER_NEW_URL" in dh and "/api/douban/new" in dh,
          "DoubanHome tries the Worker /api/douban/new first")
    check("isUsableNewPayload" in dh, "DoubanHome validates the Worker payload before accepting it")
    check("NEW_MAX" in dh and "out.size() < NEW_MAX" in dh, "Douban 新片 capped at 10")
    check('"豆瓣新片"' in dh, "Douban 新片 remarks present")
    check("fetchHot" in dh and "sort=U" in dh, "DoubanHome.fetchHot (sort=U) untouched")

    # ---- Static checks: TV home follows the selected source ----
    hs = read("HomeScreen.java")
    check("composeHomeVideos" in hs, "HomeScreen.composeHomeVideos present")
    check("DoubanHome.fetchNew" in hs, "loadHome fetches Douban 新片 first")
    check("Simkl is the PRIMARY home content" not in hs,
          "Simkl-as-primary comment gone (home is no longer locked to Simkl)")
    check("now only a fallback" in hs or "only a fallback" in hs,
          "Simkl is documented as fallback-only in loadHome")
    check("recommendationsPending" in hs, "navigation snapshot (categories-only) kept")
    check("readDiskSnapshot" in hs and "writeDiskSnapshot" in hs,
          "disk snapshot read/write kept")
    check("windowSize" in hs and "(40 / COLUMNS)" in hs,
          "2.0.54 TV lightweight window (40 cards) kept")
    check("tvCardsSmall" not in hs, "no change to the poster-size helper here")
    check('"豆瓣新片 · Simkl 熱門"' in hs or "Simkl 熱門" in hs,
          "Simkl-fallback status text kept")
    check('"來源載入失敗，顯示豆瓣新片"' in hs, "catch fallback shows Douban 新片 before hot")
    check("a.reduced" in hs, "reduced-mode (TV lightweight) branches kept")

    # ---- Static checks: source picker TV focus ----
    spk = read("SourcePicker.java")
    check("if (tv) card.setFocusable(true)" in spk,
          "SourcePicker: TV cards re-focusable after bindTouchFeedback")
    check("if (tv) del.setFocusable(true)" in spk,
          "SourcePicker: TV delete buttons re-focusable after bindTouchFeedback")
    check("bindTouchFeedback(card)" in spk and "bindTouchFeedback(del)" in spk,
          "SourcePicker: touch feedback kept (phone taps unchanged)")
    check("selectSource" in spk and "SELECTED_SITE" in spk and "LOADING_SITE" in spk,
          "SourcePicker: selectSource still saves SELECTED_SITE/LOADING_SITE and reopens home")
    check("選擇後返回首頁，顯示該片源的內容" in spk,
          "SourcePicker subtitle kept (now true: the home follows the source)")

    # ---- Static checks: TitleTranslator order + Worker suggest ----
    tt = read("TitleTranslator.java")
    check("TmdbClient.translateTitle" in tt, "TitleTranslator calls the TMDB fallback")
    check("lookupDouban" in tt and "/api/douban/suggest" in tt,
          "TitleTranslator Douban lookup tries the Worker /api/douban/suggest first")
    check("parseSuggest" in tt, "TitleTranslator.parseSuggest extracted (testable)")
    ia = tt.index("AwwratedTitles.lookup")
    it = tt.index("TmdbClient.translateTitle")
    idb = tt.index("lookupDouban")
    check(0 <= ia < it < idb,
          "TitleTranslator order: Awwrated -> TMDB -> Douban (Worker -> direct)")
    check("looksForeign" in tt, "TMDB lookup gated to foreign (Latin-letter) titles")

    # ---- Static checks: TMDB translate helper ----
    tc = read("TmdbClient.java")
    check("translateTitle" in tc and "pickTranslatedTitle" in tc,
          "TmdbClient.translateTitle + pickTranslatedTitle present")
    check("original_title" in tc and "original_name" in tc,
          "TMDB match uses original_title/original_name")
    check("language=zh-TW" in tc, "TMDB translation search uses zh-TW")

    # ---- Static checks: updater ----
    up = read("UpdatePolicy.java")
    check("drive.usercontent.google.com" in up and "hasApkParams" in up,
          "UpdatePolicy.apkUrl accepts the direct usercontent confirm form")
    check('"/uc".equals(u.getPath())' in up,
          "UpdatePolicy.apkUrl still accepts the Drive /uc form")
    check("transferUrl" in up and "drive.usercontent.google.com" in up,
          "UpdatePolicy.transferUrl still allows both Drive hosts")

    # ---- Regression anchors: 2.0.51-2.0.57 ----
    ah = read("AwwratedHome.java")
    check("platformPasses" in ah and "awwrated-availability-v4.json" in ah,
          "2.0.57 CN/KR + TW filter kept")
    check("regionOf" in ah and '"TW".equals(token)' in ah, "2.0.57 regionOf TW kept")
    il = read("ImageLoader.java")
    check("proxyPosterUrl" in il and "/img?url=" in il, "2.0.56 poster Worker proxy kept")
    check("tvCardsSmall" in il, "2.0.54 TV lightweight poster size kept")
    check("0xff141414" in il, "2.0.53 TV solid background kept")
    sa = read("SupabaseAuth.java")
    check("signInWithPassword" in sa and "importSession" in sa,
          "2.0.53 password sign-in + 2.0.54 QR login kept")
    check("tv-login" in read("TvLoginDialog.java"), "2.0.54 TV QR login dialog kept")
    cts = read("ConfigTransferServer.java")
    check("/push" in cts and "/login" in cts, "2.0.52 push + 2.0.54 login routes kept")
    mds = read("MobileDetailScreen.java")
    check("startFreshSourceCheck" in mds and "showUnplayable" in mds,
          "2.0.55 immediate-play flow kept")
    pb = read("PlaybackControls.java")
    check("⛶" in pb and "▯" not in pb, "2.0.51 fullscreen glyph ⛶ kept (no tofu ▯)")
    ms = read("MobileShell.java")
    check("▶ 播放" in ms and "立即觀看" not in ms, "2.0.57 big hero kept (no iPhone card)")
    sh = read("SimklHome.java")
    check("msearch:" in sh and "toTraditionalAll" in sh,
          "Simkl tap behaviour (msearch + translation) kept")

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.73"' in ev and "20074" in ev, "edition-versions.json = 2.0.73 / 20074")
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
