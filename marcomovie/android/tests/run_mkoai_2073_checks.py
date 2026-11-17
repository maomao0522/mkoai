#!/usr/bin/env python3
# mkoai 2.0.73 checks — real-code JVM checks + static regression checks.
# Runs Home2073Checks (the home-bottom white-haze diagnosis on real
# screenshot pixels), plus static greps for the 2.0.73 items:
# (back) the watch-history page gains a back circle at the top-left,
#   the same style as the search tab's back button (38dp dark-glass
#   circle, "‹" 22sp white, same glassBg + press feedback, tag
#   history-back). Tap pops the Screen exactly like the system back
#   button (a.onBackPressed): dock entry returns to the home tab,
#   settings / welcome / TV entries return to the page that opened
#   history. It sits above the eyebrow (body index 0) and shows even
#   when the history is empty. Nothing else on the page moves (tone
#   follow, cards, clear pill untouched).
# (dock) the dock label is "記錄" now (the old four-glyph label was
#   truncated to three glyphs on the dock). Only the dock label
#   changes: the history page title stays "觀看歷史" and every other
#   觀看記錄 string is untouched.
# (haze) the home-bottom white haze is gone: the BottomEdgeFadeView
#   layer (Layer 3, 92dp bottom gradient) is removed. Its colour only
#   followed the tone inside the diffusion flip block, so a hero
#   rotation that kept the light class left it on a previous (lighter)
#   hero's tone while the page followed the new one — a stale light
#   veil over the home bottom (measured band ~(129,129,135) grey
#   against the (93,81,81) page tone). Other tabs never showed it
#   (switchTab re-tints the veil to the current tone on leaving home).
#   With the layer gone the home bottom is the flat page tone like
#   every other tab. The BottomEdgeFadeView class stays (unused) so
#   the 2066 veil-colour pins and the 2069-2071 runner slices keep
#   working; the dock pill (own dark glass) and the hero's own bottom
#   dissolve (updateHeroScrim) are untouched.
# The manifest / edition-versions bump to 2.0.73 / 20074 is the
# parent agent's step AFTER this work, so this runner does NOT pin
# the mobile/tv version — only that lite stays 0.5.3 / 53.
# Also spot-checks that the 2.0.72 items still hold.
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
CLASSES = "/tmp/home2073-classes"
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
         os.path.join(ROOT, "android/tests/Home2073Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.73 sources + Home2073Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2073Checks ----
    p, f = run_jvm_class("Home2073Checks")
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
    cs = read("CatalogScreen.java")

    # ---- Static: (back) the history page back button ----
    check(cs.count("history-back") == 1,
          "(back) the history back button carries the history-back tag (once)")
    check("MobileShell.glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff)" in cs,
          "(back) the button uses the search back button's exact glassBg")
    check("glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff)" in ms,
          "(back) the search back button still uses that same glassBg (the style copied from it)")
    check('a.text("‹", 22, MainActivity.WHITE)' in cs,
          "(back) the glyph is ‹ 22sp white, like the search button")
    check("new LinearLayout.LayoutParams(a.dp(38), a.dp(38))" in cs,
          "(back) the button is 38x38dp, like the search button")
    check("MobileDetailScreen.bindTouchFeedback(historyBackBtn)" in cs,
          "(back) the button has the same press feedback as the search button")
    check("historyBackBtn.setOnClickListener(v -> a.onBackPressed());" in cs,
          "(back) tap pops the Screen exactly like the system back button (never exits the app from here)")
    check("a.body.addView(backRow, 0);" in cs,
          "(back) the button row sits at body index 0, above the eyebrow")
    check(cs.index("history-back") < cs.index("display-only dedupe"),
          "(back) the button is built before the entries load, so it shows even when the history is empty")
    check("historyBackBtn.setFocusable(true)" in cs
          and cs.index("bindTouchFeedback(historyBackBtn)") < cs.index("historyBackBtn.setFocusable(true)"),
          "(back) TV: focusable is set AFTER bindTouchFeedback (which clears it), so the remote can reach it")
    check('renderPage("觀看歷史", "CONTINUE WATCHING");' in cs,
          "(back) the history title + eyebrow are unchanged")
    check('a.addButton(a.body, a.navButton("清空觀看歷史"' in cs,
          "(back) the clear-history pill is untouched")

    # ---- Static: (dock) the dock label is 記錄 ----
    check('private static final String HISTORY_DOCK_LABEL = "記錄";' in ms,
          "(dock) HISTORY_DOCK_LABEL is now 記錄")
    check(ms.count('"觀看記錄"') == 0,
          "(dock) no 觀看記錄 string literal is left in MobileShell")
    check("tabIdx == HISTORY_DOCK_INDEX ? HISTORY_DOCK_LABEL : TAB_NAMES[tabIdx]" in ms,
          "(dock) the dock label still comes from HISTORY_DOCK_LABEL (same use site)")
    check(cs.count("觀看記錄") == 2,
          "(dock) the other 觀看記錄 strings are untouched (CatalogScreen dialog title + TV summary)")
    check('navButton("觀看歷史", "history-open"' in ma,
          "(dock) the settings / welcome 觀看歷史 link is untouched")
    check('renderPage("觀看歷史"' in cs,
          "(dock) the history page title is still 觀看歷史")

    # ---- Static: (haze) the bottom-edge veil layer is gone ----
    check("new BottomEdgeFadeView" not in ms,
          "(haze) the BottomEdgeFadeView is never instantiated any more")
    check("bottomEdgeFade" not in ms,
          "(haze) the bottomEdgeFade field and both setBaseColor call sites are gone")
    check("private static final class BottomEdgeFadeView" in ms,
          "(haze) the class stays (unused) so the 2066 pins and the 2069-2071 runner slices keep working")
    check("buildNavigationBar(false)" in ms and "floatingDock = bottomDock" in ms,
          "(haze) the dock pill itself is untouched (own dark glass, still built and kept)")
    check("HeroDiffusion2063.scrimColors(heroDiffusionTarget)" in ms,
          "(haze) the hero's own bottom dissolve is untouched (the title's only protection)")
    check("bottomFadeColors" in ht and "static int[] bottomFadeColors(int baseRgb)" in ht,
          "(haze) bottomFadeColors stays (2066 pins it; only its caller is gone)")

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
          and "2.0.71" not in hs and "2.0.72" not in hs and "2.0.73" not in hs,
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
        check("2.0.73" not in text,
              "forbidden file untouched: no 2.0.73 marker in " + rel)

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
