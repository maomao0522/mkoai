package app.mkoai.player;

/** Merge concurrent metadata refreshes without losing unrelated lists or newer entries. */
final class MetadataCachePolicy {
    static org.json.JSONObject merge(org.json.JSONObject latest, org.json.JSONObject incoming) throws org.json.JSONException {
        for (String group : new String[]{"items", "lists"}) {
            org.json.JSONObject src = incoming.optJSONObject(group);
            if (src == null) continue;
            org.json.JSONObject dest = latest.optJSONObject(group);
            if (dest == null) { dest = new org.json.JSONObject(); latest.put(group, dest); }
            java.util.Iterator<String> keys = src.keys();
            while (keys.hasNext()) {
                String key = keys.next(); org.json.JSONObject candidate = src.optJSONObject(key);
                org.json.JSONObject current = dest.optJSONObject(key);
                if (candidate != null && (current == null || candidate.optLong("ts",0) >= current.optLong("ts",0))) dest.put(key,candidate);
            }
        }
        return latest;
    }
    private MetadataCachePolicy() {}
}
