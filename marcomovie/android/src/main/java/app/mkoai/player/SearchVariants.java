package app.mkoai.player;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep the first useful response; expand sequel numeral forms so '2' also matches 'II' / '第二季'. */
final class SearchVariants {
    private static final String[] ROMAN = {
        "", "", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"
    };
    private static final String[] CN_NUM = {"", "", "二", "三", "四", "五", "六", "七", "八", "九"};

    private static final Pattern TRAILING_ARABIC_SEQUEL =
            Pattern.compile(
                    "^([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z\\s·]{0,18}?[\\u4e00-\\u9fa5A-Za-z])\\s*([2-9])\\s*$");
    private static final Pattern EMBEDDED_ARABIC_SEQUEL =
            Pattern.compile(
                    "^([\\u4e00-\\u9fa5]{2,14})\\s*([2-9])\\s*([：:·\\-—\\s]*[\\u4e00-\\u9fa5]{2,14})$");
    private static final Pattern TRAILING_ROMAN_SEQUEL =
            Pattern.compile(
                    "(?i)^([\\u4e00-\\u9fa5]{2,16})\\s*(VIII|VII|III|IX|IV|VI|II|V)\\s*([：:·\\-—\\s]*[\\u4e00-\\u9fa5]{0,14})\\s*$");
    private static final Pattern EXPLICIT_CN_SEASON =
            Pattern.compile(
                    "^([\\u4e00-\\u9fa5A-Za-z]{2,16})\\s*第\\s*([二三四五六七八九2-9])\\s*[季部]\\s*$");
    private static final Pattern COMIC_OR_DERIVATIVE_PATTERN =
            Pattern.compile("(短漫|漫劇|動態漫|漫畫|有聲漫|爽文短劇|女頻短劇|男頻短劇|解說|速看|一口氣看完)");

    interface Match {
        boolean test(String title);
    }

    interface Fetch {
        List<Cms.Video> get(String term) throws Exception;
    }

    static String normalizeUnicodeRoman(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("Ⅷ", "VIII")
                .replace("Ⅶ", "VII")
                .replace("Ⅵ", "VI")
                .replace("Ⅳ", "IV")
                .replace("Ⅸ", "IX")
                .replace("Ⅲ", "III")
                .replace("Ⅱ", "II")
                .replace("Ⅴ", "V");
    }

    private static int romanToInt(String r) {
        if (r == null) return 0;
        String u = r.trim().toUpperCase(Locale.ROOT);
        for (int i = 2; i < ROMAN.length; i++) {
            if (ROMAN[i].equals(u)) return i;
        }
        return 0;
    }

