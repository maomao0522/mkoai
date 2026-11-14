package app.mkoai.player;

import java.util.*;
import org.json.*;

/** Source-advertised filter values stay opaque; local filters use video metadata only. */
final class VideoFilters {
    static final String[] DIMENSIONS = {"class", "area", "year"};
    static final String[] LABELS = {"型別", "國家 / 地區", "年份"};

    static final class Choice {
        final String label, value;

        Choice(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    static final class Definition {
        final String dimension, key, label;
        boolean remote;
        final List<Choice> choices = new ArrayList<>();

        Definition(String dimension, String key, String label, boolean remote) {
            this.dimension = dimension;
            this.key = key;
            this.label = label;
            this.remote = remote;
            choices.add(new Choice("全部", ""));
        }

        String labelFor(String value) {
            for (Choice choice : choices) if (choice.value.equals(value)) return choice.label;
            return "全部";
        }
    }

    static boolean allowedKey(String key) {
        return Arrays.asList("class", "genre", "area", "region", "country", "year", "lang", "by")
                .contains(key);
    }

    static String dimension(String key, String label) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        String l = label == null ? "" : label.trim();
        if (k.equals("year") || k.equals("yr") || l.contains("年份") || l.contains("年代"))
            return "year";
        if (Arrays.asList("area", "region", "country").contains(k)
                || l.contains("地区")
                || l.contains("国家")
                || l.contains("产地")
                || l.contains("区域")) return "area";
        if (Arrays.asList("class", "genre", "type", "cateid", "plot", "style").contains(k)
                || l.contains("类型")
                || l.contains("题材")
                || l.contains("剧情")
                || l.contains("分类")
                || l.contains("风格")) return "class";
        return "";
    }

    static List<Definition> definitions(String home, String category, List<Cms.Video> videos) {
        Map<String, Definition> remote = new LinkedHashMap<>();
        try {
            Object filters = new JSONObject(home).opt("filters");
            JSONArray groups =
                    filters instanceof JSONArray
                            ? (JSONArray) filters
                            : filters instanceof JSONObject
                                    ? ((JSONObject) filters).optJSONArray(category)
                                    : null;
            if (groups != null)
                for (int i = 0; i < Math.min(groups.length(), 32); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    String key = group.optString("key"), label = group.optString("name");
                    String dim = dimension(key, label);
                    // Preserve arbitrary Spider keys for recognized dimensions. HTTP engines
                    // additionally restrict keys that could alter their request routing.
                    if (dim.isEmpty() || key.isEmpty() || key.length() > 64) continue;
                    JSONArray values = group.optJSONArray("value");
                    if (values == null) values = group.optJSONArray("values");
                    Definition def =
                            new Definition(
                                    dim, key, LABELS[Arrays.asList(DIMENSIONS).indexOf(dim)], true);
                    Set<String> seen = new HashSet<>();
                    seen.add("");
                    if (values != null)
                        for (int n = 0; n < Math.min(values.length(), 256); n++) {
                            JSONObject item = values.optJSONObject(n);
                            String value =
                                    item == null
                                            ? values.optString(n)
                                            : item.optString("v", item.optString("value"));
                            String name =
                                    item == null
                                            ? value
                                            : item.optString("n", item.optString("name", value));
                            if (name.equals("全部") || name.equals("不限")) continue;
                            if (!value.isEmpty() && !name.isEmpty() && seen.add(value))
                                def.choices.add(new Choice(name, value));
                        }
                    if (def.choices.size() > 1
                            && (!remote.containsKey(dim) || key.equalsIgnoreCase(dim)))
                        remote.put(dim, def);
                }
        } catch (JSONException ignored) {
        }
        List<Definition> result = new ArrayList<>();
        for (int i = 0; i < DIMENSIONS.length; i++) {
            String dim = DIMENSIONS[i];
            Definition def = remote.get(dim);
            if (def == null) {
                def = new Definition(dim, dim, LABELS[i], false);
                Set<String> values = new LinkedHashSet<>();
                if (dim.equals("class"))
                    Collections.addAll(
                            values, "劇情", "喜劇", "動作", "愛情", "科幻", "懸疑", "犯罪", "驚悚", "動畫", "紀錄片");
                if (dim.equals("area"))
                    Collections.addAll(
                            values, "中國大陸", "中國香港", "中國臺灣", "美國", "英國", "日本", "韓國", "法國", "印度",
                            "泰國");
                if (dim.equals("year")) {
                    int year = Calendar.getInstance().get(Calendar.YEAR);
                    for (int n = year; n >= year - 15; n--) values.add(String.valueOf(n));
                }
                for (Cms.Video video : videos) {
                    String raw =
                            dim.equals("class")
                                    ? video.genre
                                    : dim.equals("area") ? video.area : video.year;
                    if (raw != null)
                        for (String value : raw.split("[ /、,，;；|]+")) {
                            value = value.trim();
                            if (!value.isEmpty() && value.length() <= 24 && values.size() < 256)
                                values.add(value);
                        }
                }
                for (String value : values) def.choices.add(new Choice(value, value));
            }
            result.add(def);
        }
        return result;
    }

