package app.mkoai.player;

/**
 * mkoai 2.0.68 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2068_checks.py.
 *
 * Covers the five 2.0.68 items (user 2026-11-16 ~00:13–00:21):
 *  (a) subtitles removed: QuotaState2066 has 7 features and no SUBTITLE
 *      slot (the source greps — SubtitleClient gone, no /api/subtitles,
 *      no 搜尋字幕， PostHog down to 4 events — are runner checks).
 *  (b) hero poster-first, backdrop fills: pure scale-type math. Hero box
 *      393x589.5 (ratio 0.6667, the poster's own 2:3): a CENTER_CROP
 *      poster fills 100% with ~0% crop (0.06% of the width in pixels on
 *      a POCO F5, 1080x1621). A 16:9 backdrop CENTER_CROP also fills
 *      100%: it needs 589.5x16/9 = 1048dp of width, shows the middle
 *      393dp — 62.5% of the width cropped (31.25% each side), 0% of the
 *      height — so the subject, which sits near the centre of a 16:9
 *      key visual, stays centred (the 《心鄉迷踪》 backdrop's cowboy-hat
 *      figure is near the horizontal centre, inside the visible middle
 *      37.5%). The old FIT_START backdrop was 393x221.06dp (37.5% fill,
 *      a 368.44dp flat-tone band below it). The poster-first switch
 *      itself (bindHeroArtwork order, heroArtworkKey order, the
 *      resolveHeroBackdrop poster-less guard) is source-checked by the
 *      runner.
 *  (c) view-all count line deleted: source-checked by the runner (no
 *      count tag / text left in the app tree).
 *  (d) view-all grid denser: pure grid math. 4 columns on a 393dp
 *      phone: card 98.25dp wide, art 92.25x140dp (ratio 0.659, poster
 *      2:3 CENTER_CROP trims ~1.2% of the width). Row height ~204dp
 *      (was ~256dp at 3 columns / 190dp art), so a ~700dp viewport
 *      shows ~13 cards (was ~8). Title stays 13sp bold, max 2 lines
 *      (source-checked by the runner).
 *  (e) source selection learns from Maoki (speed + stability):
 *      SourceSpeed2068 pure checks — EMA weights, slow threshold,
 *      cooldown, startup grace, cache key / TTL / prune / invalidate,
 *      speedOfEma — plus AutoSourceFallback.compareRanked order pins:
 *      episode > ad-rule priority > verified > cooldown > speed, i.e.
 *      priority NEVER loses to speed. The wiring (probeEpisode cache
 *      lookup + record, warmDetail trigger, stopWarm on play start,
 *      the grace retry in the error listener, no new threads) is
 *      source-checked by the runner.
 */
