"""mkoai 2.0.52 static + JVM checks (no device, no build needed).

Verifies the phone-to-TV source push (same Wi-Fi, no cloud, no SQL):
  1. versions are 2.0.52 / 20053 (mobile + TV)
  2. TV push: QR receive dialog, LAN server, /push endpoint, token, merge
  3. phone push: QR scan activity, manual fallback, POST /push
  4. settings entries (TV receive, phone send) without touching GPT UI
  5. no Supabase in the push path; no render-feel merge; marcomovie untouched

Run: python3 android/tests/run_mkoai_2052_checks.py
Exit code 0 = all checks pass.
"""
from pathlib import Path
import json
import os
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


# ---- Java unit test (QR, token, merge, live server) ----
classes = Path('/tmp/tvpush2052-classes')
classes.mkdir(exist_ok=True)
files = [
    ROOT / 'shared/java/com/pilipala/source/Warehouse.java',
    SRC / 'RequestScope.java',
    SRC / 'Cms.java',
    SRC / 'TvPush.java',
    SRC / 'TvLogin.java',
    SRC / 'ConfigTransferServer.java',
    ROOT / 'android/tests/TvPushChecks.java',
]
subprocess.run(
    [str(JDK / 'bin/javac'), '-encoding', 'UTF-8', '-cp', str(ORGJSON),
     '-d', str(classes), *[str(p) for p in files]],
    check=True)
run = subprocess.run(
    [str(JDK / 'bin/java'), '-cp', str(classes) + os.pathsep + str(ORGJSON),
     'app.mkoai.player.TvPushChecks'],
    check=True, text=True, encoding='utf8', capture_output=True)
print(run.stdout, end='')
checks += int(re.search(r'(\d+) TvPush checks passed', run.stdout).group(1))

# ---- version ----
editions = json.loads(read('android/edition-versions.json'))
for key in ('android-mobile', 'android-tv'):
    check(editions[key]['versionName'] == '2.0.52', f'{key} versionName is 2.0.52')
    check(editions[key]['versionCode'] == 20053, f'{key} versionCode is 20053')
manifest = read('android/src/main/AndroidManifest.xml')
check('android:versionCode="20053"' in manifest, 'AndroidManifest versionCode is 20053')
check('android:versionName="2.0.52"' in manifest, 'AndroidManifest versionName is 2.0.52')
check('android.permission.CAMERA' in manifest, 'CAMERA permission declared')
check('android.hardware.camera' in manifest and 'android:required="false"' in manifest,
      'camera feature declared as not required')
check('.QrScanActivity' in manifest, 'QrScanActivity declared in the manifest')

# ---- server: /push route, token, single-use ----
server = read('android/src/main/java/app/mkoai/player/ConfigTransferServer.java')
check('interface PushImporter' in server, 'PushImporter interface exists')
check('"/push"' in server, 'server routes /push')
check('TvPush.tokenMatches(token, key())' in server, 'push validates the token')
check('replyJson(' in server, 'push replies with JSON')
check(re.search(r'static void replyJson\([^{]*\{.*?catch \(JSONException',
                server, re.S) is not None,
      'replyJson catches JSONException (android.jar org.json has it checked)')
check('TvPush.MAX_PUSH_BYTES' in server, 'push body is size-capped')
check('String key()' in server, 'server exposes the one-time key')
check('pushAddress(String host)' in server, 'server exposes the push address')
check('importer == null' in server, 'configure POST guards a null importer')

# ---- TvPush helper ----
helper = read('android/src/main/java/app/mkoai/player/TvPush.java')
check('mkoai://tv-push?ip=' in helper, 'QR content is mkoai://tv-push')
check('parseQrContent' in helper, 'QR parser exists')
check('pairingCode' in helper, 'pairing code helper exists')
check('tokenMatches' in helper, 'token matcher exists')
check('MergeResult merge(' in helper, 'merge helper exists')
check('existingApis' in helper, 'merge dedups by resolved api')
check('Cms.parseConfig(pushedJson' in helper, 'pushed JSON is validated by the real parser')

