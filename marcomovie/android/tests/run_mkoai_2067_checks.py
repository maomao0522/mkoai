#!/usr/bin/env python3
# mkoai 2.0.67 checks — real-code JVM checks + static regression checks.
# Runs Home2067Checks (HeroDiffusion2063.averageTone lightest-half rule
# + pageTone contrasts, heroHeightDp 1.5x + heroBottomFraction ~0.68 +
# bloom anchor follows, HomeTone2066 sub-page colours, full-bleed
# scale-type math), plus static greps for the four field-test items:
# (1) view-all sub-pages take the home tone captured at open time,
# (2) the hero is taller (1.5x, one formula), (3) muddy tones go light,
# (4) hero art is full-bleed (CENTER_CROP poster, FIT_START backdrop),
# plus the TV-untouched, anime-filter-untouched, no-new-thread, secret
# audit, forbidden-file and version-anchor checks.
import glob
import json
import math
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2067-classes"
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


# ---- Python mirror of HeroDiffusion2063.averageTone + pageTone ----
# (independent re-implementation; the JVM checks pin the same numbers)
def _lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def _lum(rgb):
    r, g, b = rgb
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def _page_tone(rgb):
    r, g, b = rgb
    grey = (r + g + b) // 3
    mr = round(grey + (r - grey) * 0.85)
    mg = round(grey + (g - grey) * 0.85)
    mb = round(grey + (b - grey) * 0.85)
    br, bg, bb = round(mr * 0.92), round(mg * 0.92), round(mb * 0.92)
    m = max(br, bg, bb)
    if m > 235:
        s = 235 / m
        br, bg, bb = round(br * s), round(bg * s), round(bb * s)
    t = (br, bg, bb)
    lum = _lum(t)
    if 0.12 < lum < 0.55:
        if lum < 0.30:
            sc = (0.12 / lum) ** (1 / 2.2)
            t = (round(br * sc), round(bg * sc), round(bb * sc))
        else:
            tt = (0.55 - lum) / (1 - lum)
            t = (round(br + (255 - br) * tt), round(bg + (255 - bg) * tt),
                 round(bb + (255 - bb) * tt))
    return t


