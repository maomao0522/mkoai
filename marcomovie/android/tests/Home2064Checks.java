package app.mkoai.player;

/**
 * mkoai 2.0.64 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2064_checks.py.
 *
 * Covers the 2.0.64 diffusion strengthening + bloom (HeroDiffusion2063.
 * bloomColors — the page background is a vertical fade from the hero
 * tone down into a deeper pass of the same hue, not one flat colour;
 * the strengthened tone itself is covered by Home2063Checks):
 *  bloomColors — 6 stops, tone held for the top three, midpoint,
 *      far colour (the tone diffused once more) at the bottom; the
 *      far colour is always backgroundTone(tone), the midpoint always
 *      lerp(tone, far, 0.5)
 *  topBarTone / fadeColors — unchanged: the bar takes the tone itself,
 *      the fade reproduces the 2.0.38 veil for the fixed base colour
 * Covers the 2.0.64 hero anime signals (VideoKind2061.isAnimeHero /
 * filterNonAnimeHero — hero-only, the recommendation page and the type
 * badge never see them):
 *  isAnime — the live-found category words (AI漫剧 / 漫画 / 漫畫 /
 *      卡通）; a "漫剧" short-drama category (爽文短） is not anime
 *  isAnimeHero — a candidate with no category at all is anime when
 *      its numeric type id resolves to an anime category in the
 *      source's cached list, and when its title itself carries an
 *      anime word; an unknown type id, a null map, and a plain title
 *      all stay non-anime (fail-open)
 *  isRecommendation / badgeLabel — unchanged by both new signals: a
 *      movie stays a recommendation with a 「電影」 badge even when
 *      its type id points at an anime category or its title says 動畫
 * Covers Cms.Video.typeId — the numeric category id, 0 by default.
 */
