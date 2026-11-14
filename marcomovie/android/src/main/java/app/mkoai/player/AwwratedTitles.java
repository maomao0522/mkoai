package app.mkoai.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * mkoai awwrated title map. awwrated.com/zh-tw/screen lists titles with a
 * correct Traditional Chinese name (lang.zhtw) alongside the English name
 * (lang.en) — the exact Taiwan translation, no machine conversion involved.
 * The page is a Next.js app; the data is embedded in the
 * __NEXT_DATA__ JSON (Apollo state), so the app fetches the page once and
 * parses the embedded JSON.
 *
 * <p>The map is preloaded in the background at app start (MainActivity) and
 * cached on disk (awwrated-titles.json under the app files dir), so
 * translations are instant on repeat launches and survive offline use until
 * the next refresh. A title not in the map falls back to the Douban lookup
 * (TitleTranslator), then to the original title — never a wrong name.
 */
final class AwwratedTitles {
    static final String PAGE_URL = "https://awwrated.com/zh-tw/screen";
    private static final String CACHE_FILE = "awwrated-titles.json";
    private static final Map<String, String> MAP = new HashMap<>();
    private static volatile boolean loaded = false;
    private static volatile boolean loading = false;

    /** Normalize an English title for map lookup (case/punctuation-insensitive). */
    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Traditional Chinese title for an English title, or null when unknown. */
    static String lookup(String englishTitle) {
        String key = norm(englishTitle);
        if (key.isEmpty()) return null;
        synchronized (MAP) {
            String hit = MAP.get(key);
            if (hit != null) return hit;
        }
        return null;
    }

    /** True once a map (disk cache or fresh fetch) has been loaded. */
    static boolean isLoaded() {
        return loaded;
    }

    /**
     * Load the map. First the disk cache (instant, may be stale), then a
     * background refresh from the live page. Safe to call repeatedly; the
     * refresh runs at most once per process.
     */
    static void preload(final File filesDir) {
        loadFromDisk(filesDir);
        if (loading) return;
        loading = true;
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Map<String, String> fresh = fetchMap();
                                    if (!fresh.isEmpty()) {
                                        synchronized (MAP) {
                                            MAP.clear();
                                            MAP.putAll(fresh);
                                        }
                                        loaded = true;
                                        saveToDisk(filesDir, fresh);
                                    }
                                } catch (Throwable ignored) {
                                } finally {
                                    loading = false;
                                }
                            }
                        },
                        "awwrated-preload")
                .start();
    }

    /** Fetch the live page and build the en -> zhtw map. */
    static Map<String, String> fetchMap() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; POCO F5) AppleWebKit/537.36"
                        + " (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        String html = Cms.fetch(PAGE_URL, headers);
        int start = html.indexOf("<script id=\"__NEXT_DATA__\"");
        if (start < 0) throw new IllegalStateException("no __NEXT_DATA__");
        start = html.indexOf('>', start) + 1;
        int end = html.indexOf("</script>", start);
        if (end < 0) throw new IllegalStateException("no __NEXT_DATA__ end");
        JSONObject root = new JSONObject(html.substring(start, end));
        JSONObject props = root.optJSONObject("props");
        JSONObject pageProps = props == null ? null : props.optJSONObject("pageProps");
        JSONObject state =
                pageProps == null ? null : pageProps.optJSONObject("__APOLLO_STATE__");
        if (state == null) throw new IllegalStateException("no __APOLLO_STATE__");
        Map<String, String> out = new HashMap<>();
        Iterator<String> keys = state.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!k.startsWith("Post:")) continue;
            JSONObject post = state.optJSONObject(k);
            if (post == null) continue;
            JSONObject data = post.optJSONObject("awwratedData");
            if (data == null) continue;
            JSONObject lang = data.optJSONObject("lang");
            if (lang == null) continue;
            JSONObject enObj = lang.optJSONObject("en");
            JSONObject zhObj = lang.optJSONObject("zhtw");
            String en = enObj == null ? "" : enObj.optString("title", "").trim();
            String zh = zhObj == null ? "" : zhObj.optString("title", "").trim();
            if (en.isEmpty() || zh.isEmpty()) continue;
            out.put(norm(en), zh);
        }
        return out;
    }

    private static void loadFromDisk(File filesDir) {
        if (loaded || filesDir == null) return;
        try {
            File f = new File(filesDir, CACHE_FILE);
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONObject root = new JSONObject(new String(buf, "UTF-8"));
            Map<String, String> disk = new HashMap<>();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                disk.put(k, root.optString(k, ""));
            }
            if (!disk.isEmpty()) {
                synchronized (MAP) {
                    MAP.clear();
                    MAP.putAll(disk);
                }
                loaded = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveToDisk(File filesDir, Map<String, String> map) {
        if (filesDir == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                root.put(e.getKey(), e.getValue());
            }
            try (FileOutputStream out =
                    new FileOutputStream(new File(filesDir, CACHE_FILE))) {
                out.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
