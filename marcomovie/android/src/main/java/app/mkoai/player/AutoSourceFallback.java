package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * mkoai auto source fallback: when the current source cannot load a title's detail
 * (timeout / unreachable / bad format), search every other searchable source for the
 * same title in parallel and probe each match's detail. A source whose detail loads
 * with playable lines is usable — the player itself is the real test. The episode-URL
 * probe only ORDERS candidates (verified first); it never rejects one (2.0.44: a
 * direct m3u8 answering 403 to a bare HEAD is anti-hotlink, not a dead stream).
 * The best-ranked candidate wins — episode-carrying first, then probe-verified,
 * then source priority — so the user never picks a source by hand and never lands
 * on a dead-end error page.
 */
final class AutoSourceFallback {
    static final class Playable {
        final Cms.Site site;
        /** Search-result listing (poster/title) used to open the detail page. */
        final Cms.Video listing;
        /** Fetched detail (with playable lines), passed as `ready` so it is not re-fetched. */
        final Cms.Video detail;

        Playable(Cms.Site site, Cms.Video listing, Cms.Video detail) {
            this.site = site;
            this.listing = listing;
            this.detail = detail;
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        // mkoai: normalize to Simplified first, so a Traditional title matches
        // the same work stored in Simplified (and vice versa).
        return ChineseConvert.t2s(s).replaceAll("[\\s\\p{Punct}·•・]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasPlayableLines(MainActivity a, Cms.Site site, Cms.Video v) {
        if (v == null || v.lines == null) return false;
        boolean hideNetdisk = a.prefs.getBoolean(Prefs.HIDE_NETDISK, false);
        for (Cms.Line line : v.lines) {
            if (line.episodes == null || line.episodes.isEmpty()) continue;
            // mkoai: when the user hides netdisk features, a netdisk-only detail does
            // not count as playable — keep looking for a non-netdisk source.
            if (hideNetdisk) {
                String firstUrl = line.episodes.get(0).url;
                if (PlaybackController.looksLikeNetdiskLine(site, line, firstUrl)) continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Best search hit for the wanted title: exact match (season suffixes
     * stripped), or a result that starts with the wanted title — else none.
     * 2.0.42: the old want.contains(t) direction let a SHORTER result
     * match a LONGER wanted title (the Problem A false positive, e.g.
     * wanted 「交換身分過聖誕」 matching a source's 「交換身分」). A null
     * here is fail-safe: the caller simply tries the next source.
     */
    private static Cms.Video bestMatch(List<Cms.Video> videos, String title) {
        if (videos == null || videos.isEmpty()) return null;
        String want = norm(title);
        if (want.isEmpty()) {
            for (Cms.Video v : videos) if (!SearchVariants.isSearchJunk(v)) return v;
            return null;
        }
        for (Cms.Video v : videos) {
            // mkoai 2.0.34: never pick a trailer-only or commentary-only
            // entry as the "match" — it is not the show.
            if (SearchVariants.isSearchJunk(v)) continue;
            if (SearchVariants.strictTitleMatch(want, norm(v.title))) return v;
        }
        return null;
    }

    /**
     * Find a playable source for {@code listing}. Calls {@code done} once on the UI
     * thread with the best playable (site, listing, detail), or null when no source
     * can play this title. {@code failedSiteKey} is skipped (it just failed).
     * {@code wantedEpisode} (may be null) is the episode the user wants: sources
     * whose detail carries it rank first, but a source is never rejected for
     * lacking it — the caller decides (2.0.43: has-title-but-no-episode shows
     * the 「無法播放」 dialog instead of silently playing another episode).
     *
     * mkoai 2.0.44: the episode-URL probe is ranking-only, NEVER a hard reject.
     * A direct stream URL that answers 403 (anti-hotlink) or times out does not
     * mean the player cannot play it — the player resolves the stream with its
     * own parser and headers. Catalog presence (detail loads with playable
     * lines) is what makes a source usable; the probe only orders candidates.
     * A candidate that carries the wanted episode AND probes verified wins
     * after a 1.5s grace (so a higher-priority source still in flight, e.g.
     * the ad-free 巨量, is not beaten by a faster lower-priority one); a
     * candidate carrying the wanted episode from the top-priority source
     * wins immediately (nothing can outrank it). If nothing verifies, the
     * best-ranked collected candidate is delivered when all sources report
     * — never null while any source has the title.
     */
    static void find(
            MainActivity a,
            Cms.Video listing,
            String failedSiteKey,
            MainActivity.Done<Playable> done) {
        find(a, listing, failedSiteKey, null, done);
    }

    static void find(
            MainActivity a,
            Cms.Video listing,
            String failedSiteKey,
            String wantedEpisode,
            MainActivity.Done<Playable> done) {
        final int ticket = a.generation;
        String title = Cms.searchTitle(listing);
        if (title == null || title.isEmpty() || a.config == null) {
            a.ui.post(
                    () -> {
                        try {
                            done.accept(null);
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        // mkoai 2.0.34: youth mode (default OFF) excludes adult-mixed
        // sources from auto source selection.
        final boolean youthOn = a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
        // 2.0.42: effective 倫理 hiding (youth ON + 「隱藏倫理片」 ON).
        final boolean hideLunli = SearchVariants.hideLunli(a);
        List<Cms.Site> sites = new ArrayList<>();
        for (Cms.Site s : a.config.sites) {
            if (!s.searchable || !a.available(s) || s.key.equals(failedSiteKey)) continue;
            if (youthOn && SearchVariants.isAdultSource(s)) continue;
            sites.add(s);
        }
        sites = SourcePreferences.prioritized(a, sites);
        if (sites.isEmpty()) {
            a.ui.post(
                    () -> {
                        try {
                            done.accept(null);
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        // mkoai 2.0.68: a warmed candidate (detail-page pre-resolve) plays
        // immediately — no search, no probe, no waiting for the best
        // (Maoki takePrepared). The warmed detail carries all episodes;
        // takeWarmed only delivers it when it carries the wanted one.
        RankedCandidate warmed = takeWarmed(a, title, wantedEpisode, failedSiteKey);
        if (warmed != null) {
            if (a.isFinishing() || ticket != a.generation) return;
            final Playable warmResult = warmed.playable;
            a.ui.post(
                    () -> {
                        try {
                            done.accept(warmResult);
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        final List<Cms.Site> ordered = sites;
        final java.util.Map<String, Integer> priorityByKey = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) priorityByKey.put(ordered.get(i).key, i);
        final int total = sites.size();
        final List<RankedCandidate> collected =
                java.util.Collections.synchronizedList(new ArrayList<RankedCandidate>());
        final AtomicInteger finished = new AtomicInteger();
        final AtomicBoolean delivered = new AtomicBoolean();
        final AtomicBoolean graceStarted = new AtomicBoolean();
        final SearchCoordinator[] holder = new SearchCoordinator[1];
        Runnable deliverBest =
                () -> {
                    if (!delivered.compareAndSet(false, true)) return;
                    if (holder[0] != null) {
                        try {
                            holder[0].close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (a.isFinishing() || ticket != a.generation) return;
                    RankedCandidate best = bestRanked(collected);
                    final Playable result = best == null ? null : best.playable;
                    a.ui.post(
                            () -> {
                                try {
                                    done.accept(result);
                                } catch (Exception ignored) {
                                }
                            });
                };
        // 2.0.44: after the first episode-carrying VERIFIED candidate arrives,
        // give the higher-priority sources a 1.5s grace to arrive too, so the
        // ad-free priority source (巨量) still wins over a faster lower-priority
        // one. The grace never delays delivery past all-sources-finished.
        Runnable startGrace =
                () -> {
                    if (!graceStarted.compareAndSet(false, true)) return;
                    a.ui.postDelayed(deliverBest, 1500L);
                };
        SearchCoordinator coordinator =
                new SearchCoordinator(
                        (site, query) -> Cms.listing(a.engine(site).search(query, 1)).videos,
                        (site, videos, outcome) -> {
                            if (outcome == SearchCoordinator.Outcome.RESULT) {
                                // mkoai 2.0.34: youth mode drops adult-marked
                                // hits before picking a match.
                                List<Cms.Video> usable = videos;
                                if (youthOn && videos != null && !videos.isEmpty()) {
                                    usable = new ArrayList<>();
                                    for (Cms.Video v : videos) {
                                        if (!SearchVariants.isAdult(v, hideLunli)) usable.add(v);
                                    }
                                }
                                Cms.Video match = bestMatch(usable, title);
                                if (match != null) {
                                    try {
                                        String raw = a.engine(site).detail(match.id);
                                        Cms.Listing parsed = Cms.listing(raw);
                                        if (!parsed.videos.isEmpty()) {
                                            Cms.Video detail = parsed.videos.get(0);
                                            if ((detail.id == null || detail.id.trim().isEmpty())
                                                    && match.id != null)
                                                detail.id = match.id.trim();
                                            if (hasPlayableLines(a, site, detail)) {
                                                // 2.0.44: catalog presence is
                                                // enough to be a candidate —
                                                // the probe only ranks, it
                                                // never rejects. Record the
                                                // candidate BEFORE probing,
                                                // so a top-priority source
                                                // carrying the wanted episode
                                                // wins immediately instead of
                                                // waiting out its own probe.
                                                boolean hasEpisode =
                                                        detailHasEpisode(detail, wantedEpisode);
                                                int pri =
                                                        priorityByKey.getOrDefault(
                                                                site.key, ordered.size());
                                                // mkoai 2.0.68: learned speed + health ride
                                                // along for the ranking tiebreakers (they
                                                // never outrank the priority above).
                                                SourceSpeed2068.Health health =
                                                        SourceSpeed2068.healthFor(a, site.key);
                                                // mkoai 2.0.78: quality tier of the
                                                // line auto-pick would play
                                                // here, captured at collection
                                                // time for the ranking.
                                                int tier =
                                                        QualityTier2078.tierForDetail(
                                                                detail,
                                                                a.prefs.getString(
                                                                        Prefs.PREFERRED_LINE, ""),
                                                                a.prefs.getBoolean(
                                                                        Prefs.HIDE_NETDISK, false));
                                                RankedCandidate rc =
                                                        new RankedCandidate(
                                                                new Playable(site, match, detail),
                                                                hasEpisode,
                                                                false,
                                                                pri,
                                                                health == null ? -1 : health.probeMs,
                                                                health != null
                                                                        && SourceSpeed2068.coolingDown(
                                                                                health,
                                                                                System.currentTimeMillis()),
                                                                tier);
                                                collected.add(rc);
                                                if (hasEpisode && pri == 0) {
                                                    // Nothing can outrank the
                                                    // top-priority source
                                                    // carrying the episode.
                                                    deliverBest.run();
                                                    return;
                                                }
                                                Probe probe =
                                                        probeEpisode(
                                                                a, site, detail, wantedEpisode);
                                                rc.verified = probe.verified;
                                                rc.latencyMs = probe.latencyMs;
                                                if (hasEpisode && probe.verified)
                                                    startGrace.run();
                                            }
                                        }
                                    } catch (Exception ignored) {
                                        /* This source cannot play it; try the next one. */
                                    }
                                }
                            }
                            if (finished.incrementAndGet() >= total) deliverBest.run();
                        },
                        12000L,
                        25000L);
        holder[0] = coordinator;
        // mkoai: search with the Simplified form (most sources are Simplified).
        coordinator.start(sites, ChineseConvert.t2s(title));
    }

    /**
     * 2.0.44: does this fetched detail carry the wanted episode (by normalized
     * name — 第01集 matches 第1集 — or exact name)? A null or blank wanted
     * episode always matches: the caller only wants "any playable lines".
     */
    static boolean detailHasEpisode(Cms.Video detail, String wantedEpisode) {
        if (wantedEpisode == null || wantedEpisode.trim().isEmpty()) return true;
        if (detail == null || detail.lines == null) return false;
        String wantNorm = WatchHistory.normEpisode(wantedEpisode);
        String wantTrim = wantedEpisode.trim();
        for (Cms.Line line : detail.lines) {
            if (line == null || line.episodes == null) continue;
            for (Cms.Episode ep : line.episodes) {
                if (ep == null || ep.name == null) continue;
                if (WatchHistory.normEpisode(ep.name).equals(wantNorm)
                        || ep.name.trim().equals(wantTrim)) return true;
            }
        }
        return false;
    }

    /** 2.0.44: a collected reselect candidate, with the ranking signals. {@code verified} and {@code latencyMs} are filled in after the probe runs. {@code emaMs} / {@code coolingDown} are the learned speed + health at collection time (mkoai 2.0.68). {@code qualityTier} is the HD/normal/TC tier of the line auto-pick would actually play for this detail (mkoai 2.0.78), captured at collection time so warmed candidates carry it too. */
    static final class RankedCandidate {
        final Playable playable;
        final boolean hasEpisode;
        volatile boolean verified;
        final int priority;
        /** This round's probe latency (ms); -1 when the probe never ran / the URL is not direct. */
        volatile long latencyMs = -1;
        /** Learned probe EMA (ms) at collection time; -1 when nothing was ever recorded. */
        final long emaMs;
        /** True when this source failed playback within the cooldown at collection time. */
        final boolean coolingDown;
        /** Quality tier of the line that would play (QualityTier2078); lower is better. */
        final int qualityTier;

        RankedCandidate(Playable playable, boolean hasEpisode, boolean verified, int priority) {
            this(playable, hasEpisode, verified, priority, -1, false);
        }

        RankedCandidate(
                Playable playable,
                boolean hasEpisode,
                boolean verified,
                int priority,
                long emaMs,
                boolean coolingDown) {
            this(playable, hasEpisode, verified, priority, -1, emaMs, coolingDown);
        }

        RankedCandidate(
                Playable playable,
                boolean hasEpisode,
                boolean verified,
                int priority,
                long emaMs,
                boolean coolingDown,
                int qualityTier) {
            this(playable, hasEpisode, verified, priority, -1, emaMs, coolingDown, qualityTier);
        }

        RankedCandidate(
                Playable playable,
                boolean hasEpisode,
                boolean verified,
                int priority,
                long latencyMs,
                long emaMs,
                boolean coolingDown) {
            this(
                    playable,
                    hasEpisode,
                    verified,
                    priority,
                    latencyMs,
                    emaMs,
                    coolingDown,
                    QualityTier2078.TIER_NORMAL);
        }

        RankedCandidate(
                Playable playable,
                boolean hasEpisode,
                boolean verified,
                int priority,
                long latencyMs,
                long emaMs,
                boolean coolingDown,
                int qualityTier) {
            this.playable = playable;
            this.hasEpisode = hasEpisode;
            this.verified = verified;
            this.priority = priority;
            this.latencyMs = latencyMs;
            this.emaMs = emaMs;
            this.coolingDown = coolingDown;
            this.qualityTier = qualityTier;
        }
    }

    /**
     * 2.0.44: better candidate first — carries the wanted episode, then
     * source priority order (the user's ad-free priority sources first),
     * then probe-verified. Priority outranks the probe on purpose: the
     * probe is a flaky signal (a 403 is usually anti-hotlink, not a dead
     * stream), while the priority list is the user's explicit choice — and
     * a genuinely dead stream is caught by the player's own error
     * fallback at play time.
     *
     * mkoai 2.0.68: two speed tiebreakers AFTER the three above, so they
     * can never outrank the ad-rule priority — a source inside its
     * post-failure cooldown sinks below a healthy one at the same
     * priority, then the faster one wins (learned EMA first, else this
     * round's probe latency). Pure and unit-tested.
     *
     * mkoai 2.0.78: the HD/normal/TC quality tier sits after the ad-rule
     * priority and before probe-verified — an HD candidate beats a TC
     * candidate at the same priority even when the TC probe was far
     * faster, and a TC candidate is last among its priority peers.
     * The ad-rule priority still wins first (it is the user's explicit
     * choice for ad handling; quality is a ranking signal like speed).
     */
    static int compareRanked(RankedCandidate x, RankedCandidate y) {
        int c = Boolean.compare(y.hasEpisode, x.hasEpisode);
        if (c != 0) return c;
        c = Integer.compare(x.priority, y.priority);
        if (c != 0) return c;
        c = Integer.compare(x.qualityTier, y.qualityTier);
        if (c != 0) return c;
        c = Boolean.compare(y.verified, x.verified);
        if (c != 0) return c;
        c = Boolean.compare(x.coolingDown, y.coolingDown);
        if (c != 0) return c;
        return Long.compare(
                SourceSpeed2068.speedOfEma(x.emaMs, x.latencyMs),
                SourceSpeed2068.speedOfEma(y.emaMs, y.latencyMs));
    }

    /** 2.0.44: best-ranked collected candidate, or null when none was collected. */
    static RankedCandidate bestRanked(List<RankedCandidate> list) {
        RankedCandidate best = null;
        for (RankedCandidate c : list) {
            if (c == null) continue;
            if (best == null || compareRanked(c, best) < 0) best = c;
        }
        return best;
    }

    // ---- mkoai 2.0.68: detail-page pre-resolve (Maoki _preResolveTop + warmup) ----
    //
    // While the user reads the detail page, the play episode's URLs are
    // prepared in the background: the on-screen source's episode URL is
    // probed (its detail is already in hand — no search, no re-fetch),
    // then the top 3 prioritized sources are searched + detailed +
    // probed, then up to 2 known-slow sources (EMA > 3s) get extra slots
    // after the fast ones — Maoki's 3 slots + 2 slow extras. Results land
    // in the probe cache (probeEpisode) and in the prepared map below,
    // so a later find() for the same title can deliver without searching
    // (takeWarmed — Maoki takePrepared).
    //
    // Bounds: at most 6 sites, one coordinator, the shared
    // SearchCoordinator pools (no new threads). The warm stops when play
    // starts (stopWarm, called from PlaybackController.resolvePlayer —
    // Maoki's race-start stop) or when the user leaves the page (the
    // listener sees a stale ticket and closes itself). A tap never waits
    // for the warm: find() only uses entries that already finished.
    private static final class WarmedEntry {
        final RankedCandidate candidate;
        final long createdMs;

        WarmedEntry(RankedCandidate candidate, long createdMs) {
            this.candidate = candidate;
            this.createdMs = createdMs;
        }
    }

    /** Prepared candidates by normalized title (memory only — never synced, never persisted). */
    private static final java.util.concurrent.ConcurrentHashMap<String, List<WarmedEntry>> WARMED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The one in-flight warm coordinator, if any. */
    private static volatile SearchCoordinator warmCoordinator;

    private static final Object WARM_LOCK = new Object();

    /** Stop an in-flight warm (play started, or a newer warm replaces it). */
    static void stopWarm() {
        synchronized (WARM_LOCK) {
            if (warmCoordinator != null) {
                try {
                    warmCoordinator.close();
                } catch (Exception ignored) {
                }
                warmCoordinator = null;
            }
        }
    }

    /**
     * Warm the play episode's sources for one detail page. Fire-and-forget:
     * returns immediately, never blocks the tap. The warmed episode is the
     * one the Play button will play ({@code playEp} from
     * {@code detailPlayEpisode}).
     */
    static void warmDetail(MainActivity a, Cms.Site site, Cms.Video detail, Cms.Episode playEp) {
        try {
            if (a == null || a.config == null || site == null || detail == null || playEp == null) {
                return;
            }
            String title = Cms.searchTitle(detail);
            if (title == null || title.isEmpty()) return;
            String wantedEpisode = playEp.name;
            stopWarm();
            final int ticket = a.generation;
            final boolean youthOn = a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
            final boolean hideLunli = SearchVariants.hideLunli(a);
            List<Cms.Site> sites = new ArrayList<>();
            for (Cms.Site s : a.config.sites) {
                if (!s.searchable || !a.available(s)) continue;
                if (youthOn && SearchVariants.isAdultSource(s)) continue;
                sites.add(s);
            }
            final List<Cms.Site> ordered = SourcePreferences.prioritized(a, sites);
            List<Cms.Site> warm = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            warm.add(site);
            seen.add(site.key);
            for (Cms.Site s : ordered) {
                if (warm.size() >= 1 + SourceSpeed2068.PRERESOLVE_SLOTS) break;
                if (seen.add(s.key)) warm.add(s);
            }
            int slowAdded = 0;
            for (Cms.Site s : ordered) {
                if (slowAdded >= SourceSpeed2068.PRERESOLVE_SLOW_EXTRA) break;
                if (seen.contains(s.key)) continue;
                if (SourceSpeed2068.isSlow(SourceSpeed2068.healthFor(a, s.key))) {
                    seen.add(s.key);
                    warm.add(s);
                    slowAdded++;
                }
            }
            final Cms.Site onScreen = site;
            final Cms.Video onScreenDetail = detail;
            final String warmTitle = title;
            final java.util.Map<String, Integer> priorityByKey = new java.util.HashMap<>();
            for (int i = 0; i < ordered.size(); i++) priorityByKey.put(ordered.get(i).key, i);
            final int total = warm.size();
            final AtomicInteger finished = new AtomicInteger();
            final List<WarmedEntry> entries =
                    java.util.Collections.synchronizedList(new ArrayList<WarmedEntry>());
            WARMED.put(norm(warmTitle), entries);
            final SearchCoordinator coordinator =
                    new SearchCoordinator(
                            (s2, query) -> {
                                if (s2.key.equals(onScreen.key)) {
                                    // Detail already in hand — no search.
                                    List<Cms.Video> one = new ArrayList<>();
                                    one.add(onScreenDetail);
                                    return one;
                                }
                                return Cms.listing(a.engine(s2).search(query, 1)).videos;
                            },
                            (s2, videos, outcome) -> {
                                try {
                                    if (ticket != a.generation) {
                                        stopWarm();
                                        return;
                                    }
                                    if (outcome == SearchCoordinator.Outcome.RESULT) {
                                    List<Cms.Video> usable = videos;
                                    if (youthOn && videos != null && !videos.isEmpty()) {
                                        usable = new ArrayList<>();
                                        for (Cms.Video v : videos) {
                                            if (!SearchVariants.isAdult(v, hideLunli))
                                                usable.add(v);
                                        }
                                    }
                                    Cms.Video match = bestMatch(usable, warmTitle);
                                    if (match != null) {
                                        try {
                                            Cms.Video warmDetail;
                                            if (s2.key.equals(onScreen.key)) {
                                                // No re-fetch — use the
                                                // detail the page shows.
                                                warmDetail = onScreenDetail;
                                            } else {
                                                String raw = a.engine(s2).detail(match.id);
                                                Cms.Listing parsed = Cms.listing(raw);
                                                if (parsed.videos.isEmpty()) return;
                                                warmDetail = parsed.videos.get(0);
                                                if ((warmDetail.id == null
                                                                || warmDetail.id.trim().isEmpty())
                                                        && match.id != null)
                                                    warmDetail.id = match.id.trim();
                                            }
                                            if (hasPlayableLines(a, s2, warmDetail)) {
                                                boolean hasEpisode =
                                                        detailHasEpisode(warmDetail, wantedEpisode);
                                                int pri =
                                                        priorityByKey.getOrDefault(
                                                                s2.key, ordered.size());
                                                SourceSpeed2068.Health health =
                                                        SourceSpeed2068.healthFor(a, s2.key);
                                                Probe probe =
                                                        probeEpisode(
                                                                a, s2, warmDetail, wantedEpisode);
                                                // mkoai 2.0.78: quality tier
                                                // of the line auto-pick would
                                                // play here.
                                                int warmTier =
                                                        QualityTier2078.tierForDetail(
                                                                warmDetail,
                                                                a.prefs.getString(
                                                                        Prefs.PREFERRED_LINE, ""),
                                                                a.prefs.getBoolean(
                                                                        Prefs.HIDE_NETDISK, false));
                                                entries.add(
                                                        new WarmedEntry(
                                                                new RankedCandidate(
                                                                        new Playable(
                                                                                s2, match, warmDetail),
                                                                        hasEpisode,
                                                                        probe.verified,
                                                                        pri,
                                                                        probe.latencyMs,
                                                                        health == null
                                                                                ? -1
                                                                                : health.probeMs,
                                                                        health != null
                                                                                && SourceSpeed2068
                                                                                        .coolingDown(
                                                                                                health,
                                                                                                System
                                                                                                        .currentTimeMillis()),
                                                                        warmTier),
                                                                System.currentTimeMillis()));
                                            }
                                        } catch (Exception ignored) {
                                            /* This source cannot play it; skip it. */
                                        }
                                    }
                                }
                                } finally {
                                    if (finished.incrementAndGet() >= total) stopWarm();
                                }
                            },
                            12000L,
                            25000L);
            synchronized (WARM_LOCK) {
                warmCoordinator = coordinator;
            }
            coordinator.start(warm, ChineseConvert.t2s(warmTitle));
        } catch (Throwable ignored) {
            // Warm failure never affects anything — the tap takes the cold path.
        }
    }

    /**
     * A warmed candidate for this title + episode, or null. Only entries
     * that already finished and are still fresh (3 min) count, only ones
     * whose detail carries the wanted episode are delivered, and the
     * failed site is skipped — find() then searches normally instead of
     * waiting.
     */
    private static RankedCandidate takeWarmed(
            MainActivity a, String title, String wantedEpisode, String failedSiteKey) {
        if (title == null || title.isEmpty()) return null;
        List<WarmedEntry> entries = WARMED.get(norm(title));
        if (entries == null || entries.isEmpty()) return null;
        List<WarmedEntry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        long now = System.currentTimeMillis();
        RankedCandidate best = null;
        for (WarmedEntry e : snapshot) {
            if (e == null || e.candidate == null || e.candidate.playable == null) continue;
            if (now - e.createdMs > SourceSpeed2068.PREPARED_FRESH_MS) continue;
            if (failedSiteKey != null
                    && failedSiteKey.equals(e.candidate.playable.site.key)) continue;
            // The warmed detail carries all episodes — re-check it for the
            // wanted episode (the warm probed one episode; the user may
            // ask for another).
            boolean hasEpisode =
                    detailHasEpisode(e.candidate.playable.detail, wantedEpisode);
            if (!hasEpisode) continue;
            RankedCandidate fresh =
                    new RankedCandidate(
                            e.candidate.playable,
                            true,
                            e.candidate.verified,
                            e.candidate.priority,
                            e.candidate.latencyMs,
                            e.candidate.emaMs,
                            e.candidate.coolingDown,
                            e.candidate.qualityTier);
            if (best == null || compareRanked(fresh, best) < 0) best = fresh;
        }
        return best;
    }

    /**
     * mkoai manual source picker: search every searchable source for the same title and collect
     * ALL sources whose detail actually loads with playable lines (not just the first). Calls
     * {@code done} once on the UI thread with the list (possibly empty). When the user hides
     * netdisk features, netdisk sources are excluded. The current source is included first when
     * it is playable, so the user can see where they are.
     */
    static void findAll(
            MainActivity a,
            Cms.Video listing,
            String currentSiteKey,
            MainActivity.Done<List<Playable>> done) {
        final int ticket = a.generation;
        String title = Cms.searchTitle(listing);
        if (title == null || title.isEmpty() || a.config == null) {
            a.ui.post(
                    () -> {
                        try {
                            done.accept(new ArrayList<>());
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        boolean hideNetdisk = a.prefs.getBoolean(Prefs.HIDE_NETDISK, false);
        // mkoai 2.0.34: youth mode (default OFF) excludes adult-mixed
        // sources from the manual source picker too.
        final boolean youthOn = a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
        List<Cms.Site> sites = new ArrayList<>();
        for (Cms.Site s : a.config.sites) {
            if (!s.searchable || !a.available(s)) continue;
            if (hideNetdisk && SourcePreferences.isNetdiskSite(s)) continue;
            if (youthOn && SearchVariants.isAdultSource(s)) continue;
            sites.add(s);
        }
        sites = SourcePreferences.prioritized(a, sites);
        if (sites.isEmpty()) {
            a.ui.post(
                    () -> {
                        try {
                            done.accept(new ArrayList<>());
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        final int total = sites.size();
        final List<Playable> found = java.util.Collections.synchronizedList(new ArrayList<>());
        // 2.0.44: probe result per site — the probe only ranks (verified
        // first), it never drops a source from the manual picker.
        final java.util.concurrent.ConcurrentHashMap<String, Boolean> verifiedByKey =
                new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger finished = new AtomicInteger();
        final AtomicBoolean delivered = new AtomicBoolean();
        Runnable deliver =
                () -> {
                    if (!delivered.compareAndSet(false, true)) return;
                    if (a.isFinishing() || ticket != a.generation) return;
                    // Current source first, then probe-verified, then the rest
                    // in prioritized order.
                    List<Playable> ordered = new ArrayList<>(found);
                    ordered.sort(
                            (x, y) -> {
                                int c =
                                        Boolean.compare(
                                                !x.site.key.equals(currentSiteKey),
                                                !y.site.key.equals(currentSiteKey));
                                if (c != 0) return c;
                                return Boolean.compare(
                                        !verifiedByKey.getOrDefault(x.site.key, false),
                                        !verifiedByKey.getOrDefault(y.site.key, false));
                            });
                    final List<Playable> result = ordered;
                    a.ui.post(
                            () -> {
                                try {
                                    done.accept(result);
                                } catch (Exception ignored) {
                                }
                            });
                };
        SearchCoordinator coordinator =
                new SearchCoordinator(
                        (site, query) -> Cms.listing(a.engine(site).search(query, 1)).videos,
                        (site, videos, outcome) -> {
                            if (outcome == SearchCoordinator.Outcome.RESULT) {
                                Cms.Video match = bestMatch(videos, title);
                                if (match != null) {
                                    try {
                                        String raw = a.engine(site).detail(match.id);
                                        Cms.Listing parsed = Cms.listing(raw);
                                        if (!parsed.videos.isEmpty()) {
                                            Cms.Video detail = parsed.videos.get(0);
                                            if ((detail.id == null || detail.id.trim().isEmpty())
                                                    && match.id != null)
                                                detail.id = match.id.trim();
                                            if (hasPlayableLines(a, site, detail)) {
                                                // mkoai 2.0.44: a detail with
                                                // lines is usable — the probe
                                                // only ranks (verified first),
                                                // it never drops a source. A
                                                // 403 to a bare HEAD is
                                                // anti-hotlink, not a dead
                                                // stream; the player resolves
                                                // it at play time.
                                                Probe probe = probeEpisode(a, site, detail);
                                                verifiedByKey.put(site.key, probe.verified);
                                                found.add(new Playable(site, match, detail));
                                            }
                                        }
                                    } catch (Exception ignored) {
                                        /* This source cannot play it; skip it. */
                                    }
                                }
                            }
                            if (finished.incrementAndGet() >= total) deliver.run();
                        },
                        12000L,
                        25000L);
        // mkoai: search with the Simplified form (most sources are Simplified).
        coordinator.start(sites, ChineseConvert.t2s(title));
    }

    /**
     * mkoai 2.0.36: a stream probe. The 2.0.34 auto-select only checked that
     * the detail page loads with lines — a line whose episode URL is dead
     * (404 / hangs / unparseable) still "won" and the player spun forever
     * on it. The probe checks the URL the player would actually open:
     * <ul>
     *   <li>direct stream URL (.m3u8 / .mp4 / …): HEAD it (3s budget) and
     *       measure the round-trip; an .m3u8 is also fetched (32KB cap) so
     *       its declared resolution feeds the quality ranking.</li>
     *   <li>anything else (page URL that needs the sniffer, proxy://,
     *       magnet:, …): cannot be verified cheaply — reported as
     *       verified-but-unknown, never blocked, quality from the line
     *       name only, latency unknown (-1).</li>
     * </ul>
     * mkoai 2.0.44: the result is RANKING-ONLY — a failed probe never
     * rejects the source (see find()). A 403 here usually means the CDN's
     * anti-hotlink check, not a dead stream: the player sends its own
     * headers and resolves the URL at play time. The probe now sends
     * browser-grade headers (full Chrome UA + the source API's origin as
     * Referer) to cut down on those false 403s.
     */
    static final class Probe {
        final boolean verified;
        final boolean direct;
        final long latencyMs;
        final int qualityHeight;

        Probe(boolean verified, boolean direct, long latencyMs, int qualityHeight) {
            this.verified = verified;
            this.direct = direct;
            this.latencyMs = latencyMs;
            this.qualityHeight = qualityHeight;
        }
    }

    /** 2.0.44: browser-grade User-Agent for the probe, so anti-hotlink CDNs stop answering 403 to a bare HEAD. */
    static final String PROBE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 2.0.44: Referer for the probe — the origin of the source's own API host. Pure and unit-tested. */
    static String probeReferer(Cms.Site site) {
        if (site == null || site.api == null) return "";
        try {
            java.net.URL u = new java.net.URL(site.api);
            StringBuilder r =
                    new StringBuilder(u.getProtocol()).append("://").append(u.getHost());
            if (u.getPort() != -1) r.append(':').append(u.getPort());
            return r.append('/').toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void setProbeHeaders(java.net.HttpURLConnection conn, String referer) {
        conn.setRequestProperty("User-Agent", PROBE_USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
    }

    /** 2.0.44: URL of the wanted episode on this line (normalized-name or exact match), else null when this line does not carry it. */
    static String episodeUrl(Cms.Line line, String wantedEpisode) {
        Cms.Episode ep = findEpisode(line, wantedEpisode);
        return ep == null ? null : ep.url;
    }

    /** mkoai 2.0.68: the episode episodeUrl() would return, so the caller can also use its name (probe-cache key). */
    private static Cms.Episode findEpisode(Cms.Line line, String wantedEpisode) {
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        if (wantedEpisode != null && !wantedEpisode.trim().isEmpty()) {
            String wantNorm = WatchHistory.normEpisode(wantedEpisode);
            String wantTrim = wantedEpisode.trim();
            for (Cms.Episode ep : line.episodes) {
                if (ep == null || ep.name == null) continue;
                if (WatchHistory.normEpisode(ep.name).equals(wantNorm)
                        || ep.name.trim().equals(wantTrim)) return ep;
            }
            return null;
        }
        return line.episodes.get(0);
    }

    /** Probe the first episode of the line the detail page would auto-pick. */
    static Probe probeEpisode(MainActivity a, Cms.Site site, Cms.Video detail) {
        return probeEpisode(a, site, detail, null);
    }

    /**
     * 2.0.44: probe the WANTED episode's URL when it can be found in the
     * detail (preferred line first, then other lines), else the first
     * episode of the preferred line. The result is ranking-only — a failed
     * probe never rejects the source (see find()).
     */
    static Probe probeEpisode(MainActivity a, Cms.Site site, Cms.Video detail, String wantedEpisode) {
        int nameQuality = 0;
        String url = null;
        String probedEpisode = wantedEpisode;
        try {
            if (detail == null || detail.lines == null || detail.lines.isEmpty())
                return new Probe(false, false, -1, 0);
            int lineIdx = SourcePreferences.lineIndex(a, detail);
            if (lineIdx < 0 || lineIdx >= detail.lines.size()) lineIdx = 0;
            Cms.Line line = detail.lines.get(lineIdx);
            nameQuality = SearchVariants.lineQualityHeight(line.name);
            Cms.Episode probedEp = findEpisode(line, wantedEpisode);
            if (probedEp == null) {
                // Wanted episode not on the preferred line — look on other lines.
                for (Cms.Line other : detail.lines) {
                    if (other == line) continue;
                    probedEp = findEpisode(other, wantedEpisode);
                    if (probedEp != null) break;
                }
            }
            if (probedEp == null) {
                // Wanted episode is on no line — probe the first episode of
                // the preferred line so the ranking still measures the stream.
                if (line.episodes != null && !line.episodes.isEmpty())
                    probedEp = line.episodes.get(0);
            }
            if (probedEp == null) return new Probe(false, false, -1, nameQuality);
            url = probedEp.url;
            if (probedEp.name != null) probedEpisode = probedEp.name;
            if (url == null) return new Probe(false, false, -1, nameQuality);
            url = LocalProxy.rewrite(url.trim());
        } catch (Exception ignored) {
            return new Probe(false, false, -1, nameQuality);
        }
        // Not a direct stream URL (parse-type page, proxy, magnet, …):
        // the player resolves it at play time, so the probe cannot judge
        // it — pass it through as verified-but-unknown.
        if (!Cms.isDirectStreamUrl(url)) return new Probe(true, false, -1, nameQuality);
        // mkoai 2.0.68: a cached verified verdict skips the network probe
        // entirely (6h TTL, keyed by site + title + episode + URL).
        String cacheKey =
                SourceSpeed2068.cacheKey(
                        site == null ? "" : site.key,
                        Cms.searchTitle(detail),
                        probedEpisode,
                        url);
        SourceSpeed2068.Verdict cached = SourceSpeed2068.lookupCached(a, cacheKey);
        if (cached != null) {
            return new Probe(
                    true, true, cached.latencyMs, Math.max(nameQuality, cached.qualityHeight));
        }
        boolean isM3u8 =
                url.split("[?#]", 2)[0].toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")
                        || url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8?");
        long start = System.currentTimeMillis();
        String referer = probeReferer(site);
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("HEAD");
            setProbeHeaders(conn, referer);
            int code = conn.getResponseCode();
            if (code == 405 || code == 501) {
                // Some CDNs refuse HEAD — retry as a 1KB ranged GET.
                conn.disconnect();
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setInstanceFollowRedirects(true);
                setProbeHeaders(conn, referer);
                conn.setRequestProperty("Range", "bytes=0-1023");
                code = conn.getResponseCode();
            }
            long latency = System.currentTimeMillis() - start;
            if (code < 200 || code >= 400)
                return finishProbe(
                        a, site, detail, probedEpisode, url,
                        new Probe(false, true, latency, nameQuality));
            if (!isM3u8)
                return finishProbe(
                        a, site, detail, probedEpisode, url,
                        new Probe(true, true, latency, nameQuality));
            // HLS master playlist: read the declared resolutions.
            String playlist = readProbeBody(conn, url, 32768, referer);
            int playlistHeight = SearchVariants.m3u8BestHeight(playlist);
            return finishProbe(
                    a, site, detail, probedEpisode, url,
                    new Probe(
                            true, true, latency, Math.max(nameQuality, playlistHeight)));
        } catch (Exception ignored) {
            return finishProbe(
                    a, site, detail, probedEpisode, url,
                    new Probe(false, true, System.currentTimeMillis() - start, nameQuality));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * mkoai 2.0.68: one completed probe's bookkeeping — record the latency
     * sample for the speed ranking (skipped when this thread was
     * interrupted: a cancelled probe is a lower bound, not a latency)
     * and store a verified verdict in the 6h cache. Failures are recorded
     * but never cached (they may have been transient). Returns the probe
     * unchanged.
     */
    private static Probe finishProbe(
            MainActivity a,
            Cms.Site site,
            Cms.Video detail,
            String episode,
            String url,
            Probe probe) {
        try {
            String siteKey = site == null ? "" : site.key;
            SourceSpeed2068.recordProbe(a, siteKey, probe.latencyMs, probe.verified);
            if (probe.verified) {
                String key =
                        SourceSpeed2068.cacheKey(
                                siteKey, Cms.searchTitle(detail), episode, url);
                SourceSpeed2068.storeCached(a, key, true, probe.latencyMs, probe.qualityHeight);
            }
        } catch (Throwable ignored) {
        }
        return probe;
    }

    private static String readProbeBody(
            java.net.HttpURLConnection head, String url, int maxBytes, String referer) {
        java.net.HttpURLConnection get = null;
        try {
            get = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            get.setConnectTimeout(3000);
            get.setReadTimeout(3000);
            get.setInstanceFollowRedirects(true);
            setProbeHeaders(get, referer);
            if (get.getResponseCode() >= 400) return "";
            java.io.InputStream in = get.getInputStream();
            byte[] buf = new byte[maxBytes];
            int read = 0;
            int n;
            while (read < maxBytes
                    && (n = in.read(buf, read, maxBytes - read)) != -1) read += n;
            return new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        } finally {
            if (get != null) get.disconnect();
        }
    }

    /** One candidate for merged-card auto-select: an already-known search hit. */
    static final class Candidate {
        final Cms.Site site;
        final Cms.Video video;

        Candidate(Cms.Site site, Cms.Video video) {
            this.site = site;
            this.video = video;
        }
    }

    /**
     * mkoai 2.0.34: auto-select for a merged search card. The candidates are
     * already known (same title from different sources) — rank them
     * Maoki-style (title match, then episode completeness, then source
     * priority) and race the top 4 in parallel.
     *
     * mkoai 2.0.36: a candidate only wins after its stream probe passes
     * (a direct episode URL that answers dead is filtered out; parse-type
     * URLs pass through as unverifiable). Each batch's verified
     * candidates are ranked by quality (m3u8 resolution / line-name
     * marker), then probe speed, then the Maoki-style rank — so a
     * higher-quality, faster source wins over merely the first to answer.
     * If nothing verifies within the 12s budget, the best-ranked
     * candidate with playable lines is delivered as a fallback (the
     * caller then opens its detail page so the detail-page fallback can
     * take over). Calls {@code done} once on the UI thread with the
     * winner, or null when no candidate can play.
     *
     * mkoai 2.0.44: the probe is ranking-only everywhere (see find()) — an
     * unverified candidate is never dropped here either; it stays in the
     * fallback ranking and is delivered when nothing verifies.
     */
    static void findFromCandidates(
            MainActivity a,
            List<Candidate> candidates,
            String query,
            MainActivity.Done<Playable> done) {
        final int ticket = a.generation;
        // mkoai 2.0.34: youth mode (default OFF) excludes adult-mixed
        // sources and adult-marked titles from merged-card auto-select.
        final boolean youthOn = a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
        // 2.0.42: effective 倫理 hiding (youth ON + 「隱藏倫理片」 ON).
        final boolean hideLunli = SearchVariants.hideLunli(a);
        List<Candidate> ranked = new ArrayList<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c != null
                        && c.site != null
                        && c.video != null
                        && !SearchVariants.isSearchJunk(c.video)
                        && !(youthOn
                                && (SearchVariants.isAdultSource(c.site)
                                        || SearchVariants.isAdult(c.video, hideLunli)))) ranked.add(c);
            }
        }
        if (ranked.isEmpty() || a.config == null) {
            a.ui.post(
                    () -> {
                        try {
                            done.accept(null);
                        } catch (Exception ignored) {
                        }
                    });
            return;
        }
        // Source priority order (user's preferred source first), for tie-breaking.
        List<Cms.Site> ordered = new ArrayList<>();
        for (Cms.Site s : a.config.sites) if (a.available(s)) ordered.add(s);
        ordered = SourcePreferences.prioritized(a, ordered);
        final java.util.Map<String, Integer> priority = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) priority.put(ordered.get(i).key, i);
        final String q = query == null ? "" : query;
        // javac 8: a lambda may only capture effectively-final locals.
        // `ordered` was reassigned above, so capture its size once here.
        final int fallbackPriority = ordered.size();
        ranked.sort(
                (x, y) ->
                        SearchVariants.compareAutoCandidates(
                                q,
                                x.video,
                                x.site.name,
                                priority.getOrDefault(x.site.key, fallbackPriority),
                                y.video,
                                y.site.name,
                                priority.getOrDefault(y.site.key, fallbackPriority)));
        final List<Candidate> order = ranked;
        final AtomicReference<Playable> winner = new AtomicReference<>();
        // mkoai 2.0.36: first playable (by rank) whose detail has lines,
        // regardless of the probe — the last-resort fallback when no
        // candidate verifies within the budget. Indexed by rank so the
        // fallback is the best-ranked one, not the first to finish.
        final Playable[] fallbackByRank = new Playable[order.size()];
        final AtomicBoolean delivered = new AtomicBoolean();
        Runnable deliver =
                () -> {
                    if (!delivered.compareAndSet(false, true)) return;
                    if (a.isFinishing() || ticket != a.generation) return;
                    Playable p = winner.get();
                    if (p == null) {
                        for (Playable f : fallbackByRank) {
                            if (f != null) {
                                p = f;
                                break;
                            }
                        }
                    }
                    final Playable result = p;
                    a.ui.post(
                            () -> {
                                try {
                                    done.accept(result);
                                } catch (Exception ignored) {
                                }
                            });
                };
        /** A candidate whose detail loads with lines AND whose stream probe passed. */
        final class Verified {
            final Playable playable;
            final Probe probe;
            final int rank;

            Verified(Playable playable, Probe probe, int rank) {
                this.playable = playable;
                this.probe = probe;
                this.rank = rank;
            }
        }
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        4,
                        r -> {
                            Thread t = new Thread(r, "mkoai-autopick");
                            t.setDaemon(true);
                            return t;
                        });
        final java.util.concurrent.CompletionService<Verified> completion =
                new java.util.concurrent.ExecutorCompletionService<>(pool);
        final long deadline = System.currentTimeMillis() + 12000L;
        Thread driver =
                new Thread(
                        () -> {
                            try {
                                int next = 0;
                                while (next < order.size() && winner.get() == null) {
                                    int batchEnd = Math.min(order.size(), next + 4);
                                    int submitted = 0;
                                    for (int i = next; i < batchEnd; i++) {
                                        final Candidate c = order.get(i);
                                        final int rank = i;
                                        completion.submit(
                                                () -> {
                                                    try {
                                                        String raw =
                                                                a.engine(c.site)
                                                                        .detail(c.video.id);
                                                        Cms.Listing parsed = Cms.listing(raw);
                                                        if (!parsed.videos.isEmpty()) {
                                                            Cms.Video detail =
                                                                    parsed.videos.get(0);
                                                            if ((detail.id == null
                                                                            || detail.id
                                                                                    .trim()
                                                                                    .isEmpty())
                                                                    && c.video.id != null)
                                                                detail.id = c.video.id.trim();
                                                            if (hasPlayableLines(
                                                                    a, c.site, detail)) {
                                                                Playable p =
                                                                        new Playable(
                                                                                c.site,
                                                                                c.video,
                                                                                detail);
                                                                fallbackByRank[rank] = p;
                                                                Probe probe =
                                                                        probeEpisode(
                                                                                a, c.site, detail);
                                                                if (probe.verified)
                                                                    return new Verified(
                                                                            p, probe, rank);
                                                            }
                                                        }
                                                    } catch (Exception ignored) {
                                                        /* This candidate cannot play; try the next one. */
                                                    }
                                                    return null;
                                                });
                                        submitted++;
                                    }
                                    next = batchEnd;
                                    // mkoai 2.0.36: collect this batch's
                                    // verified candidates and pick the
                                    // best (quality, then speed, then
                                    // rank) instead of taking the first to
                                    // finish. After the first verified
                                    // result, give the rest of the batch
                                    // 1.5s grace to arrive, then decide.
                                    List<Verified> batchVerified = new ArrayList<>();
                                    long firstVerifiedAt = 0;
                                    int collected = 0;
                                    while (collected < submitted) {
                                        long remain = deadline - System.currentTimeMillis();
                                        if (remain <= 0) break;
                                        long wait = remain;
                                        if (firstVerifiedAt > 0) {
                                            wait =
                                                    Math.min(
                                                            remain,
                                                            1500L
                                                                    - (System.currentTimeMillis()
                                                                            - firstVerifiedAt));
                                            if (wait <= 0) break;
                                        }
                                        java.util.concurrent.Future<Verified> f = null;
                                        try {
                                            f =
                                                    completion.poll(
                                                            wait,
                                                            java.util.concurrent.TimeUnit
                                                                    .MILLISECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                        if (f == null) break;
                                        collected++;
                                        Verified v = null;
                                        try {
                                            v = f.get();
                                        } catch (Exception ignored) {
                                        }
                                        if (v != null) {
                                            if (firstVerifiedAt == 0)
                                                firstVerifiedAt = System.currentTimeMillis();
                                            batchVerified.add(v);
                                        }
                                    }
                                    Verified best = null;
                                    for (Verified v : batchVerified) {
                                        if (best == null
                                                || SearchVariants.compareVerified(
                                                                v.probe.qualityHeight,
                                                                v.probe.latencyMs,
                                                                v.rank,
                                                                best.probe.qualityHeight,
                                                                best.probe.latencyMs,
                                                                best.rank)
                                                        < 0) best = v;
                                    }
                                    if (best != null && winner.compareAndSet(null, best.playable))
                                        break;
                                }
                            } finally {
                                pool.shutdownNow();
                                deliver.run();
                            }
                        },
                        "mkoai-autopick-driver");
        driver.setDaemon(true);
        driver.start();
    }

    /** mkoai 2.0.48: is 「自動選源直接播放」 on? Default ON (never stored = ON). */
    static boolean autoDirectPlay(MainActivity a) {
        try {
            if (a == null || a.prefs == null) return true;
            return a.prefs.getBoolean(Prefs.AUTO_DIRECT_PLAY, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** mkoai 2.0.48: picker dialog labels (pure — unit-testable). Index 0 is auto-select. */
    static String[] pickerNames(List<Playable> playables, String currentSiteKey) {
        List<Playable> list = playables == null ? new ArrayList<Playable>() : playables;
        String[] names = new String[list.size() + 1];
        names[0] = "✦ 自動選擇最佳片源";
        for (int i = 0; i < list.size(); i++) {
            Playable p = list.get(i);
            int eps = 0;
            if (p != null && p.detail != null && p.detail.lines != null)
                for (Cms.Line ln : p.detail.lines)
                    if (ln.episodes != null) eps += ln.episodes.size();
            names[i + 1] =
                    (p == null || p.site == null ? "" : p.site.name)
                            + " · "
                            + eps
                            + " 集"
                            + ((p != null
                                            && p.site != null
                                            && p.site.key.equals(currentSiteKey))
                                    ? "（目前）"
                                    : "");
        }
        return names;
    }

    /**
     * mkoai 2.0.48: OFF-mode source picker for a play entry — the same
     * findAll + 「選擇片源」 dialog as the manual source button, but
     * entered from a play press. Index 0 (✦ 自動選擇最佳片源) runs
     * onAuto (the caller's normal auto path); any other choice delivers
     * that playable to onChosen. No playable source -> onAuto (the auto
     * path then shows 「無法播放」 if truly nothing can play). Never
     * throws; the dialog only shows while the activity is alive.
     */
    static void pickSource(
            final MainActivity a,
            final Cms.Video listing,
            final String currentSiteKey,
            final MainActivity.Done<Playable> onChosen,
            final Runnable onAuto) {
        try {
            a.toast("正在搜尋可用片源…");
            findAll(
                    a,
                    listing,
                    currentSiteKey,
                    playables -> {
                        try {
                            if (playables == null || playables.isEmpty()) {
                                if (onAuto != null) onAuto.run();
                                return;
                            }
                            String[] names = pickerNames(playables, currentSiteKey);
                            new AppDialog.Builder(a)
                                    .wrapChoices()
                                    .setTitle("選擇片源")
                                    .setItems(
                                            names,
                                            (d, index) -> {
                                                try {
                                                    if (index == 0) {
                                                        if (onAuto != null) onAuto.run();
                                                        return;
                                                    }
                                                    if (onChosen != null) {
                                                        onChosen.accept(
                                                                playables.get(index - 1));
                                                    }
                                                } catch (Exception ignored) {
                                                }
                                            })
                                    .show();
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Throwable ignored) {
            if (onAuto != null) onAuto.run();
        }
    }

    /**
     * mkoai 2.0.48: play one episode from an already-chosen playable
     * (shared by the auto path and the OFF-mode source picker):
     * preferred line first, then the rest; the episode is matched by
     * normalized name, or by bookmark index when names do not line up
     * across sources. No episode match -> onUnplayable (never a blank
     * player, never a silent jump to another episode).
     */
    static void playPlayableEpisode(
            MainActivity a,
            Playable playable,
            String wantName,
            WatchHistory.Entry remembered,
            int resumeAt,
            Runnable onUnplayable) {
        if (playable == null || playable.detail == null || playable.detail.lines.isEmpty()) {
            if (onUnplayable != null) onUnplayable.run();
            return;
        }
        Cms.Video detail = playable.detail;
        String wantNorm = WatchHistory.normEpisode(wantName);
        List<Integer> order = new ArrayList<>();
        int pref = SourcePreferences.lineIndex(a, detail);
        if (pref >= 0 && pref < detail.lines.size()) order.add(pref);
        for (int i = 0; i < detail.lines.size(); i++) if (i != pref) order.add(i);
        Cms.Line bestLine = null;
        Cms.Episode bestEp = null;
        for (int li : order) {
            Cms.Line line = detail.lines.get(li);
            for (Cms.Episode cand : line.episodes) {
                if (WatchHistory.normEpisode(cand.name).equals(wantNorm)
                        || (cand.name != null
                                && wantName != null
                                && cand.name.trim().equals(wantName.trim()))) {
                    bestLine = line;
                    bestEp = cand;
                    break;
                }
            }
            if (bestEp != null) break;
        }
        if (bestEp == null && remembered != null && remembered.index >= 0 && !order.isEmpty()) {
            Cms.Line line = detail.lines.get(order.get(0));
            if (remembered.index < line.episodes.size()) {
                bestLine = line;
                bestEp = line.episodes.get(remembered.index);
            }
        }
        if (bestEp == null) {
            if (onUnplayable != null) onUnplayable.run();
            return;
        }
        a.resumeAt = resumeAt;
        a.resolvePlayer(playable.site, detail, bestLine, bestEp);
    }
}
