// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Separate network queue; installs only an official same-signed newer APK after explicit user action. */
final class UpdateManager {
    static final class Release extends UpdateRelease {
        Release(JSONObject o, String channel, boolean mobile) throws Exception {
            super(o, channel, mobile);
        }
    }

    private static UpdateManager instance;

    static synchronized UpdateManager get(Context c) {
        if (instance == null) instance = new UpdateManager(c.getApplicationContext());
        return instance;
    }

    final Context context;
    final SharedPreferences prefs;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    volatile Release available;
    volatile String message = "尚未檢查更新";
    volatile boolean busy;
    volatile int percent = -1;
    private volatile boolean cancel;
    private volatile HttpURLConnection activeConnection;
    private Runnable listener;
    private long noticedCode;

    boolean cancelled() {
        return cancel;
    }

    void resetCancellation() {
        cancel = false;
    }

    interface Connector {
        HttpURLConnection open(String address, long offset) throws Exception;
    }

    final Connector connector;

    private UpdateManager(Context c) {
        this(c, c.getSharedPreferences("updates", 0), UpdateManager::open);
    }

    UpdateManager(Context c, SharedPreferences preferences, Connector connection) {
        context = c;
        prefs = preferences;
        connector = connection;
        int code = installedCode(c), old = prefs.getInt("installed_code", code);
        if (code > old) {
            prefs.edit()
                    .putString("result", "已升級至 " + installedVersion(context) + "。" + bridgeNote())
                    .remove("download_sha")
                    .remove("pending_code")
                    .apply();
            apk().delete();
        } else if (prefs.contains("pending_code"))
            prefs.edit()
                    .putString("result", "上次安裝未完成，當前版本仍可使用。可重新選擇安裝。")
                    .remove("pending_code")
                    .apply();
        prefs.edit().putInt("installed_code", code).apply();
        try {
            available =
                    new Release(
                            new JSONObject(prefs.getString("metadata", "")),
                            channel(),
                            mobileEdition());
        } catch (Exception ignored) {
        }
        if (available != null
                && (available.code <= code || available.minSdk > Build.VERSION.SDK_INT))
            available = null;
        message = prefs.getString("result", "尚未檢查更新");
    }

    static int installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean mobileEdition() {
        return mobileEdition(context);
    }

    private static boolean mobileEdition(Context context) {
        try {
            android.content.pm.ApplicationInfo info =
                    context.getPackageManager()
                            .getApplicationInfo(
                                    context.getPackageName(), PackageManager.GET_META_DATA);
            return UpdatePolicy.mobileEdition(
                    info.metaData == null
                            ? null
                            : info.metaData.getString("app.mkoai.player.EDITION"));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("無法識別安裝版本", e);
        }
    }

    String channel() {
        return prefs.getString("channel", "preview");
    }

    String mode() {
        return prefs.getString("mode", "notify");
    }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    String bridgeNote() {
        return "配置、收藏與觀看記錄保留在本機。";
    }

    synchronized void listen(Runnable callback) {
        listener = callback;
    }

    private void changed() {
        main.post(
                () -> {
                    Runnable callback;
                    synchronized (this) {
                        callback = listener;
                    }
                    if (callback != null) callback.run();
                });
    }

    File folder() {
        File f = new File(context.getCacheDir(), "updates");
        f.mkdirs();
        return f;
    }

    File apk() {
        return new File(folder(), "release.apk");
    }

    boolean ready() {
        Release r = available;
        return r != null
                && r.code > installedCode(context)
                && apk().isFile()
                // size -1 = manifest omitted it; SHA-256 (download_sha) gates it.
                && (r.size <= 0 || apk().length() == r.size)
                && r.sha.equals(prefs.getString("download_sha", ""));
    }

    boolean consumeNotice() {
        Release r = available;
        if (r == null
                || r.code <= installedCode(context)
                || noticedCode == r.code
                || "off".equals(mode())) return false;
        noticedCode = r.code;
        return true;
    }

