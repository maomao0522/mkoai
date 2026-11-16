#!/usr/bin/env python3
# mkoai 2.0.71 checks — real-code JVM checks + static regression checks.
# Runs Home2071Checks (top-veil + bottom-scrim + title-wash geometry on
# the measured POCO F5 numbers), plus static greps for the 2.0.71 items:
# (fog) top veil 250dp -> 150dp (same topFadeColors shape); bottom scrim
#   ramp moves back (five transparent stops, 25% at 5/7, 75% at 6/7,
#   opaque bottom — no seam with the bloom).
# (dock) search joins the dock (DOCK_TAB_INDICES {0,1,2,3,5,4}, no-live
#   {0,1,3,5,4}); watch history joins as pseudo-index 5 (opens
#   historyPage, never switchTab — rail + switchTab guard + no highlight).
# (bar) top bar is the pill only, moved up (+20dp -> +8dp, badge
#   +78dp -> +58dp) and shrunk 5/6 (pill 48dp -> 40dp); the brand
#   is the handwritten rose-gold "mkoai" image (28dp tall, no pill
#   background, no tint). The account button LEFT the top bar for
#   the settings header (see (account)).
# (account) login moved to Settings: the Level 1 header is now a row
#   ("設定" left, a 40dp account button top-right) built by
#   MobileIosSettings.buildAccountButton (user-approved one-time
#   edit of that file) — signed in: email initial circle opening
#   accountSync(); signed out: a "登入" pill starting Google
#   sign-in. The old top-bar button is gone.
# (tone-settings) the live settings page follows the hero tone too:
#   its ScrollView is transparent (a.root shows through) and a
#   MobileShell tree-walk (retintSettingsNow) re-tints every flat
#   settings text by its creation colour on every render and on
#   every diffusion flip. The settings page is flat (no dark glass
#   cards — the 2.0.71 Part F "settings titles" pins only covered
#   dead populateSettingsBody code), so the walk is what makes the
#   live page follow the tone at all.
# (source) the source pill leaves the top bar; the live destination
#   is the Settings tab -> 訂閱與賬號 pane -> '切換當前預設片源'
#   capsule (MobileIosSettings, shows 當前：name, opens
#   chooseSources). The same row in MobileShell.populateSettingsBody
#   is dead code (renderLegacySettingsTabUnused only, no callers).
# Also spot-checks that the 2.0.70 transitions still hold.
# (The 2.0.69/2.0.70 runners still run in the full suite and pin the
# items this version deliberately changed — they were updated in place.)

import glob
import os
import re
import struct
import subprocess
import sys
import zlib

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
CLASSES = "/tmp/home2071-classes"
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


