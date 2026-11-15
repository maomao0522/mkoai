#!/usr/bin/env python3
# mkoai 2.0.63 checks — real-code JVM checks + static regression checks.
# Runs Home2063Checks (HeroDiffusion2063 colour math: averageTone,
# backgroundTone, lerpColor, scrimColors, fallback; HeroSwipe2063 tap /
# fling / pause math; the hero anime filter; TitleTranslator
# all-Chinese home cards: fast path, 80-point TMDB gate, Douban
# suggest check, msearch-id rewrite, bounded batch), plus static greps
# for the phone-home diffusion wiring in MobileShell (hero scrim fades
# into the page colour, background animates on rotation, sampling off
# the UI thread with a generation guard, wallpaper guard, per-poster
# tone cache), the all-Chinese translation hook in startTmdbRows, the
# view-all page + search-row + settings colour fixes, the TV home
# untouched, the 2.0.62 markers still present, the secret audit, and
# the version anchors.
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2063-classes"
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
         os.path.join(SRC, "MainActivity.java"),
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "MobileShell.java"),
         os.path.join(ROOT, "android/tests/Home2063Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.63 sources + Home2063Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2063Checks ----
    p, f = run_jvm_class("Home2063Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    hd = read("HeroDiffusion2063.java")

    # ---- Static: diffusion wiring (phone home only) ----
    check("HeroDiffusion2063.scrimColors(heroDiffusionTarget)" in ms,
          "diffusion: hero scrim is built from the diffusion colour")
    check("0xdf141414" not in ms,
          "diffusion: the old hard black scrim gradient is gone from MobileShell "
          "(the one 0xff141414 left is the app root background, covered by the "
          "diffusion-tinted ScrollView on the home page)")
    check("card.setBackgroundColor(heroDiffusionTarget)" in ms,
          "diffusion: hero card sits on the diffusion colour, not a black band")
    check("applyHeroDiffusion(next)" in ms and "applyHeroDiffusion(0)" in ms,
          "diffusion: retint runs on page change and at carousel build")
    check("ValueAnimator.ofArgb" in ms and "setDuration(600)" in ms,
          "diffusion: page background animates 600ms on rotation")
    check("RequestScope.submit(a.artwork" in ms,
          "diffusion: sampling runs on the artwork pool, off the UI thread")
    check("instanceof BitmapDrawable" in ms,
          "diffusion: samples only the already-decoded bitmap drawable")
    check("postDelayed" in ms and "HERO_TONE_MAX_RETRIES" in ms,
          "diffusion: undecided posters retry briefly, never block a tap")
    check("heroDiffusionGen" in ms and "gen != heroDiffusionGen" in ms,
          "diffusion: generation guard stops stale samples recolouring a left hero")
    check("heroToneCache" in ms and "heroToneCache.put(key, tone)" in ms,
          "diffusion: one tone cached per poster URL")
    check("WALLPAPER_ENABLED" in ms
          and "if (a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) return;" in ms,
          "diffusion: skipped when the user has a custom wallpaper")
    check("heroDiffusionShown" in ms and "heroDiffusionTarget" in ms,
          "diffusion: shown vs target colours tracked separately (no jump mid-animation)")
    check("homeScroll.setBackground(heroBloomGradient)" in ms
          and "bloomColors(heroDiffusionShown)" in ms,
          "diffusion: the animation retints the home ScrollView bloom "
          "(one GradientDrawable, re-tinted per frame — not a flat colour)")

    # ---- Static: swipe wiring (phone home only) ----
    check("requestDisallowInterceptTouchEvent(true)" in ms,
          "swipe: horizontal drags lock the vertical parent ScrollView out")
    check("VelocityTracker.obtain()" in ms and "computeCurrentVelocity(1000)" in ms,
          "swipe: flick velocity is tracked and read on release")
    check("HeroSwipe2063.targetPage(page[0], count," in ms,
          "swipe: the settle page comes from the fling/distance rule")
    check("HeroSwipe2063.flingThresholdPxPerSec(density)" in ms,
          "swipe: the fling threshold scales with screen density")
    check("HeroSwipe2063.resumeDelayMs(manual)" in ms
          and "HeroSwipe2063.shouldPauseAutoAdvance(" in ms,
          "swipe: auto-advance pauses 24s after a manual swipe, 12s after a tap")
    check("ValueAnimator.ofInt(fromX, toX)" in ms
          and "HeroSwipe2063.AUTO_TRANSITION_MS" in ms
          and "DecelerateInterpolator" in ms,
          "swipe: auto-advance glides over 900ms (slow), not a ~250ms snap")
    check("autoAnimator.cancel()" in ms,
          "swipe: a touch down stops a slow auto-glide in flight")
    check("heroSwipeDragged" in ms and "if (heroSwipeDragged) return;" in ms,
          "swipe: a drag never opens the detail page (tap guard on the card click)")
    check("a.dp(HeroSwipe2063.TAP_SLOP_DP)" in ms,
          "swipe: the drag threshold is the same tap slop the checks use")
    check("8000L" not in ms,
          "swipe: no raw 8000ms literal left — the interval is the named constant")

    # ---- Static: pure swipe math present ----
    sw = read("HeroSwipe2063.java")
    check("TAP_SLOP_DP = 12f" in sw and "FLING_VELOCITY_DP_PER_SEC = 400f" in sw,
          "swipe math: 12dp tap slop, 400dp/s fling threshold")
    check("PAGE_CHANGE_FRACTION = 0.10f" in sw and "MANUAL_PAUSE_MS = 24000L" in sw,
          "swipe math: tenth-page distance rule, 24s post-swipe pause")
    check("AUTO_ADVANCE_MS = 12000L" in sw and "AUTO_TRANSITION_MS = 900L" in sw,
          "swipe math: 12s slow rotation, 900ms glide")
    check("isTap" in sw and "targetPage" in sw and "clampPage" in sw
          and "shouldPauseAutoAdvance" in sw and "resumeDelayMs" in sw,
          "swipe math: all five pure helpers present")

    # ---- Static: hero anime filter (phone home only) ----
    vk = read("VideoKind2061.java")
    check("isAnime" in vk and "filterNonAnime" in vk,
          "anime filter: isAnime + filterNonAnime live in VideoKind2061")
    check('"动画", "動畫"' in vk and '"动漫", "動漫"' in vk
          and '"日漫"' in vk and '"国漫", "國漫"' in vk,
          "anime filter: all six user-named forms matched, both scripts")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter: the carousel filters its candidates at the single choke point")
    check("if (heroList.isEmpty()) heroList = candidates;" in ms,
          "anime filter: fail-open — an all-anime list keeps the original heroes")

    # ---- Static: all-Chinese home cards (2.0.63 phase 2b) ----
    tt = read("TitleTranslator.java")
    check("if (!looksForeign(foreignTitle)) return foreignTitle;" in tt,
          "translate: a Chinese title returns before any network (fast path)")
    check("if (!out.equals(foreignTitle)) CACHE.put(foreignTitle, out);" in tt,
          "translate: only a real translation is cached, never a failed fallback")
    check("invokeAll(tasks, budgetMs, TimeUnit.MILLISECONDS)" in tt,
          "translate: the batch runs under one global budget, not a 6s get per title")
    check("toTraditionalAll(titles, 8000L)" in tt,
          "translate: the default batch budget is 8000ms")
    check("setDaemon(true)" in tt,
          "translate: batch threads are daemon, never hold the process open")
    check("static void translateItems(" in tt and "static void translateVideos(" in tt
          and "static String rewriteSearchId(" in tt,
          "translate: translateItems + translateVideos + rewriteSearchId present")
    check("v.id = rewriteSearchId(v.id, zh);" in tt,
          "translate: a translated video rewrites its msearch id, nothing else")
    check("TitleTranslator.translateItems(allRowItems, 5000L)" in ms,
          "translate: startTmdbRows translates every row item after the metadata fetch")
    check("2.0.63: translate every home-row item" in ms,
          "translate: the startTmdbRows hook is documented at the call site")
    check("BoundedBatch.run(metadataJobs, 4, 12000L)" in ms
          and ms.index("TitleTranslator.translateItems(allRowItems, 5000L)")
          > ms.index("BoundedBatch.run(metadataJobs, 4, 12000L)"),
          "translate: the hook runs after the metadata batch, before the row loop")

    # ---- Static: view-all page + search row + settings colours (phase 2a leftovers) ----
    check("// mkoai 2.0.63: the poster overlay keeps only the remarks" in ms,
          "view-all: the poster overlay keeps only the remarks badge (year no longer twice)")
    check("// mkoai 2.0.63: the subtitle keeps only the year line" in ms,
          "view-all: the subtitle keeps only the year line (remarks live on the poster)")
    check("name.setMaxLines(2);" in ms,
          "view-all: the card title allows two lines (no more mid-word truncation)")
    check("lp.setMargins(a.dp(5), a.dp(5), a.dp(14), a.dp(19));" in ms,
          "view-all: the card margins are (5,5,14,19), aligned with the home")
    check("glassBg(0xb81c2421, 0xb8141a18, a.dp(16), a.dp(0.7f), 0x26ffffff)" in ms,
          "search row: the green tint is replaced by the home card neutrals (alpha kept)")
    check("glassBg(0xb81b2320, 0xb8131917, a.dp(16)" not in ms,
          "search row: no 16dp green-tinted glass is left")
    ios = read("MobileIosSettings.java")
    check("0x8cebebf5" not in ios and "0xff9ca3af" in ios,
          "settings: the About legal text is the muted grey 0xff9ca3af")
    check(ios.count("13.5f") == 1,
          "settings: the disclaimer is 13sp now (the one 13.5f left is the % value text)")

    # ---- Static: pure colour math present ----
    check("FALLBACK_BG = 0xff6b7683" in hd,
          "diffusion math: fallback is the 2.0.61 slate")
    check("MUTE_FACTOR" in hd and "DARKEN_FACTOR" in hd and "MAX_BG_CHANNEL = 140" in hd,
          "diffusion math: mute + darken + brightest-channel clamp")
    check("SCRIM_STOPS = 8" in hd and "SCRIM_TINT" not in hd
          and "BLOOM_STOPS = 6" in hd,
          "diffusion math: 8-stop scrim (tone ramps in), 6-stop bloom, no black tint")
    check("averageTone" in hd and "backgroundTone" in hd and "lerpColor" in hd
          and "scrimColors" in hd and "bloomColors" in hd,
          "diffusion math: all five pure helpers present")

    # ---- Static: TV home untouched ----
    hs = read("HomeScreen.java")
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs,
          "TV home untouched: no diffusion reference in HomeScreen.java")
    check("HOME_SLATE_BG" not in hs,
          "TV home untouched: the slate constant is phone-home only")

    # ---- Static: 2.0.62 markers still present ----
    sh = read("SimklHome.java")
    check("WorkerApi" in sh and "/api/simkl" in sh,
          "2.0.62 marker: Simkl still goes through the Worker")
    check("/api/posthog" in read("PostHogClient.java"),
          "2.0.62 marker: PostHog still goes through the Worker")
    check("fetchMoviesItems" in read("TraktHome.java"),
          "2.0.62 marker: Trakt rows intact")
    check("今晚播出" in read("HomeRows2061.java"),
          "2.0.62 marker: the 5 chart rows still defined")
    check("builtinHlsAdRules" in read("Cms.java"),
          "2.0.61 marker: built-in ad rules intact")
    check("accountSyncLinkKeys" in read("SettingsScreen.java"),
          "2.0.61 marker: QR login link keys intact")

    # ---- Static: secret audit (changed files) ----
    for rel in ("HeroDiffusion2063.java", "HeroSwipe2063.java", "MobileShell.java",
                "TitleTranslator.java", "MobileIosSettings.java"):
        text = read(rel)
        check("phc_" not in text and "os_v2_" not in text and "client_id=" not in text,
              "secret audit: no key literal in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.64"' in ev and "20065" in ev, "edition-versions.json = 2.0.64 / 20065")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.64"' in man and 'android:versionCode="20065"' in man,
          "AndroidManifest.xml = 2.0.64 / 20065")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
