package app.mkoai.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * mkoai 2.0.60 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2060_checks.py.
 *
 * Covers:
 *  1. HomeScreen.mergeAllSources (Feature C): merges >=2 sources, dedupes
 *     the same title by normalized title, caps at the TV limit, caps each
 *     source's contribution, skips empty titles/ids.
 *  2. HomeScreen.fetchAggregated (Feature C performance): completes within
 *     the shared budget when a source is slow/dead (it is skipped, not
 *     waited on); never exceeds the TV card cap; paints progressively
 *     (onProgress fires with cards before the slow source finishes) on
 *     pool threads, never the caller's thread.
 *  3. Built-in ad-rule defaults (Cms.parseConfig): a juliang api with no
 *     rule gets the admedia host; a 360zyzz api with no rule gets the
 *     360zyx host; a mdzyapi.com api with no rule gets the play.modujx17.com
 *     host; a source WITH its own rule keeps it; an unrelated api
 *     gets no rule.
 *  4. Bundled 19-source default config (assets/default-config.json, via
 *     the -Dmkoai.defaultConfig property): 19 sites, all three ad rules
 *     (巨量/360/魔都動漫), the 6 removed sources absent, the 3 new sources
 *     present, and the two clean new sources (豪華/暴風） carry no rule.
 *  5. composeHomeVideos Douban-first order is preserved for the merged home.
 *  6. Performance constants: TV cap 40, budget 20s, 8 threads.
 */
public class Home2060Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(Object got, Object want, String name) {
        if (got == null ? want != null : !got.equals(want)) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static Cms.Video video(String id, String title) {
        Cms.Video v = new Cms.Video();
        v.id = id;
        v.title = title;
        return v;
    }

    static List<Cms.Video> videos(String prefix, int n) {
        List<Cms.Video> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(video(prefix + "-id-" + i, prefix + " Title " + i));
        return out;
    }

    static List<String> titles(List<Cms.Video> vs) {
        List<String> out = new ArrayList<>();
        for (Cms.Video v : vs) out.add(v.title);
        return out;
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. mergeAllSources ----
        List<List<Cms.Video>> two =
                Arrays.asList(
                        Arrays.asList(video("a1", "八仙"), video("a2", "流浪地球")),
                        Arrays.asList(video("b1", "八仙"), video("b2", "哪吒")));
        List<Cms.Video> merged = HomeScreen.mergeAllSources(two, HomeScreen.ALL_SOURCES_CAP);
        eq(merged.size(), 3, "merge: two sources merge to 3 unique titles");
        check(titles(merged).contains("八仙"), "merge: first source's 八仙 is kept");
        check(titles(merged).contains("流浪地球"), "merge: 流浪地球 is kept");
        check(titles(merged).contains("哪吒"), "merge: 哪吒 is kept");
        // Same title with different punctuation/spacing is still one title.
        List<List<Cms.Video>> dupes =
                Arrays.asList(
                        Arrays.asList(video("a1", "八仙！")),
                        Arrays.asList(video("b1", "八 仙")),
                        Arrays.asList(video("c1", "八仙")));
        eq(HomeScreen.mergeAllSources(dupes, HomeScreen.ALL_SOURCES_CAP).size(), 1,
                "merge: 八仙！ / 八 仙 / 八仙 dedupe to one card");
        // TV cap: even with many videos the merged list never exceeds 40.
        List<List<Cms.Video>> many = new ArrayList<>();
        for (int s = 0; s < 6; s++) many.add(videos("src" + s, 20));
        eq(HomeScreen.mergeAllSources(many, HomeScreen.ALL_SOURCES_CAP).size(),
                HomeScreen.ALL_SOURCES_CAP, "merge: total never exceeds the TV cap (40)");
        // Per-source cap: one source cannot contribute more than 10.
        List<List<Cms.Video>> oneBig = Arrays.asList(videos("only", 30));
        eq(HomeScreen.mergeAllSources(oneBig, HomeScreen.ALL_SOURCES_CAP).size(),
                HomeScreen.ALL_SOURCES_PER_SITE_CAP,
                "merge: one source contributes at most 10");
        // Empty title / empty id are skipped.
        List<List<Cms.Video>> junk =
                Arrays.asList(
                        Arrays.asList(video("x1", ""), video("", "NoId"), video("x2", "Good")));
        List<Cms.Video> clean = HomeScreen.mergeAllSources(junk, HomeScreen.ALL_SOURCES_CAP);
        eq(clean.size(), 1, "merge: empty title / empty id are skipped");
        eq(clean.get(0).title, "Good", "merge: only the valid video survives");
        eq(HomeScreen.mergeAllSources(null, HomeScreen.ALL_SOURCES_CAP).size(), 0,
                "merge: null input -> empty list");
        eq(HomeScreen.mergeAllSources(new ArrayList<>(), HomeScreen.ALL_SOURCES_CAP).size(), 0,
                "merge: empty input -> empty list");

        // ---- 2. fetchAggregated: budget, cap, progressive + async ----
        // (a) A slow source (30s) with a 2s budget is skipped; the call
        // returns in ~2s, not 30s.
        List<Callable<List<Cms.Video>>> jobs = new ArrayList<>();
        jobs.add(() -> videos("fast", 3));
        jobs.add(
                () -> {
                    Thread.sleep(30_000);
                    return videos("slow", 3);
                });
        jobs.add(() -> videos("mid", 2));
        long start = System.currentTimeMillis();
        List<Cms.Video> agg =
                HomeScreen.fetchAggregated(jobs, HomeScreen.ALL_SOURCES_CAP, 2_000, null);
        long elapsed = System.currentTimeMillis() - start;
        check(elapsed < 10_000,
                "fetch: slow source skipped within the budget (took " + elapsed + "ms, not 30s)");
        check(titles(agg).contains("fast Title 0"), "fetch: fast source's videos are in");
        check(titles(agg).contains("mid Title 0"), "fetch: mid source's videos are in");
        boolean hasSlow = false;
        for (Cms.Video v : agg) if (v.title.startsWith("slow")) hasSlow = true;
        check(!hasSlow, "fetch: slow source's videos are NOT in the result");
        // A throwing job (dead source) is skipped silently.
        List<Callable<List<Cms.Video>>> dead = new ArrayList<>();
        dead.add(() -> videos("ok", 2));
        dead.add(
                () -> {
                    throw new RuntimeException("dead source");
                });
        List<Cms.Video> aggDead =
                HomeScreen.fetchAggregated(dead, HomeScreen.ALL_SOURCES_CAP, 5_000, null);
        eq(aggDead.size(), 2, "fetch: a dead source is skipped, the live one still returns");
        // (b) Cap: jobs returning many videos still never exceed 40.
        List<Callable<List<Cms.Video>>> big = new ArrayList<>();
        for (int s = 0; s < 8; s++) big.add(() -> videos("big", 25));
        List<Cms.Video> aggBig =
                HomeScreen.fetchAggregated(big, HomeScreen.ALL_SOURCES_CAP, 10_000, null);
        check(aggBig.size() <= HomeScreen.ALL_SOURCES_CAP,
                "fetch: merged list never exceeds the TV cap (got " + aggBig.size() + ")");
        // (c) Progressive + async: onProgress fires with cards BEFORE the
        // slow source finishes, and jobs run on pool threads (not the caller).
        final AtomicLong firstProgressMs = new AtomicLong(-1);
        final AtomicLong slowDoneMs = new AtomicLong(-1);
        final AtomicReference<String> jobThread = new AtomicReference<>("");
        final List<List<Cms.Video>> progressSeen = new ArrayList<>();
        List<Callable<List<Cms.Video>>> prog = new ArrayList<>();
        prog.add(
                () -> {
                    jobThread.set(Thread.currentThread().getName());
                    return videos("quick", 4);
                });
        prog.add(
                () -> {
                    Thread.sleep(3_000);
                    slowDoneMs.set(System.currentTimeMillis());
                    return videos("slowpoke", 2);
                });
        long progStart = System.currentTimeMillis();
        List<Cms.Video> aggProg =
                HomeScreen.fetchAggregated(
                        prog,
                        HomeScreen.ALL_SOURCES_CAP,
                        10_000,
                        partial -> {
                            if (firstProgressMs.get() < 0) {
                                firstProgressMs.set(System.currentTimeMillis());
                            }
                            progressSeen.add(new ArrayList<>(partial));
                        });
        check(firstProgressMs.get() > 0, "fetch: onProgress fired at least once");
        check(
                firstProgressMs.get() > 0
                        && slowDoneMs.get() > 0
                        && firstProgressMs.get() < slowDoneMs.get(),
                "fetch: first paint happened before the slow source finished (progressive)");
        check(
                jobThread.get() != null && jobThread.get().startsWith("mkoai-home-aggregate"),
                "fetch: jobs run on the aggregate pool thread, not the caller ("
                        + jobThread.get()
                        + ")");
        check(
                !Thread.currentThread().getName().equals(jobThread.get()),
                "fetch: the pool thread is not the calling thread");
        check(
                progressSeen.size() >= 1 && !progressSeen.get(0).isEmpty(),
                "fetch: the first progress callback already carried cards");
        check(aggProg.size() >= 4, "fetch: the final merged list includes the quick source");

        // ---- 3. Built-in ad-rule defaults (Cms.parseConfig) ----
        Cms.Config juliang =
                Cms.parseConfig(
                        "{\"sites\":[{\"key\":\"j\",\"name\":\"巨量\",\"type\":1,\"api\":\"https://api.juliang.live/api/provide/vod/at/xml/\"}]}",
                        "");
        eq(juliang.sites.size(), 1, "builtin: juliang site parses");
        check(juliang.sites.get(0).hlsAdRules != null,
                "builtin: juliang (no own rule) gets a built-in rule");
        eq(
                juliang
                        .sites
                        .get(0)
                        .hlsAdRules
                        .getJSONArray("hosts")
                        .getString(0),
                "admedia.mujiziyuan99.com",
                "builtin: juliang rule host is admedia.mujiziyuan99.com");
        Cms.Config c360 =
                Cms.parseConfig(
                        "{\"sites\":[{\"key\":\"s\",\"name\":\"360\",\"type\":1,\"api\":\"https://360zyzz.com/api.php/provide/vod\"}]}",
                        "");
        check(c360.sites.get(0).hlsAdRules != null,
                "builtin: 360 (no own rule) gets a built-in rule");
        eq(
                c360.sites.get(0).hlsAdRules.getJSONArray("hosts").getString(0),
                "vod.360zyx.vip",
                "builtin: 360 rule host is vod.360zyx.vip");
        Cms.Config modu =
                Cms.parseConfig(
                        "{\"sites\":[{\"key\":\"m\",\"name\":\"魔都動漫\",\"type\":1,\"api\":\"https://www.mdzyapi.com/api.php/provide/vod/\"}]}",
                        "");
        check(modu.sites.get(0).hlsAdRules != null,
                "builtin: 魔都動漫 (no own rule) gets a built-in rule");
        eq(
                modu.sites.get(0).hlsAdRules.getJSONArray("hosts").getString(0),
                "play.modujx17.com",
                "builtin: 魔都動漫 rule host is play.modujx17.com");
        Cms.Config own =
                Cms.parseConfig(
                        "{\"sites\":[{\"key\":\"j\",\"name\":\"巨量\",\"type\":1,\"api\":\"https://api.juliang.live/api/provide/vod/at/xml/\",\"hlsAdRules\":{\"enabled\":true,\"hosts\":[\"my-own.example\"],\"urlPrefixes\":[]}}]}",
                        "");
        eq(
                own.sites.get(0).hlsAdRules.getJSONArray("hosts").getString(0),
                "my-own.example",
                "builtin: a source with its own rule keeps it (built-in does not override)");
        Cms.Config other =
                Cms.parseConfig(
                        "{\"sites\":[{\"key\":\"x\",\"name\":\"xgzy\",\"type\":1,\"api\":\"https://caiji.xgzyapi.com/api.php/provide/vod/at/xml/\"}]}",
                        "");
        check(other.sites.get(0).hlsAdRules == null,
                "builtin: an unrelated api gets no built-in rule");

        // ---- 4. Bundled 19-source default config ----
        String assetPath = System.getProperty("mkoai.defaultConfig");
        if (assetPath == null || assetPath.isEmpty()) {
            assetPath = "android/src/main/assets/default-config.json";
        }
        String bundled =
                new String(
                        java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(assetPath)),
                        java.nio.charset.StandardCharsets.UTF_8);
        Cms.Config def = Cms.parseConfig(bundled, "");
        eq(def.sites.size(), 19, "bundled: the default config has 19 sites");
        Set<String> names = new HashSet<>();
        for (Cms.Site s : def.sites) names.add(s.name);
        for (String removed : new String[] {"wujin", "电影天堂", "Mkoai1", "yzzy影視", "uku優酷", "niuniu"}) {
            check(!names.contains(removed), "bundled: removed source " + removed + " is absent");
        }
        for (String added : new String[] {"豪華資源", "魔都動漫", "暴風資源"}) {
            check(names.contains(added), "bundled: new source " + added + " is present");
        }
        Cms.Site bundledJuliang = null, bundled360 = null, bundledModu = null;
        for (Cms.Site s : def.sites) {
            if ("巨量".equals(s.name)) bundledJuliang = s;
            if ("360".equals(s.name)) bundled360 = s;
            if ("魔都動漫".equals(s.name)) bundledModu = s;
            if ("豪華資源".equals(s.name) || "暴風資源".equals(s.name)) {
                check(s.hlsAdRules == null,
                        "bundled: " + s.name + " is clean and carries no rule");
            }
        }
        check(bundledJuliang != null && bundledJuliang.hlsAdRules != null,
                "bundled: 巨量 carries its ad rule in the file");
        check(bundled360 != null && bundled360.hlsAdRules != null,
                "bundled: 360 carries its ad rule in the file");
        check(bundledModu != null && bundledModu.hlsAdRules != null,
                "bundled: 魔都動漫 carries its ad rule in the file");
        eq(
                bundledJuliang.hlsAdRules.getJSONArray("hosts").getString(0),
                "admedia.mujiziyuan99.com",
                "bundled: 巨量 rule host is admedia.mujiziyuan99.com");
        eq(
                bundled360.hlsAdRules.getJSONArray("hosts").getString(0),
                "vod.360zyx.vip",
                "bundled: 360 rule host is vod.360zyx.vip");
        eq(
                bundledModu.hlsAdRules.getJSONArray("hosts").getString(0),
                "play.modujx17.com",
                "bundled: 魔都動漫 rule host is play.modujx17.com");
        int ruleCount = 0;
        for (Cms.Site s : def.sites) if (s.hlsAdRules != null) ruleCount++;
        eq(ruleCount, 3, "bundled: exactly 3 sources carry an ad rule in the file");

        // ---- 5. composeHomeVideos Douban-first order preserved ----
        List<Cms.Video> douban = Arrays.asList(video("d1", "豆瓣新片甲"), video("d2", "豆瓣新片乙"));
        List<Cms.Video> composed =
                HomeScreen.composeHomeVideos(douban, merged, null);
        check(!composed.isEmpty() && "豆瓣新片甲".equals(composed.get(0).title),
                "compose: Douban 新片 still lead the merged home");
        check(composed.size() >= 2, "compose: merged source videos follow Douban");

        // ---- 6. Performance constants ----
        eq(HomeScreen.ALL_SOURCES_CAP, 40, "perf: TV merged-home cap is 40");
        eq(HomeScreen.ALL_SOURCES_PER_SITE_CAP, 10, "perf: per-source contribution cap is 10");
        eq(HomeScreen.ALL_SOURCES_BUDGET_MS, 20_000L, "perf: shared aggregate budget is 20s");
        eq(HomeScreen.ALL_SOURCES_THREADS, 8, "perf: aggregate uses 8 parallel threads");

        System.out.println(passed + " Home2060 checks passed");
    }
}
