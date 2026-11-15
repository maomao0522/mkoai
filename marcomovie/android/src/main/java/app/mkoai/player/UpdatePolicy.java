// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import java.net.URI;

/** Public update metadata policy, also tested without Android. */
final class UpdatePolicy {
    static final long MAX_APK = 128L * 1024 * 1024;
    /**
     * mkoai 2.0.42: the user's own release manifest on Drive — the ONLY
     * update source. The MarcoMovie official mirror (marcotv.wlj9919.top)
     * and GitHub releases are NOT consulted anymore: mkoai versions only
     * exist on the user's Drive. The file must be shared "anyone with the
     * link can view"; if it is not, the check fails (never worked around
     * in code — fix the sharing setting instead).
     */
    static final String MKOA_MANIFEST =
            "https://drive.google.com/uc?export=download&id=1YMPyb7lfsFMIBmKyv5KoVFIACRvXRihD";

    // mkoai: the update feed is the user's Drive manifest (both channels
    // read the same file; the manifest carries no channel concept).
    static String[] feeds(String channel) {
        return new String[] {MKOA_MANIFEST};
    }

    static String platform(boolean mobile) {
        return mobile ? "android-mobile" : "android-tv";
    }

    static boolean mobileEdition(String edition) {
        if ("mobile".equals(edition)) return true;
        if ("tv".equals(edition)) return false;
        throw new IllegalArgumentException("Unknown application edition");
    }

    static String[] feeds(String channel, boolean mobile) {
        return new String[] {MKOA_MANIFEST};
    }

    static boolean editionUrl(String url, String version, boolean mobile) {
        // mkoai: the apkUrl is a Drive download link, which carries no
        // filename — the edition is enforced by apkUrl() (Drive only) and,
        // at install time, by verifyApk (package + versionCode +
        // versionName + edition metadata + signer + SHA-256).
        return apkUrl(url);
    }

    /** Keep the release's actual tag; never switch editions when retrying a download. */
    static String[] downloads(String version, boolean mobile, String primary, String github) {
        if (!editionUrl(primary, version, mobile))
            throw new IllegalArgumentException("APK edition");
        // mkoai: a single Drive download link — no mirror / GitHub fallback.
        return new String[] {primary};
    }

    static boolean apkUrl(String value) {
        try {
            URI u = new URI(value);
            if (!"https".equals(u.getScheme())
                    || u.getUserInfo() != null
                    || u.getPort() != -1
                    || u.getFragment() != null
                    || u.getPath().contains("..")
                    || !u.getRawPath().equals(u.getPath())) return false;
            String q = u.getQuery();
            // mkoai: release APKs are Drive download links only.
            if ("drive.google.com".equals(u.getHost()) && "/uc".equals(u.getPath()))
                return q != null && q.matches("export=download&id=[A-Za-z0-9_-]+");
            // mkoai 2.0.58: the direct form Drive serves for large APKs.
            // The /uc link 303-redirects to a "Download warning" page
            // without confirm=t, so the APK itself never downloads and
            // verifyApk fails ("下載或校驗失敗"). The manifest must be
            // able to point at the direct form instead; the host/path
            // and the three required params are all enforced here.
            if ("drive.usercontent.google.com".equals(u.getHost())
                    && "/download".equals(u.getPath())) {
                return hasApkParams(u.getRawQuery());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The direct-download query must carry id=&lt;Drive id&gt;,
     * export=download and confirm=t (any order, no other params).
     * Parsed from the RAW query so the check sees exactly what the
     * server sees (a %26 inside a value must not forge a parameter).
     */
    private static boolean hasApkParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return false;
        String id = null;
        boolean exportOk = false, confirmOk = false;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) return false;
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            if ("id".equals(k)) id = v;
            else if ("export".equals(k)) exportOk = "download".equals(v);
            else if ("confirm".equals(k)) confirmOk = "t".equals(v);
            else return false;
        }
        return id != null && id.matches("[A-Za-z0-9_-]+") && exportOk && confirmOk;
    }

    static boolean transferUrl(String value) {
        try {
            URI u = new URI(value);
            String h = u.getHost();
            // mkoai: Drive only. drive.google.com/uc redirects APK/manifest
            // downloads to drive.usercontent.google.com — both must be
            // allowed or the redirect is rejected as untrusted. Nothing
            // else (no mirror, no GitHub, no sign-in pages).
            return "https".equals(u.getScheme())
                    && u.getUserInfo() == null
                    && u.getPort() == -1
                    && ("drive.google.com".equals(h) || "drive.usercontent.google.com".equals(h));
        } catch (Exception e) {
            return false;
        }
    }

    static void validate(
            int code,
            String version,
            int minSdk,
            long size,
            String sha,
            String url,
            boolean preview,
            String channel) {
        validate(code, version, minSdk, size, sha, url, preview, channel, false);
    }

    static void validate(
            int code,
            String version,
            int minSdk,
            long size,
            String sha,
            String url,
            boolean preview,
            String channel,
            boolean mobile) {
        if (code <= 0
                || version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")
                || version.length() > 48
                || minSdk < 23
                || minSdk > 100
                // mkoai: size may be unknown (-1) when the manifest omits it;
                // the SHA-256 check in verifyApk is the real gate then.
                || (size != -1 && (size < 1 || size > MAX_APK))
                || sha == null
                || !sha.matches("[a-f0-9]{64}")
                || !apkUrl(url)
                || !editionUrl(url, version, mobile)
                || (!"stable".equals(channel) && !"preview".equals(channel))
                || ("stable".equals(channel) && preview))
            throw new IllegalArgumentException("Invalid update metadata");
    }

    static boolean newer(int offered, int installed) {
        return offered > installed;
    }

    static long resumeOffset(long requested, int status, String range, long size)
            throws java.io.IOException {
        if (requested < 0 || requested >= size) throw new java.io.IOException("invalid offset");
        if (status == 200) return 0;
        if (status == 404) throw new java.io.IOException("http 404");
        if (status != 206 || requested == 0 || range == null)
            throw new java.io.IOException("invalid range response");
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range);
        try {
            if (!m.matches()
                    || Long.parseLong(m.group(1)) != requested
                    || Long.parseLong(m.group(2)) != size - 1
                    || Long.parseLong(m.group(3)) != size)
                throw new java.io.IOException("range mismatch");
        } catch (NumberFormatException e) {
            throw new java.io.IOException("range number", e);
        }
        return requested;
    }
}
