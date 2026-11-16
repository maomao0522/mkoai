#!/usr/bin/env python3
# mkoai 2.0.70 checks — real-code JVM checks + static regression checks.
# Runs Home2070Checks (the poster decode cap: non-detail 800K -> 2M
# pixels, so the 780x1170 hero decodes full-size instead of 390x585),
# plus static greps for the smoothness items:
# (C) transitions — the shared fadeInView helper (200ms, Decelerate,
#     GPU properties only, reduced skips to the final state); open()
#     fades the new page in (200ms, 24dp rise), renderReturn fades back
#     (180ms); switchTab defers the rebuild to the next frame
#     (post + guards) and fades the new tab in (180ms, 16dp); resume
#     fades the retained root (150ms); search top bar + results fade
#     in; the first 3 home rows stagger in (150ms, 12dp, 30ms); the
#     hero showcase card gets bindTouchFeedback (the instant alpha
#     0.72 press feedback); the drawer picker keeps its 165ms slide-in.
# (D) smoothness — auto-glide 900ms -> 400ms (the forbidden
#     HeroSwipe2063 constant is untouched, the call site passes its
#     own duration) + tone sampling waits the glide out; diffusion
#     retint 600ms -> 300ms; bloom colors + veil shaders are cached
#     and only rebuilt when the tone/height changes; the second
#     full-screen frost layer (HomeAmbient) is gone, the canvas paints
#     the identical frost; LOADING_SITE commit -> apply (x3, the crash
#     paths stay commit); pull-refresh MOVE setText only when the text
#     changes; the view-all grid fills 2 rows per frame like the TMDB
#     rows; resume rekindles only posters not yet decoded; the
#     wallpaper decode runs on the artwork pool (downsampled to
#     <=1920px) instead of the UI thread; posters are re-requested at
#     the real layout width and a late small decode never clobbers a
#     sharp one. D7 (loosen resume()) was checked and SKIPPED: homeReady
#     is only set after the home load, resuming a never-loaded home
#     would leave it blank.
# Plus the 2.0.69 holds, TV-untouched, anime-filter-untouched,
# DeepL-untouched, secret audit, forbidden-file and version-anchor
# checks.
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2070-classes"
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


