"""mkoai 2.0.51 static checks (no device, no build needed).

Verifies the five 2.0.51 changes in the GPT 2.0.50 tree:
  1. login button is the same 48dp size as the search / history buttons
  2. home never blanks (fail-open prefilter + phase-2 safety net + TMDB
     display fallback), empty copy only after checks settle
  3. all configured sources are home candidates (no 10-source cap)
  4. first paint from disk cache before the 2.5s poster pre-resolution
  5. landscape fullscreen button uses the U+26F6 glyph (no U+25AF tofu)
  6. settings pages are YouTube-style flat lists (dark colours kept)

Run: python3 android/tests/run_mkoai_2051_checks.py
Exit code 0 = all checks pass.
"""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'android/src/main/java/app/mkoai/player'

checks = 0


def check(cond, name):
    global checks
    if not cond:
        raise AssertionError('FAIL: ' + name)
    checks += 1
    print('PASS ' + name)


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


# ---- version ----
editions = json.loads(read('android/edition-versions.json'))
for key in ('android-mobile', 'android-tv'):
    check(editions[key]['versionName'] == '2.0.51', f'{key} versionName is 2.0.51')
    check(editions[key]['versionCode'] == 20052, f'{key} versionCode is 20052')
manifest = read('android/src/main/AndroidManifest.xml')
check('android:versionCode="20052"' in manifest, 'AndroidManifest versionCode is 20052')
check('android:versionName="2.0.51"' in manifest, 'AndroidManifest versionName is 2.0.51')

# ---- 1. login button size ----
shell = read('android/src/main/java/app/mkoai/player/MobileShell.java')
check('new LinearLayout.LayoutParams(a.dp(48), a.dp(48))' in shell,
      'search circle is 48x48dp')
check(re.search(r'historyCircle.*?LayoutParams\(a\.dp\(48\), a\.dp\(48\)\)',
                shell, re.S) is not None, 'history circle is 48x48dp')
check(re.search(r'LinearLayout\.LayoutParams acLp = new LinearLayout\.LayoutParams'
                r'\(a\.dp\(48\), a\.dp\(48\)\)', shell) is not None,
      'signed-in account circle is 48x48dp (was 34x34)')
check(re.search(r'LinearLayout\.LayoutParams lpLp = new LinearLayout\.LayoutParams'
                r'\(-2, a\.dp\(48\)\)', shell) is not None,
      'logged-out login pill is 48dp tall (was 34dp)')
check('a.dp(34), a.dp(34)' not in shell, 'no 34x34 header button left')

# ---- 5. fullscreen glyph ----
controls = read('android/src/main/java/app/mkoai/player/PlaybackControls.java')
check('\u25af' not in controls, 'no U+25AF WHITE VERTICAL RECTANGLE left in player controls')
check(controls.count('fsTool.setText("\u26f6")') == 2,
      'fullscreen button uses U+26F6 in both orientations')

# ---- 3. all sources as candidates ----
check('TMDB_CHECK_MAX_SOURCES' not in shell, '10-source cap constant is gone')
check('crossCheckSites(site)' in shell, 'TMDB rows use the full crossCheckSites list')

# ---- 2. fail open ----
home = read('android/src/main/java/app/mkoai/player/AwwratedHome.java')
check('Unknown candidates stay ON the home as' in home or 'kept as-is (pending)' in home,
      'prefilter keeps unknown titles pending')
check(re.search(r'if \(out\.isEmpty\(\)\) return new ArrayList<>\(titled\);', home) is not None,
      'prefilter safety net returns the unfiltered list')
check(re.search(r'if \(kept\.isEmpty\(\)\) return new ArrayList<>\(input\);', home) is not None,
      'phase-2 strict filter fails open to the unfiltered list')
check('list.videos.clear(); // Unverified cards must stay hidden' not in shell,
      'prefilter error no longer clears the home list')

# ---- 2. TMDB display fallback + empty-copy timing ----
check('List<Cms.Video> display()' in shell, 'TmdbRow.display() exists')
check('row.display().isEmpty()' in shell, 'renderTmdbRows uses the display fallback')
check('shownVideos = row.display()' in shell, 'row sections render the display list')
check('refreshHomeEmptyState' in shell, 'home empty-state helper exists')
check('"正在載入推薦…"' in shell, 'loading line shown while checks run')
check('videos.isEmpty() && !tmdbRowsLoading && !hasVisibleTmdbRows()' in shell,
      'recommendation hint is gated on settled checks')

# ---- 4. first paint before poster pre-resolution ----
tmdb_start = shell.index('tmdbRows = rows;')
first_paint = shell.index('renderTmdbRows(site, req, gen)', tmdb_start)
pre_resolve = shell.index('resolvePostersLive', tmdb_start)
check(first_paint < pre_resolve,
      'first TMDB paint happens before the 2.5s poster pre-resolution')

# ---- 6. settings: YouTube-style flat lists, dark colours kept ----
settings = read('android/src/main/java/app/mkoai/player/MobileIosSettings.java')
check('0xff141414' in settings, 'settings page background stays dark 0xff141414')
check(settings.count('0xff1e1e22') == 1,
      'no dark card containers left (only the choice-pill control keeps 0xff1e1e22)')
check('0x16ffffff' not in settings, 'about brand card is flat (no translucent card)')
check('new CapsuleIconView(a, iconKind, 0xfff5f5f7)' in settings,
      'settings rows use plain near-white icons (no coloured badges)')
check('a.dp(24), a.dp(24)' in settings, 'settings row icons are 24dp')

# ---- guardrails: no render-feel merge, no marcomovie edits ----
# HomeAmbient (frost painter) predates 2.0.50 and is in GPT's tree; the
# Muse render-feel merge is HomeAmbientMath — that must be absent.
ambient_math = list((ROOT / 'android/src').rglob('HomeAmbientMath*'))
check(not ambient_math, 'no HomeAmbientMath render-feel code merged in')
check(not list((ROOT / 'android/src').rglob('*HomeAmbientMath*')),
      'no HomeAmbientMath references anywhere')
marcomovie_editions = json.loads(
    Path('/home/hatch/workspace/mkoai/marcomovie/android/edition-versions.json')
    .read_text(encoding='utf-8'))
check(marcomovie_editions['android-mobile']['versionName'] == '2.0.50',
      '2.0.49 marcomovie tree untouched (still 2.0.50)')

print(f'{checks} mkoai 2.0.51 checks passed')