    void setMode(String value) {
        if (!Arrays.asList("notify", "download", "off").contains(value)) return;
        prefs.edit().putString("mode", value).apply();
        if ("off".equals(value)) cancel = true;
        changed();
    }

    synchronized boolean setChannel(String value) {
        if (busy || !Arrays.asList("stable", "preview").contains(value)) return false;
        prefs.edit()
                .putString("channel", value)
                .remove("metadata")
                .remove("download_sha")
                .remove("last_attempt")
                .apply();
        available = null;
        apk().delete();
        new File(folder(), "release.part").delete();
        prefs.edit().remove("partial_sha").apply();
        noticedCode = 0;
        message = "渠道已切換，請檢查更新";
        changed();
        return true;
    }

    void automatic() {
        // mkoai 2.0.42: silent background check against the user's Drive
        // manifest, throttled to once per 6h — the MainActivity runnable
        // fires every minute while home is in the foreground, and the
        // manifest does not change that often.
        if (System.currentTimeMillis() - prefs.getLong("last_attempt", 0)
                < 6L * 60 * 60 * 1000) return;
        check(false);
    }

    synchronized void check(boolean manual) {
        if (busy) return;
        busy = true;
        cancel = false;
        message = "正在檢查更新…";
        percent = -1;
        changed();
        final String channel = channel();
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply();
        worker.execute(
                () -> {
                    try {
                        JSONObject data = readRelease(channel);
                        Release r = new Release(data, channel, mobileEdition());
                        if (cancel) throw new InterruptedIOException();
                        if (!UpdatePolicy.newer(r.code, installedCode(context))) {
                            clearOffer();
                            message = "當前渠道暫無更高版本（本機 " + installedVersion(context) + "）";
                        } else if (r.minSdk > Build.VERSION.SDK_INT) {
                            clearOffer();
                            message = "發現新版，但其系統版本要求不適合本機，已停止下載";
                        } else {
                            available = r;
                            message = "發現新版 " + r.version;
                            prefs.edit().putString("metadata", data.toString()).apply();
                            if ("download".equals(mode()) && !ready()) downloadFile(r);
                        }
                    } catch (InterruptedIOException e) {
                        message = cancel ? "已取消，當前版本不受影響" : "網路連線超時；若已有下載進度，再次下載可繼續";
                    } catch (Exception e) {
                        message = "檢查或下載失敗，請檢查網路後重試；現有版本仍可使用";
                        AppLog.w("UpdateManager", "Check failed", e);
                    } finally {
                        busy = false;
                        percent = -1;
                        changed();
                    }
                });
    }

    synchronized void download() {
        if (busy || available == null) return;
        Release r = available;
        if (r.minSdk > Build.VERSION.SDK_INT || r.code <= installedCode(context)) return;
        busy = true;
        cancel = false;
        worker.execute(
                () -> {
                    try {
                        downloadFile(r);
                    } catch (InterruptedIOException e) {
                        message = cancel ? "下載已取消" : "下載超時，已保留進度，可再次下載繼續";
                    } catch (Exception e) {
                        message = "下載或校驗失敗，未安裝。請重試或從 mkoai 發布渠道下載";
                        AppLog.w("UpdateManager", "Download failed", e);
                    } finally {
                        busy = false;
                        percent = -1;
                        changed();
                    }
                });
    }

    private void clearOffer() {
        available = null;
        prefs.edit().remove("metadata").remove("download_sha").apply();
        apk().delete();
    }

    void cancel() {
        cancel = true;
        HttpURLConnection active = activeConnection;
        if (active != null) workerCancel(active);
        message = "正在取消…";
        changed();
    }

    private void workerCancel(HttpURLConnection active) {
        new Thread(active::disconnect, "MarcoMovie-cancel-update").start();
    }