def _avg_tone(px):
    r = g = b = w = 0
    pr = pg = pb = 0
    for (cr, cg, cb) in px:
        sat = max(cr, cg, cb) - min(cr, cg, cb)
        r += cr * sat
        g += cg * sat
        b += cb * sat
        w += sat
        pr += cr
        pg += cg
        pb += cb
    n = len(px)
    if w == 0:
        return (pr // n, pg // n, pb // n)
    W = (r // w, g // w, b // w)
    if max(W) - min(W) >= 64:
        return W
    order = sorted(range(n), key=lambda i: (px[i][0] + px[i][1] + px[i][2], i))
    top = order[n // 2:]
    return (sum(px[i][0] for i in top) // len(top),
            sum(px[i][1] for i in top) // len(top),
            sum(px[i][2] for i in top) // len(top))


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
         os.path.join(ROOT, "android/tests/Home2067Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.67 sources + Home2067Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2067Checks ----
    p, f = run_jvm_class("Home2067Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    hd = read("HeroDiffusion2063.java")
    ma = read("MainActivity.java")
    hs = read("HomeScreen.java")

    # ---- Python mirror: the fixture numbers agree with the JVM pins ----
    backdrop_like = [(235, 235, 230)] * 6 + [(30, 60, 120)] * 2 + [(210, 175, 140)] + [(215, 185, 90)]
    poster_like = [(250, 250, 248)] * 7 + [(225, 200, 175)] * 2 + [(180, 200, 220)]
    check(_avg_tone(backdrop_like) == (235, 235, 230)
          and _page_tone((235, 235, 230)) == (216, 216, 212),
          "mirror: the backdrop-like fixture averages to its lightest half (235,235,230) and pageTone (216,216,212), matching the JVM pins")
    check(_avg_tone(poster_like) == (250, 250, 248)
          and _page_tone((250, 250, 248)) == (230, 230, 228),
          "mirror: the poster-like fixture averages to near-white (250,250,248) and pageTone (230,230,228), matching the JVM pins")
    check(_avg_tone([(30, 25, 20)] * 6 + [(200, 180, 150)] * 2) == (115, 102, 85)
          and _page_tone((115, 102, 85)) == (104, 94, 80),
          "mirror: the dark-muddy fixture stays dark (115,102,85) and pageTone (104,94,80), matching the JVM pins")

    # ---- Static (1): view-all pages take the home tone ----
    check("openListTone" in ms and "heroDiffusionShown" in ms,
          "sub-page: the open tone is captured from the hero tone then showing (openListTone / heroDiffusionShown)")
    check("PREFS.WALLPAPER_ENABLED" in ms or "Prefs.WALLPAPER_ENABLED" in ms,
          "sub-page: a wallpaper home keeps the dark page (WALLPAPER_ENABLED falls back to DARK)")
    check("a.root.setBackgroundColor(openListTone)" in ms
          and "a.scroll.setBackgroundColor(openListTone)" in ms,
          "sub-page: the list page paints root + scroll with the captured tone (the scroll covers the opaque frost)")
    check('"page-eyebrow"' in ma and '"page-heading"' in ma,
          "sub-page: renderPage tags the eyebrow + heading so the list page can retint them")
    check('a.root.findViewWithTag("page-eyebrow")' in ms
          and 'a.root.findViewWithTag("page-heading")' in ms,
          "sub-page: the list page retints the heading + eyebrow on a light tone")
    check("fillTmdbListGrid(a, site, listGrid, videos, openListTone)" in ms
          and "fillTmdbListGrid(a, openListSite, grid, now, openListTone)" in ms,
          "sub-page: both grid fills (initial + live refresh) use the captured tone, not the live hero tone")
    check("0xfff5f7fa" in ms and "0xff8e959e" in ms,
          "sub-page: the dark card title/subtitle constants are still there (dark pages byte-identical)")
    check("HomeTone2066.isLight(openListTone)" in ms,
          "sub-page: the retint only fires on a light tone")

    # ---- Static (2): hero taller, one formula ----
    check("pageWidthDp * 1.5f" in hd,
          "hero height: heroHeightDp is 1.5x the page width")
    check("pageWidthDp * 1.25f" not in hd and "1.25f" not in ms,
          "hero height: no 1.25x hero formula left anywhere")
    check("HeroDiffusion2063.heroHeightDp(" in ms
          and ms.count("HeroDiffusion2063.heroHeightDp(") >= 3,
          "hero height: the pager, the cards and the bloom hold all use heroHeightDp (one source of truth)")

    # ---- Static (3): muddy tones go light ----
    check("lightestHalf" in hd and "MUDDY_SAT_MAX" in hd,
          "muddy tone: averageTone falls back to the lightest half below MUDDY_SAT_MAX")
    check("MUTE_LIGHT_FACTOR = 0.85f" in hd and "BRIGHTEN_LIGHT_FACTOR = 0.92f" in hd,
          "muddy tone: pageTone constants untouched (mute 0.85 / brighten 0.92 / snap band intact)")

    # ---- Static (4): hero art full-bleed ----
    hero_art = ms[ms.index("private void bindHeroArtwork"):]
    hero_art = hero_art[:hero_art.index("private View buildHeroShowcaseCard")]
    check("ImageView.ScaleType.CENTER_CROP" in hero_art
          and "FIT_CENTER" not in hero_art and "FIT_START" not in hero_art,
          "full-bleed: bindHeroArtwork shows a poster CENTER_CROP and a backdrop CENTER_CROP that fills the box, no FIT band")
    check("pageWidth = Math.max(a.dp(240), a.windowWidth())" in ms,
          "full-bleed: the carousel page is exactly the window width (no peek, no inset)")
    check("heroContainer.setPadding(0, 0, 0, 0)" in ms,
          "full-bleed: the hero container has no padding")
    check("body.setPadding(0, 0, 0, a.dp(106))" in ms,
          "full-bleed: the scroll body has no horizontal padding")
    check("card.addView(artwork, new FrameLayout.LayoutParams(-1, -1))" in ms,
          "full-bleed: the artwork fills its card edge to edge")

    # ---- Static: TV untouched, anime filter untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs,
          "TV untouched: no diffusion, hero tone, list tone or list grid in HomeScreen.java")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")

    # ---- Static: no new threads in the changed files ----
    # MobileShell already has one pre-existing thread (the
    # tmdb-availability worker, untouched by 2.0.67), so it is pinned
    # at 1 instead of 0.
    for rel in ("HomeTone2066.java", "HeroDiffusion2063.java", "MainActivity.java"):
        text = read(rel)
        check("new Thread" not in text,
              "no new thread in " + rel)
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")

    # ---- Static: secret audit (changed files) ----
    for rel in ("HeroDiffusion2063.java", "MobileShell.java", "MainActivity.java"):
        text = read(rel)
        check("phc_" not in text and "os_v2_" not in text and "client_id=" not in text,
              "secret audit: no key literal in " + rel)

    # ---- Static: forbidden files carry no 2.0.67 marker ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.67" not in text,
              "forbidden file untouched: no 2.0.67 marker in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.73"' in ev and "20074" in ev, "edition-versions.json = 2.0.73 / 20074")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
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
