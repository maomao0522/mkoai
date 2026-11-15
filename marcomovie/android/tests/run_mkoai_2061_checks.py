#!/usr/bin/env python3
# mkoai 2.0.61 checks — real-code JVM checks + static regression checks.
# Runs HomeRows2061Checks (all 12 items: QR login on the signed-in phone
# account page, phone home restyle, TV dialog focus, permission-return,
# APK auto-delete, TV auto-load-more, two-level row order, recommendations
# filter, merged-home default, card type labels, ad-rule source priority,
# aspect modes 上下填滿 / 左右填滿),
# plus static greps for the new features, the 2.0.58/2.0.59/2.0.60 markers,
# and the version anchors.
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2061-classes"
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


def method_body(src, start_marker):
    """Text from start_marker to the next method-level 'private'/'public'/'static' def."""
    i = src.find(start_marker)
    if i < 0:
        return ""
    j = src.find("\n    private ", i + 1)
    k = src.find("\n    static ", i + 1)
    ends = [x for x in (j, k) if x > 0]
    return src[i:min(ends)] if ends else src[i:]


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
         os.path.join(SRC, "SettingsScreen.java"),
         os.path.join(SRC, "AppDialog.java"),
         os.path.join(SRC, "UpdateScreen.java"),
         os.path.join(SRC, "UpdateManager.java"),
         os.path.join(SRC, "HomeScreen.java"),
         os.path.join(SRC, "HomeRows2061.java"),
         os.path.join(SRC, "VideoKind2061.java"),
         os.path.join(SRC, "SourcePreferences.java"),
         os.path.join(SRC, "Cms.java"),
         os.path.join(SRC, "Prefs.java"),
         os.path.join(SRC, "DoubanHome.java"),
         os.path.join(SRC, "SimklHome.java"),
         os.path.join(SRC, "TmdbClient.java"),
         os.path.join(SRC, "MobileShell.java"),
         os.path.join(SRC, "MainActivity.java"),
         os.path.join(SRC, "SourceController.java"),
         os.path.join(SRC, "AwwratedHome.java"),
         os.path.join(SRC, "PlaybackControls.java"),
         os.path.join(SRC, "MobileIosSettings.java"),
         os.path.join(ROOT, "android/tests/HomeRows2061Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.61 sources + HomeRows2061Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: HomeRows2061Checks ----
    p, f = run_jvm_class("HomeRows2061Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    # ---- Static: (a) QR login on the signed-in phone account page ----
    ss = read("SettingsScreen.java")
    check("accountSyncLinkKeys" in ss, "(a) SettingsScreen has accountSyncLinkKeys")
    check("sync-qr-login-phone" in ss, "(a) the phone QR link key exists")
    check("掃碼登入電視" in ss and "掃電視的 QR 碼，讓電視登入手機同一個帳號" in ss,
          "(a) the phone QR button is labelled 掃碼登入電視 with its subtitle")
    check("PhoneLoginDialog" in ss, "(a) the QR button opens the PhoneLoginDialog scanner")
    check("for (String key : accountSyncLinkKeys(signedIn, a.isTvDevice()))" in ss,
          "(a) accountSync() renders its links from accountSyncLinkKeys")

    # ---- Static: (b) phone home restyle ----
    ms = read("MobileShell.java")
    check("HomeRows2061.HOME_SLATE_BG" in ms, "(b) phone home uses the muted blue-grey background")
    check("继续观看" in ms and "home-continue-watching" in ms,
          "(b) the 继续观看 row is rendered as its own container")
    check("查看全部 >" in ms, "(b) rows show 查看全部 >")
    check("a.text(\"▶ 播放\"" not in ms, "(b) the hero has no ▶ 播放 button any more")
    check("featured.description" in ms, "(b) the hero shows a description")
    check("Dots" in ms or "dot" in ms.lower(), "(b) the hero keeps its carousel dots")
    check("nav-more" in ms, "(b) the header has the ... menu button")
    hr = read("HomeRows2061.java")
    check("热门电影" in hr and "热门剧集" in hr and "豆瓣高分" in hr,
          "(b) Simplified row labels are present")
    hs = read("HomeScreen.java")
    check("HOME_SLATE_BG" not in hs, "(b) the TV home does not take the phone slate background")

    # ---- Static: (c) TV dialog focus ----
    ad = read("AppDialog.java")
    check("tvFocusTarget" in ad and "firstFocusable" in ad,
          "(c) AppDialog has the tvFocusTarget + firstFocusable helpers")
    check("focusPositiveButton" in ad, "(c) Builder has the focusPositiveButton opt-in")
    check("tvFocusTarget(" in ad and "focusPositive" in ad,
          "(c) the TV focus block uses tvFocusTarget with the opt-in")
    us = read("UpdateScreen.java")
    check(us.count("focusPositiveButton()") >= 2,
          "(c) both install dialogs opt into positive focus")

    # ---- Static: (d) return from the permission settings ----
    check("PERMISSION_RETURN_KEY" in us and "resumeAfterPermission" in us,
          "(d) UpdateScreen has the permission-return flag + resume hook")
    check("ACTION_MANAGE_UNKNOWN_APP_SOURCES" in us,
          "(d) install() still opens the system permission page")
    check(us.find("PERMISSION_RETURN_KEY") < us.find("ACTION_MANAGE_UNKNOWN_APP_SOURCES"),
          "(d) the return flag is set before the settings intent is launched")
    check("a.ui.post" in us and "showInstallConfirm" in us,
          "(d) resume re-opens the update screen (posted) and offers install when granted")
    check("canRequestPackageInstalls" in us,
          "(d) resume re-checks the install permission on return")
    ma = read("MainActivity.java")
    check("resumeAfterPermission" in ma and "onResume" in ma,
          "(d) MainActivity.onResume calls the permission-return hook")

    # ---- Static: (e) APK auto-delete after a successful install ----
    check("installSucceeded" in us and "deleteDownloadedApk" in us,
          "(e) UpdateScreen deletes the APK only on a confirmed install")
    check("已安裝新版" in us and "已返回應用" in us,
          "(e) install result is reported in both cases")
    um = read("UpdateManager.java")
    check("deleteDownloadedApk" in um and "shouldDeleteAfterUpgrade" in um,
          "(e) UpdateManager has the delete + upgrade-decision helpers")
    check("shouldDeleteAfterUpgrade(" in um and "installedCode" in um,
          "(e) the constructor deletes the APK via shouldDeleteAfterUpgrade")
    body = method_body(um, "void deleteDownloadedApk()")
    check("release.part" not in body and "partial_sha" not in body,
          "(e) the post-install delete never touches the partial file")
    check("download_sha" in body, "(e) the post-install delete clears the download sha")

    # ---- Static: (f) TV auto-load-more ----
    check("onLoadMore" in hs and "autoLoadMore" in hs and "shouldAutoLoadMore" in hs,
          "(f) HomeScreen has onLoadMore + autoLoadMore + shouldAutoLoadMore")
    check("lastAutoLoadKey" in hs, "(f) auto-load remembers the last bottom it fired for")
    check("pill(label, tag, action, focusHook)" in hs or "focusHook" in hs,
          "(f) pill() has the focus-hook overload (the outline listener is kept)")

    # ---- Static: (h) recommendations filter wiring ----
    vk = read("VideoKind2061.java")
    check("isRecommendation" in vk and "filterRecommendations" in vk,
          "(h) VideoKind2061 has the recommendations filter")
    check("kindOf" in vk and "isExcludedCategory" in vk,
          "(h) the classifier has kindOf + the variety/documentary/kids exclusion")
    check("composeHomeVideos" in hs and "filterRecommendations" in hs,
          "(h) the TV 推薦 grid is filtered in composeHomeVideos")
    check("filterRecommendations" in ms, "(h) the phone recommendation sections are filtered")
    check(len(re.findall(r"filterRecommendations\s*\(\s*currentVideos\s*\)", ms)) >= 3,
          "(h) the phone hero is filtered at all three build sites")

    # ---- Static: (i) merged home is the default ----
    check("tv_merged_default_migrated" in ma,
          "(i) MainActivity.home() has the one-time merged-default migration")
    check("remove(Prefs.SELECTED_SITE)" in ma,
          "(i) the migration clears a pre-2.0.61 pinned source once")
    check(ma.find("isTvDevice") < ma.find("tv_merged_default_migrated"),
          "(i) the migration is TV-only (after the phone early-return)")
    check("allSourcesSite" in ma, "(i) home() still defaults to the all-sources home")
    sp_picker = read("SourcePicker.java")
    check("putString(Prefs.SELECTED_SITE, site.key)" in sp_picker,
          "(i) picking one source in the picker still pins it")
    check("selectAllSources" in sp_picker and "remove(Prefs.SELECTED_SITE)" in sp_picker,
          "(i) 全部片源 still returns to the merged view")

    # ---- Static: (j) card type labels (2.0.66: bottom-left, no pill) ----
    check("addTypeBadge" not in vk and "badgeLabel" in vk,
          "(j) VideoKind2061 keeps badgeLabel; addTypeBadge is deleted (2.0.66)")
    check("電影" in vk and "劇" in vk, "(j) the badge labels are 電影 / 劇")
    check("addPosterOverlay(a, art, v.remarks, null, VideoKind2061.badgeLabel(v))" in ms,
          "(j) the phone home cards pass the type label into the bottom overlay row")
    check("VideoKind2061.badgeLabel(video)" in hs,
          "(j) the TV home cards pass the type label into the bottom overlay row")

    # ---- Static: (k) ad-rule sources are the top preference ----
    spref = read("SourcePreferences.java")
    check("PRIORITY_SOURCE_HOSTS" in spref, "(k) the priority list is host-based now")
    check("PRIORITY_SOURCE_APIS" not in spref,
          "(k) the old exact-URL priority list is gone")
    hosts_body = spref.split("PRIORITY_SOURCE_HOSTS")[1].split("};")[0]
    check(hosts_body.find("api.juliang.live") < hosts_body.find("360zyzz.com")
          < hosts_body.find("mdzyapi.com") < hosts_body.find("caiji.xgzyapi.com"),
          "(k) priority order is 巨量, 360, 魔都動漫, xgzy")
    check("PRIORITY_SOURCE_APIS" not in read("AwwratedHome.java"),
          "(k) AwwratedHome no longer uses the old list")
    check("PRIORITY_SOURCE_APIS" not in ms,
          "(k) MobileShell no longer uses the old list")
    check("firstPriorityApi" in spref, "(k) the first-priority api comes from the config")
    check("priorityIndex" in hs, "(k) the merged dedup ranks copies by priority")
    check("v.sourceApi = target.api" in hs,
          "(k) merged-home videos are tagged with their source for the dedup")
    cms = read("Cms.java")
    check("builtinHlsAdRules" in cms, "(k) the built-in ad rules are untouched")
    check("api.juliang.live" in cms and "admedia.mujiziyuan99.com" in cms,
          "(k) ad rule intact: juliang -> admedia host")
    check("360zyzz.com" in cms and "vod.360zyx.vip" in cms,
          "(k) ad rule intact: 360zyzz -> 360zyx host")
    check("mdzyapi.com" in cms and "play.modujx17.com" in cms,
          "(k) ad rule intact: mdzyapi.com -> play.modujx17.com host")
    check("typeName" in cms, "(h)(j) Cms.Video carries the category (typeName)")

    # ---- Static: (l) aspect modes 上下填滿 / 左右填滿 ----
    pc = read("PlaybackControls.java")
    check("上下填滿" in pc and "左右填滿" in pc,
          "(l) the player 畫面比例 menu has 上下填滿 + 左右填滿")
    check(pc.find("裁切填充") < pc.find("上下填滿") < pc.find("左右填滿"),
          "(l) the two new modes are appended after 裁切填充 (indices 5, 6)")
    check("surfaceSize" in pc, "(l) PlaybackControls has the pure surfaceSize math")
    check("surfaceSize(" in pc and "resize" in pc,
          "(l) resize() uses surfaceSize (live path = tested math)")
    pr = read("Prefs.java")
    check("ranges.put(ASPECT, new int[] {0, 6})" in pr,
          "(l) Prefs ASPECT range is 0-6")
    mis = read("MobileIosSettings.java")
    check("預設畫面比例" in mis and "上下填滿" in mis and "左右填滿" in mis,
          "(l) the 預設畫面比例 setting offers the two new modes")
    check("Math.min(6, aspect)" in mis,
          "(l) the default-aspect clamp covers the new modes")
    check("上下填滿" in ss and "左右填滿" in ss,
          "(l) the SettingsScreen 畫面比例 choice offers the two new modes")
    check("new int[] {0, 1, 2, 3, 4, 5, 6}" in ss,
          "(l) the SettingsScreen aspect values cover indices 0-6")

    # ---- Static: 2.0.58 markers still present ----
    check("composeHomeVideos" in hs, "2.0.58 marker: composeHomeVideos")
    dh = read("DoubanHome.java")
    check("fetchNew" in dh, "2.0.58 marker: DoubanHome.fetchNew")
    tt = read("TmdbClient.java")
    check("translateTitle" in tt, "2.0.58 marker: TmdbClient.translateTitle")
    up = read("UpdatePolicy.java")
    check("drive.usercontent" in up, "2.0.58 marker: updater accepts drive.usercontent confirm form")

    # ---- Static: 2.0.59 markers still present ----
    hf = read("HlsAdFilter.java")
    check("class HlsAdFilter" in hf, "2.0.59 marker: HlsAdFilter")
    af = read("AdAwareHlsPlaylistParserFactory.java")
    check("AdAwareHlsPlaylistParserFactory" in af, "2.0.59 marker: AdAwareHlsPlaylistParserFactory")
    pr = read("Prefs.java")
    check("FILTER_HLS_ADS" in pr, "2.0.59 marker: filter_hls_ads pref")

    # ---- Static: 2.0.60 markers still present ----
    check("ALL_SOURCES_KEY" in hs and "loadAllSources" in hs,
          "2.0.60 marker: TV all-sources home")
    check("AdMarks" in read("AdMarks.java"), "2.0.60 marker: AdMarks")
    check("skip-ad" in read("PlaybackControls.java"), "2.0.60 marker: player ad-skip buttons")
    bundled_path = os.path.join(ROOT, "android/src/main/assets/default-config.json")
    check(os.path.exists(bundled_path), "2.0.60 marker: bundled default-config.json exists")
    if os.path.exists(bundled_path):
        with open(bundled_path, encoding="utf-8") as f:
            bundled = json.load(f)
        check(len(bundled.get("sites", [])) == 19, "2.0.60 marker: bundled default has 19 sites")

    # ---- Version ----
    ev = open(os.path.join(ROOT, "android/edition-versions.json"), encoding="utf-8").read()
    check('"2.0.68"' in ev and "20069" in ev, "edition-versions.json = 2.0.68 / 20069")
    lite = json.loads(ev).get("android-lite", {})
    check(lite.get("versionName") == "0.5.3" and lite.get("versionCode") == 53,
          "edition-versions.json: lite stays 0.5.3 / 53")
    man = open(os.path.join(ROOT, "android/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    check('android:versionName="2.0.68"' in man and 'android:versionCode="20069"' in man,
          "AndroidManifest.xml = 2.0.68 / 20069")

    print(f"\n{TOTAL - len(FAILED)}/{TOTAL} checks passed")
    if FAILED:
        print("FAILURES:")
        for f in FAILED:
            print(" - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
