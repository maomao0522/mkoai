#!/usr/bin/env python3
# mkoai 2.0.65 checks — real-code JVM checks + static regression checks.
# Runs Home2064Checks (HeroDiffusion2063.bloomColors — the page
# background is a vertical bloom from the hero tone down into a deeper
# pass of the same hue; topBarTone / fadeColors unchanged; the hero-only
# anime signals in VideoKind2061.isAnimeHero / filterNonAnimeHero —
# type-id resolution against the source's cached category list plus the
# last-resort title fallback — with isRecommendation / badgeLabel
# proven untouched; Cms.Video.typeId), plus static greps for the
# phone-home wiring in MobileShell (the bloom drawable on the
# ScrollView, the hero-only filter at the single choke point with the
# site-key-guarded category map, the five poster cards with the glass
# stroke gone and a faint shadow instead, card sizes and spacing
# untouched), the TV home untouched, the settings files untouched, the
# secret audit, and the version anchors.
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2064-classes"
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
         os.path.join(ROOT, "android/tests/Home2064Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.65 sources + Home2064Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2064Checks ----
    p, f = run_jvm_class("Home2064Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    hd = read("HeroDiffusion2063.java")
    vk = read("VideoKind2061.java")
    cms = read("Cms.java")

    # ---- Static: bloom wiring (phone home only) ----
    check("heroBloomGradient" in ms
          and "homeScroll.setBackground(heroBloomGradient)" in ms,
          "bloom: the ScrollView background is the one bloom drawable")
    check("heroBloomGradient.setColors(HeroDiffusion2063.bloomColorsAnchored(" in ms,
          "bloom: every animation frame re-tints the same drawable, never re-creates it "
          "(mkoai 2.0.65: anchored to the hero's bottom edge)")
    check("MUTE_FACTOR = 0.70f" in hd and "DARKEN_FACTOR = 0.65f" in hd
          and "MAX_BG_CHANNEL = 140" in hd,
          "bloom math: mute 70% / darken 65% / clamp 140 (the clamp stays the contrast guard)")
    check("SCRIM_STOPS = 8" in hd and "BLOOM_STOPS = 6" in hd
          and "SCRIM_TINT" not in hd,
          "bloom math: 8-stop scrim, 6-stop bloom, no black tint")
    check("topBarTone" not in ms and "home-top-pill" in ms,
          "top bar: the flat band is gone — a floating pill over the hero (2.0.66), topBarTone deleted")
    check("homeTopFade.setBaseColor(heroDiffusionShown)" in ms,
          "top fade: still follows the shown tone on the home tab")
    check("heroDiffusionShown" in ms and "heroDiffusionTarget" in ms,
          "bloom: shown vs target colours still tracked separately (no jump mid-animation)")

    # ---- Static: hero anime signals (phone home only) ----
    check("isAnimeHero" in vk and "filterNonAnimeHero" in vk,
          "anime signals: isAnimeHero + filterNonAnimeHero live in VideoKind2061")
    check('"漫剧"' in vk and '"漫画", "漫畫"' in vk and '"卡通"' in vk,
          "anime signals: the live-found words (漫剧 / 漫画 / 漫畫 / 卡通) are in the list")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime signals: the carousel filters at the single choke point, hero-only")
    check("heroTypeNames(Cms.Site site)" in ms
          and "site.key.equals(cachedCategorySiteKey)" in ms,
          "anime signals: the type-id map comes from the cached categories for this site only")
    check("if (heroList.isEmpty()) heroList = candidates;" in ms,
          "anime signals: fail-open — an all-anime list keeps the original heroes")
    check("v.typeId = j.optInt(\"type_id\", 0)" in cms,
          "anime signals: the JSON parse keeps the numeric type id")
    check("xmlTagValue(block, \"tid\")" in cms and "parseTypeId" in cms,
          "anime signals: the XML parse keeps the numeric type id (<tid> / <type_id>)")
    check("public int typeId;" in cms,
          "anime signals: Video carries the type id (0 = unknown)")
    rec_start = vk.find("static boolean isRecommendation")
    rec_end = vk.find("static List<Cms.Video> filterRecommendations", rec_start)
    badge_start = vk.find("static String badgeLabel")
    badge_end = vk.find("static boolean isRecommendation", badge_start)
    check(rec_start > 0 and rec_end > rec_start
          and "typeId" not in vk[rec_start:rec_end]
          and badge_start > 0 and badge_end > badge_start
          and "typeId" not in vk[badge_start:badge_end],
          "anime signals: isRecommendation and badgeLabel never read the type id (hero-only proof)")
    check("for (Cms.Category c : currentCategories)" in ms,
          "anime signals: the type-id map is resolved from the cached categories")

    # ---- Static: poster cards — hard border gone, sizes untouched ----
    check("a.dp(0.6f), 0x26ffffff" not in ms,
          "cards: no 0.6dp glass stroke left on any phone-home poster card")
    check(ms.count("art.setElevation(a.dp(2))") == 5,
          "cards: all five poster arts get the faint 2dp shadow")
    check(ms.count("a.dp(12), 0, 0") == 5,
          "cards: all five poster arts keep the rounded dark placeholder, stroke zero")
    check("lp.setMargins(a.dp(3), a.dp(3), a.dp(6), a.dp(12));" in ms,
          "cards: the grid card margins are (3,3,6,12) (2.0.68 denser view-all grid)")
    check("card.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(8));" in ms,
          "cards: the grid card padding is (3,3,3,8) (2.0.68 denser view-all grid)")

    # ---- Static: TV home untouched ----
    hs = read("HomeScreen.java")
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "bloomColors" not in hs,
          "TV home untouched: no diffusion or bloom reference in HomeScreen.java")

    # ---- Static: settings files untouched ----
    for rel in ("SettingsScreen.java", "MobileIosSettings.java", "UpdateScreen.java"):
        text = read(rel)
        check("HeroDiffusion2063" not in text and "bloomColors" not in text
              and "isAnimeHero" not in text,
              "settings untouched: no 2.0.65 diffusion or hero code in " + rel)

    # ---- Static: secret audit (changed files) ----
    for rel in ("HeroDiffusion2063.java", "VideoKind2061.java", "Cms.java", "MobileShell.java"):
        text = read(rel)
        check("phc_" not in text and "os_v2_" not in text and "client_id=" not in text,
              "secret audit: no key literal in " + rel)

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
