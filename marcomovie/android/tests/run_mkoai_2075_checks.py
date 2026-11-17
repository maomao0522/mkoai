#!/usr/bin/env python3
# mkoai 2.0.75 checks — javac + static regression checks for the QR
# scanner black screen (no pure-Java logic to unit-test on the JVM: the
# fix is Android Camera1 + SurfaceHolder lifecycle only).
#
# (bug) the QR scanner (掃碼登入電視 → 掃描電視 QR 碼) showed a black
#   screen on the user's POCO F5 (field report 2026-11-17 ~19:36,
#   screenshot: title 對準電視上的 QR 碼, 關閉 button, camera area
#   entirely black, no "無法開啟相機" toast). Root cause: onCreate called
#   startCamera() immediately after setContentView, but the SurfaceView's
#   surface does not exist yet at that point (it is created
#   asynchronously after the window attaches). Camera.open() +
#   setPreviewDisplay(holder) + startPreview() all "succeeded" against
#   the not-yet-created surface, so the preview rendered nowhere; and
#   surfaceCreated() then skipped the re-attach because camera was
#   already non-null — black forever. Fix (QrScanActivity.java only):
#   (a) startCamera() returns early when the holder's surface is null
#       or not valid yet (surfaceCreated calls it again once the surface
#       exists); (b) surfaceCreated() re-attaches the preview
#       (stopPreview + setPreviewDisplay(holder) + startPreview) when
#       the camera is already open. The permission flow, the ZXing decode
#       loop, and the qr_text result contract are unchanged.
# (tv google login) NOT in 2.0.75: the TV's built-in browser cannot hand
#   the mkoai://login-callback redirect back to the app
#   (net::ERR_UNKNOWN_URL_SCHEME on the TV, 2026-11-17 ~19:37 — Google
#   had already accepted the login, the tokens are visible in the URL,
#   the browser just will not pass them to mkoai). That is the TV
#   browser's behaviour, not app code; the TV login paths are the QR
#   scan (fixed here), the manual IP + code entry, and the password
#   login (2.0.53). No code change for it in this version.
# The manifest / edition-versions bump to 2.0.75 / 20076 is the
# parent agent's step AFTER this work, so this runner does NOT pin
# the mobile/tv version — only that lite stays 0.5.3 / 53.
# Also spot-checks that the 2.0.74 items still hold.
#
import glob
import json
import os
import subprocess
import sys

ROOT = os.path.expanduser("~/workspace/mkoai/gpt-2050/marcomovie")
SRC = os.path.join(ROOT, "android/src/main/java/app/mkoai/player")
JDK = os.path.expanduser("~/workspace/jdk21")
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


ORGJSON = ("/home/hatch/.gradle-kazumi/caches/modules-2/files-2.1/org.json/"
           "json/20180813/8566b2b0391d9d4479ea225645c6ed47ef17fe41/"
           "json-20180813.jar")
ANDROID = os.path.join(ROOT, "tools/android-35/android.jar")


