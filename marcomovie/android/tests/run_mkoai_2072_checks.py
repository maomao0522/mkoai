#!/usr/bin/env python3
# mkoai 2.0.72 checks — real-code JVM checks + static regression checks.
# Runs Home2072Checks (history tone-follow roles + the grey-bottom
# fix on real Wenwu poster pixels + the gone home veil), plus static
# greps for the 2.0.72 items:
# (history) the watch-history page follows the home hero tone:
#   MainActivity.pageTone (published by MobileShell.applyPageTone)
#   + retintRenderPage() (tag-gated, called from the diffusion
#   flip) paints the history scroll + root and walks the body,
#   flipping only text that sits directly on the page background
#   (HomeTone2066.retintPageColor); dark-glass subtrees are skipped
#   by their non-transparent background. The shell's Layer 0 canvas
#   paints the flat tone now (was an opaque frost field that hid
#   the root colour, so the tabs never actually followed the tone).
#   renderPage itself is UNCHANGED (root still re-created DARK).
# (veil) the home top veil is gone (topFadeHeightPx() == 0 on the
#   home tab); other tabs keep statusBarHeight + 24dp. The bottom
#   scrim is untouched.
# (grey) the bloom far colour is pageTone(bg) on both sides now
#   (was backgroundTone on a dark page, which faded the page
#   bottom grey). backgroundTone itself is untouched (legacy
#   bloomColors + its 2063-2065 pins).
# (detail) the detail page's openListTone behaviour is unchanged.
# (welcome) the no-config welcome page (TV-only) is untouched.
# The manifest / edition-versions bump to 2.0.72 / 20073 is the
# parent agent's step AFTER this work, so this runner does NOT pin
# the mobile/tv version — only that lite stays 0.5.3 / 53.
# Also spot-checks that the 2.0.71 items still hold.

