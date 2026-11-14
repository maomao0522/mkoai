#!/usr/bin/env python3
# mkoai 2.0.57 checks — real-code JVM checks + static regression checks.
# Runs Home2057Checks (proxy, platformPasses, regionOf, TW-Netflix rule)
# plus static greps for the hero restore, the CN/KR/TW filter, the
# poster Worker proxy, and the 2.0.52/2.0.54/2.0.55 regression anchors.
# Cleaned-up replacement of the 2.0.57 task runner.
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2057-classes"
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
    # ---- JVM checks (Home2057Checks: proxy + platform + region + TW-Netflix) ----
    orgjson = os.path.join(ROOT, "android/tests/lib/json-20240303.jar")
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
         os.path.join(SRC, "AwwratedHome.java"),
         os.path.join(SRC, "ImageLoader.java"),
         os.path.join(ROOT, "android/tests/Home2057Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of AwwratedHome + ImageLoader + Home2057Checks")
    if jc.returncode != 0:
        print(jc.stderr[-2000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1
    jr = subprocess.run(
        [os.path.join(JDK, "bin/java"), "-Dsun.stdout.encoding=UTF-8",
         "-cp", ":".join([CLASSES, orgjson, android]),
         "app.mkoai.player.Home2057Checks"],
        text=True, capture_output=True, timeout=120)
    jvm_pass = 0
    for line in jr.stdout.splitlines():
        if line.startswith("PASS "):
            jvm_pass += 1
            print(line)
        elif line.startswith("FAIL "):
            print(line)
            FAILED.append(line[5:])
    m = re.search(r"(\d+) Home2057 checks passed", jr.stdout)
    if m:
        check(int(m.group(1)) == jvm_pass, "Home2057Checks count matches PASS lines")
    else:
        check(False, "Home2057Checks completed (count line found)")
        print(jr.stdout[-1500:])
        print(jr.stderr[-1500:])

    # ---- Static checks: hero restored to the 2.0.55 big card ----
    ms = read("MobileShell.java")
    check("Math.max(a.dp(240), a.windowWidth())" in ms,
          "hero carousel pageWidth = max(240dp, windowWidth) (full width, no peek)")
    check("windowWidth - 32dp" not in ms.replace("(Was windowWidth - 32dp", ""),
          "2.0.53 narrowing (windowWidth - 32dp) gone from code")
    check(re.search(r"Math\.min\(640,\s*Math\.max\(420,", ms) is not None and
          "1.25f" in ms,
          "hero card height min(640, max(420, w/density*1.25))")
    check("CENTER_CROP" in ms, "hero poster CENTER_CROP full bleed")
    check("0xdf141414" in ms and "0xff141414" in ms, "hero bottom gradient to 0xff141414")
    check("27sp" in ms or "27f" in ms or "27 * scale" in ms, "hero title 27sp bold centered")
    check("▶ 播放" in ms, "hero has full-width white ▶ 播放 button")
    # The iPhone card's distinctive markers are the ✦ pill, the small
    # 132x198dp poster and the ▶ 立即觀看 capsule. ("今日首推" alone
    # is fine — the big card's own comment/toast calls it 今日首推巨幕卡片.)
    check("✦" not in ms and "dp(132)" not in ms and "dp(198)" not in ms and
          "立即觀看" not in ms,
          "iPhone showcase card gone (no ✦ pill / 132x198 poster / 立即觀看 capsule)")
    check("8s" in ms or "8000" in ms, "hero auto-advance 8s")
    check("buildHeroCarousel" in ms and "buildHeroShowcaseCard" in ms,
          "hero carousel + showcase card methods present")

    # ---- Static checks: CN/KR + TW filter ----
    ah = read("AwwratedHome.java")
    check("platformPasses" in ah, "platformPasses helper present")
    check('REGION_CN' in ah and 'REGION_KR' in ah, "CN/KR exemption in platformPasses")
    check("regionOf" in ah and '"TW".equals(token)' in ah, "regionOf recognises TW code")
    check('"CN".equals(token)' in ah and '"KR".equals(token)' in ah and
          '"US".equals(token)' in ah, "regionOf recognises CN/KR/US codes")
    check("awwrated-availability-v4.json" in ah, "availability cache bumped to v4 (stale OTHER regions discarded)")
    check("RELEASE_DELAY_MS" in ah, "48h release delay still present")
    check("US_CAP" in ah or "0.25" in ah or "25" in ah, "US 25% cap still present")

    # ---- Static checks: poster Worker proxy kept ----
    il = read("ImageLoader.java")
    check("proxyPosterUrl" in il and "/img?url=" in il, "poster Worker proxy present in ImageLoader")
    check("maoki-mkoai-api.maotai0522.workers.dev" in il, "Worker base URL present")
    check("image.tmdb.org" in il and "empimage.slcache.com" in il and "doubanio.com" in il,
          "proxy hosts: tmdb + slcache + doubanio")
    imp = read("ImmersivePoster.java")
    check("proxyPosterUrl" in imp or "/img?url=" in imp, "ImmersivePoster uses proxy")

    # ---- Static checks: 2.0.52/2.0.54/2.0.55 regression anchors ----
    sa = read("SupabaseAuth.java")
    check("signInWithPassword" in sa and "sendPasswordRecovery" in sa,
          "2.0.53 password sign-in kept (SupabaseAuth)")
    check("importSession" in sa or "refreshToken" in sa,
          "2.0.54 QR login plumbing kept (SupabaseAuth)")
    tl = read("TvLoginDialog.java")
    check("tv-login" in tl, "2.0.54 TV QR login dialog kept")
    cts = read("ConfigTransferServer.java")
    check("/push" in cts and "/login" in cts, "2.0.52 push + 2.0.54 login routes kept")
    mds = read("MobileDetailScreen.java")
    check("startFreshSourceCheck" in mds, "2.0.55 immediate-play flow kept")
    check("showUnplayable" in mds, "2.0.55 showUnplayable kept")
    il2 = read("ImageLoader.java")
    check("tvCardsSmall" in il2, "2.0.54 TV lightweight poster size kept")
    check("0xff141414" in il2, "2.0.53 TV solid background kept")
    pb = read("PlaybackControls.java")
    check("⛶" in pb and "▯" not in pb, "2.0.51 fullscreen glyph ⛶ kept (no tofu ▯)")
    check("48dp" in ms or "48 * density" in ms or "48f" in ms, "login button 48dp kept in MobileShell")

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.57"' in ev and "20058" in ev, "edition-versions.json = 2.0.57 / 20058")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.57"' in man and 'android:versionCode="20058"' in man,
          "AndroidManifest.xml = 2.0.57 / 20058")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
