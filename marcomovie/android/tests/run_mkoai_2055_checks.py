"""mkoai 2.0.55 static + JVM checks (no device, no build needed).

Verifies the two play-flow fixes on the phone detail page:
  1. no false 「無法播放」 dialog (issue 1): the dialog only appears
     after a fresh cross-source check genuinely completes with zero
     available sources; while the check runs, the page shows
     「正在檢查片源…」 and never shows the dialog.
  2. a play tap opens the player immediately (issue 2): the already-shown
     source (current site/detail, preferred line = the line on screen)
     is the first candidate; the fresh check no longer blocks the tap.
     A dead first candidate falls back through the player's own
     line/source hop (PlaybackController, untouched).
  3. 2.0.54 regression: QR login, push, TV lite, TV banner untouched.

Run: python3 android/tests/run_mkoai_2055_checks.py
Exit code 0 = all checks pass.
"""
from pathlib import Path
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'android/src/main/java/app/mkoai/player'
JDK = Path('/home/hatch/workspace/jdk21')
ORGJSON = ROOT / 'android/tests/lib/json-20240303.jar'

checks = 0


def check(cond, name):
    global checks
    if not cond:
        raise AssertionError('FAIL: ' + name)
    checks += 1
    print('PASS ' + name)


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


# ---- Java unit test (episode-matching gate, real Cms + WatchHistory) ----
classes = Path('/tmp/playflow2055-classes')
classes.mkdir(exist_ok=True)
files = [
    ROOT / 'shared/java/com/pilipala/source/Warehouse.java',
    SRC / 'RequestScope.java',
    SRC / 'Cms.java',
    SRC / 'WatchHistory.java',
    SRC / 'ChineseConvert.java',
    ROOT / 'android/tests/PlayFlow2055Checks.java',
]
subprocess.run(
    [str(JDK / 'bin/javac'), '-encoding', 'UTF-8', '-cp', str(ORGJSON),
     '-d', str(classes), *[str(p) for p in files]],
    check=True)
run = subprocess.run(
    [str(JDK / 'bin/java'), '-cp', str(classes) + ':' + str(ORGJSON),
     'app.mkoai.player.PlayFlow2055Checks'],
    check=True, text=True, encoding='utf8', capture_output=True)
print(run.stdout, end='')
checks += int(re.search(r'(\d+) PlayFlow2055 checks passed', run.stdout).group(1))

# ---- version ----
editions = json.loads(read('android/edition-versions.json'))
for key in ('android-mobile', 'android-tv'):
    check(editions[key]['versionName'] == '2.0.55', f'{key} versionName is 2.0.55')
    check(editions[key]['versionCode'] == 20056, f'{key} versionCode is 20056')
check(editions['android-lite']['versionName'] == '0.5.3'
      and editions['android-lite']['versionCode'] == 53,
      'android-lite untouched (still 0.5.3 / 53)')
manifest = read('android/src/main/AndroidManifest.xml')
check('android:versionCode="20056"' in manifest, 'AndroidManifest versionCode is 20056')
check('android:versionName="2.0.55"' in manifest, 'AndroidManifest versionName is 2.0.55')

# ---- the real detailHasEpisode body (the gate's match rule) ----
fallback = read('android/src/main/java/app/mkoai/player/AutoSourceFallback.java')
m = re.search(r'static boolean detailHasEpisode\(Cms\.Video detail, String wantedEpisode\) \{(.*?)\n    \}',
              fallback, re.S)
check(m is not None, 'detailHasEpisode exists in AutoSourceFallback')
body = m.group(1)
check('WatchHistory.normEpisode(ep.name).equals(wantNorm)' in body,
      'detailHasEpisode matches by normalized episode name')
check('ep.name.trim().equals(wantTrim)' in body,
      'detailHasEpisode also matches by exact trimmed name')
check('wantedEpisode == null || wantedEpisode.trim().isEmpty()' in body,
      'detailHasEpisode: blank wanted episode always matches')
check('AutoSourceFallback.find' not in body and 'showUnplayable' not in body,
      'detailHasEpisode is pure (no find, no dialog)')

# ---- MobileDetailScreen: immediate play, dialog only after a completed check ----
detail = read('android/src/main/java/app/mkoai/player/MobileDetailScreen.java')
check('private static void playWithFreshSource(\n            MainActivity a,\n            Cms.Site site,\n            Cms.Video current,'
      in detail, 'playWithFreshSource takes the current site + detail')
check('private static void playWithFreshSourceAuto(\n            MainActivity a,\n            Cms.Site site,\n            Cms.Video current,'
      in detail, 'playWithFreshSourceAuto takes the current site + detail')
check('private static void startFreshSourceCheck(' in detail,
      'startFreshSourceCheck exists (the only place that runs find on a play press)')
check('AutoSourceFallback.detailHasEpisode(current, wantName)' in detail,
      'auto path gates immediate play on detailHasEpisode')
check('new AutoSourceFallback.Playable(site, current, current)' in detail,
      'first candidate is the shown source (site + current detail)')
check(detail.count('AutoSourceFallback.find(') == 4,
      'find appears 4 times: 3 pre-existing detail-open paths + startFreshSourceCheck')
