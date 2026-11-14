// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import java.io.IOException;
import org.json.JSONObject;

/** Edition-bound metadata shared by the Android updater and offline behavior checks. */
class UpdateRelease {
    final int code, minSdk;
    final long size;
    final String version, url, sha, notes;
    final boolean preview;
    final String[] downloads;

    UpdateRelease(JSONObject data, String channel, boolean mobile) throws Exception {
        // mkoai 2.0.42: the feed is the user's Drive manifest:
        // {"mobile": {"version","versionCode","apkUrl","sha256","notes"},
        //  "tv": {...}} — no "schema" field, no "size"/"minSdk" unless the
        // manifest adds them. Legacy schema-1 feeds (if any) are still
        // accepted when they carry a schema field.
        if (data.has("schema") && data.getInt("schema") != 1)
            throw new IOException("Unsupported feed");
        boolean dedicated = data.has("platform");
        if (dedicated && !UpdatePolicy.platform(mobile).equals(data.getString("platform")))
            throw new IOException("更新資訊與安裝版本不匹配");
        // mkoai manifest: per-edition objects under "mobile" / "tv".
        // Legacy feeds contain TV metadata at the root. A phone MUST have its own
        // mobile artifact; absence is not permission to use the TV package.
        JSONObject artifact;
        if (mobile && data.has("mobile")) artifact = data.getJSONObject("mobile");
        else if (!mobile && data.has("tv")) artifact = data.getJSONObject("tv");
        else if (!dedicated && mobile) artifact = data.getJSONObject("mobile");
        else artifact = data;
        code =
                artifact.has("versionCode")
                        ? artifact.getInt("versionCode")
                        : data.getInt("versionCode");
        version =
                artifact.has("versionName")
                        ? artifact.getString("versionName")
                        : artifact.has("version")
                                ? artifact.getString("version")
                                : data.has("versionName")
                                        ? data.getString("versionName")
                                        : data.getString("version");
        // mkoai APKs are built with minSdk 23; the manifest may omit it.
        minSdk = artifact.has("minSdk") ? artifact.getInt("minSdk") : data.optInt("minSdk", 23);
        // mkoai manifest may omit size (-1 = unknown; SHA-256 gates it).
        size = artifact.has("size") ? artifact.getLong("size") : -1;
        url = artifact.has("apkUrl") ? artifact.getString("apkUrl") : artifact.getString("url");
        sha = artifact.getString("sha256");
        preview =
                artifact.has("prerelease")
                        ? artifact.getBoolean("prerelease")
                        : data.optBoolean("prerelease", false);
        notes = artifact.optString("notes", data.optString("notes", ""));
        UpdatePolicy.validate(code, version, minSdk, size, sha, url, preview, channel, mobile);
        downloads =
                UpdatePolicy.downloads(version, mobile, url, artifact.optString("githubUrl", ""));
        if (notes.length() > 8000) throw new IOException("Notes too long");
    }
}