public class Home2064Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(int got, int want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got 0x" + Integer.toHexString(got)
                            + ", want 0x" + Integer.toHexString(want) + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static Cms.Video video(String typeName, String genre, String tag) {
        Cms.Video v = new Cms.Video();
        v.typeName = typeName;
        v.genre = genre;
        v.tag = tag;
        return v;
    }

    static Cms.Video heroVideo(String title, int typeId) {
        Cms.Video v = new Cms.Video();
        v.title = title;
        v.typeId = typeId;
        return v;
    }

    static java.util.Map<Integer, String> typeNames() {
        java.util.Map<Integer, String> m = new java.util.HashMap<>();
        m.put(40, "日韩动漫");
        m.put(14, "香港剧");
        m.put(2, "国产动漫");
        return m;
    }

    public static void main(String[] args) throws Exception {
        // ---- bloomColors ----
        int[] bloom = HeroDiffusion2063.bloomColors(0xff484d53);
        eq(bloom.length, HeroDiffusion2063.BLOOM_STOPS,
                "bloomColors: six stops");
        check(bloom[0] == 0xff484d53 && bloom[1] == 0xff484d53 && bloom[2] == 0xff484d53,
                "bloomColors: the tone holds for the top three stops (the hero's height)");
        eq(bloom[3], 0xff3c4044,
                "bloomColors: fourth stop is the midpoint between tone and far colour");
        eq(bloom[4], 0xff303235,
                "bloomColors: fifth stop is the far colour (the tone diffused once more)");
        eq(bloom[5], 0xff303235,
                "bloomColors: the far colour holds to the bottom");
        eq(bloom[4], HeroDiffusion2063.backgroundTone(0xff484d53),
                "bloomColors: the far colour is backgroundTone(tone) by definition");
        eq(bloom[3], HeroDiffusion2063.lerpColor(0xff484d53, bloom[4], 0.5f),
                "bloomColors: the midpoint is lerp(tone, far, 0.5) by definition");
        int[] slateBloom = HeroDiffusion2063.bloomColors(0xff6b7683);
        check(slateBloom[0] == 0xff6b7683 && slateBloom[4] == 0xff484d53
                        && slateBloom[3] == 0xff5a626b,
                "bloomColors: the slate bloom is slate -> mid grey -> darker slate");
        int[] blackBloom = HeroDiffusion2063.bloomColors(0xff000000);
        check(blackBloom[0] == 0xff000000 && blackBloom[3] == 0xff000000
                        && blackBloom[5] == 0xff000000,
                "bloomColors: a black tone blooms to black everywhere");
        int[] tealBloom = HeroDiffusion2063.bloomColors(0xff30625c);
        eq(tealBloom[4], 0xff253c39,
                "bloomColors: a teal tone fades into a deeper teal, never grey or black");
        eq(tealBloom[3], 0xff2b4f4b,
                "bloomColors: the teal midpoint stays in the same hue family");

        // ---- topBarTone / fadeColors (unchanged in 2.0.64) ----
        eq(HeroDiffusion2063.topBarTone(0xff30625c), 0xff30625c,
                "topBarTone: the bar takes the page tone itself, no extra darkening");
        eq(HeroDiffusion2063.topBarTone(HeroDiffusion2063.FALLBACK_BG),
                HeroDiffusion2063.FALLBACK_BG,
                "topBarTone: the slate fallback passes through unchanged");
        int[] fade = HeroDiffusion2063.fadeColors(HeroDiffusion2063.TOP_FADE_FIXED_RGB);
        check(fade[0] == 0xd80d1c16 && fade[1] == 0x780d1c16 && fade[2] == 0x000d1c16,
                "fadeColors: the fixed base reproduces the original 2.0.38 veil exactly");
        eq(HeroDiffusion2063.fadeColors(0xff30625c)[0], 0xd830625c,
                "fadeColors: a hero tone tints the veil with the same alphas");
        check(HeroDiffusion2063.fadeColors(0xff30625c)[1] == 0x7830625c
                        && HeroDiffusion2063.fadeColors(0xff30625c)[2] == 0x0030625c,
                "fadeColors: an opaque ARGB tone is masked to RGB — the veil still fades to transparent, never a solid band");

        // ---- Cms.Video.typeId ----
        eq(new Cms.Video().typeId, 0,
                "typeId: a fresh Video has type id 0 (unknown)");

        // ---- isAnime: the live-found category words ----
        check(VideoKind2061.isAnime(video("AI漫剧", null, null)),
                "isAnime: category AI漫剧 is anime (live: a source files anime under it)");
        check(VideoKind2061.isAnime(video("漫画", null, null)),
                "isAnime: category 漫画 is anime");
        check(VideoKind2061.isAnime(video("漫畫", null, null)),
                "isAnime: category 漫畫 (Traditional) is anime");
        check(VideoKind2061.isAnime(video("卡通", null, null)),
                "isAnime: category 卡通 is anime");
        check(VideoKind2061.isAnime(video("日韩动漫", null, null)),
                "isAnime: category 日韩动漫 is anime");
        check(!VideoKind2061.isAnime(video("爽文短", null, null)),
                "isAnime: category 爽文短 is not anime (its titles may say 漫剧, its category does not)");
        check(!VideoKind2061.isAnime(video("电影", null, null)),
                "isAnime: category 电影 is still not anime");

        // ---- isAnimeHero: type-id resolution ----
        java.util.Map<Integer, String> names = typeNames();
        check(VideoKind2061.isAnimeHero(heroVideo("名侦探光之美少女！", 40), names),
                "isAnimeHero: no category + type id 40 (日韩动漫) is anime");
        check(VideoKind2061.isAnimeHero(heroVideo("仙逆", 2), names),
                "isAnimeHero: no category + type id 2 (国产动漫) is anime");
        check(!VideoKind2061.isAnimeHero(heroVideo("玫瑰战争国语", 14), names),
                "isAnimeHero: no category + type id 14 (香港剧) is not anime");
        check(!VideoKind2061.isAnimeHero(heroVideo("一部没有分类的片", 99), names),
                "isAnimeHero: an unknown type id stays non-anime (fail-open)");
        check(!VideoKind2061.isAnimeHero(heroVideo("名侦探光之美少女！", 40), null),
                "isAnimeHero: a null category map degrades to the category fields alone");
        check(!VideoKind2061.isAnimeHero(heroVideo("名侦探光之美少女！", 0), names),
                "isAnimeHero: type id 0 (unknown) never resolves");
        check(VideoKind2061.isAnimeHero(video("动漫", null, null), null),
                "isAnimeHero: a classified anime needs no map at all");

        // ---- isAnimeHero: title fallback (hero-only, last resort) ----
        check(VideoKind2061.isAnimeHero(heroVideo("新番速递", 0), null),
                "isAnimeHero: a title saying 新番 is anime even with no category");
        check(VideoKind2061.isAnimeHero(heroVideo("動畫大電影", 0), null),
                "isAnimeHero: a title saying 動畫 is anime even with no category");
        check(!VideoKind2061.isAnimeHero(heroVideo("奧德賽", 0), null),
                "isAnimeHero: a plain title with no category is not anime");
        check(!VideoKind2061.isAnimeHero(heroVideo(null, 0), null),
                "isAnimeHero: a null title never throws, is not anime");
        check(!VideoKind2061.isAnimeHero(null, names),
                "isAnimeHero: null video is not anime");

        // ---- filterNonAnimeHero ----
        java.util.List<Cms.Video> mixed = new java.util.ArrayList<>();
        mixed.add(heroVideo("名侦探光之美少女！", 40));
        mixed.add(heroVideo("玫瑰战争国语", 14));
        mixed.add(heroVideo("奧德賽", 0));
        mixed.add(heroVideo("新番速递", 0));
        mixed.add(video("电视剧", null, null));
        java.util.List<Cms.Video> kept = VideoKind2061.filterNonAnimeHero(mixed, names);
        eq(kept.size(), 3,
                "filterNonAnimeHero: three non-anime titles survive out of five");
        check("玫瑰战争国语".equals(kept.get(0).title)
                        && "奧德賽".equals(kept.get(1).title)
                        && "电视剧".equals(kept.get(2).typeName),
                "filterNonAnimeHero: the survivors keep their original order");
        eq(VideoKind2061.filterNonAnimeHero(null, names).size(), 0,
                "filterNonAnimeHero: null in gives an empty list");
        java.util.List<Cms.Video> allAnime = new java.util.ArrayList<>();
        allAnime.add(heroVideo("名侦探光之美少女！", 40));
        allAnime.add(heroVideo("新番速递", 0));
        eq(VideoKind2061.filterNonAnimeHero(allAnime, names).size(), 0,
                "filterNonAnimeHero: an all-anime list filters to empty (the caller falls back)");

        // ---- isRecommendation / badgeLabel: untouched by the new signals ----
        Cms.Video movieById = video("电影", null, null);
        movieById.typeId = 40;
        check(VideoKind2061.isRecommendation(movieById),
                "recommendation untouched: a movie stays a recommendation even with an anime type id");
        check("電影".equals(VideoKind2061.badgeLabel(movieById)),
                "badge untouched: a movie keeps its 「電影」 badge even with an anime type id");
        Cms.Video movieByTitle = heroVideo("動畫大電影", 0);
        movieByTitle.typeName = "电影";
        check(VideoKind2061.isAnimeHero(movieByTitle, null),
                "hero-only proof: the title fallback drops it from the hero");
        check(VideoKind2061.isRecommendation(movieByTitle),
                "recommendation untouched: the same title stays a recommendation");
        check("電影".equals(VideoKind2061.badgeLabel(movieByTitle)),
                "badge untouched: the same title keeps its 「電影」 badge");

        System.out.println(passed + " Home2064 checks passed");
    }
}