import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2072-classes"
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
         os.path.join(SRC, "ImageDecodePolicy.java"),
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
         os.path.join(ROOT, "android/tests/Home2072Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.72 sources + Home2072Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2072Checks ----
    p, f = run_jvm_class("Home2072Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    ma = read("MainActivity.java")
    il = read("ImageLoader.java")
    idp = read("ImageDecodePolicy.java")
    hs = read("HomeScreen.java")
    hd = read("HeroDiffusion2063.java")
    ht = read("HomeTone2066.java")

    # ---- Static: veil — the home top veil is gone ----
    check("if (activeTab == 0) return 0;" in ms,
          "(veil) topFadeHeightPx() is 0 on the home tab (no top fog on the poster)")
    check("return statusBarHeight() + a.dp(24);" in ms,
          "(veil) other tabs keep statusBarHeight + 24dp")
    check("activeTab == 0 ? 150 : 24" not in ms
          and "activeTab == 0 ? 250 : 24" not in ms,
          "(veil) the old 150dp / 250dp home veil formulas are gone")
    top_fade = ms[ms.index("private static final class TopEdgeFadeView"):]
    top_fade = top_fade[:top_fade.index("private static final class BottomEdgeFadeView")]
    check("topFadeColors(baseRgb)" in top_fade,
          "(veil) the veil still uses topFadeColors (shape unchanged, still builds the other tabs' veil)")
    check("HeroDiffusion2063.scrimColors(heroDiffusionTarget)" in ms,
          "(veil) the bottom scrim is untouched (still the only in-card fade)")

    # ---- Static: history — the page follows the hero tone ----
    check("int pageTone = DARK;" in ma,
          "(history) MainActivity has the pageTone field (DARK until the first tone lands)")
    check('static final String TONE_PAGE_TAG = "tone-page";' in ma,
          "(history) the tone-page scroll tag exists")
    check("void applyHistoryTone()" in ma and "void retintRenderPage()" in ma
          and "retintRenderPageWalk" in ma,
          "(history) MainActivity has applyHistoryTone / retintRenderPage / the body walk")
    check("applyHistoryTone();" in ma,
          "(history) historyPage() applies the tone after the content is built")
    check("isTvDevice() || prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)" in ma,
          "(history) the tone apply no-ops on TV and with a wallpaper (today's dark page kept)")
    check("scroll.setTag(TONE_PAGE_TAG);" in ma,
          "(history) the history scroll is tagged tone-following")
    check("TONE_PAGE_TAG.equals(scroll.getTag())" in ma,
          "(history) retintRenderPage no-ops unless the current scroll carries the tag")
    check("root.setBackgroundColor(pageTone)" in ma
          and "scroll.setBackgroundColor(pageTone)" in ma,
          "(history) the root + scroll take the tone (the showTmdbListPage openListTone pattern)")
    check("root.setBackgroundColor(DARK)" in ma,
          "(history) renderPage itself is unchanged (root still re-created DARK; the tone goes on after)")
    check("PixelFormat.TRANSPARENT" in ma,
          "(history) the walk skips only non-transparent backgrounds (dark glass stays, stroke-only ovals do not block)")
    check("HomeTone2066.retintPageColor(tv.getCurrentTextColor(), pageTone)" in ma,
          "(history) the walk flips text by its current colour through retintPageColor")
    check("a.retintRenderPage();" in ms,
          "(history) the diffusion flip re-tints the history page live (no-op off it)")
    check("a.pageTone = want;" in ms,
          "(history) applyPageTone publishes the tone to MainActivity.pageTone")
    check("ambientCanvas.invalidate();" in ms,
          "(history) applyPageTone invalidates the Layer 0 canvas when the tone changes")
    check("canvas.drawColor(a != null ? a.pageTone : 0xff141414);" in ms,
          "(tabs-follow) the shell Layer 0 canvas paints the flat hero tone")
    check("HomeAmbient.paintFrost(canvas, w, h, paint)" not in ms,
          "(tabs-follow) the opaque frost call is gone from MobileShell (it hid the root tone on every tab)")

    # ---- Static: grey — the bloom far is pageTone on both sides ----
    check("int far = pageTone(bg);" in hd,
          "(grey) bloomColorsAnchored far is pageTone(bg) on both sides")
    check("HomeTone2066.isLight(bg) ? pageTone(bg) : backgroundTone(bg)" not in hd,
          "(grey) the old light/dark far split is gone")
    check("far = backgroundTone(bg);" in hd,
          "(grey) backgroundTone is still used by the legacy bloomColors shape (unchanged)")
    check("static int backgroundTone(int argb)" in hd,
          "(grey) backgroundTone itself is untouched")

    # ---- Static: detail — openListTone behaviour unchanged ----
    check("a.root.setBackgroundColor(openListTone);" in ms
          and "a.scroll.setBackgroundColor(openListTone);" in ms,
          "(detail) the detail / list page still sets root + scroll to its own captured openListTone")
    check("openListTone =" in ms,
          "(detail) openListTone is still captured at open time")
    check("a.root.setBackgroundColor(initAmbient)" in read("MobileDetailScreen.java"),
          "(detail) the detail page keeps its own ambient root (does not follow the home tone)")

    # ---- Static: welcome page untouched (TV-only, no config) ----
    check("把今晚，留給好故事。" in ma,
          "(welcome) the no-config welcome page text is untouched")

    # ---- Static: 2.0.71 holds (spot checks; the 2071 runner covers them in full) ----
    check("private static final int[] DOCK_TAB_INDICES = {0, 1, 2, 3, 5, 4};" in ms,
          "2.0.71 holds: the dock order is still {0,1,2,3,5,4}")
    check("private void retintSettingsTree(ViewGroup parent, int tone)" in ms,
          "2.0.71 holds: the settings tree-walk is still there")
    check(ms.count("applyPageTone();") == 2,
          "2.0.71 holds: applyPageTone still runs at the end of renderTabNow AND in the diffusion flip")
    check("static void fadeInView(" in ms and "DecelerateInterpolator" in ms,
          "2.0.70 holds: the shared fadeInView helper is still there")
    check("heroDiffusionAnimator.setDuration(300)" in ms,
          "2.0.70 holds: diffusion retint is still 300ms")

    # ---- Static: TV untouched, anime filter untouched, DeepL untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs
          and "2.0.71" not in hs and "2.0.72" not in hs,
          "TV untouched: no diffusion, hero tone, list tone, list grid or 2.0.71/2.0.72 marker in HomeScreen.java")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")
    check("deepl_quota_exhausted" in read("QuotaState2066.java")
          and os.path.exists(os.path.join(SRC, "TranslateClient.java")),
          "DeepL untouched: deepl_quota_exhausted + TranslateClient still there (removal paused)")

    # ---- Static: no new threads ----
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")
    check(ma.count("new Thread") == 0 and il.count("new Thread") == 0
          and idp.count("new Thread") == 0,
          "no new thread in MainActivity / ImageLoader / ImageDecodePolicy either")

    # ---- Static: secret audit (whole app tree) ----
    check(grep_app("phc_") == 0 and grep_app("os_v2_") == 0
          and grep_app("client_id=") == 0,
          "secret audit: no key literal in the app tree")

    # ---- Static: forbidden files carry no 2.0.72 marker (NO exception this time) ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.72" not in text,
              "forbidden file untouched: no 2.0.72 marker in " + rel)

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