    private void downloadFile(Release r) throws Exception {
        // size -1 = manifest omitted it; skip the space/size gates and let
        // the SHA-256 check in verifyApk decide (the manifest we publish
        // always includes size, so this is only a fallback).
        if (r.size > 0 && folder().getUsableSpace() < r.size + 16 * 1024 * 1024L)
            throw new IOException("space");
        File part = new File(folder(), "release.part");
        // size unknown (-1): no resume without a known size — restart.
        if (r.size <= 0
                || !r.sha.equals(prefs.getString("partial_sha", ""))
                || part.length() > r.size) {
            part.delete();
            prefs.edit().putString("partial_sha", r.sha).commit();
        }
        percent = r.size > 0 ? (int) (part.length() * 100 / r.size) : -1;
        boolean verified = false;
        try {
            Exception failure = null;
            boolean valid = false;
            String[] sources = r.downloads;
            for (int source = 0; source < sources.length && !valid; source++) {
                for (int attempt = 0;
                        attempt < 2 && (r.size <= 0 || part.length() < r.size);
                        attempt++) {
                    if (cancel) throw new InterruptedIOException();
                    message =
                            (sources[source].startsWith("https://drive.google.com/")
                                            ? "正在從雲端硬碟下載 "
                                            : "正在下載 ")
                                    + r.version;
                    changed();
                    try {
                        transfer(r, part, sources[source]);
                        failure = null;
                    } catch (IOException e) {
                        failure = e;
                        if (cancel) throw new InterruptedIOException();
                        if (e.getMessage() != null && e.getMessage().contains("404")) break;
                    }
                }
                // size known: complete when the byte count matches; unknown:
                // complete when the transfer ended (EOF) with bytes on disk.
                boolean complete = r.size > 0 ? part.length() == r.size : part.length() > 0;
                if (complete) {
                    try {
                        verifyApk(context, part, r);
                        valid = true;
                    } catch (Exception e) {
                        failure = e;
                        if (!part.delete()) throw new IOException("discard invalid APK", e);
                    }
                }
            }
            if (!valid) {
                if (failure != null) throw failure;
                throw new IOException("short download");
            }
            if (cancel) throw new InterruptedIOException();
            if (apk().exists() && !apk().delete()) throw new IOException("replace");
            if (!part.renameTo(apk())) throw new IOException("rename");
            prefs.edit().putString("download_sha", r.sha).remove("partial_sha").apply();
            message = "新版已下載並校驗，選擇“安裝已下載版本”繼續";
            verified = true;
        } finally {
            if (cancel) {
                part.delete();
                prefs.edit().remove("partial_sha").apply();
            }
            if (!verified && !cancel && part.exists()) message = "下載中斷，已保留進度，可再次下載繼續";
        }
    }