    static Map<String, String> remoteValues(
            List<Definition> definitions, Map<String, String> selected) {
        return remoteValues(definitions, selected, false);
    }

    static Map<String, String> remoteValues(
            List<Definition> definitions,
            Map<String, String> selected,
            boolean includeHttpCmsFallback) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Definition def : definitions) {
            String value = selected.get(def.dimension);
            if (value == null || value.isEmpty()) continue;
            if (def.remote) {
                values.put(def.key, value);
            } else if (includeHttpCmsFallback && allowedKey(def.dimension)) {
                String queryValue = value;
                if (def.dimension.equals("area")) {
                    queryValue =
                            queryValue
                                    .replace("中國大陸", "大陸")
                                    .replace("中國香港", "香港")
                                    .replace("中國臺灣", "臺灣");
                }
                values.put(def.dimension, queryValue);
            }
        }
        return values;
    }

    static boolean hasLocal(List<Definition> definitions, Map<String, String> selected) {
        for (Definition def : definitions) {
            String value = selected.get(def.dimension);
            if (!def.remote && value != null && !value.isEmpty()) return true;
        }
        return false;
    }

    static List<Cms.Video> apply(
            List<Cms.Video> videos, List<Definition> definitions, Map<String, String> selected) {
        List<Cms.Video> result = new ArrayList<>();
        for (Cms.Video video : videos) {
            boolean match = true;
            for (Definition def : definitions) {
                String value = selected.get(def.dimension);
                if (def.remote || value == null || value.isEmpty()) continue;
                if (!matchesVideo(video, def.labelFor(value), def.dimension)) {
                    match = false;
                    break;
                }
            }
            if (match) result.add(video);
        }
        return result;
    }

    private static boolean meaningfulGenre(String raw) {
        if (raw == null) return false;
        String cleaned =
                raw.replace("電影", "")
                        .replace("電視劇", "")
                        .replace("連續劇", "")
                        .replace("劇集", "")
                        .replace("綜藝", "")
                        .replace("動漫", "")
                        .replace("影視", "")
                        .replaceAll("[ /、,，;；|]+", "")
                        .trim();
        return !cleaned.isEmpty();
    }

    private static String joinFields(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    static boolean matchesVideo(Cms.Video video, String label, String dimension) {
        if (video == null) return false;
        if (dimension.equals("year")) {
            if (video.year != null && !video.year.trim().isEmpty()) {
                return matches(video.year, label, "year");
            }
            return matches(joinFields(video.remarks, video.tag, video.title), label, "year");
        }
        if (dimension.equals("area")) {
            if (video.area != null && !video.area.trim().isEmpty()) {
                return matches(video.area, label, "area");
            }
            return matches(
                    joinFields(video.remarks, video.tag, video.description, video.title),
                    label,
                    "area");
        }
        if (meaningfulGenre(video.genre)) {
            return matches(video.genre, label, "class")
                    || matches(joinFields(video.tag, video.remarks), label, "class");
        }
        return matches(
                joinFields(video.genre, video.tag, video.remarks, video.description, video.title),
                label,
                "class");
    }

    private static String normalizeAreaText(String s) {
        return s.replace("中國大陸", "大陸")
                .replace("內地", "大陸")
                .replace("國產", "大陸")
                .replace("國語", "大陸")
                .replace("普通話", "大陸")
                .replace("中國香港", "香港")
                .replace("港劇", "香港")
                .replace("港片", "香港")
                .replace("粵語", "香港")
                .replace("中國臺灣", "臺灣")
                .replace("臺劇", "臺灣")
                .replace("歐美", "美國")
                .replace("好萊塢", "美國")
                .replace("美劇", "美國")
                .replace("韓劇", "韓國")
                .replace("韓語", "韓國")
                .replace("日劇", "日本")
                .replace("日語", "日本")
                .replace("日漫", "日本")
                .replace("英劇", "英國")
                .replace("泰劇", "泰國");
    }

    static boolean matches(String raw, String value, String dimension) {
        if (raw == null || raw.trim().isEmpty() || value == null || value.trim().isEmpty())
            return false;
        if (dimension.equals("area")) {
            raw = normalizeAreaText(raw);
            value = normalizeAreaText(value);
        }
        if (dimension.equals("year"))
            return raw.matches(
                    ".*(?<![0-9])" + java.util.regex.Pattern.quote(value) + "(?![0-9]).*");
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        String lowerVal = value.toLowerCase(Locale.ROOT);
        if (lowerRaw.contains(lowerVal)) return true;
        if (dimension.equals("class") && lowerVal.length() > 2) {
            String stem = lowerVal.replaceAll("[片劇漫]$", "");
            if (stem.length() >= 2 && lowerRaw.contains(stem)) return true;
        }
        return false;
    }
}
