#!/usr/bin/env python3
# mkoai 2.0.74 checks — real-code JVM checks + static regression checks.
# Runs Home2074Checks (the delete-source width bug, the fallback rule,
# and the delete / delete-all persistence round-trip), plus static greps
# for the 2.0.74 items:
# (bug) deleting the CURRENT source (user field video, 2.0.73, POCO F5,
#   2026-11-17 ~14:43) left the picker broken — every per-row dot and
#   ✕, the ✓, the bottom 管理線路 / 更新配置 / 完成, the top-right
#   預設偏好源 all gone — and the page underneath popped back from
#   訂閱與賬號 to the Settings main page. Root cause, two parts:
#   (a) edit() ended with back.clear() + open(home) + show(a): open(home)
#       replaced a.root and switchTab(4) reset Settings to its root, and
#   (b) show(a) then ran while the new root was never laid out, so
#       a.root.getWidth() was 0 and the trailing setLayout passed a
#       NEGATIVE width (0 - marginH) — the window came back wider than
#       the screen (healthy x 57-301, broken x 50-323 in the same
#       360-wide frames) and the weight-1 columns pushed every right-side
#       control off-screen. Fix: edit() no longer navigates — it refreshes
#       the Settings page in place (so the 當前： capsule follows the new
#       current source) and just re-shows the picker — and the trailing
#       setLayout reads the stable a.windowWidth().
# (delete-all) the picker gains a one-click delete-all (phone only):
#   a fourth footer button 刪除全部 (red), confirm first with a title
#   that states it deletes every source and leaves 0, then one commit
#   clears all at once (SELECTED_SITE / LOADING_SITE cleared, picker
#   shows 0 個片源, home shows its empty welcome, 更新配置 can import
#   again). Delete-all RESETS the profile for that origin (order and
#   removed both empty), so re-importing the SAME config brings every
#   source back — 全刪等於清空重來 (user decision 2026-11-17). Single
#   deletes keep hiding their source on re-import (2.0.38 rule).
#   TV has no such picker, so the button is not added there.
# (0 sources) deleting down to 0 works: the picker shows 0 個片源, the
#   home empty state (currentSite null -> renderEmptyWelcome) holds, and
#   nothing points at a deleted source.
# The manifest / edition-versions bump to 2.0.74 / 20075 is the
# parent agent's step AFTER this work, so this runner does NOT pin
# the mobile/tv version — only that lite stays 0.5.3 / 53.
# Also spot-checks that the 2.0.73 items still hold.
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
CLASSES = "/tmp/home2074-classes"
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
         os.path.join(SRC, "SourcePicker.java"),
         os.path.join(ROOT, "android/tests/Home2074Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.74 sources + Home2074Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2074Checks ----
    p, f = run_jvm_class("Home2074Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    spk = read("SourcePicker.java")
    ms = read("MobileShell.java")
    ma = read("MainActivity.java")
    hs = read("HomeScreen.java")
    cs = read("CatalogScreen.java")
    ht = read("HomeTone2066.java")

    # edit() is the method between its signature and show(); the select
    # paths (selectSource / selectAllSources) live after show() and DO
    # still navigate home, so the no-navigation pin must be scoped here.
    edit_body = spk.split(
        "private static void edit(MainActivity a, Cms.Config config, Cms.Site site, int target) {"
    )[1].split("static void show(MainActivity a) {")[0]

    # ---- Static: (bug) edit() no longer navigates away ----
    check("a.back.clear();" not in edit_body,
          "(bug) edit() no longer clears the back stack (the page underneath stays)")
    check("a.open(new MainActivity.Screen(a::home)" not in edit_body,
          "(bug) edit() no longer opens home (no root replacement, no Settings reset)")
    check("refreshSettingsInPlace(a);" in edit_body,
          "(bug) edit() refreshes the Settings page in place after the edit")
    check("\n            refreshSettingsInPlace(a);\n            show(a);" in edit_body,
          "(bug) edit() ends by re-showing the picker on the same page")
    check("a.current.mobileHome.refreshSettingsAfterSourceEdit();" in spk,
          "(bug) the in-place refresh goes through the current shell (the MobileIosSettings:84 precedent)")
    check("void refreshSettingsAfterSourceEdit() {" in ms,
          "(bug) MobileShell has refreshSettingsAfterSourceEdit")
    check("resolveActiveSite();" in ms.split("void refreshSettingsAfterSourceEdit()")[1].split("}")[0],
          "(bug) refreshSettingsAfterSourceEdit re-resolves the active site first (fallback wins)")
    check("refreshSettingsPage(0);" in ms.split("void refreshSettingsAfterSourceEdit()")[1].split("}")[0],
          "(bug) refreshSettingsAfterSourceEdit re-renders Settings in place with no slide")
    check("if (activeTab == 4 && contentContainer != null)" in ms,
          "(bug) the in-place refresh is a no-op unless on the Settings tab")

    # ---- Static: (bug) the trailing setLayout can never go negative ----
    check("a.root.getWidth() - marginH" not in spk,
          "(bug) the buggy trailing width (root.getWidth() - marginH) is gone")
    check("window.setLayout(Math.min(a.dp(720), a.windowWidth() - marginH), -2);" in spk,
          "(bug) the trailing setLayout now reads the stable windowWidth()")
    check("window.setLayout(panelWidth, -2);" in spk,
          "(bug) the first setLayout (panelWidth from windowWidth) is unchanged")
    check("int windowWidth() {" in ma and "usableWidth > 0 ? usableWidth" in ma,
          "(bug) windowWidth() is the stable last-measured width (or display width)")

    # ---- Static: (bug) the picker still builds every control ----
    check('"預設偏好源"' in spk and '"source-preferred"' in spk,
          "(bug) the 預設偏好源 button is still built")
    check('a.text("✕", 14, 0xff9ca3af)' in spk,
          "(bug) the per-row ✕ delete button is still built")
    check('current ? "✓" : "›"' in spk,
          "(bug) the per-row ✓ / › mark is still built")
    check('"管理線路"' in spk and '"source-manage"' in spk,
          "(bug) the footer 管理線路 button is still built")
    check('"完成", "source-close"' in spk,
          "(bug) the footer 完成 button is still built")
    check("confirmDeleteSource(a, dialog, site)" in spk,
          "(bug) the ✕ still goes through the confirm dialog")

    # ---- Static: (bug) selecting a source still navigates home (no regression) ----
    check(spk.count("a.back.clear();") == 2,
          "(select) only selectSource + selectAllSources still clear back (edit no longer does)")
    check(spk.count("a.open(new MainActivity.Screen(a::home), false);") == 2,
          "(select) only selectSource + selectAllSources still open home")
    check('a.prefs\n                .edit()\n                .putString(Prefs.SELECTED_SITE, site.key)' in spk
          or "putString(Prefs.SELECTED_SITE, site.key)" in spk,
          "(select) selectSource still pins SELECTED_SITE / LOADING_SITE")
    check("選擇後返回首頁，顯示該片源的內容" in spk,
          "(select) the picker subtitle is unchanged (selecting still returns home)")

    # ---- Static: (delete-all) the footer button + confirm + clear ----
    check('"刪除全部"' in spk and '"source-delete-all"' in spk,
          "(delete-all) the footer has a 刪除全部 button (tag source-delete-all)")
    check("deleteAllBtn.setTextColor(0xffef4444);" in spk,
          "(delete-all) the button is red (destructive)")
    footer_gap = spk.split('"完成", "source-close"')[1].split('"刪除全部"')[0]
    check("if (!tv) {" in footer_gap,
          "(delete-all) the button is added only on phone (TV has no such picker)")
    check('setTitle("刪除全部片源？")' in spk,
          "(delete-all) the confirm title states it deletes all sources")
    check("0 個片源" in spk.split('setTitle("刪除全部片源？")')[1].split(".show();")[0],
          "(delete-all) the confirm message states 0 sources will be left")
    check('setPositiveButton("全部刪除"' in spk,
          "(delete-all) the confirm positive button is 全部刪除")
    check("deleteAllSources(a, config)" in spk,
          "(delete-all) confirming calls deleteAllSources once")
    delete_all_body = spk.split("private static void deleteAllSources(")[1].split(
        "\n    static void showSavedConfigs")[0]
    check("editor.remove(Prefs.SELECTED_SITE);" in delete_all_body
          and "editor.remove(Prefs.LOADING_SITE);" in delete_all_body,
          "(delete-all) SELECTED_SITE / LOADING_SITE are cleared (nothing points at a deleted source)")
    check("config.sites.clear();" in delete_all_body,
          "(delete-all) all sites are cleared at once")
    check("refreshSettingsInPlace(a);" in delete_all_body
          and "show(a);" in delete_all_body,
          "(delete-all) the picker re-shows on the same page after clearing")
    check(delete_all_body.count("editor.commit()") == 1,
          "(delete-all) exactly one commit clears all (not one per source)")
    check("profileAfterDeleteAll(saved)" in delete_all_body,
          "(delete-all) the profile is reset via profileAfterDeleteAll (not profileAfterDelete)")
    check('saved.put("removed", new org.json.JSONArray());' in spk
          and 'saved.put("order", new org.json.JSONArray());' in spk,
          "(delete-all) the reset empties order AND removed (same config re-imports whole)")
    check('"目前沒有片源可以刪除"' in spk,
          "(delete-all) tapping it with 0 sources is a harmless notice, not a crash")

    # ---- Static: (0 sources) the home empty state still holds ----
    check("if (a.config == null || currentSite == null) {" in ms
          and "renderEmptyWelcome();" in ms,
          "(empty) the home still shows its empty welcome with 0 sources (no crash)")
    check('a.text(a.config.sites.size() + " 個片源"' in spk,
          "(empty) the picker count follows the list size (0 個片源 when empty)")
    check("if (config == null || config.sites.isEmpty()) {" in spk,
          "(empty) manage() still guards the 0-source case with a notice")

    # ---- Static: the extracted pure helpers are the real code paths ----
    check("static Cms.Site pickFallback(" in spk,
          "(helpers) pickFallback is the real fallback (edit calls it)")
    check("pickFallback(sites, preferredKeys, availableKeys)" in spk,
          "(helpers) edit() calls pickFallback with the real preferred/available sets")
    check("static org.json.JSONObject profileAfterDelete(" in spk,
          "(helpers) profileAfterDelete is the real profile writer (edit calls it for single deletes / reorders)")
    check("static org.json.JSONObject profileAfterDeleteAll(" in spk,
          "(helpers) profileAfterDeleteAll is the real reset writer (deleteAllSources calls it)")
    check("static java.util.List<Cms.Site> applyProfileToSites(" in spk,
          "(helpers) applyProfileToSites is the real profile reader (applyEdits calls it)")
    check("applyProfileToSites(new java.util.ArrayList<>(config.sites), order, removed)" in spk,
          "(helpers) applyEdits filters through applyProfileToSites (same algorithm)")

    # ---- Static: 2.0.73 holds (spot checks; the 2073 runner covers them in full) ----
    check(cs.count("history-back") == 1,
          "2.0.73 holds: the history back button tag is still there once")
    check('private static final String HISTORY_DOCK_LABEL = "記錄";' in ms,
          "2.0.73 holds: the dock label is still 記錄")
    check("new BottomEdgeFadeView" not in ms,
          "2.0.73 holds: the bottom-edge veil layer is still gone")
    check("if (activeTab == 0) return 0;" in ms,
          "2.0.73 holds: the home top veil is still 0")
    check("private static final int[] DOCK_TAB_INDICES = {0, 1, 2, 3, 5, 4};" in ms,
          "2.0.71 holds: the dock order is still {0,1,2,3,5,4}")
    check("static void fadeInView(" in ms and "DecelerateInterpolator" in ms,
          "2.0.70 holds: the shared fadeInView helper is still there")
    check("heroDiffusionAnimator.setDuration(300)" in ms,
          "2.0.70 holds: diffusion retint is still 300ms")

    # ---- Static: TV untouched, anime filter untouched, DeepL untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs
          and "2.0.71" not in hs and "2.0.72" not in hs and "2.0.73" not in hs
          and "2.0.74" not in hs,
          "TV untouched: no diffusion, hero tone, list tone, list grid, delete-all or 2.0.71-2.0.74 marker in HomeScreen.java")
    check("刪除全部" not in hs and "source-delete-all" not in hs,
          "TV untouched: the delete-all button is not in the TV home")
    check("VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site))" in ms,
          "anime filter untouched: the carousel still filters at the single choke point")
    check("deepl_quota_exhausted" in read("QuotaState2066.java")
          and os.path.exists(os.path.join(SRC, "TranslateClient.java")),
          "DeepL untouched: deepl_quota_exhausted + TranslateClient still there (removal paused)")

    # ---- Static: no new threads ----
    check(ms.count("new Thread") == 1,
          "no new thread in MobileShell.java (the one is the pre-existing tmdb-availability worker)")
    check(ma.count("new Thread") == 0,
          "no new thread in MainActivity either")
    check(spk.count("new Thread") == 0,
          "no new thread in SourcePicker.java")

    # ---- Static: secret audit (whole app tree) ----
    check(grep_app("phc_") == 0 and grep_app("os_v2_") == 0
          and grep_app("client_id=") == 0,
          "secret audit: no key literal in the app tree")

    # ---- Static: forbidden files carry no 2.0.74 marker ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.74" not in text,
              "forbidden file untouched: no 2.0.74 marker in " + rel)

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