idx_check = detail.index('private static void startFreshSourceCheck(')
idx_unplay = detail.index('private static void showUnplayable(', idx_check)
check_body = detail[idx_check:idx_unplay]
check(check_body.count('AutoSourceFallback.find(') == 1,
      'startFreshSourceCheck runs find exactly once (the only find on a play press)')
check(check_body.count('showUnplayable(a, title)') == 2,
      'startFreshSourceCheck shows the dialog only on its two completion branches')
check('a.toast("正在重新選擇片源…")' not in detail,
      'the blocking "正在重新選擇片源…" toast is gone')
check('a.toast("正在檢查片源…")' in detail,
      'fresh check shows "正在檢查片源…" while it runs')
# showUnplayable must only be reachable after the check completes:
# from startFreshSourceCheck (find callback) or from the OFF-mode picker
# (a user-picked playable that lacks the episode). It must NOT be called
# on the immediate-play path.
check('showUnplayable(a, title)' in detail, 'showUnplayable still exists')
check(detail.count('showUnplayable(a, title)') == 3,
      'showUnplayable has exactly 3 call sites (picker + fresh-check null + fresh-check episode miss)')
idx_auto = detail.index('private static void playWithFreshSourceAuto(')
idx_check = detail.index('private static void startFreshSourceCheck(')
auto_block = detail[idx_auto:idx_check]
check('showUnplayable' not in auto_block,
      'the immediate-play path never shows the dialog')
check('AutoSourceFallback.find(' not in auto_block,
      'the immediate-play path never starts a fresh check')
check('playPlayableEpisode(' in auto_block,
      'the immediate-play path calls playPlayableEpisode directly')
# all play call sites pass the current site + detail
check('playWithFreshSource(a, s, v, remembered, null)' in detail,
      'hero play button passes site + shown detail')
check('playWithFreshSource(a, site, video, remembered, null)' in detail,
      'episode list play passes site + shown detail')
check('playWithFreshSource(a, site, video, null, ep.name)' in detail,
      'episode tap passes site + shown detail')
check('playWithFreshSource(a, listing,' not in detail,
      'no call site still passes the bare listing')
check('playWithFreshSource(a, video,' not in detail,
      'no call site still passes the bare video')

# ---- the player's own fallback is untouched (first-candidate failure path) ----
ctrl = read('android/src/main/java/app/mkoai/player/PlaybackController.java')
check('AutoSourceFallback.find(' in ctrl,
      'player error fallback still runs find for a source hop')
check('_sourceHopCount' in ctrl and 'sourceHop < 2' in ctrl,
      'player source hop (max 2) still there')
check('showPlaybackDeadEnd' in ctrl,
      'player dead-end UI still there (genuine end state after a completed check)')
check('static void showPlaybackLoading' in ctrl,
      'player loading overlay still there (player shows 加载中 while resolving)')
check('static void openPlayer' in ctrl,
      'openPlayer still there (direct m3u8 opens the player synchronously)')

# ---- 2.0.54 regression: QR login, push, TV lite, TV banner untouched ----
server = read('android/src/main/java/app/mkoai/player/ConfigTransferServer.java')
check('"/login"' in server, '/login route still there (2.0.54 QR login kept)')
check('"/push"' in server, '/push route still there (2.0.52 push kept)')
check('interface LoginImporter' in server, 'LoginImporter still there')
main = read('android/src/main/java/app/mkoai/player/MainActivity.java')
check('if (isTvDevice()) reduced = true;' in main,
      'TV still forces reduced motion (2.0.54 lite kept)')
check('TvLoginDialog tvLogin;' in main, 'TV login dialog wiring still there')
home = read('android/src/main/java/app/mkoai/player/HomeScreen.java')
check('(40 / COLUMNS) * COLUMNS' in home, 'TV home still caps at 40 cards per page')
img = read('android/src/main/java/app/mkoai/player/ImageLoader.java')
check('"/t/p/w342/"' in img, 'TV card posters still w342')
settings = read('android/src/main/java/app/mkoai/player/SettingsScreen.java')
check('"掃碼登入"' in settings, 'TV QR login entry still there')
check('"掃碼登入電視"' in settings, 'phone QR login entry still there')
from PIL import Image
import hashlib
banner_path = ROOT / 'android/src/main/res/drawable-nodpi/logo_banner.png'
check(Image.open(banner_path).convert('RGB').size == (640, 360),
      'TV banner still 640x360')
check(hashlib.sha256(
    (ROOT / 'android/src/main/res/mipmap-xxxhdpi/ic_launcher.png')
    .read_bytes()).hexdigest()
    == '6295ac667fe75620afc0a41fb60dc7de816195d7474c43247e6c5e7fe8b381bc',
      'phone launcher icon untouched (still the 2.0.25 pink-gold M + play)')

# ---- guardrails: no marcomovie edits, TV detail screen untouched ----
marcomovie_editions = json.loads(
    Path('/home/hatch/workspace/mkoai/marcomovie/android/edition-versions.json')
    .read_text(encoding='utf-8'))
check(marcomovie_editions['android-mobile']['versionName'] == '2.0.50',
      'marcomovie tree untouched (still 2.0.50)')
tv_detail = read('android/src/main/java/app/mkoai/player/DetailScreen.java')
check('startFreshSourceCheck' not in tv_detail,
      'TV detail screen untouched (2.0.55 is a phone play-flow fix)')

print(f'{checks} mkoai 2.0.55 checks passed')