def main():
    global TOTAL
    qr = read("QrScanActivity.java")

    # ---- javac: QrScanActivity.java compiles (JDK 21, full classpath) ----
    jars = ":".join(glob.glob(os.path.join(ROOT, "tools/java-dependencies/*.jar")))
    aars = ":".join(glob.glob(os.path.join(ROOT, "android/build/aar/*/classes.jar")))
    cp = ":".join([ORGJSON, ANDROID, jars, aars])
    sp = ":".join([os.path.join(ROOT, "android/src/main/java"),
                   os.path.join(ROOT, "shared/java"),
                   os.path.join(ROOT, "android/build/generated")])
    jc = subprocess.run(
        [os.path.join(JDK, "bin/javac"), "-encoding", "UTF-8",
         "-cp", cp, "-sourcepath", sp, "-d", "/tmp/qr2075-classes",
         os.path.join(SRC, "QrScanActivity.java")],
        text=True, capture_output=True, timeout=300)
    check(jc.returncode == 0, "javac of QrScanActivity.java (2.0.75)")
    if jc.returncode != 0:
        print(jc.stderr[-3000:])

    # ---- Static: (bug) startCamera() waits for a real surface ----
    guard = "preview.getHolder().getSurface() == null"
    check(guard in qr,
          "(bug) startCamera() checks the holder surface for null before opening the camera")
    check("!preview.getHolder().getSurface().isValid()" in qr,
          "(bug) startCamera() also checks the surface isValid() before opening the camera")
    start_body = qr.split("private void startCamera() {")[1].split(
        "\n    private void releaseCamera()")[0]
    check("camera = Camera.open();" in start_body,
          "(bug) startCamera() still opens the camera once the guard passes")
    check(start_body.index(guard) < start_body.index("camera = Camera.open();"),
          "(bug) the surface guard runs BEFORE Camera.open() (not after)")
    check("camera.setPreviewDisplay(preview.getHolder());" in start_body
          and "camera.startPreview();" in start_body,
          "(bug) startCamera() still attaches the preview display and starts the preview")

    # ---- Static: (bug) surfaceCreated() re-attaches an already-open camera ----
    surf_body = qr.split("public void surfaceCreated(SurfaceHolder holder) {")[1].split(
        "\n    @Override\n    public void surfaceChanged")[0]
    check("camera == null" in surf_body,
          "(bug) surfaceCreated() still handles the camera == null case (first creation)")
    check("startCamera();" in surf_body,
          "(bug) surfaceCreated() starts the camera when it is not open yet")
    check("camera.stopPreview();" in surf_body,
          "(bug) surfaceCreated() stops the preview before re-attaching (clean re-attach)")
    check("camera.setPreviewDisplay(holder);" in surf_body,
          "(bug) surfaceCreated() re-attaches the preview display to the created surface")
    check("camera.startPreview();" in surf_body,
          "(bug) surfaceCreated() restarts the preview after re-attaching")
    check("PackageManager.PERMISSION_GRANTED" in surf_body,
          "(bug) surfaceCreated() still gates on the camera permission")

    # ---- Static: the permission flow is unchanged ----
    check('requestPermissions(new String[] {android.Manifest.permission.CAMERA}, REQUEST_CAMERA);'
          in qr,
          "(permission) onCreate still requests CAMERA when not granted")
    check("if (requestCode == REQUEST_CAMERA) {" in qr
          and "startCamera();" in qr.split("public void onRequestPermissionsResult")[1].split(
              "private void startCamera()")[0],
          "(permission) granting the permission still starts the camera")
    check('"需要相機權限才能掃碼，可改用手動輸入 IP 與配對碼"' in qr,
          "(permission) denying still toasts the manual-entry fallback and finishes")

    # ---- Static: the decode loop + result contract are unchanged ----
    check("public void onPreviewFrame(final byte[] data, Camera camera) {" in qr,
          "(decode) onPreviewFrame still receives preview frames")
    check("new QRCodeReader()" in qr and "PlanarYUVLuminanceSource" in qr,
          "(decode) the ZXing QR decode is still there")
    check('setResult(\n                                                RESULT_OK,\n                                                new Intent().putExtra("qr_text", text));'
          in qr or 'putExtra("qr_text", text)' in qr,
          "(decode) a decoded QR still returns qr_text to the caller")
    check("REQUEST_QR_SCAN = 102" in read("PhoneLoginDialog.java"),
          "(decode) the scan request code is unchanged (PhoneLoginDialog still matches it)")

    # ---- Static: lifecycle release is unchanged ----
    check("surfaceDestroyed(SurfaceHolder holder) {" in qr
          and "releaseCamera();" in qr.split("surfaceDestroyed")[1].split("}")[0],
          "(lifecycle) surfaceDestroyed still releases the camera")
    check("protected void onPause() {" in qr
          and "releaseCamera();" in qr.split("protected void onPause()")[1].split("}")[0],
          "(lifecycle) onPause still releases the camera")
    check("decoder.shutdownNow();" in qr,
          "(lifecycle) onDestroy still shuts the decoder executor down")

    # ---- Static: no new threads ----
    check(qr.count("new Thread") == 0,
          "no new thread in QrScanActivity.java (the single-thread decoder executor is pre-existing)")

    # ---- Static: 2.0.74 holds (spot checks; the 2074 runner covers them in full) ----
    spk = read("SourcePicker.java")
    ms = read("MobileShell.java")
    check("a.back.clear();" not in
          spk.split("private static void edit(MainActivity a, Cms.Config config, Cms.Site site, int target) {")[1].split("static void show(MainActivity a) {")[0],
          "2.0.74 holds: edit() still does not clear the back stack")
    check("window.setLayout(Math.min(a.dp(720), a.windowWidth() - marginH), -2);" in spk,
          "2.0.74 holds: the picker setLayout still reads the stable windowWidth()")
    check('"刪除全部"' in spk and '"source-delete-all"' in spk,
          "2.0.74 holds: the delete-all button is still there")
    check("profileAfterDeleteAll(saved)" in spk,
          "2.0.74 holds: delete-all still resets via profileAfterDeleteAll")
    check("static Cms.Site pickFallback(" in spk,
          "2.0.74 holds: pickFallback is still the fallback")

    # ---- Static: 2.0.73 holds (spot checks) ----
    cs = read("CatalogScreen.java")
    check(cs.count("history-back") == 1,
          "2.0.73 holds: the history back button tag is still there once")
    check('private static final String HISTORY_DOCK_LABEL = "記錄";' in ms,
          "2.0.73 holds: the dock label is still 記錄")

    # ---- Static: TV untouched, DeepL untouched ----
    hs = read("HomeScreen.java")
    check("QrScanActivity" not in hs and "2.0.75" not in hs,
          "TV untouched: no QR scanner or 2.0.75 marker in HomeScreen.java")
    check("deepl_quota_exhausted" in read("QuotaState2066.java")
          and os.path.exists(os.path.join(SRC, "TranslateClient.java")),
          "DeepL untouched: deepl_quota_exhausted + TranslateClient still there (removal paused)")

    # ---- Static: secret audit (whole app tree) ----
    check(grep_app("phc_") == 0 and grep_app("os_v2_") == 0
          and grep_app("client_id=") == 0,
          "secret audit: no key literal in the app tree")

    # ---- Static: forbidden files carry no 2.0.75 marker ----
    for rel in ("Cms.java", "GlobalSearchScreen.java", "TitleTranslator.java",
                "HeroSwipe2063.java", "HomeRows2061.java", "MobileIosSettings.java",
                "UpdateScreen.java", "SettingsScreen.java", "HomeScreen.java"):
        text = read(rel)
        check("2.0.75" not in text,
              "forbidden file untouched: no 2.0.75 marker in " + rel)

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
