#!/usr/bin/env python3
# mkoai 2.0.65 checks — real-code JVM checks + static regression checks.
# Runs Home2065Checks (HeroDiffusion2063.heroBottomFraction /
# bloomColorsAnchored / topFadeColors — the home rest state already
# looks like the scrolled state: the bloom's tone hold is anchored to
# the hero's bottom edge at rest, and the top bar melts into the hero
# through a top fade; bloomColors unchanged; MaterialSwitch2065
# constants — 52x32dp track, 24dp thumb on a 4dp pad, coral ON,
# mid-grey OFF), plus static greps for the phone settings Material
# restyle (Material switch in SettingsScreen.toggle + MobileIosSettings
# .addSwitchRow, radio rows instead of pills in addChoices, bold
# section titles, all settings keys still present, TV branches
# intact), the UpdateScreen phone Material gating, the hero
# rest-state wiring in MobileShell, the anime filter untouched, the
# TV home untouched, the secret audit, and the version anchors.
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2065-classes"
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
         os.path.join(SRC, "MaterialSwitch2065.java"),
         os.path.join(SRC, "SettingsScreen.java"),
         os.path.join(SRC, "UpdateScreen.java"),
         os.path.join(SRC, "MainActivity.java"),
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "MobileShell.java"),
         os.path.join(ROOT, "android/tests/Home2065Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.65 sources + Home2065Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2065Checks ----
    p, f = run_jvm_class("Home2065Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    hd = read("HeroDiffusion2063.java")
    ss = read("SettingsScreen.java")
    mis = read("MobileIosSettings.java")
    us = read("UpdateScreen.java")
    msw = read("MaterialSwitch2065.java")

    # ---- Static: Material switch component ----
    check("TRACK_WIDTH_DP = 52f" in msw and "TRACK_HEIGHT_DP = 32f" in msw
          and "THUMB_DP = 24f" in msw and "THUMB_PAD_DP = 4f" in msw,
          "Material switch: 52x32dp track, 24dp thumb, 4dp pad")
    check("TRACK_ON = 0xffe34b58" in msw and "TRACK_OFF = 0xff55575b" in msw,
          "Material switch: ON is the mkoai coral #E34B58, OFF is solid mid-grey")
    check("animator.setDuration(160)" in msw
          and "DecelerateInterpolator" in msw,
          "Material switch: same 160ms decelerate animation as the iOS switch")
    check("void setChecked(boolean next, boolean animate)" in msw
          and "boolean isChecked()" in msw,
          "Material switch: same public API as IosSwitchView (setChecked / isChecked)")
    check("new Thread" not in msw and "Executor" not in msw,
          "Material switch: no new thread, no executor (draws on the UI thread only)")

    # ---- Static: SettingsScreen phone toggle + section titles ----
    check("MaterialSwitch2065.MaterialSwitchView(a, initial)" in ss,
          "settings: the phone toggle row uses the Material switch")
    check("new LinearLayout.LayoutParams(a.dp(52), a.dp(32))" in ss,
          "settings: the phone toggle switch is 52x32dp")
    check(ss.count("IosSwitchView") == 2,
          "settings: IosSwitchView survives exactly once — the TV card row only "
          "(type + constructor on the one TV line)")
    check(ss.count("row.setBackground(a.bg(0xff222226, 14));") == 2,
          "settings: both TV card rows (link + toggle) keep their dark card background")
    check("void sectionTitle(String title)" in ss
          and "a.text(title, 16, MainActivity.WHITE)" in ss
          and "if (a.isTvDevice()) return;" in ss,
          "settings: sectionTitle is a bold white 16sp title, phone only (no-op on TV)")
    check(ss.count('sectionTitle("') == 14,
          "settings: 14 section titles (6 in data, 5 in playback, 3 in appearance)")
    check('sectionTitle("搜尋")' in ss and 'sectionTitle("配置源")' in ss
          and 'sectionTitle("內容與帳號")' in ss and 'sectionTitle("資料與備份")' in ss
          and 'sectionTitle("分析與推播")' in ss and 'sectionTitle("恢復")' in ss,
          "settings: data() groups are 搜尋 / 配置源 / 內容與帳號 / 資料與備份 / 分析與推播 / 恢復")
    check('sectionTitle("播放器與解碼")' in ss and 'sectionTitle("畫面與速度")' in ss
          and 'sectionTitle("自動播放與跳過")' in ss and 'sectionTitle("字幕與彈幕")' in ss
          and 'sectionTitle("畫面色彩與音效")' in ss,
          "settings: playback() groups are 播放器與解碼 / 畫面與速度 / 自動播放與跳過 / 字幕與彈幕 / 畫面色彩與音效")
    check('sectionTitle("顯示")' in ss and 'sectionTitle("首頁")' in ss
          and 'sectionTitle("動畫與效果")' in ss,
          "settings: appearance() groups are 顯示 / 首頁 / 動畫與效果")

    # ---- Static: every settings key still present (nothing renamed/removed) ----
    settings_keys = [
        "SEARCH_THREADS", "SEARCH_TIMEOUT", "EXPERIMENTAL_CSP",
        "HIDE_NETDISK", "MERGE_SAME_TITLE", "YOUTH_MODE",
        "ANALYTICS_ENABLED", "AUTO_NEXT", "AUTO_DIRECT_PLAY",
        "FILTER_HLS_ADS", "DANMAKU_DEFAULT", "TUNNELED_PLAYBACK",
        "SOFTWARE_AUDIO", "PREFER_AAC", "REDUCED_TRANSPARENCY",
        "STARTUP_ANIMATION", "HOME_AMBIENT", "REDUCED",
        "SHOW_HERO_BANNER", "UI_SCALE_PERCENT", "NAV_SCALE_PERCENT",
        "HOME_COLUMNS", "WALLPAPER_DIM", "VIDEO_OUTPUT",
        "PLAYER_ENGINE", "DECODER_MODE", "TV_PLAYER_STYLE", "ASPECT",
        "BUFFER_SECONDS", "PLAY_SPEED", "REPEAT_MODE", "SKIP_INTRO",
        "SKIP_OUTRO", "SUBTITLE_SIZE", "SUBTITLE_BOTTOM",
        "SUBTITLE_STYLE", "PICTURE_EFFECT", "AUDIO_EFFECT",
        "TV_DOCK_POSITION", "WALLPAPER_ENABLED",
    ]
    missing = [k for k in settings_keys if ("Prefs." + k) not in ss]
    check(not missing,
          "settings: all " + str(len(settings_keys))
          + " Prefs keys still in SettingsScreen.java"
          + ("" if not missing else " (missing: " + ", ".join(missing) + ")"))
    check("for (String key : KEYS) edit.remove(key);" in ss,
          "settings: the reset loop still removes the same KEYS list")

    # ---- Static: MobileIosSettings — pills gone, radio rows in ----
    check("IosSwitchView" not in mis,
          "iOS settings: no IosSwitchView left (the switch is Material everywhere)")
    check("MaterialSwitch2065.MaterialSwitchView(a, initial)" in mis,
          "iOS settings: addSwitchRow uses the Material switch")
    check("new FlowWrapLayout" not in mis,
          "iOS settings: no pill wrap is constructed any more (the class definition stays, unused)")
    check('"✓ ' not in mis,
          "iOS settings: the check-mark pill prefix is gone")
    check("0xffe34b58" in mis and "GradientDrawable.OVAL" in mis
          and '"density-option-" + i' in mis,
          "iOS settings: the radio circle is coral when selected, grey ring when not, tag kept")
    check("a.text(section.title, 16f, 0xfff5f5f7)" in mis,
          "iOS settings: section titles are bold white 16sp (were 13sp grey)")
    check("a.text(label, 14f, 0xffd1d5db)" in mis,
          "iOS settings: choice group labels are bold 14sp light grey")
    check("a.text(title, 16f, 0xfff5f5f7)" in mis,
          "iOS settings: switch row titles are 16sp (were 15sp)")
    check("Prefs.MOBILE_POSTER_DENSITY" in mis
          and '"舒展", "標準", "緊湊"' in mis,
          "iOS settings: the density picker key and its three options are unchanged")
    check("Prefs.QUALITY_PREF" in mis,
          "iOS settings: the quality preference key is unchanged")

    # ---- Static: UpdateScreen phone Material gating ----
    check("if (tv) heroCard.setBackground" in us,
          "update: the hero card background is TV-only (phone is flat)")
    check("if (tv) groupCard.setBackground" in us,
          "update: the settings group card background is TV-only (phone is flat)")
    check(us.count("tv ? 14 : 999") == 3,
          "update: pill radius (999) on phone, 14 on TV — primary bg + both focus radii")
    check("0x55ffffff" in us,
          "update: the phone secondary button is transparent with a faint outline")
    check("cardSurface(0xd81e1e22, 0xe6191920, 24, 0x2effffff)" in us
          and "cardSurface(0xb81e1e22, 0xb8191920, 20, 0x26ffffff)" in us,
          "update: the TV card surfaces are still in the file (gated, not deleted)")

    # ---- Static: home rest-state diffusion ----
    check("HeroDiffusion2063.heroBottomFraction(" in ms,
          "rest state: the carousel computes the hero's bottom-edge fraction at rest")
    check(ms.count("bloomColorsAnchored(") == 2,
          "rest state: both bloom sites (create + re-tint) use the anchored bloom")
    check("heroDiffusionShown, heroDiffusionHoldFraction" in ms,
          "rest state: the bloom is anchored to heroDiffusionHoldFraction")
    check("hero-diffusion-topfade" not in ms
          and "heroTopFades" not in ms,
          "rest state: the 2.0.65 IN-CARD hero top fade is gone in 2.0.66 (the bar is a floating pill now)")
    check("topFadeColors" in ms,
          "rest state: 2.0.69 wires topFadeColors back in as the ROOT-LEVEL top veil (not the in-card fade)")
    check("HOME_TOP_BAR_HEIGHT_DP" not in ms and "HOME_TOP_BAR_HEIGHT_DP" not in hd
          and "HOLD_FRACTION_MIN = 0.40f" in hd
          and "HOLD_FRACTION_MAX = 0.90f" in hd,
          "rest state: the 60dp bar is deleted; the hold is still clamped to 0.40..0.90")
    check("bloomColorsAnchored" in hd and "topFadeColors" in hd
          and "heroBottomFraction" in hd and "heroHeightDp" in hd,
          "rest state: the new helpers live in HeroDiffusion2063")
    check("HeroDiffusion2063.bloomColors(heroDiffusionShown)" not in ms,
          "rest state: the old viewport-fixed bloom call is gone from MobileShell")
    check("bloomColors(heroDiffusionShown)" not in ms,
          "rest state: no viewport-fixed bloom call left anywhere in MobileShell")

    # ---- Static: anime filter + swipe/rotation untouched ----
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")
    check("HeroSwipe2063" in ms,
          "swipe untouched: the carousel still uses HeroSwipe2063")
    check("isAnimeHero" in read("VideoKind2061.java")
          and "filterNonAnimeHero" in read("VideoKind2061.java"),
          "anime filter untouched: VideoKind2061 still carries the hero-only signals")

    # ---- Static: TV home untouched ----
    hs = read("HomeScreen.java")
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "bloomColors" not in hs and "MaterialSwitch2065" not in hs
          and "sectionTitle" not in hs,
          "TV home untouched: no diffusion, bloom, Material switch or section title in HomeScreen.java")

    # ---- Static: no new threads in the changed files ----
    # MobileShell already has one pre-existing thread (the
    # tmdb-availability worker, line ~3419, untouched by 2.0.65 —
    # verified: the 2.0.65 MobileShell diff contains no "new Thread"),
    # so it is pinned at 1 instead of 0.
    for rel in ("MaterialSwitch2065.java", "SettingsScreen.java",
                "MobileIosSettings.java", "UpdateScreen.java",
                "HeroDiffusion2063.java"):
        text = read(rel)
        check("new Thread" not in text,
              "no new thread in " + rel)
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")

    # ---- Static: secret audit (changed files) ----
    for rel in ("MaterialSwitch2065.java", "SettingsScreen.java",
                "MobileIosSettings.java", "UpdateScreen.java",
                "MobileShell.java", "HeroDiffusion2063.java"):
        text = read(rel)
        check("phc_" not in text and "os_v2_" not in text and "client_id=" not in text,
              "secret audit: no key literal in " + rel)

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.69"' in ev and "20070" in ev, "edition-versions.json = 2.0.69 / 20070")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.69"' in man and 'android:versionCode="20070"' in man,
          "AndroidManifest.xml = 2.0.69 / 20070")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