    private void transfer(Release r, File part, String address) throws Exception {
        long offset = part.length();
        HttpURLConnection conn = connector.open(address, offset);
        activeConnection = conn;
        try {
            long start;
            if (r.size <= 0) {
                // Unknown size: no resume; a fresh 200 is the only valid start.
                if (offset > 0) throw new IOException("resume needs size");
                if (conn.getResponseCode() != 200)
                    throw new IOException("HTTP " + conn.getResponseCode());
                start = 0;
            } else {
                start =
                        UpdatePolicy.resumeOffset(
                                offset,
                                conn.getResponseCode(),
                                conn.getHeaderField("Content-Range"),
                                r.size);
            }
            long declared = conn.getContentLength();
            if (declared > 0 && r.size > 0 && declared != r.size - start)
                throw new IOException("size");
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(part, start > 0)) {
                byte[] bytes = new byte[32768];
                long total = start;
                int n, last = -1;
                while ((n = in.read(bytes)) != -1) {
                    if (cancel) throw new InterruptedIOException();
                    total += n;
                    if (r.size > 0 && total > r.size) throw new IOException("limit");
                    out.write(bytes, 0, n);
                    int now = r.size > 0 ? (int) (total * 100 / r.size) : -1;
                    if (now != last) {
                        last = now;
                        percent = now;
                        changed();
                    }
                }
                if (r.size > 0 && total != r.size) throw new IOException("short download");
            }
        } finally {
            activeConnection = null;
            conn.disconnect();
        }
    }

    static void verifyApk(Context c, File file, Release r) throws Exception {
        // size -1 = manifest omitted it; length is skipped, SHA-256 gates it.
        if ((r.size > 0 && file.length() != r.size) || !sha(file).equals(r.sha))
            throw new IOException("digest");
        PackageManager pm = c.getPackageManager();
        int flags =
                Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES
                        : PackageManager.GET_SIGNATURES;
        PackageInfo next =
                pm.getPackageArchiveInfo(
                        file.getAbsolutePath(), flags | PackageManager.GET_META_DATA);
        PackageInfo current = pm.getPackageInfo(c.getPackageName(), flags);
        if (next == null
                || !c.getPackageName().equals(next.packageName)
                || next.versionCode != r.code
                || next.versionCode <= current.versionCode
                || !r.version.equals(next.versionName)
                || next.applicationInfo == null
                || (Build.VERSION.SDK_INT >= 24
                        && (next.applicationInfo.minSdkVersion != r.minSdk
                                || next.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)))
            throw new IOException("package identity or compatibility");
        boolean nextMobile =
                UpdatePolicy.mobileEdition(
                        next.applicationInfo.metaData == null
                                ? null
                                : next.applicationInfo.metaData.getString(
                                        "app.mkoai.player.EDITION"));
        if (nextMobile != mobileEdition(c)) throw new IOException("安裝包型別不匹配");
        Set<String> a = packageSignatures(current), b = packageSignatures(next);
        if (a.isEmpty() || !a.equals(b)) throw new IOException("signer");
    }

    @SuppressWarnings("deprecation")
    private static Set<String> packageSignatures(PackageInfo info) {
        if (info == null) return Collections.emptySet();
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            android.content.pm.Signature[] certs =
                    info.signingInfo.hasMultipleSigners()
                            ? info.signingInfo.getApkContentsSigners()
                            : info.signingInfo.getSigningCertificateHistory();
            Set<String> modern = signatures(certs);
            if (!modern.isEmpty()) return modern;
        }
        return signatures(info.signatures);
    }

    private static Set<String> signatures(android.content.pm.Signature[] signatures) {
        Set<String> result = new HashSet<>();
        if (signatures != null)
            for (android.content.pm.Signature s : signatures) result.add(s.toCharsString());
        return result;
    }

    static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    static HttpURLConnection open(String address) throws Exception {
        return open(address, 0);
    }

    private static HttpURLConnection open(String address, long offset) throws Exception {
        for (int hops = 0; hops < 6; hops++) {
            if (!UpdatePolicy.transferUrl(address)) throw new IOException("untrusted host");
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(45000);
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            c.setRequestProperty("User-Agent", "MarcoMovie-Updater");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setUseCaches(false);
            int status;
            try {
                status = c.getResponseCode();
            } catch (Exception e) {
                c.disconnect();
                throw e;
            }
            if (status == 200 || (offset > 0 && status == 206)) return c;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IOException("redirect");
                address = new URI(address).resolve(next).toString();
            } else {
                c.disconnect();
                throw new IOException("HTTP " + status);
            }
        }
        throw new IOException("redirect limit");
    }

    private JSONObject readRelease(String channel) throws Exception {
        Exception failure = null;
        for (String address : UpdatePolicy.feeds(channel, mobileEdition())) {
            if (cancel) throw new InterruptedIOException();
            try {
                JSONObject data = new JSONObject(new String(readFeed(address), "UTF-8"));
                new Release(data, channel, mobileEdition());
                return data;
            } catch (Exception e) {
                failure = e;
                if (cancel) throw new InterruptedIOException();
            }
        }
        throw failure == null ? new IOException("no update source") : failure;
    }

    private byte[] readFeed(String address) throws Exception {
        HttpURLConnection c = connector.open(address, 0);
        activeConnection = c;
        try (InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int n;
            while ((n = in.read(bytes)) != -1) {
                if (cancel) throw new InterruptedIOException();
                if (out.size() + n > 32768) throw new IOException("feed limit");
                out.write(bytes, 0, n);
            }
            return out.toByteArray();
        } finally {
            activeConnection = null;
            c.disconnect();
        }
    }
}