    private static int cnOrDigitToInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        char c = s.trim().charAt(0);
        if (c >= '2' && c <= '9') return c - '0';
        for (int i = 2; i < CN_NUM.length; i++) {
            if (CN_NUM[i].charAt(0) == c) return i;
        }
        return 0;
    }

    /**
     * Expand sequel numeral forms (e.g. "斗罗大陆2" -> ["斗罗大陆2", "斗罗大陆II", "斗罗大陆第二季", "斗罗大陆二"]) so
     * literal-substring CMS/netdisk spiders return the canonical anime/series instead of only
     * comic-drama spin-offs.
     */
    static List<String> sequelVariants(String query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (query == null) return Collections.emptyList();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        out.add(trimmed);
        String norm = normalizeUnicodeRoman(trimmed);
        if (!norm.equals(trimmed)) out.add(norm);

        Matcher mTrail = TRAILING_ARABIC_SEQUEL.matcher(norm);
        if (mTrail.matches()) {
            String prefix = mTrail.group(1).trim();
            int n = mTrail.group(2).charAt(0) - '0';
            if (n >= 2 && n <= 9 && !prefix.matches("(?i).*(?:1080|720|2160|4k)$")) {
                out.add(prefix + ROMAN[n]);
                out.add(prefix + "第" + CN_NUM[n] + "季");
                out.add(prefix + CN_NUM[n]);
            }
            return new ArrayList<>(out);
        }

        Matcher mEmbed = EMBEDDED_ARABIC_SEQUEL.matcher(norm);
        if (mEmbed.matches()) {
            String prefix = mEmbed.group(1).trim();
            int n = mEmbed.group(2).charAt(0) - '0';
            String suffix = mEmbed.group(3).trim();
            String cleanSuffix = suffix.replaceFirst("^[：:·\\-—\\s]+", "");
            if (n >= 2 && n <= 9) {
                out.add(prefix + ROMAN[n] + cleanSuffix);
                out.add(prefix + ROMAN[n]);
            }
            return new ArrayList<>(out);
        }

        Matcher mRoman = TRAILING_ROMAN_SEQUEL.matcher(norm);
        if (mRoman.matches()) {
            String prefix = mRoman.group(1).trim();
            int n = romanToInt(mRoman.group(2));
            String suffix = mRoman.group(3) == null ? "" : mRoman.group(3).trim();
            String cleanSuffix = suffix.replaceFirst("^[：:·\\-—\\s]+", "");
            if (n >= 2 && n <= 9) {
                if (cleanSuffix.isEmpty()) {
                    out.add(prefix + n);
                    out.add(prefix + "第" + CN_NUM[n] + "季");
                } else {
                    out.add(prefix + n + cleanSuffix);
                    out.add(prefix + n);
                }
            }
            return new ArrayList<>(out);
        }

        Matcher mSeason = EXPLICIT_CN_SEASON.matcher(norm);
        if (mSeason.matches()) {
            String prefix = mSeason.group(1).trim();
            int n = cnOrDigitToInt(mSeason.group(2));
            if (n >= 2 && n <= 9) {
                out.add(prefix + n);
                out.add(prefix + ROMAN[n]);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Taiwan <-> mainland title aliases where the names differ completely
     * (not just Traditional vs Simplified script, which t2s already
     * handles). Keys and values are stored Simplified; lookup normalizes
     * the query with t2s. Both directions are in the map. Only well-known
     * titles where the two names share no characters — script-only
     * differences are NOT listed here.
     */
    private static final java.util.Map<String, String> TITLE_ALIASES =
            new java.util.LinkedHashMap<>();

    static {
        putAlias("玩命关头", "速度与激情");
        putAlias("捍卫战士", "壮志凌云");
        putAlias("铁达尼号", "泰坦尼克号");
        putAlias("魔戒", "指环王");
        putAlias("蜘蛛人", "蜘蛛侠");
        putAlias("星际大战", "星球大战");
        putAlias("星际效应", "星际穿越");
        putAlias("全面启动", "盗梦空间");
        putAlias("刺激1995", "肖申克的救赎");
        putAlias("神鬼奇航", "加勒比海盗");
        putAlias("怪兽与它们的产地", "神奇动物在哪里");
        putAlias("厉阴宅", "招魂");
        putAlias("寄生上流", "寄生虫");
        putAlias("尸速列车", "釜山行");
        putAlias("模犯生", "天才枪手");
        putAlias("淑女鸟", "伯德小姐");
        putAlias("魔鬼终结者", "终结者");
        putAlias("终极追杀令", "这个杀手不太冷");
        putAlias("MIB星际战警", "黑衣人");
        putAlias("骇客任务", "黑客帝国");
        putAlias("神鬼认证", "谍影重重");
        putAlias("恶灵古堡", "生化危机");
        putAlias("天外奇迹", "飞屋环游记");
        putAlias("活人生吃", "活死人黎明");
        putAlias("漫步在云端", "云中漫步");
        putAlias("艾米莉的异想世界", "天使爱美丽");
        putAlias("王者之声", "国王的演讲");
        putAlias("脑筋急转弯", "头脑特工队");
        putAlias("神鬼战士", "角斗士");
        putAlias("神鬼第六感", "小岛惊魂");
        putAlias("神隐少女", "千与千寻");
        putAlias("明天过后", "后天");
        putAlias("水行侠", "海王");
        putAlias("可可夜总会", "寻梦环游记");
        putAlias("飞机上有蛇", "航班蛇患");
        putAlias("料理鼠王", "美食总动员");
        putAlias("地狱怪客", "地狱男爵");
        putAlias("美国心玫瑰情", "美国丽人");
        putAlias("追杀比尔", "杀死比尔");
        putAlias("101忠狗", "101斑点狗");
        putAlias("动物方城市", "疯狂动物城");
        putAlias("大英雄天团", "超能陆战队");
        putAlias("瓦力", "机器人总动员");
        putAlias("超人特攻队", "超人总动员");
        putAlias("汽车总动员", "赛车总动员");
        putAlias("虫虫危机", "虫虫特工队");
        putAlias("哈比人", "霍比特人");
        putAlias("魔鬼克星", "捉鬼敢死队");
        putAlias("野蛮游戏", "勇敢者游戏");
        putAlias("摩登大圣", "变相怪杰");
        putAlias("王牌威龙", "神探飞机头");
        putAlias("黑色追缉令", "低俗小说");
        putAlias("斗阵俱乐部", "搏击俱乐部");
        putAlias("火线追缉令", "七宗罪");
        putAlias("四海好家伙", "好家伙");
        putAlias("险路勿近", "老无所依");
        putAlias("黑金企业", "血色将至");
        putAlias("顶尖对决", "致命魔术");
        putAlias("记忆拼图", "记忆碎片");
        putAlias("惊心动魄", "不死劫");
        putAlias("异裂", "玻璃先生");
        putAlias("噤界", "寂静之地");
        putAlias("鬼修女", "修女");
        putAlias("大法师", "驱魔人");
        putAlias("鬼店", "闪灵");
        putAlias("半夜鬼上床", "猛鬼街");
        putAlias("十三号星期五", "黑色星期五");
        putAlias("七夜怪谈", "午夜凶铃");
        putAlias("骇人怪物", "汉江怪物");
        putAlias("原罪犯", "老男孩");
        putAlias("看见魔鬼", "看见恶魔");
        putAlias("雷神索尔", "雷神");
        putAlias("钢铁人", "钢铁侠");
        putAlias("金钢狼", "金刚狼");
        putAlias("神力女超人", "神奇女侠");
        putAlias("沙赞", "雷霆沙赞");
        putAlias("哥吉拉", "哥斯拉");
        putAlias("敦克尔克大行动", "敦刻尔克");
        // mkoai 2.0.39: found by the availability diagnosis — the TMDB
        // Taiwan name and the source's mainland name differ, so neither
        // script conversion alone can match them. Stored Simplified:
        // aliasNames looks the t2s-converted query up in this map.
        putAlias("亚森·罗苹", "鲁邦");
        putAlias("快打旋风", "街头霸王");
    }

    private static void putAlias(String a, String b) {
        TITLE_ALIASES.put(a, b);
        TITLE_ALIASES.put(b, a);
    }

    /**
     * Alias names for a query (not including the query itself). Exact match
     * first; otherwise a prefix match, so sequels work too
     * (玩命關頭8 -> 速度與激情8, 玩命關頭：特別行動 -> 速度與激情：特別行動).
     * Keys are at least 2 chars so a one-character query never triggers an
     * alias. Returns Simplified names; callers apply scriptVariants.
     */
    static List<String> aliasNames(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        String q = ChineseConvert.t2s(query.trim());
        if (q.length() < 2) return out;
        String direct = TITLE_ALIASES.get(q);
        if (direct != null) {
            out.add(direct);
            return out;
        }
        for (java.util.Map.Entry<String, String> e : TITLE_ALIASES.entrySet()) {
            String key = e.getKey();
            if (key.length() >= 2 && q.startsWith(key)) {
                out.add(e.getValue() + q.substring(key.length()));
                return out;
            }
        }
        return out;
    }

    /** The query itself plus its alias names, for scoring and matching. */
    static List<String> aliasVariants(String query) {
        List<String> out = new ArrayList<>();
        if (query != null && !query.trim().isEmpty()) out.add(query.trim());
        out.addAll(aliasNames(query));
        return out;
    }

    /**
     * mkoai Simplified/Traditional variants: the query is searched in both
     * scripts, so a Traditional query finds a Simplified source (and a
     * Simplified Douban title finds a Traditional listing). The original term
     * stays first; the converted forms follow.
     */
    static List<String> scriptVariants(String query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (query == null) return Collections.emptyList();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        out.add(trimmed);
        String simplified = ChineseConvert.t2s(trimmed);
        if (!simplified.equals(trimmed)) out.add(simplified);
        // 2.0.42: word-level mainland forms (身份 ...) — character
        // conversion cannot produce them, but only they find the mainland
        // listing. Added right after Simplified so they stay within
        // run()'s 3-attempt cap.
        for (String w : wordVariants(simplified)) out.add(w);
        String traditional = ChineseConvert.s2t(trimmed);
        if (!traditional.equals(trimmed) && !traditional.equals(simplified))
            out.add(traditional);
        return new ArrayList<>(out);
    }

    static List<String> mergeTerms(String query, List<String> baseTerms) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(sequelVariants(query));
        // mkoai: also search the other script (Simplified <-> Traditional).
        for (String t : scriptVariants(query)) merged.add(t);
        // mkoai 2.0.31: also search known Taiwan <-> mainland title aliases
        // (e.g. 玩命關頭 -> 速度與激情), Simplified first so the alias is
        // tried within the 3-attempt cap.
        for (String a : aliasNames(query)) {
            for (String t : scriptVariants(a)) merged.add(t);
        }
        if (baseTerms != null) {
            for (String t : baseTerms) {
                if (t != null && !t.trim().isEmpty()) merged.add(t.trim());
            }
        }
        return new ArrayList<>(merged);
    }

    static boolean wantsComicOrShortDrama(String query) {
        if (query == null || query.isEmpty()) return false;
        return COMIC_OR_DERIVATIVE_PATTERN.matcher(query).find() || query.contains("短剧");
    }

    static boolean isComicOrShortDrama(Cms.Video v) {
        return isComicOrShortDrama(v, "");
    }

    static boolean isComicOrShortDrama(Cms.Video v, String siteName) {
        if (v == null) return false;
        String tag = v.tag == null ? "" : v.tag;
        String title = v.title == null ? "" : v.title;
        String remarks = v.remarks == null ? "" : v.remarks;
        if (COMIC_OR_DERIVATIVE_PATTERN.matcher(tag).find() || tag.contains("短剧")) return true;
        if (COMIC_OR_DERIVATIVE_PATTERN.matcher(title).find()) return true;
        if (COMIC_OR_DERIVATIVE_PATTERN.matcher(remarks).find()) return true;
        if (siteName != null && !siteName.isEmpty()) {
            if (siteName.contains("短漫") || siteName.contains("漫剧")) return true;
        }
        return false;
    }

    /**
     * mkoai 2.0.34: trailer-only entries (e.g. 《急診室的奇蹟（预告片）》) are not
     * the title itself — they never play the show, so search results, home
     * availability checks and auto source selection all skip them. A title
     * only counts as a trailer when it carries an explicit trailer marker:
     * a bracketed （预告片）/（預告片） suffix (or a bare trailing 预告片/預告片）,
     * or a tag/remarks field that is exactly 预告片/預告片. A real show that
     * merely mentions trailers elsewhere in its metadata is not affected.
     */
    static boolean isTrailerOnly(Cms.Video v) {
        if (v == null) return false;
        String title = v.title == null ? "" : v.title.trim();
        if (!title.isEmpty()) {
            // Bracketed suffix: （预告片） / （預告片） / [预告片] / 【預告片】, also "预告".
            if (title.matches("(?i).*[(（【\\[]\\s*(預告片|预告片|預告|预告|Trailer)\\s*[)）】\\]]\\s*$"))
                return true;
            // Bare trailing marker without brackets: "XXX预告片" / "XXX預告片".
            if (title.endsWith("预告片") || title.endsWith("預告片")) return true;
        }
        String tag = v.tag == null ? "" : v.tag.trim();
        if (tag.equals("预告片") || tag.equals("預告片")) return true;
        String remarks = v.remarks == null ? "" : v.remarks.trim();
        if (remarks.equals("预告片") || remarks.equals("預告片")) return true;
        return false;
    }

    /**
     * mkoai 2.0.34: movie-commentary / recap entries (e.g. 《回来吧大叔[电影解说]》)
     * are narration over the plot, not the show itself — search results, home
     * availability checks and auto source selection all skip them. Markers:
     * a bracketed [电影解说]/[影視解說] in the title, a tag/remarks naming
     * 解说/解說/影视解说/速看, or a title ending in 解说/解說/速看/一口氣看完.
     * Ordinary titles that merely contain these words mid-title are not affected.
     */
    static boolean isCommentaryOnly(Cms.Video v) {
        if (v == null) return false;
        String title = v.title == null ? "" : v.title.trim();
        if (!title.isEmpty()) {
            if (title.matches("(?i).*[(（【\\[]\\s*(电影解说|電影解說|影视解说|影視解說|解说|解說|速看)\\s*[)）】\\]]"))
                return true;
            if (title.endsWith("解说")
                    || title.endsWith("解說")
                    || title.endsWith("速看")
                    || title.endsWith("一口气看完")
                    || title.endsWith("一口氣看完")) return true;
        }
        String tag = v.tag == null ? "" : v.tag;
        if (tag.contains("解说")
                || tag.contains("解說")
                || tag.contains("影视解说")
                || tag.contains("影視解說")
                || tag.contains("速看")) return true;
        String remarks = v.remarks == null ? "" : v.remarks;
        if (remarks.contains("解说")
                || remarks.contains("解說")
                || remarks.contains("影视解说")
                || remarks.contains("影視解說")
                || remarks.contains("速看")) return true;
        return false;
    }

    /** Trailer-only or commentary-only: not the show itself, never shown as a result. */
    static boolean isSearchJunk(Cms.Video v) {
        return isTrailerOnly(v) || isCommentaryOnly(v);
    }

    /**
     * mkoai 2.0.36: quality signal for auto source selection. Line names
     * sometimes carry a quality marker (4K / 藍光 / 1080P / 超清 / 高清);
     * this turns the marker into a comparable height. 0 = no marker (the
     * line may still be high quality — unknown, not low).
     */
    static int lineQualityHeight(String lineName) {
        if (lineName == null) return 0;
        String n = lineName;
        if (n.contains("4K") || n.contains("4k") || n.contains("2160")) return 2160;
        if (n.contains("藍光")
                || n.contains("蓝光")
                || n.contains("1080")
                || n.contains("1080P")
                || n.contains("1080p")) return 1080;
        if (n.contains("超清")
                || n.contains("超高清")
                || n.contains("720")
                || n.contains("720P")
                || n.contains("720p")
                || n.contains("高清")) return 720;
        if (n.contains("普清") || n.contains("標清") || n.contains("标清") || n.contains("480"))
            return 480;
        return 0;
    }

    /**
     * mkoai 2.0.36: best vertical resolution declared by an HLS master
     * playlist (#EXT-X-STREAM-INF RESOLUTION=WxH). 0 when the playlist
     * declares none (single-variant playlists) or is not a playlist.
     */
    static int m3u8BestHeight(String playlist) {
        if (playlist == null || playlist.isEmpty()) return 0;
        int best = 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(playlist);
        while (m.find()) {
            try {
                int h = Integer.parseInt(m.group(1));
                if (h > best) best = h;
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    /**
     * mkoai 2.0.36: order for verified auto-select candidates. Playability
     * is already established (unverified candidates never reach here), so
     * the order is: higher quality first, then faster probe first, then
     * the original Maoki-style rank (title / completeness / priority).
     * An unknown probe latency (parse-type URL, not directly measurable)
     * sorts after any measured latency.
     */
    static int compareVerified(
            int qualityA, long latencyA, int rankA, int qualityB, long latencyB, int rankB) {
        if (qualityA != qualityB) return Integer.compare(qualityB, qualityA);
        long la = latencyA < 0 ? Long.MAX_VALUE : latencyA;
        long lb = latencyB < 0 ? Long.MAX_VALUE : latencyB;
        if (la != lb) return Long.compare(la, lb);
        return Integer.compare(rankA, rankB);
    }

    /**
     * mkoai 2.0.34: adult-content detection, used only when youth mode is
     * ON (Prefs.YOUTH_MODE, default OFF). Conservative on purpose — a
     * title only counts as adult when it carries an explicit marker:
     * <ul>
     *   <li>a category (tag/genre) naming 成人 / 伦理 / 三级 / 情色 / AV / 18禁,</li>
     *   <li>or a JAV-style catalogue code in the title (e.g. SSPD-134).</li>
     * </ul>
     * Ordinary titles are never affected: "伦理" as a passing word inside
     * a synopsis does not count (only tag/genre are checked for it), and
     * words like 熟女 / 写真 are deliberately NOT markers — they appear
     * in mainstream dramas too.
     */
    static boolean isAdult(Cms.Video v) {
        return isAdult(v, true);
    }

    /**
     * mkoai 2.0.42: hideLunli=false skips ONLY the 倫理 clause — the
     * 「隱藏倫理片」 switch lets the user turn the 倫理 part off separately
     * while youth mode still hides the other adult markers (成人 / 三級 /
     * 情色 / 18禁 / 限制級 / AV / JAV code).
     */
    static boolean isAdult(Cms.Video v, boolean hideLunli) {
        if (v == null) return false;
        String tag = v.tag == null ? "" : v.tag;
        String genre = v.genre == null ? "" : v.genre;
        String cat = tag + " " + genre;
        if (cat.contains("成人")
                || (hideLunli && (cat.contains("伦理") || cat.contains("倫理")))
                || cat.contains("三级")
                || cat.contains("三級")
                || cat.contains("情色")
                || cat.contains("18禁")
                || cat.contains("限制级")
                || cat.contains("限制級")) return true;
        // "AV" only as a standalone category token, never as a substring.
        for (String part : cat.split("[\\s,/、]+")) {
            if (part.equalsIgnoreCase("av") || part.equalsIgnoreCase("porn")) return true;
        }
        String title = v.title == null ? "" : v.title;
        // JAV catalogue code: 2-6 letters, a dash, 3+ digits (SSPD-134).
        if (java.util.regex.Pattern.compile("(?i)\\b[A-Z]{2,6}-\\d{3,}\\b")
                .matcher(title)
                .find()) return true;
        return false;
    }

    /**
     * mkoai 2.0.42: 倫理-only marker — the tag/genre names 倫理 / 伦理.
     * Used by the separate 「隱藏倫理片」 switch.
     */
    static boolean isLunli(Cms.Video v) {
        if (v == null) return false;
        String cat = (v.tag == null ? "" : v.tag) + " " + (v.genre == null ? "" : v.genre);
        return cat.contains("伦理") || cat.contains("倫理");
    }

    /**
     * mkoai 2.0.42: effective 倫理 hiding — youth mode ON and the separate
     * 「隱藏倫理片」 switch ON (default ON, so it follows youth mode unless
     * the user turns it off separately). Youth mode OFF → no effect.
     */
    static boolean hideLunli(MainActivity a) {
        return a.prefs.getBoolean(Prefs.YOUTH_MODE, false)
                && a.prefs.getBoolean(Prefs.HIDE_LUNLI, true);
    }

    /**
     * mkoai 2.0.34: sources whose catalogue mixes adult titles in with
     * mainstream ones. When youth mode is ON, these sources are skipped
     * entirely in search and auto source selection. (Only 大地 today —
     * its front page mixes adult titles; every other configured source
     * is mainstream-only as far as its listings show.)
     */
    static boolean isAdultSource(Cms.Site site) {
        if (site == null || site.api == null) return false;
        String api = site.api.toLowerCase(java.util.Locale.ROOT);
        return api.contains("dadiapi.com");
    }

    /**
     * mkoai 2.0.34: episode completeness of one search result, for auto source
     * selection (Maoki-style: prefer the most complete copy). Parsed from the
     * remarks field ("全16集" / "更新至8集" / "第5集" / "已完结"). A finished
     * title scores far above an in-progress one; otherwise more episodes wins.
     */
    static int episodeCompleteness(Cms.Video v) {
        if (v == null) return 0;
        String remarks = v.remarks == null ? "" : v.remarks.trim();
        if (remarks.isEmpty()) return 0;
        int count = 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+)\\s*集").matcher(remarks);
        if (m.find()) {
            try {
                count = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        boolean finished =
                remarks.contains("已完结")
                        || remarks.contains("已完結")
                        || remarks.contains("全" + count + "集");
        return (finished ? 10000 : 0) + count;
    }

    /**
     * mkoai 2.0.34: Maoki-style auto source ranking for a merged search card
     * (negative = a ranks first). Order: title-match tier first (exact >
     * reverse-prefix > prefix > reverse-contains > contains, via scoreVideo
     * — the small preferred-site bonus inside it only breaks ties), then episode completeness (finished /
     * more episodes first), then the source priority order (lower index
     * first). A source whose copy is incomplete never outranks a complete
     * one on title score alone.
     */
    static int compareAutoCandidates(
            String query,
            Cms.Video a, String siteA, int priorityA,
            Cms.Video b, String siteB, int priorityB) {
        // NOTE: the preferred-site bonus is deliberately NOT applied here
        // (both sides score as non-preferred). The bonus is a tie-breaker,
        // but a +20 wobble inside the title score could let an incomplete
        // copy from the preferred source outrank a complete copy elsewhere
        // — completeness must decide among equal title tiers. Source
        // preference is already the last tie-breaker (priorityA/B).
        int sa = scoreVideo(query, a, siteA, false);
        int sb = scoreVideo(query, b, siteB, false);
        if (sa != sb) return Integer.compare(sb, sa);
        int ca = episodeCompleteness(a);
        int cb = episodeCompleteness(b);
        if (ca != cb) return Integer.compare(cb, ca);
        return Integer.compare(priorityA, priorityB);
    }

    static String canonicalSearchKey(String text) {
        if (text == null) return "";
        // mkoai 2.0.31: normalize script first (Traditional -> Simplified)
        // so a Traditional query scores an exact match against a Simplified
        // title (and vice versa). Without this, 好搭檔 vs 好搭档 scored 0
        // and the exact match was buried below loose matches.
        String s = normalizeUnicodeRoman(ChineseConvert.t2s(text.trim()));
        // Canonicalize Roman sequel numerals after CJK into Arabic digits for fair comparison
        for (int i = ROMAN.length - 1; i >= 2; i--) {
            s = s.replaceAll("([\\u4e00-\\u9fa5])\\s*" + ROMAN[i] + "(?![A-Za-z])", "$1" + i);
        }
        for (int i = 2; i < CN_NUM.length; i++) {
            s = s.replaceAll("第\\s*" + CN_NUM[i] + "\\s*[季部]", String.valueOf(i));
        }
        s = s.replaceAll("第\\s*([2-9])\\s*[季部]", "$1");
        s =
                s.replaceAll(
                        "(?i)(?<=[\\u4e00-\\u9fa5])\\s*(?:season\\s*0*|s0*)([2-9])(?![0-9A-Za-z])",
                        "$1");
        s = s.replaceAll("第\\s*[一1]\\s*[季部]", "");
        s = s.replaceAll("(?i)(?<=[\\u4e00-\\u9fa5])\\s*(?:season\\s*0*1|s0*1)(?![0-9A-Za-z])", "");
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。/\\\\]", "");
    }

    /**
     * mkoai 2.0.47: the title-match tier alone (no site/tag bonuses) —
     * 320 exact / 290 reverse-prefix / 240 prefix / 200 reverse-contains
     * / 140 contains / 0 no substantive overlap. Extracted from
     * scoreVideo so the flood filter and the merged-view ranking share
     * exactly one definition of "this title matches the query". Script
     * is normalized (canonicalSearchKey), so a Traditional query scores
     * an exact match against a Simplified title and vice versa. A
     * one-character title can never match (guarded to 2+ chars), and a
     * loose flood row that merely shares a word with the query scores 0
     * (e.g. 「速度與激情：特別行動」 vs query 「艾倫的宇宙」).
     */
    static int titleTier(String query, String title) {
        if (query == null || title == null) return 0;
        String tKey = canonicalSearchKey(title);
        if (tKey.isEmpty()) return 0;
        // Score against the query AND its known aliases (Taiwan <-> mainland),
        // taking the best match. canonicalSearchKey now normalizes script, so
        // 好搭檔 matches 好搭档 exactly; the alias keys let 速度與激情 score
        // as an exact match for a 玩命關頭 query.
        int best = 0;
        for (String q : aliasVariants(query)) {
            String qKey = canonicalSearchKey(q);
            if (qKey.isEmpty()) continue;
            if (tKey.equals(qKey)) best = Math.max(best, 320);
            else if (tKey.startsWith(qKey)) best = Math.max(best, 240);
            else if (tKey.contains(qKey)) best = Math.max(best, 140);
            // mkoai 2.0.45: reverse tiers — the query can be LONGER
            // than the title (title + subtitle, e.g. query
            // 「九月五日：人質危機」 vs title 《九月五日》). The old
            // one-way tiers gave that exact title 0 and buried it
            // below loose matches. Reverse-prefix (query starts with
            // the title) sits just below exact; reverse-contains
            // (query contains the title) sits between prefix and
            // contains. Tier gaps (320/290/240/200/140 = 30/50/40/60)
            // all stay larger than the +20 preferred-site bonus, so
            // a preferred source still only wins among equal matches
            // (2.0.34 rule). Guarded to titles of 2+ chars so a
            // one-character title cannot match every query.
            else if (tKey.length() >= 2 && qKey.startsWith(tKey)) {
                best = Math.max(best, 290);
            } else if (tKey.length() >= 2 && qKey.contains(tKey)) {
                best = Math.max(best, 200);
            }
        }
        return best;
    }

    /** True when the title substantively overlaps the query (any tier > 0). */
    static boolean hasSubstantiveOverlap(String query, String title) {
        return titleTier(query, title) > 0;
    }

    static int scoreVideo(String query, Cms.Video v, String siteName, boolean preferredSite) {
        if (v == null) return Integer.MIN_VALUE / 2;
        int score = 0;
        // mkoai 2.0.34: the preferred-site bonus must stay SMALLER than the gap
        // between title-match tiers (exact 320 / prefix 240 / contains 140),
        // minus the tag bonus (45). The old +180 buried an exact match from
        // another source (e.g. 巨量《權欲》 320+45=365) below prefix matches
        // from the preferred source (e.g. Mkoai4《權欲之巔》 240+45+180=465).
        // +20 keeps the preferred source first only among equal matches.
        if (preferredSite) score += 20;
        score += titleTier(query, v.title);
        String tag = v.tag == null ? "" : v.tag;
        if (tag.contains("动漫") || tag.contains("番") || tag.contains("剧") || tag.contains("影")) {
            if (!isComicOrShortDrama(v, siteName)) score += 45;
        }
        if (!wantsComicOrShortDrama(query) && isComicOrShortDrama(v, siteName)) {
            score -= 600;
        }
        return score;
    }

    static void rankVideos(final String query, List<Cms.Video> videos, final String siteName) {
        if (videos == null || videos.size() <= 1) return;
        Collections.sort(
                videos,
                (a, b) ->
                        Integer.compare(
                                scoreVideo(query, b, siteName, false),
                                scoreVideo(query, a, siteName, false)));
    }

    /**
     * True when at least one result's title actually matches the query
     * (script-insensitive: both sides converted to Simplified and
     * normalized). The CMS search always returns ~20 loosely-matched rows,
     * so "results non-empty" proves nothing — without this gate, a
     * Traditional query returns the first variant's loose rows and never
     * tries the Simplified variant, so Traditional search finds nothing
     * (e.g. 好搭檔 vs the source's 好搭档).
     */
    /**
     * mkoai 2.0.42: strip a trailing season marker from an already
     * normalized title key (Simplified, no punctuation/spaces): 第N季/部
     * （絕命毒師第一季 → 絕命毒師）, season N, s01. A bare trailing number
     * is NOT stripped — 速度與激情9 is a different film from 速度與激情.
     */
    static String stripSeasonSuffix(String key) {
        if (key == null) return "";
        String k = key;
        k = k.replaceAll("第[一二三四五六七八九十零0-9]+[季部]$", "");
        k = k.replaceAll("season[0-9]+$", "");
        k = k.replaceAll("s[0-9]+$", "");
        return k;
    }

    /**
     * mkoai 2.0.42: Taiwan <-> mainland WORD variants that character-level
     * Simplified/Traditional conversion cannot produce. Taiwan 身分 vs
     * mainland 身份 (分 U+5206 vs 份 U+4EFD) is the same word written with
     * different characters — t2s/s2t leave both unchanged, so without this
     * the app never searches the mainland form and never matches the
     * mainland listing. Verified live 2026-11-13: only the mainland query
     * 「交换身份过圣诞」 finds 《交換身分過聖誕》 (9 sources); the
     * Traditional query and the t2s hybrid 「交换身分过圣诞」 find nothing
     * exact. Extensible: add new pairs here (first = Taiwan form,
     * second = mainland form).
     */
    static final String[][] WORD_VARIANTS = {
        {"身分", "身份"},
    };

    /** Word-swapped forms of the query (both directions). */
    static List<String> wordVariants(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String[] pair : WORD_VARIANTS) {
            if (query.contains(pair[0])) out.add(query.replace(pair[0], pair[1]));
            if (query.contains(pair[1])) out.add(query.replace(pair[1], pair[0]));
        }
        return out;
    }

    /**
     * Canonicalize a normalized title key for matching: unify the
     * Taiwan/mainland word variants (身份 -> 身分) so the same work matches
     * across scripts. Applied to BOTH sides, so same-script equality is
     * never broken.
     */
    static String canonicalKey(String key) {
        if (key == null) return "";
        String k = key;
        for (String[] pair : WORD_VARIANTS) k = k.replace(pair[1], pair[0]);
        return k;
    }

    /**
     * mkoai 2.0.42: strict title gate for availability / search matching.
     * Two normalized keys match when they are equal after the season
     * suffix is stripped, or when the RESULT starts with the query (a
     * longer form of the same title, e.g. a subtitle after the main
     * title). NEVER the reverse: a result that is only a PREFIX of the
     * query is a different title (e.g. source result 「交換身分」 must NOT
     * match query 「交換身分過聖誕」 — that false positive let a title no
     * source actually has onto the home screen in 2.0.41).
     */
    static boolean strictTitleMatch(String queryKey, String resultKey) {
        String q = stripSeasonSuffix(canonicalKey(queryKey));
        String t = stripSeasonSuffix(canonicalKey(resultKey));
        if (q.isEmpty() || t.isEmpty()) return false;
        return t.equals(q) || t.startsWith(q);
    }

    static boolean hasTitleMatch(List<Cms.Video> videos, String query) {
        // Match against the query AND its known aliases (Taiwan <-> mainland),
        // all script-normalized. Without the alias keys, a 玩命關頭 search
        // would never count the 速度與激情 results as a match.
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (String q : aliasVariants(query)) {
            String k = Cms.normTitle(ChineseConvert.t2s(q));
            if (!k.isEmpty()) keys.add(k);
        }
        if (keys.isEmpty()) return false;
        for (Cms.Video v : videos) {
            if (v == null || v.title == null) continue;
            String t = Cms.normTitle(ChineseConvert.t2s(v.title));
            if (t.isEmpty()) continue;
            for (String q : keys) {
                if (strictTitleMatch(q, t)) return true;
            }
        }
        return false;
    }

    static List<Cms.Video> run(String query, List<String> variants, Match matches, Fetch fetch)
            throws Exception {
        Exception failure = null;
        int attempts = 0;
        Set<String> sequelSet = new LinkedHashSet<>(sequelVariants(query));
        boolean hasSequelForms = sequelSet.size() > 1;
        boolean allowComic = wantsComicOrShortDrama(query);

        List<Cms.Video> merged = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        boolean foundPrimaryMedia = false;
        int sequelQueriesDone = 0;

        for (String term : variants) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (++attempts > 3) break;
            boolean isSequelTerm = sequelSet.contains(term);
            try {
                List<Cms.Video> batch = fetch.get(term);
                if (isSequelTerm) sequelQueriesDone++;
                if (batch != null) {
                    for (Cms.Video v : batch) {
                        if (v != null
                                && v.id != null
                                && !v.id.isEmpty()
                                && (term.equalsIgnoreCase(query)
                                        || isSequelTerm
                                        || matches.test(v.title))
                                && ids.add(v.id)) {
                            merged.add(v);
                            if (allowComic || !isComicOrShortDrama(v)) {
                                foundPrimaryMedia = true;
                            }
                        }
                    }
                }
                if (!merged.isEmpty() && foundPrimaryMedia && hasTitleMatch(merged, query)) {
                    return finishSearch(query, merged);
                }
                if (!merged.isEmpty() && !hasSequelForms && hasTitleMatch(merged, query)) {
                    return finishSearch(query, merged);
                }
            } catch (SearchCoordinator.VerificationRequiredException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                failure = e;
            }
        }
        if (!merged.isEmpty()) {
            return finishSearch(query, merged);
        }
        if (failure != null) throw failure;
        return Collections.emptyList();
    }

    /**
     * mkoai 2.0.47: flood suppression for one source's merged results.
     * When the merged set already holds a real title match, every entry
     * with no substantive title overlap (titleTier 0 — e.g. Mkoai1's
     * 39 loose 「宇宙」 rows for query 「艾倫的宇宙」, or yzzy's unfiltered
     * dump) is dropped, so the flood can never bury the correct result
     * in this source's own list. When there is NO title match anywhere
     * in the set, nothing is dropped (fail open — a broad/loose search
     * keeps its results). The caller (GlobalSearchScreen) applies the
     * same rule ACROSS sources for the merged view, because the exact
     * match and the flood usually live in different sources.
     */
    static List<Cms.Video> finishSearch(String query, List<Cms.Video> merged) {
        if (merged != null && !merged.isEmpty() && hasTitleMatch(merged, query)) {
            merged.removeIf(v -> v == null || titleTier(query, v.title) <= 0);
        }
        if (merged != null) rankVideos(query, merged, "");
        return merged;
    }
}