def read_tree(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
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
         os.path.join(ROOT, "android/tests/Home2070Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.70 sources + Home2070Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2070Checks ----
    p, f = run_jvm_class("Home2070Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    ma = read("MainActivity.java")
    il = read("ImageLoader.java")
    idp = read("ImageDecodePolicy.java")
    hs = read("HomeScreen.java")

    # ---- Static (C9): the shared fade helper ----
    helper = ms[ms.index("static void fadeInView("):ms.index("static void bindCardTouchFeedback(")]
    check("DecelerateInterpolator" in helper and "setDuration(durationMs)" in helper
          and "setStartDelay(startDelayMs)" in helper and "withLayer()" in helper,
          "(C9) fadeInView: DecelerateInterpolator, duration + start delay, withLayer (GPU properties only)")
    check("a.reduced" in helper,
          "(C9) fadeInView: reduced (forced on TV) skips straight to the final state")
    check("setAlpha(0f)" in helper and "setTranslationY" in helper
          and ".alpha(1f)" in helper and "translationY(0f)" in helper,
          "(C9) fadeInView: starts at alpha 0 with the slide offset, animates to alpha 1 / offset 0")

    # ---- Static (C1): detail open / return fades ----
    check("MobileShell.fadeInView(this, root, 200, 24, 0)" in ma,
          "(C1) open() fades the new page in (200ms, 24dp rise)")
    check("MobileShell.fadeInView(this, root, 180, 0, 0)" in ma,
          "(C1) renderReturn() fades back in (180ms, no slide)")
    check("s.render.run();" in ma,
          "(C1) open() still renders synchronously first — the fade masks the pop-in, it does not defer the page")

    # ---- Static (C2): tab switch defers the rebuild, then fades in ----
    sw = ms[ms.index("void switchTab(int index)"):ms.index("private void renderTabNow()")]
    check("a.ui.post(" in sw and "renderTabNow();" in sw,
          "(C2) switchTab defers the tab build to the next frame via a.ui.post")
    check("if (req != homeRequest) return;" in sw
          and "a.current.mobileHome != this" in sw
          and "a.root != retainedRoot" in sw,
          "(C2) the deferred build is guarded (stale request / replaced home / replaced root drop out)")
    check("fadeInView(a, contentContainer, 180, 16, 0)" in ms,
          "(C2) the new tab fades in (180ms, 16dp rise) after renderTabNow")
    check("so no animation is needed here" not in ms,
          "(C2) the old 'no animation is needed here' comment is gone")

    # ---- Static (C3/C4/C5/C6): search, resume, rows, results ----
    check("fadeInView(a, topBar, 180, 12, 0)" in ms,
          "(C3) search top bar fades in (180ms, 12dp rise)")
    check("fadeInView(a, retainedRoot, 150, 0, 0)" in ms,
          "(C4) resume() fades the retained root (150ms)")
    check("if (i < 3) fadeInView(a, section, 150, 12, i * 30L);" in ms,
          "(C5) the first 3 home rows stagger in (150ms, 12dp, 30ms apart)")
    check("fadeInView(a, resultList, 150, 0, 0)" in ms and "resultsBefore" in ms,
          "(C6) search results fade in (150ms) only when the list was empty and now has results")

    # ---- Static (C7/C8): drawer kept, hero press feedback ----
    check("setDuration(165)" in ms,
          "(C7) the drawer picker keeps its 165ms Decelerate slide-in (untouched)")
    card = ms[ms.index("private View buildHeroShowcaseCard"):ms.index("private void applyHeroDiffusion")]
    check("MobileDetailScreen.bindTouchFeedback(card);" in card,
          "(C8) the hero showcase card gets bindTouchFeedback (instant alpha 0.72 on press)")

    # ---- Static (D2): carousel glide + diffusion retint ----
    check("HERO_AUTO_GLIDE_MS = 400L" in ms
          and "autoAnimator.setDuration(HERO_AUTO_GLIDE_MS)" in ms,
          "(D2) auto-glide is 400ms (was 900ms) via MobileShell's own constant")
    check("setDuration(HeroSwipe2063.AUTO_TRANSITION_MS)" not in ms,
          "(D2) the call site no longer uses the forbidden file's 900ms constant")
    check("heroAutoGliding ? 450L : 500L" in ms,
          "(D2) tone sampling waits the glide out (450ms while gliding, 500ms otherwise)")
    check("heroDiffusionAnimator.setDuration(300)" in ms,
          "(D2) diffusion retint is 300ms (was 600ms)")
    check("setDuration(600)" not in ms,
          "(D2) no 600ms diffusion duration remains")

    # ---- Static (D3): bloom + veil caches ----
    check("lastBloomTone" in ms and "lastBloomScroll" in ms
          and "heroDiffusionShown != lastBloomTone" in ms,
          "(D3) bloom colors are cached: setColors + setBackground only when the tone or the ScrollView changes")
    top_fade = ms[ms.index("private static final class TopEdgeFadeView"):]
    top_fade = top_fade[:top_fade.index("private static final class BottomEdgeFadeView")]
    bot_fade = ms[ms.index("private static final class BottomEdgeFadeView"):]
    check("cachedShader" in top_fade and "cachedBaseRgb" in top_fade and "cachedH" in top_fade,
          "(D3) TopEdgeFadeView caches its LinearGradient (rebuilt only when the base colour or height changes)")
    check("cachedShader" in bot_fade and "cachedBaseRgb" in bot_fade and "cachedH" in bot_fade,
          "(D3) BottomEdgeFadeView caches its LinearGradient the same way")

    # ---- Static (D4): frost single layer ----
    check("new HomeAmbient(a)" not in ms and "homeAmbientView = null;" in ms,
          "(D4) the second full-screen frost layer is gone (homeAmbientView stays null)")
    check("FILTER_BITMAP_FLAG" in ms,
          "(D4) the canvas keeps painting the identical frost, now with FILTER_BITMAP_FLAG like HomeAmbient")
    check(os.path.exists(os.path.join(SRC, "HomeAmbient.java")),
          "(D4) HomeAmbient stays for MainActivity + TV (only the duplicate layer is removed)")

    # ---- Static (D5): posters — layout-time re-request + no clobber ----
    check("addOnLayoutChangeListener" in il and "removeOnLayoutChangeListener(this)" in il
          and "poster(a, addr, image, det)" in il,
          "(D5) poster() re-requests once laid out, at the real width (one-shot layout listener)")
    check("curBm.getWidth() >= bitmap.getWidth()" in il
          and "curBm.getHeight() >= bitmap.getHeight()" in il,
          "(D5) a late small decode never clobbers a sharper bitmap already showing")
    check("shownBm != null && !shownBm.isRecycled()" in ms,
          "(D5) resume() rekindles only posters that are not already showing a decoded bitmap")
    check("long maxPixels = 2L * 1024 * 1024;" in idp and "800L * 1024" not in idp,
          "(D5) decode cap is 2M pixels for non-detail too (was 800K)")

    # ---- Static (D6): main-thread relief ----
    check(ms.count(".commit()") == 0,
          "(D6) MobileShell has no commit() left (both LOADING_SITE writes are apply())")
    check("putString(Prefs.LOADING_SITE, siteSnapshot.key).apply()" in ms
          and 'putString(Prefs.LOADING_SITE, "").apply()' in ms,
          "(D6) both MobileShell LOADING_SITE writes are apply()")
    check(ma.count(".commit()") == 2
          and 'putString(Prefs.LOADING_SITE, "").apply()' in ma,
          "(D6) MainActivity LOADING_SITE is apply(); only the 2 crash-path commit()s remain")
    check("String badgeText =" in ms
          and "badgeText.contentEquals(pullRefreshBadge.getText())" in ms,
          "(D6) pull-refresh MOVE setText only when the text actually changes")
    check("fillTmdbListGridChunk(" in ms and "tmdbGridFillEpoch" in ms
          and "Math.min(start + 8, videos.size())" in ms
          and "if (!listGrid.isAttachedToWindow()) return;" in ms
          and "listGrid.postOnAnimation(" in ms,
          "(D6) the view-all grid fills 2 rows (8 cards) per frame via postOnAnimation, with epoch/gen/attach guards")

    # ---- Static (D8): wallpaper off the UI thread ----
    canvas = ms[ms.index("private static final class AmbientCanvasView"):]
    canvas = canvas[:canvas.index("private static final class TopEdgeFadeView")]
    check("RequestScope.submit(" in canvas and "decodeWallpaper(" in canvas
          and "wallpaperDecoding" in canvas,
          "(D8) wallpaper decodes on the artwork pool (existing pool, no new thread)")
    check("private static Bitmap decodeWallpaper(File wpFile)" in ms,
          "(D8) wallpaper decodes downsampled to at most 1920px on the long side")
    check("BitmapFactory.decodeFile(wpFile.getAbsolutePath())" not in ms,
          "(D8) the synchronous full-size UI-thread decode is gone")

    # ---- Static: 2.0.69 items still hold ----
    check("overlayLp.topMargin = statusBarHeight() + a.dp(8);" in ms,
          "2.0.71 moved: top bar is back at statusBarHeight + 8dp (was + 20dp in 2.0.69)")
    check("badgeLp.topMargin = statusBarHeight() + a.dp(58);" in ms,
          "2.0.71 moved: badge is back at statusBarHeight + 58dp (was + 78dp in 2.0.69)")
    check("statusBarHeight() + a.dp(activeTab == 0 ? 150 : 24)" in ms,
          "2.0.71 moved: home veil pulled back to 150dp (was 250dp) / short other-tab veil (24dp)")
    check("topFadeColors(baseRgb)" in top_fade,
          "2.0.69 holds: the root veil still uses topFadeColors")
    check("hero-diffusion-topfade" not in ms and "heroTopFades" not in ms,
          "2.0.69 holds: the in-card hero top fade stays gone")
    check("topBarTone" not in ms,
          "2.0.69 holds: no solid dark top band is back")
    check(grep_app("SubtitleClient") == 0 and grep_app("/api/subtitles") == 0
          and grep_app("搜尋字幕") == 0,
          "2.0.68 holds: subtitles still fully removed")
    check(grep_app("tmdb-list-count") == 0 and grep_app("僅顯示來源裡有的片") == 0,
          "2.0.68 holds: view-all count line still deleted")
    grid = ms[ms.index("private void fillTmdbListGrid"):]
    grid = grid[:grid.index("private void followTopSectionPoster")]
    check("i % 4 == 0" in grid and "a.dp(140)" in grid,
          "2.0.68 holds: view-all grid still 4 columns, 140dp art")
    check(os.path.exists(os.path.join(SRC, "SourceSpeed2068.java"))
          and "takeWarmed(" in read("AutoSourceFallback.java")
          and "AutoSourceFallback.stopWarm()" in read("PlaybackController.java"),
          "2.0.68 holds: Maoki source selection (warm / takeWarmed / race-stop) still wired")

    # ---- Static: TV untouched, anime filter untouched, DeepL untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs
          and "2.0.70" not in hs,
          "TV untouched: no diffusion, hero tone, list tone, list grid or 2.0.70 marker in HomeScreen.java")
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

    # ---- Static: forbidden files carry no 2.0.70 marker ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.70" not in text,
              "forbidden file untouched: no 2.0.70 marker in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.71"' in ev and "20072" in ev, "edition-versions.json = 2.0.71 / 20072")
    import json as _json
    lite = _json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.71"' in man and 'android:versionCode="20072"' in man,
          "AndroidManifest.xml = 2.0.71 / 20072")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