def brand_png_info(path):
    """Decode a PNG's pixels with stdlib only (no PIL on the VM).
    Returns (w, h, color_type, transparent_frac, top_opaque_quantized,
    corners_transparent) or None if the file is not a decodable
    non-interlaced RGBA PNG."""
    d = open(path, "rb").read()
    if d[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    pos, idat, w = 8, b"", None
    h = ct = None
    while pos < len(d):
        ln, typ = struct.unpack(">I4s", d[pos:pos + 8])
        data = d[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, _bd, ct, _c, _f, inter = struct.unpack(">IIBBBBB", data)
            if inter != 0 or ct != 6:
                return None
        elif typ == b"IDAT":
            idat += data
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    out, prev, i = bytearray(), bytearray(stride), 0
    for _y in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        if f == 1:
            for x in range(4, stride): line[x] = (line[x] + line[x - 4]) & 255
        elif f == 2:
            for x in range(stride): line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                b, c = prev[x], prev[x - 4] if x >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line; prev = line
    alphas = out[3::4]
    n = len(alphas)
    tfrac = sum(1 for a in alphas if a == 0) / n
    from collections import Counter
    c = Counter()
    for k in range(0, len(out), 4):
        if out[k + 3] > 200:
            c[(out[k] >> 4 << 4, out[k + 1] >> 4 << 4, out[k + 2] >> 4 << 4)] += 1
    top = c.most_common(1)[0][0] if c else None
    corners = (out[3] == 0 and out[(w - 1) * 4 + 3] == 0
               and out[(h - 1) * stride + 3] == 0
               and out[(h - 1) * stride + (w - 1) * 4 + 3] == 0)
    return (w, h, ct, tfrac, top, corners)


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
         os.path.join(ROOT, "android/tests/Home2071Checks.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "JVM compile of 2.0.71 sources + Home2071Checks")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])
        print(f"\n{TOTAL - len(FAILED)}/{TOTAL} passed; {len(FAILED)} failures: {FAILED}")
        return 1

    # ---- JVM: Home2071Checks ----
    p, f = run_jvm_class("Home2071Checks")
    for name in f:
        FAILED.append(name)
    TOTAL += p

    ms = read("MobileShell.java")
    ma = read("MainActivity.java")
    il = read("ImageLoader.java")
    idp = read("ImageDecodePolicy.java")
    hs = read("HomeScreen.java")
    hd = read("HeroDiffusion2063.java")

    # ---- Static: fog (1) — the top veil is pulled back 250dp -> 150dp ----
    check("statusBarHeight() + a.dp(activeTab == 0 ? 150 : 24)" in ms,
          "(fog-1) home veil is statusBarHeight + 150dp (was 250dp), other tabs stay + 24dp")
    check("statusBarHeight() + a.dp(activeTab == 0 ? 250 : 24)" not in ms,
          "(fog-1) the old 250dp home veil is gone")
    top_fade = ms[ms.index("private static final class TopEdgeFadeView"):]
    top_fade = top_fade[:top_fade.index("private static final class BottomEdgeFadeView")]
    check("topFadeColors(baseRgb)" in top_fade,
          "(fog-1) the root veil still uses topFadeColors (shape unchanged)")
    check("1f / 7" in top_fade and "6f / 7" in top_fade,
          "(fog-1) the veil gradient keeps its 8 evenly spaced stops")

    # ---- Static: fog (2) — the bottom scrim ramp moves back ----
    scrim = hd[hd.index("static int[] scrimColors"):hd.index("static int[] bloomColors")]
    check(scrim.count("0x00000000,") == 5,
          "(fog-2) scrimColors has five transparent stops (was four)")
    check("0x40000000 | rgb" in scrim and "0xc0000000 | rgb" in scrim
          and "0x80000000 | rgb" not in scrim,
          "(fog-2) scrim ramp is 25% at 5/7, 75% at 6/7 (the 50% stop is gone)")
    check("bg\n        };" in scrim,
          "(fog-2) scrim still ends on the opaque page colour (no seam with the bloom)")
    check("HeroDiffusion2063.scrimColors(heroDiffusionTarget)" in ms,
          "(fog-2) the hero card still paints the scrim (still the only in-card fade)")

    # ---- Static: dock — search joins, history joins as a pseudo-item ----
    check("private static final int[] DOCK_TAB_INDICES = {0, 1, 2, 3, 5, 4};" in ms,
          "(dock) DOCK_TAB_INDICES is {0,1,2,3,5,4} (search in, history before settings)")
    check("return hasLiveSources() ? DOCK_TAB_INDICES : new int[] {0, 1, 3, 5, 4};" in ms,
          "(dock) without live sources the dock is {0,1,3,5,4}")
    check("HISTORY_DOCK_INDEX = 5" in ms and 'HISTORY_DOCK_LABEL = "觀看記錄"' in ms,
          "(dock) history is pseudo-index 5 with its own label (TAB_NAMES stays 5 long)")
    check("TAB_NAMES = {\"主頁\", \"搜尋\", \"直播\", \"收藏\", \"設定\"}" in ms,
          "(dock) TAB_NAMES still has only the 5 real tabs")
    check("private final View[] navTabs = new View[6];" in ms
          and "private final TextView[] navLabels = new TextView[6];" in ms
          and "private final NavGlyph[] navIcons = new NavGlyph[6];" in ms,
          "(dock) the nav arrays are size 6 so index 5 has a slot")
    check('"nav-history"' in ms,
          "(dock) the history dock item carries the nav-history tag")
    check("tab.setOnClickListener(v -> openHistoryPage());" in ms,
          "(dock) tapping the history item opens the history page, not switchTab")
    check("private void openHistoryPage()" in ms
          and "a.open(new MainActivity.Screen(a::historyPage), true)" in ms,
          "(dock) openHistoryPage is the old top-circle action (a::historyPage)")
    retint = ms[ms.index("private void retintDockTabs()"):ms.index("private void retintDock()")]
    check("int highlightedDockTab = activeTab;" in retint
          and "activeTab == 1" not in retint,
          "(dock) search highlights itself now (the 'search highlights Home' rule is gone)")
    check("if (index == HISTORY_DOCK_INDEX) {" in ms,
          "(dock) switchTab guards the history pseudo-index (never clamps 5 to Settings 4)")
    check("if (pendingTab == HISTORY_DOCK_INDEX) openHistoryPage();" in ms,
          "(dock) releasing the swipe rail on the history slot opens history, not switchTab")
    check("pendingTab >= 0 ? pendingTab : activeTab" in ms,
          "(dock) the rail tracks the real activeTab while sliding (no more 'search counts as home')")

    # ---- Static: top bar — pill only, moved up + shrunk ----
    hdr = ms[ms.index("private View buildHomeTopHeaderRow()"):ms.index("private GradientDrawable topPillBg")]
    check("nav-search" not in hdr and "nav-more" not in hdr and "nav-history" not in hdr,
          "(bar) search / ... / history circles are gone from the top bar (moved to the dock)")
    check("nav-account" not in hdr and "nav-account-login" not in hdr,
          "(bar) the account button is gone from the top bar (moved to the settings header)")
    check("accountCircle" not in hdr and "loginPill" not in hdr
          and "SupabaseAuth.isSignedIn(a)" not in hdr,
          "(bar) no account-button code is left in buildHomeTopHeaderRow at all")
    check("overlayLp.topMargin = statusBarHeight() + a.dp(8);" in ms,
          "(bar) overlay topMargin is statusBarHeight + 8dp (was + 20dp)")
    check("badgeLp.topMargin = statusBarHeight() + a.dp(58);" in ms,
          "(bar) badge topMargin is statusBarHeight + 58dp (8 + 40 + 10, was + 78dp)")
    check("new LinearLayout.LayoutParams(0, a.dp(40), 1f)" in hdr,
          "(bar) pill height is 40dp (was 48dp)")
    check("R.drawable.brand_mkoai_cursive" in hdr
          and "setImageResource(R.drawable.logo_mark)" not in hdr
          and "brandTitle" not in hdr,
          "(bar) the brand is the cursive image now (logo_mark + brand TextView gone)")
    check("new LinearLayout.LayoutParams(-2, a.dp(28))" in hdr
          and "setAdjustViewBounds(true)" in hdr,
          "(bar) brand image is ~28dp tall, width follows its own ratio (347:132)")
    check("setColorFilter" not in hdr and "setImageTintList" not in hdr,
          "(bar) no tint on the brand image (rose-gold reads on light and dark pages)")
    check("homeTopToneBgs.add(pill)" not in ms and "pill.setBackground" not in ms,
          "(bar) the pill has no background any more (not in the tone-bg list, never painted)")
    brand_path = os.path.join(ROOT, "android/src/main/res/drawable-nodpi/brand_mkoai_cursive.png")
    info = brand_png_info(brand_path) if os.path.exists(brand_path) else None
    check(info is not None and info[0] == 347 and info[1] == 132 and info[2] == 6,
          "(bar) brand drawable exists: 347x132 RGBA PNG (the dancing-rosegold-light file)")
    check(info is not None and info[5] and info[3] > 0.4,
          "(bar) brand drawable has a transparent background (corners alpha 0, >40% fully transparent)")
    check(info is not None and info[4] == (224, 176, 144),
          "(bar) brand drawable is the light rose-gold #E8B39E, not the voided pink #E34B58")

    # ---- Static: source selector — moved into Settings (2.0.71) ----
    check("source-switcher" not in hdr and "siteSlot" not in hdr and "sitePill" not in hdr,
          "(source) the source pill is gone from the top bar (siteSlot + sitePill removed)")
    ios = read("MobileIosSettings.java")
    check('"訂閱與賬號"' in ios
          and ios.index('"訂閱與賬號"') < ios.index('"切換當前預設片源"'),
          "(source) the live Settings tab has the 訂閱與賬號 pane with the source capsule (MobileIosSettings, buildPages)")
    cap = ios[ios.index('"切換當前預設片源"'):ios.index('"切換當前預設片源"') + 200]
    check("a::chooseSources" in cap,
          "(source) the live capsule opens chooseSources")
    check('"當前：" + curSiteName' in cap,
          "(source) the live capsule shows the current source name live")
    check("MobileIosSettings" in hdr and "訂閱與賬號" in hdr,
          "(source) the top-bar comment cites the live MobileIosSettings 訂閱與賬號 pane, not dead populateSettingsBody")
    check("source-switcher" in hs,
          "(source) the TV home still has its own source-switcher pill (untouched)")
    ss = read("SettingsScreen.java")
    data_sec = ss[ss.index("void data() {"):]
    acc_link = data_sec[data_sec.index('"帳號同步"'):data_sec.index("this::accountSync")]
    check('"settings-account-sync"' in acc_link
          and "if (a.isTvDevice())" not in acc_link,
          "(account) the 搜尋與資料 page still has its unconditional 帳號同步 row (a second path to the same page)")
    check("private static View buildAccountButton(final MainActivity a)" in ios,
          "(account) the settings header has a buildAccountButton helper (MobileIosSettings)")
    acc = ios[ios.index("private static View buildAccountButton"):]
    acc = acc[:acc.index("private static View buildSettingsCapsule")]
    check('"settings-account"' in acc and "setTag(\"settings-account\")" in acc,
          "(account) the header button carries the settings-account tag (not the old nav-account tag)")
    check("SupabaseAuth.isSignedIn(a)" in acc
          and "SupabaseAuth.signInWithGoogle(a)" in acc
          and "new SettingsScreen(a).accountSync()" in acc,
          "(account) the header button opens accountSync() when signed in, Google sign-in when signed out")
    check("a.dp(40), a.dp(40)" in acc and 'a.text(initial, 12.5f, MainActivity.WHITE)' in acc,
          "(account) signed-in button is a 40dp circle with the email initial (12.5sp)")
    check('a.text("登入", 11, MainActivity.WHITE)' in acc
          and "setCornerRadius(a.dp(999))" in acc,
          "(account) signed-out button is a 登入 pill (11sp, stadium shape)")
    check("headerRow" in ios and "buildAccountButton(a)" in ios
          and ios.index("buildAccountButton(a)") > ios.index("LEVEL 1: PRIMARY SETTINGS PAGE"),
          "(account) the button sits in the Level 1 header row, top-right next to the 設定 title")
    check(grep_app(".settings()") == 0,
          "(account) MainActivity.settings() is still never called, so SettingsScreen.show() stays unreachable")
    show_sites = [m.start() for m in re.finditer(r"SettingsScreen\(a\)\.show\(\)", ms)]
    check(len(show_sites) == 1
          and show_sites[0] > ms.index("private void populateSettingsBody"),
          "(account) the only SettingsScreen(a).show() call is still inside dead populateSettingsBody")
    check("void accountSync()" in ss,
          "(account) accountSync() still exists (the settings header button's target)")

    # ---- Static: tone follow — every tab follows the hero tone (2.0.71) ----
    ht = read("HomeTone2066.java")
    mds = read("MobileDetailScreen.java")
    check("private void applyPageTone()" in ms,
          "(tone) applyPageTone() exists (page background = heroDiffusionShown)")
    check(ms.count("applyPageTone();") == 2,
          "(tone) applyPageTone runs at the end of renderTabNow AND in the diffusion flip")
    tone_def = ms[ms.index("private void applyPageTone()"):]
    tone_def = tone_def[:tone_def.index("private void renderTabNow")]
    check("currentSite != null ? heroDiffusionShown : 0xff141414" in tone_def
          and "ColorDrawable" in tone_def,
          "(tone) page bg is the hero tone when a source is set, 0xff141414 on the welcome page (guarded by the root's current colour)")
    check("activeTab == 0 ? heroDiffusionShown : HomeRows2061.HOME_SLATE_BG" not in ms,
          "(tone) no dock gate is left on activeTab == 0 (the dock used to be dark off-home)")
    check("HomeTone2066.dockTopRgb(heroDiffusionShown)" in ms
          and ms.count("int tone = heroDiffusionShown;") == 2,
          "(tone) dockBackground / dockLabelColor / dockLensBg all use heroDiffusionShown on every tab")
    check("applyStatusBarIcons(HomeTone2066.isLight(heroDiffusionShown))" in ms
          and "applyStatusBarIcons(false)" not in ms,
          "(tone) status-bar icons follow the tone on every tab (no more forced white off-home)")
    check(ms.count("homeTopFade.setBaseColor(heroDiffusionShown)") == 2
          and "TOP_FADE_FIXED_RGB" not in ms
          and "private int baseRgb = HomeRows2061.HOME_SLATE_BG;" in ms,
          "(tone) the top veil follows the tone on every tab (switchTab + flip; the fixed dark band AND its dark default are gone)")
    check("bottomEdgeFade.setBaseColor(heroDiffusionShown)" in ms
          and "setBaseColor(0x060a08)" not in ms
          and "private int baseRgb = HomeRows2061.HOME_SLATE_BG & 0x00ffffff;" in ms,
          "(tone) the bottom veil follows the tone on every tab (no more fixed dark band or dark default off-home)")
    check("static int accentText(int bg)" in ht
          and "TEXT_ACCENT_LIGHT_BG = 0xff0d47a1" in ht
          and "TEXT_ACCENT_DARK_BG = 0xff5ac8fa" in ht,
          "(tone) HomeTone2066.accentText: sky blue on dark pages, deep blue 0xff0d47a1 on light pages")
    check("HOME_TEXT_ACCENT = 2" in ms,
          "(tone) the text registry has the new HOME_TEXT_ACCENT role")
    check(ms.count("registerHomeText(pageTitle, HOME_TEXT_PRIMARY)") == 2,
          "(tone) my-tab '收藏' title is registered live; the second copy sits in dead populateSettingsBody (the live settings title follows via the walk below, not this pin)")
    check("registerHomeText(t, HOME_TEXT_MUTED)" in ms,
          "(tone) renderCenteredHint follows the tone (my-tab + live empty states)")
    check("registerHomeText(searchStatus, HOME_TEXT_MUTED)" in ms
          and "registerHomeText(hLabel, HOME_TEXT_MUTED)" in ms,
          "(tone) search hint + '歷史搜尋' label follow the tone")
    check("registerHomeText(titleView, HOME_TEXT_PRIMARY)" in ms
          and "registerHomeText(stateView, HOME_TEXT_MUTED)" in ms
          and "registerHomeText(empty, HOME_TEXT_MUTED)" in ms,
          "(tone) live title / status / empty state follow the tone")
    check("registerHomeText(subTitle, HOME_TEXT_PRIMARY)" in ms
          and "registerHomeText(h, HOME_TEXT_ACCENT)" in ms,
          "(tone) the dead populateSettingsBody sub-page title + section headers carry registrations (dead code only; the live settings page follows via the walk below)")
    # ---- Static: tone-settings — the LIVE settings page follows the tone ----
    # (The Part F pins above only covered dead populateSettingsBody code:
    # the live page is MobileIosSettings, flat rows, no registration.)
    check("sv.setBackgroundColor(0x00000000)" in ios
          and "sv.setBackgroundColor(0xff141414)" not in ios,
          "(tone-settings) the settings ScrollView is transparent (a.root's hero tone shows through)")
    check("void retintSettingsNow()" in ms
          and "private void retintSettingsText(int tone)" in ms
          and "private void retintSettingsTree(ViewGroup parent, int tone)" in ms,
          "(tone-settings) MobileShell has the settings tree-walk (retintSettingsNow / retintSettingsText / retintSettingsTree)")
    check("retintSettingsText(heroDiffusionShown);" in ms,
          "(tone-settings) the diffusion flip block re-tints the settings tree while sitting on the settings tab")
    check("shell.retintSettingsNow()" in ios,
          "(tone-settings) every settings render re-tints at once (renderInternal tail — covers switchTab, refresh and in-place re-render)")
    walk = ms[ms.index("private void retintSettingsTree"):]
    walk = walk[:walk.index("Re-tints the Continue Watching")]
    check("0xfff5f5f7" in walk and "0xffffffff" in walk and "0xff111827" in walk,
          "(tone-settings) the walk maps creation white + both tinted primaries to PRIMARY")
    check("0xff9ca3af" in walk and "0xffd1d5db" in walk and "0xffd1d1da" in walk
          and "0xffe5e7eb" in walk and "0xff374151" in walk,
          "(tone-settings) the walk maps all creation greys + tinted muted to MUTED")
    check('"settings-account".equals(v.getTag())' in walk,
          "(tone-settings) the walk skips the account button subtree (white on dark glass)")
    check("0x14ffffff" in walk and "0x14000000" in walk,
          "(tone-settings) hairline dividers flip with the tone too (either shade maps back)")
    check("activeTab != 4" in ms[ms.index("void retintSettingsNow()"):ms.index("void retintSettingsNow()") + 200],
          "(tone-settings) the walk no-ops off the settings tab (other tabs keep their own rules)")
    # ---- Static: tone boundary — deliberately NOT flipped ----
    check("glassBg(0xb81c2421" in ms,
          "(tone-boundary) search list rows keep their dark glass bg (title/sub stay white on it)")
    check(ms.count("glassBg(0xb034343a") == 2,
          "(tone-boundary) the search + settings '‹' back buttons keep their dark glass bg")
    check("a.root.setBackgroundColor(initAmbient)" in mds,
          "(tone-boundary) the detail page keeps its own ambient root (does not follow the home tone)")
    check("root.setBackgroundColor(DARK)" in ma,
          "(tone-boundary) history re-creates its root dark in renderPage (does not follow the home tone)")
    check("renderEmptyWelcome" in ms,
          "(tone-boundary) the no-source welcome page is untouched")

    # ---- Static: 2.0.70 holds (spot checks; the 2070 runner covers them in full) ----
    check("static void fadeInView(" in ms and "DecelerateInterpolator" in ms,
          "2.0.70 holds: the shared fadeInView helper is still there")
    check("HERO_AUTO_GLIDE_MS = 400L" in ms,
          "2.0.70 holds: auto-glide is still 400ms")
    check("heroDiffusionAnimator.setDuration(300)" in ms,
          "2.0.70 holds: diffusion retint is still 300ms")
    check("fadeInView(a, contentContainer, 180, 16, 0)" in ms,
          "2.0.70 holds: tab switch still fades the new tab in")
    check("MobileDetailScreen.bindTouchFeedback(card);" in ms,
          "2.0.70 holds: the hero card still has press feedback")

    # ---- Static: TV untouched, anime filter untouched, DeepL untouched ----
    check("HeroDiffusion2063" not in hs and "heroDiffusion" not in hs
          and "openListTone" not in hs and "fillTmdbListGrid" not in hs
          and "2.0.71" not in hs,
          "TV untouched: no diffusion, hero tone, list tone, list grid or 2.0.71 marker in HomeScreen.java")
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

    # ---- Static: forbidden files carry no 2.0.71 marker ----
    # (MobileIosSettings is NOT in this list for 2.0.71: the user
    # approved a one-time edit of it for the login move + settings
    # tone follow. The other 8 files are still forbidden.)
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.71" not in text,
              "forbidden file untouched: no 2.0.71 marker in " + rel)

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
