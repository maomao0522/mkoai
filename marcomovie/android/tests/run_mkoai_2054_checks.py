"""mkoai 2.0.54 static + JVM checks (no device, no build needed).

Verifies the TV QR login (same Wi-Fi, no cloud, no SQL) + the TV lite
edition + the TV banner now using the phone icon artwork:
  1. versions are 2.0.54 / 20055 (mobile + TV; android-lite untouched)
  2. TV login: QR encode/parse, one-time code, session validation, /login
     endpoint (wrong code / bad session / reuse / expiry / size limits)
  3. TV login dialog + phone login dialog + settings entries
  4. TV lite: no animation on TV, fewer cards per page, w342 TV posters
  5. TV banner is the phone icon mark (pink-gold M + play on black silk),
     phone launcher icon untouched; marcomovie tree untouched

Run: python3 android/tests/run_mkoai_2054_checks.py
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


# ---- Java unit test (QR, code, session, live /login server) ----
classes = Path('/tmp/tvlogin2054-classes')
classes.mkdir(exist_ok=True)
files = [
    ROOT / 'shared/java/com/pilipala/source/Warehouse.java',
    SRC / 'RequestScope.java',
    SRC / 'Cms.java',
    SRC / 'TvPush.java',
    SRC / 'TvLogin.java',
    SRC / 'ConfigTransferServer.java',
    ROOT / 'android/tests/TvLoginChecks.java',
]
subprocess.run(
    [str(JDK / 'bin/javac'), '-encoding', 'UTF-8', '-cp', str(ORGJSON),
     '-d', str(classes), *[str(p) for p in files]],
    check=True)
run = subprocess.run(
    [str(JDK / 'bin/java'), '-cp', str(classes) + ':' + str(ORGJSON),
     'app.mkoai.player.TvLoginChecks'],
    check=True, text=True, encoding='utf8', capture_output=True)
print(run.stdout, end='')
checks += int(re.search(r'(\d+) TvLogin checks passed', run.stdout).group(1))

# ---- version ----
editions = json.loads(read('android/edition-versions.json'))
for key in ('android-mobile', 'android-tv'):
    check(editions[key]['versionName'] == '2.0.54', f'{key} versionName is 2.0.54')
    check(editions[key]['versionCode'] == 20055, f'{key} versionCode is 20055')
check(editions['android-lite']['versionName'] == '0.5.3'
      and editions['android-lite']['versionCode'] == 53,
      'android-lite untouched (still 0.5.3 / 53)')
manifest = read('android/src/main/AndroidManifest.xml')
check('android:versionCode="20055"' in manifest, 'AndroidManifest versionCode is 20055')
check('android:versionName="2.0.54"' in manifest, 'AndroidManifest versionName is 2.0.54')
check('android:banner="@drawable/app_banner"' in manifest,
      'TV banner attribute still points at @drawable/app_banner')
check('.QrScanActivity' in manifest, 'QrScanActivity declared in the manifest')

# ---- server: /login route, code, session, single-use ----
server = read('android/src/main/java/app/mkoai/player/ConfigTransferServer.java')
check('interface LoginImporter' in server, 'LoginImporter interface exists')
check('"/login"' in server, 'server routes /login')
check('loginAddress(String host)' in server, 'server exposes the login address')
check('TvPush.tokenMatches(code, key())' in server, 'login validates the one-time code')
check('TvLogin.validateSession(access, refresh)' in server,
      'login validates the posted session')
check('TvLogin.MAX_LOGIN_BYTES' in server, 'login body is size-capped')
check('loginHandler == null' in server, 'login guards a null login handler')
check('applied.get()' in server and 'importing.compareAndSet(false, true)' in server,
      'login is single-use')
check('"/push"' in server, '/push route still there (2.0.52 feature kept)')

# ---- TvLogin helper ----
helper = read('android/src/main/java/app/mkoai/player/TvLogin.java')
check('mkoai://tv-login?ip=' in helper, 'QR content is mkoai://tv-login')
check('parseQrContent' in helper, 'QR parser exists')
check('validateSession' in helper, 'session validator exists')
check('jwtSub' in helper, 'JWT sub decoder exists')
check('pairingCode' in helper, 'pairing code helper exists')
check('codeMatches' in helper, 'code matcher exists')
check('MAX_LOGIN_BYTES = 16384' in helper, 'login body cap is 16KB')
check(re.search(r'^import\s+([A-Za-z0-9_.]+);', helper, re.M) is not None, 'TvLogin has imports')
check(set(re.findall(r'^import\s+([A-Za-z0-9_.]+);', helper, re.M))
      == {'java.util.Locale', 'org.json.JSONObject'},
      'TvLogin imports only java.util + org.json (pure Java, no Supabase/Android)')
check('System.out' not in helper and 'Log.' not in helper,
      'TvLogin never logs (tokens must not reach logs)')

# ---- SupabaseAuth session handover ----
auth = read('android/src/main/java/app/mkoai/player/SupabaseAuth.java')
check('importSession(Context c, String access, String refresh, long expiresIn)' in auth,
      'importSession stores a handed-over session')
check('refreshToken(Context c)' in auth, 'refreshToken exposes the current refresh token')
check('sessionExpiresInSeconds(Context c)' in auth,
      'sessionExpiresInSeconds exposes the remaining lifetime')
check('signInWithPassword' in auth, 'password sign-in still there (2.0.53 kept)')

# ---- TV login dialog ----
tv = read('android/src/main/java/app/mkoai/player/TvLoginDialog.java')
check('QRCodeWriter' in tv, 'TV dialog encodes the QR code')
check('TvLogin.buildQrContent' in tv, 'TV dialog builds the login QR content')
check('isTvDevice()' in tv, 'TV login dialog is TV-only')
check('ConfigTransferServer.LoginImporter' in tv,
      'TV importer stores the session via SupabaseAuth.importSession')
check('SupabaseAuth.importSession(' in tv, 'TV importer stores the session')
check('SupabaseSync.syncNow' in tv, 'TV runs one sync after login')
check('MINUTES.toMillis(5)' in tv, 'TV login session expires in 5 minutes')
check('Log.' not in tv and 'System.out' not in tv,
      'TV login dialog never logs (tokens must not reach logs)')

# ---- phone login dialog ----
phone = read('android/src/main/java/app/mkoai/player/PhoneLoginDialog.java')
check('REQUEST_QR_SCAN = 102' in phone, 'QR scan request code is 102')
check('QrScanActivity.class' in phone, 'phone opens the camera scanner')
check('TvLogin.parseQrContent' in phone, 'phone parses the scanned login QR')
check('"/login"' in phone, 'phone POSTs to /login')
check('"code"' in phone and '"access_token"' in phone and '"refresh_token"' in phone,
      'phone POST body has code/access_token/refresh_token')
check('SupabaseAuth.isSignedIn' in phone, 'phone checks it is signed in first')
check('SupabaseAuth.validAccessToken' in phone, 'phone sends a fresh access token')
check('手動' in phone, 'manual IP/port/code fallback exists')
check('Log.' not in phone and 'System.out' not in phone,
      'phone login dialog never logs (tokens must not reach logs)')

# ---- MainActivity wiring ----
main = read('android/src/main/java/app/mkoai/player/MainActivity.java')
check('TvLoginDialog tvLogin;' in main and 'PhoneLoginDialog phoneLogin;' in main,
      'MainActivity holds the login dialogs')
check('void tvLoginDialog()' in main, 'MainActivity has tvLoginDialog()')
check('PhoneLoginDialog.REQUEST_QR_SCAN' in main,
      'MainActivity routes the QR scan result to the phone login dialog')
check('PhonePushDialog.REQUEST_QR_SCAN' in main, 'push scan routing still there')
check('tvPushDialog()' in main, 'push dialog entry still there')

# ---- settings entries ----
settings = read('android/src/main/java/app/mkoai/player/SettingsScreen.java')
check('"掃碼登入"' in settings, 'TV settings has the QR login entry')
check('a.tvLoginDialog()' in settings, 'TV entry opens the login dialog')
check('"掃碼登入電視"' in settings, 'phone settings has the QR login entry')
check('new PhoneLoginDialog(a).show()' in settings, 'phone entry opens the login dialog')
check(re.search(r'if \(a\.isTvDevice\(\)\) \{[^}]*?link\(\s*"掃碼登入"',
                settings, re.S) is not None,
      'TV QR login entry is TV-only')
check(re.search(r'if \(!a\.isTvDevice\(\)\) \{\s*link\(\s*"掃碼登入電視"', settings) is not None,
      'phone QR login entry is phone-only')

# ---- TV lite ----
check('if (isTvDevice()) reduced = true;' in main,
      'TV forces reduced motion at startup (no animation on the old TV)')
check('a.reduced = a.isTvDevice() || a.prefs.getBoolean(Prefs.REDUCED, false)' in settings,
      'the reduce-motion toggle cannot re-enable animation on TV')
home = read('android/src/main/java/app/mkoai/player/HomeScreen.java')
check('(40 / COLUMNS) * COLUMNS' in home, 'TV home caps at 40 cards per page')
check('a.isTvDevice()' in home, 'TV home cap is TV-only')
img = read('android/src/main/java/app/mkoai/player/ImageLoader.java')
check('"/t/p/w342/"' in img, 'TV card posters load at w342')
check('tvCardsSmall ? "w342" : "w780"' in img, 'TV card size branch uses w342')
check('"/t/p/w1280/"' in img, 'detail pages still load w1280')
check('"/t/p/w500/"' in img,
      'old w500 URLs are still upgraded in place (legacy cache, not a live target)')

# ---- TV banner = phone icon artwork ----
from PIL import Image
banner_path = ROOT / 'android/src/main/res/drawable-nodpi/logo_banner.png'
banner = Image.open(banner_path).convert('RGB')
check(banner.size == (640, 360), 'TV banner is 640x360 (Android TV banner size)')
check(banner.getpixel((0, 0)) == (0, 0, 0), 'TV banner corners are black (no white frame)')
px = banner.load()
minx, miny, maxx, maxy = 640, 360, -1, -1
for y in range(0, 360, 2):
    for x in range(0, 640, 2):
        r, g, b = px[x, y]
        if r > 80 and (r - b) > 40:  # pink-gold mark, not the white corners / black silk
            minx, miny = min(minx, x), min(miny, y)
            maxx, maxy = max(maxx, x), max(maxy, y)
check(minx < 640 and maxx >= 0, 'TV banner contains the pink-gold mark')
check(280 < (minx + maxx) / 2 < 360 and 140 < (miny + maxy) / 2 < 220,
      'TV banner mark is centered')
check(260 < (maxy - miny) < 340, 'TV banner mark fills the banner height')
old_banner_sha = 'afb48249a38f80a8b8a6a2ea7bf93ed638901cd6520acd4c57dc41b10aad185d'
import hashlib
check(hashlib.sha256(banner_path.read_bytes()).hexdigest() != old_banner_sha,
      'TV banner is no longer the old 2.0.53 wordmark lockup')
icon_sha = '6295ac667fe75620afc0a41fb60dc7de816195d7474c43247e6c5e7fe8b381bc'
check(hashlib.sha256(
    (ROOT / 'android/src/main/res/mipmap-xxxhdpi/ic_launcher.png')
    .read_bytes()).hexdigest() == icon_sha,
      'phone launcher icon untouched (still the 2.0.25 pink-gold M + play)')

# ---- guardrails: no token logging, no render-feel merge, no marcomovie edits ----
for name in ('TvLogin', 'TvLoginDialog', 'PhoneLoginDialog', 'ConfigTransferServer'):
    body = read(f'android/src/main/java/app/mkoai/player/{name}.java')
    check('HomeAmbientMath' not in body, f'{name} has no render-feel code')
check(not list((ROOT / 'android/src').rglob('*HomeAmbientMath*')),
      'no HomeAmbientMath references anywhere')
marcomovie_editions = json.loads(
    Path('/home/hatch/workspace/mkoai/marcomovie/android/edition-versions.json')
    .read_text(encoding='utf-8'))
check(marcomovie_editions['android-mobile']['versionName'] == '2.0.50',
      'marcomovie tree untouched (still 2.0.50)')

print(f'{checks} mkoai 2.0.54 checks passed')