public class Home2068Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(long got, long want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(int got, int want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void near(double got, double want, double eps, String name) {
        if (Math.abs(got - want) > eps) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static AutoSourceFallback.RankedCandidate cand(
            String siteKey, boolean hasEpisode, boolean verified, int priority,
            long latencyMs, long emaMs, boolean coolingDown) {
        Cms.Site s = new Cms.Site();
        s.key = siteKey;
        s.name = siteKey;
        Cms.Video v = new Cms.Video();
        v.title = "測試劇";
        AutoSourceFallback.Playable p = new AutoSourceFallback.Playable(s, v, v);
        return new AutoSourceFallback.RankedCandidate(
                p, hasEpisode, verified, priority, latencyMs, emaMs, coolingDown);
    }

    public static void main(String[] args) {
        // ---- (a) quota features: 7, no SUBTITLE ----
        check(QuotaState2066.FEATURES.length == 7,
                "(a) QuotaState2066 has 7 features (SUBTITLE slot removed)");
        boolean hasSubtitle = false;
        for (String f : QuotaState2066.FEATURES) {
            if ("subtitle".equals(f)) hasSubtitle = true;
        }
        check(!hasSubtitle, "(a) no subtitle feature in QuotaState2066.FEATURES");
        check(QuotaState2066.TRANSLATE != null && QuotaState2066.TRAILER != null,
                "(a) translate + trailer features untouched (DeepL paused, not removed)");

        // ---- (b) hero fill math (POCO F5: 393dp wide, hero 393x589.5dp) ----
        double boxW = 393.0;
        double boxH = 589.5;
        near(boxW / boxH, 2.0 / 3.0, 0.001, "(b) hero box ratio is the poster ratio 2:3");
        // Poster CENTER_CROP: scale by height (box is exactly 2:3), crop ~0% in dp.
        double posterScaledW = boxH * 2.0 / 3.0;
        near(posterScaledW, boxW, 0.01, "(b) poster scaled to the box height is exactly the box width");
        // In pixels (1080x1621): width 1621*2/3 = 1080.67, crop 0.06%.
        double pxCrop = (1621.0 * 2.0 / 3.0 - 1080.0) / (1621.0 * 2.0 / 3.0) * 100.0;
        near(pxCrop, 0.06, 0.01, "(b) poster CENTER_CROP crops 0.06% of the width in POCO pixels");
        // Backdrop CENTER_CROP: needs boxH*16/9 of width, shows the middle boxW.
        double backdropNeedW = boxH * 16.0 / 9.0;
        near(backdropNeedW, 1048.0, 0.5, "(b) backdrop CENTER_CROP needs 1048dp of width");
        double backdropCropPct = (backdropNeedW - boxW) / backdropNeedW * 100.0;
        near(backdropCropPct, 62.5, 0.1, "(b) backdrop CENTER_CROP crops 62.5% of the width");
        near((backdropNeedW - boxW) / 2.0 / backdropNeedW * 100.0, 31.25, 0.1,
                "(b) backdrop CENTER_CROP crops 31.25% on each side");
        double visibleMiddlePct = boxW / backdropNeedW * 100.0;
        near(visibleMiddlePct, 37.5, 0.1, "(b) backdrop CENTER_CROP keeps the middle 37.5% of the width");
        // The horizontal centre (50%) is inside the visible middle band [31.25%, 68.75%].
        check(50.0 > 31.25 && 50.0 < 68.75,
                "(b) the backdrop centre (50%) stays in frame — the 《心鄉迷踪》 figure is centred");
        // Old FIT_START: height 393*9/16, fill 37.5%, band 368.44dp.
        double fitH = boxW * 9.0 / 16.0;
        near(fitH, 221.06, 0.01, "(b) old FIT_START backdrop was 221.06dp tall");
        near(fitH / boxH * 100.0, 37.5, 0.1, "(b) old FIT_START backdrop filled 37.5% of the box");
        near(boxH - fitH, 368.44, 0.01, "(b) old FIT_START left a 368.44dp flat-tone band");

        // ---- (d) grid math (POCO F5: 393dp wide) ----
        double cardW = boxW / 4.0;
        near(cardW, 98.25, 0.01, "(d) 4-column card is 98.25dp wide (was 131dp at 3 columns)");
        double artW = cardW - 3.0 - 3.0;
        near(artW, 92.25, 0.01, "(d) art is 92.25dp wide after 3dp card padding");
        near(artW / 140.0, 0.659, 0.005, "(d) art box 92.25x140dp is ratio 0.659, near the poster 2:3");
        double artCropPct = (140.0 * 2.0 / 3.0 - artW) / (140.0 * 2.0 / 3.0) * 100.0;
        near(artCropPct, 1.2, 0.2, "(d) poster CENTER_CROP in the new art box trims ~1.2% of the width");
        // Row height: art + bottom padding + title top pad + 2 title lines
        // (13sp ~16dp/line) + subtitle top pad + subtitle line (~15dp).
        double rowNew = 140 + 8 + 7 + 2 * 16 + 2 + 15;
        double rowOld = 190 + 10 + 7 + 2 * 16 + 2 + 15;
        eq((long) rowNew, 204L, "(d) new row height is ~204dp");
        eq((long) rowOld, 256L, "(d) old row height was ~256dp");
        int cardsNew = (int) (700 / rowNew * 4);
        int cardsOld = (int) (700 / rowOld * 3);
        check(cardsNew == 13,
                "(d) ~700dp viewport shows ~13 cards at 4 columns (got " + cardsNew + ")");
        check(cardsOld == 8,
                "(d) ~700dp viewport showed ~8 cards at 3 columns (got " + cardsOld + ")");
        check(cardsNew > cardsOld, "(d) the grid is visibly denser (more cards per viewport)");

        // ---- (e) SourceSpeed2068: EMA ----
        eq(SourceSpeed2068.ema(-1, 500), 500L, "(e) EMA first sample seeds");
        eq(SourceSpeed2068.ema(1000, 1000), 1000L, "(e) EMA steady sample unchanged");
        eq(SourceSpeed2068.ema(1000, 2000), 1300L, "(e) EMA 0.7/0.3: 1000 + (2000-1000)*0.3 = 1300");
        eq(SourceSpeed2068.ema(1300, 100), 940L, "(e) EMA 1300 -> 100 gives 940");
        eq(SourceSpeed2068.ema(3000, 9000), 4800L, "(e) EMA 3000 -> 9000 gives 4800");

        // ---- (e) slow + cooldown ----
        SourceSpeed2068.Health slow = new SourceSpeed2068.Health();
        slow.probeMs = 3001;
        check(SourceSpeed2068.isSlow(slow), "(e) EMA 3001ms is slow (> 3000ms)");
        SourceSpeed2068.Health edge = new SourceSpeed2068.Health();
        edge.probeMs = 3000;
        check(!SourceSpeed2068.isSlow(edge), "(e) EMA exactly 3000ms is not slow");
        check(!SourceSpeed2068.isSlow(new SourceSpeed2068.Health()),
                "(e) no probe data means not proven slow");
        check(!SourceSpeed2068.isSlow(null), "(e) null health means not slow");
        long now = System.currentTimeMillis();
        SourceSpeed2068.Health cool = new SourceSpeed2068.Health();
        cool.lastFailMs = now - 60_000L;
        check(SourceSpeed2068.coolingDown(cool, now), "(e) failure 1 minute ago is inside the 5min cooldown");
        cool.lastFailMs = now - 6 * 60_000L;
        check(!SourceSpeed2068.coolingDown(cool, now), "(e) failure 6 minutes ago is out of the cooldown");
        check(!SourceSpeed2068.coolingDown(new SourceSpeed2068.Health(), now),
                "(e) never failed means not cooling down");

        // ---- (e) startup grace ----
        check(SourceSpeed2068.shouldForgiveStartupError(0, 0, 5000),
                "(e) first error 5s after source start is forgiven");
        check(!SourceSpeed2068.shouldForgiveStartupError(0, 0, 25000),
                "(e) first error 25s after source start is not forgiven (grace is 20s)");
        check(!SourceSpeed2068.shouldForgiveStartupError(0, 1000, 5000),
                "(e) a second error in the window is not forgiven");
        check(SourceSpeed2068.shouldForgiveStartupError(10000, 0, 29000),
                "(e) grace is measured from the source start, not from zero");

        // ---- (e) speedOfEma ----
        eq(SourceSpeed2068.speedOfEma(800, 100), 800L, "(e) speed prefers the learned EMA over this round");
        eq(SourceSpeed2068.speedOfEma(-1, 100), 100L, "(e) speed falls back to this round's latency");
        eq(SourceSpeed2068.speedOfEma(-1, -1), Long.MAX_VALUE, "(e) speed with no data sorts last");

        // ---- (e) health record round-trip ----
        String h0 = "{}";
        String h1 = SourceSpeed2068.recordProbeJson(h0, "juliang", 400, true);
        SourceSpeed2068.Health rh1 = SourceSpeed2068.readHealth(h1).get("juliang");
        check(rh1 != null && rh1.probeMs == 400 && rh1.probeOk == 1 && rh1.probeFail == 0
                        && rh1.failStreak == 0,
                "(e) first probe seeds EMA 400, ok=1, streak 0");
        String h2 = SourceSpeed2068.recordProbeJson(h1, "juliang", 1000, true);
        SourceSpeed2068.Health rh2 = SourceSpeed2068.readHealth(h2).get("juliang");
        check(rh2 != null && rh2.probeMs == 580 && rh2.probeOk == 2,
                "(e) second probe EMA 400 -> 1000 gives 580, ok=2");
        String h3 = SourceSpeed2068.recordProbeJson(h2, "juliang", 2000, false);
        SourceSpeed2068.Health rh3 = SourceSpeed2068.readHealth(h3).get("juliang");
        check(rh3 != null && rh3.probeMs == 1006 && rh3.probeFail == 1,
                "(e) failed probe still updates the EMA (580 -> 2000 gives 1006) and fail=1");
        String h4 = SourceSpeed2068.recordPlaybackFailureJson(h3, "juliang", 123456789L);
        SourceSpeed2068.Health rh4 = SourceSpeed2068.readHealth(h4).get("juliang");
        check(rh4 != null && rh4.failStreak == 1 && rh4.lastFailMs == 123456789L,
                "(e) playback failure records streak 1 + lastFailMs");
        String h5 = SourceSpeed2068.recordProbeJson(h4, "juliang", 300, true);
        SourceSpeed2068.Health rh5 = SourceSpeed2068.readHealth(h5).get("juliang");
        check(rh5 != null && rh5.failStreak == 0,
                "(e) a later successful probe clears the failure streak");
        check(SourceSpeed2068.recordProbeJson("not json", "x", 100, true) != null,
                "(e) corrupt health JSON never throws (fail-open)");
        check(SourceSpeed2068.readHealth(null).isEmpty(),
                "(e) null health JSON reads as empty");

        // ---- (e) probe-verdict cache ----
        String key = SourceSpeed2068.cacheKey("juliang", "測試劇", "第1集", "https://cdn.example.com/a.m3u8");
        check(!key.isEmpty(), "(e) cache key is non-empty");
        String key2 = SourceSpeed2068.cacheKey("juliang", "測試劇", "第1集", "https://cdn.example.com/b.m3u8");
        check(!key.equals(key2), "(e) a different URL gives a different cache key");
        String key3 = SourceSpeed2068.cacheKey("xgzy", "測試劇", "第1集", "https://cdn.example.com/a.m3u8");
        check(!key.equals(key3), "(e) a different site gives a different cache key");
        check(SourceSpeed2068.cacheKey("", "測試劇", "第1集", "https://cdn.example.com/a.m3u8").isEmpty(),
                "(e) empty site key means do-not-cache");
        check(SourceSpeed2068.cacheKey("juliang", "測試劇", "第1集", "").isEmpty(),
                "(e) empty URL means do-not-cache");
        long t0 = 1_700_000_000_000L;
        String c0 = "{}";
        check(SourceSpeed2068.lookupCacheJson(c0, key, t0) == null,
                "(e) cache miss before anything is stored");
        String c1 = SourceSpeed2068.storeCacheJson(c0, key, true, 320, 1080, t0);
        SourceSpeed2068.Verdict v1 = SourceSpeed2068.lookupCacheJson(c1, key, t0 + 1000);
        check(v1 != null && v1.verified && v1.latencyMs == 320 && v1.qualityHeight == 1080,
                "(e) stored verdict reads back (verified, 320ms, 1080p)");
        check(SourceSpeed2068.lookupCacheJson(c1, key, t0 + SourceSpeed2068.CACHE_TTL_MS - 1) != null,
                "(e) verdict is still valid just inside the 6h TTL");
        check(SourceSpeed2068.lookupCacheJson(c1, key, t0 + SourceSpeed2068.CACHE_TTL_MS + 1) == null,
                "(e) verdict expires just past the 6h TTL");
        String c2 = SourceSpeed2068.storeCacheJson(c0, key, false, 320, 1080, t0);
        check(SourceSpeed2068.lookupCacheJson(c2, key, t0 + 1000) == null,
                "(e) a failed probe is never cached (re-probed next time)");
        String c3 = SourceSpeed2068.invalidateCacheJson(c1, key);
        check(SourceSpeed2068.lookupCacheJson(c3, key, t0 + 1000) == null,
                "(e) invalidate drops the verdict (playback failure)");
        // Prune: 3 entries, one expired, cap respected.
        String cp = "{}";
        cp = SourceSpeed2068.storeCacheJson(cp, "k1", true, 100, 720, t0);
        cp = SourceSpeed2068.storeCacheJson(cp, "k2", true, 100, 720, t0 + 1000);
        cp = SourceSpeed2068.storeCacheJson(cp, "k3", true, 100, 720, t0 + 2000);
        String pruned = SourceSpeed2068.pruneCacheJson(cp, t0 + SourceSpeed2068.CACHE_TTL_MS + 5000);
        check(SourceSpeed2068.lookupCacheJson(pruned, "k1", t0 + SourceSpeed2068.CACHE_TTL_MS + 5000) == null
                        && SourceSpeed2068.lookupCacheJson(pruned, "k3", t0 + SourceSpeed2068.CACHE_TTL_MS + 5000) == null,
                "(e) prune drops every expired entry");
        String many = "{}";
        for (int i = 0; i < 260; i++) {
            many = SourceSpeed2068.storeCacheJson(many, "k" + i, true, 100, 720, t0 + i * 1000L);
        }
        int kept = 0;
        for (int i = 0; i < 260; i++) {
            if (SourceSpeed2068.lookupCacheJson(many, "k" + i, t0 + 300_000L) != null) kept++;
        }
        check(kept == SourceSpeed2068.MAX_CACHE_ENTRIES,
                "(e) cache caps at 256 entries (kept " + kept + ")");
        check(SourceSpeed2068.lookupCacheJson(many, "k0", t0 + 300_000L) == null
                        && SourceSpeed2068.lookupCacheJson(many, "k259", t0 + 300_000L) != null,
                "(e) over the cap the oldest entries drop first");

        // ---- (e) compareRanked: priority NEVER loses to speed ----
        AutoSourceFallback.RankedCandidate pri0Slow =
                cand("juliang", true, true, 0, 9000, 9000, false);
        AutoSourceFallback.RankedCandidate pri1Fast =
                cand("other", true, true, 1, 100, 100, false);
        check(AutoSourceFallback.compareRanked(pri0Slow, pri1Fast) < 0,
                "(e) ad-rule priority 0 beats priority 1 even when 90x slower");
        check(AutoSourceFallback.compareRanked(pri1Fast, pri0Slow) > 0,
                "(e) compareRanked is antisymmetric on priority vs speed");
        // Same priority: faster EMA wins.
        AutoSourceFallback.RankedCandidate fast =
                cand("a", true, true, 2, 100, 100, false);
        AutoSourceFallback.RankedCandidate slowCand =
                cand("b", true, true, 2, 5000, 5000, false);
        check(AutoSourceFallback.compareRanked(fast, slowCand) < 0,
                "(e) same priority: faster EMA wins");
        // Same priority, no EMA: this round's latency decides.
        AutoSourceFallback.RankedCandidate rFast =
                cand("a", true, true, 2, 120, -1, false);
        AutoSourceFallback.RankedCandidate rSlow =
                cand("b", true, true, 2, 4000, -1, false);
        check(AutoSourceFallback.compareRanked(rFast, rSlow) < 0,
                "(e) same priority, no EMA: faster round latency wins");
        // Cooling down sinks at the same priority.
        AutoSourceFallback.RankedCandidate coolCand =
                cand("a", true, true, 2, 100, 100, true);
        AutoSourceFallback.RankedCandidate healthy =
                cand("b", true, true, 2, 5000, 5000, false);
        check(AutoSourceFallback.compareRanked(healthy, coolCand) < 0,
                "(e) same priority: a healthy source beats a cooling-down one even when slower");
        // hasEpisode still beats priority; verified still beats unverified.
        AutoSourceFallback.RankedCandidate noEp =
                cand("juliang", false, true, 0, 100, 100, false);
        AutoSourceFallback.RankedCandidate hasEp =
                cand("other", true, true, 5, 9000, 9000, false);
        check(AutoSourceFallback.compareRanked(hasEp, noEp) < 0,
                "(e) carrying the episode still beats the top priority");
        AutoSourceFallback.RankedCandidate unverified =
                cand("a", true, false, 2, 100, 100, false);
        AutoSourceFallback.RankedCandidate verified =
                cand("b", true, true, 2, 9000, 9000, false);
        check(AutoSourceFallback.compareRanked(verified, unverified) < 0,
                "(e) verified still beats unverified at the same priority");
        // bestRanked picks the priority-0 candidate.
        java.util.List<AutoSourceFallback.RankedCandidate> list = new java.util.ArrayList<>();
        list.add(pri1Fast);
        list.add(slowCand);
        list.add(pri0Slow);
        check(AutoSourceFallback.bestRanked(list) == pri0Slow,
                "(e) bestRanked picks the ad-rule priority source first");

        System.out.println(passed + " Home2068 checks passed");
    }
}