# ---- TV receive dialog ----
tv = read('android/src/main/java/app/mkoai/player/TvPushDialog.java')
check('QRCodeWriter' in tv, 'TV dialog encodes the QR code')
check('TvPush.buildQrContent' in tv, 'TV dialog builds the push QR content')
check('TvPush.PUSH_PORT' in tv, 'TV receiver prefers port 8901')
check('PhoneConfigDialog.lanAddress()' in tv, 'TV receiver uses the LAN address')
check('TvPush.merge(' in tv, 'TV importer merges pushed sites')
check('MINUTES.toMillis(5)' in tv, 'TV receive session expires in 5 minutes')
check('isTvDevice()' in tv, 'TV receive dialog is TV-only')

# ---- phone send dialog ----
phone = read('android/src/main/java/app/mkoai/player/PhonePushDialog.java')
check('REQUEST_QR_SCAN = 101' in phone, 'QR scan request code is 101')
check('QrScanActivity.class' in phone, 'phone opens the camera scanner')
check('TvPush.parseQrContent' in phone, 'phone parses the scanned QR')
check('"/push"' in phone, 'phone POSTs to /push')
check('"token"' in phone and '"type"' in phone and '"data"' in phone,
      'phone POST body has token/type/data')
check('HttpURLConnection' in phone, 'phone posts with plain HttpURLConnection')
check('手動' in phone, 'manual IP/port/code fallback exists')
check('Cms.parseConfig(content' in phone, 'phone validates pasted JSON before sending')

# ---- scanner activity ----
scan = read('android/src/main/java/app/mkoai/player/QrScanActivity.java')
check('Camera.open()' in scan, 'scanner uses the camera')
check('QRCodeReader' in scan, 'scanner decodes with ZXing QRCodeReader')
check('PlanarYUVLuminanceSource' in scan, 'scanner feeds YUV frames to ZXing')
check('requestPermissions' in scan, 'scanner requests the camera permission itself')
check('"qr_text"' in scan, 'scanner returns the decoded text')

# ---- settings entries ----
settings = read('android/src/main/java/app/mkoai/player/SettingsScreen.java')
check('手機推送來源到電視' in settings, 'TV settings has the push receive entry')
check(re.search(r'if \(a\.isTvDevice\(\)\) \{\s*link\(\s*"手機推送來源到電視"',
                settings) is not None,
      'TV push entry is TV-only')
mobile_settings = read('android/src/main/java/app/mkoai/player/MobileIosSettings.java')
check('推送來源到電視' in mobile_settings, 'phone settings has the push send entry')
check('new PhonePushDialog(a).show()' in mobile_settings,
      'phone entry opens the push dialog')
main = read('android/src/main/java/app/mkoai/player/MainActivity.java')
check('TvPushDialog tvPush;' in main and 'PhonePushDialog phonePush;' in main,
      'MainActivity holds the push dialogs')
check('void tvPushDialog()' in main, 'MainActivity has tvPushDialog()')
check('PhonePushDialog.REQUEST_QR_SCAN' in main,
      'MainActivity routes the QR scan result to the push dialog')

# ---- guardrails: no cloud in push, no render-feel merge, no marcomovie edits ----
for name in ('TvPush', 'TvPushDialog', 'PhonePushDialog', 'QrScanActivity'):
    body = read(f'android/src/main/java/app/mkoai/player/{name}.java')
    check('Supabase' not in body, f'{name} does not touch Supabase')
    check('HomeAmbientMath' not in body, f'{name} has no render-feel code')
# ConfigTransferServer also serves /login since 2.0.54 (which hands the
# Supabase session over, by design) — the /push path itself still must not
# touch Supabase.
server_body = read('android/src/main/java/app/mkoai/player/ConfigTransferServer.java')
push_path = server_body[server_body.index('void handlePush')
                         :server_body.index('TV QR login endpoint')]
check('Supabase' not in push_path, 'the /push path does not touch Supabase')
check('HomeAmbientMath' not in server_body, 'ConfigTransferServer has no render-feel code')
check(not list((ROOT / 'android/src').rglob('*HomeAmbientMath*')),
      'no HomeAmbientMath references anywhere')
check((ROOT / 'tools/java-dependencies/core-3.5.3.jar').exists(),
      'ZXing core jar already present (no new dependency)')
marcomovie_editions = json.loads(
    Path('/home/hatch/workspace/mkoai/marcomovie/android/edition-versions.json')
    .read_text(encoding='utf-8'))
check(marcomovie_editions['android-mobile']['versionName'] == '2.0.50',
      'marcomovie tree untouched (still 2.0.50)')

print(f'{checks} mkoai 2.0.52 checks passed')
