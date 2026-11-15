package app.mkoai.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: translation client, through the Cloudflare Worker. Pure logic, no UI. All network
 * goes through {@link WorkerApi}; no API key lives in the app.
 *
 * <p>Primary route: POST /api/translate {@code {text, target_lang:"ZH-HANT"}} → 200
 * {@code {source_lang, target_lang, text, translated_text, cached, generated_at}}; text over 5000
 * chars is 400 text_too_long; DeepL quota exhausted is 429 deepl_quota_exhausted. <b>There is no
 * built-in MyMemory fallback on the Worker</b> — the fallback is a separate route, GET
 * /api/mymemory?text=&target_lang=ZH-HANT (500 chars max per call, ~10,000 chars/day shared
 * quota), success {@code {source:"mymemory", translation, match, ...}}, quota exhausted is 429
 * upstream_rate_limited.
 */
public final class TranslateClient {

    private static final String TARGET_LANG = "ZH-HANT";
    private static final int DEEPL_MAX_CHARS = 5000;
    private static final int MYMEMORY_MAX_CHARS = 500;

    private TranslateClient() {}

    /**
     * Translate {@code text} to Traditional Chinese (ZH-HANT). Text over 5000 chars is truncated
     * to 5000 first. On a DeepL 429 (deepl_quota_exhausted, or any {@link WorkerApi.RateLimited})
     * it falls back to MyMemory, chunked per sentence, and the chunk translations are joined back
     * in order. If MyMemory also fails, throws {@link IOException} — it never returns the
     * untranslated text pretending the translation succeeded.
     */
    public static String translate(String text) throws IOException {
        text = truncate(text);
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
            body.put("target_lang", TARGET_LANG);
        } catch (Exception e) {
            throw new IOException("translate_request_failed", e);
        }
        try {
            JSONObject res = WorkerApi.postJson("/api/translate", body);
            String translated = res == null ? null : res.optString("translated_text", null);
            if (translated == null) throw new IOException("translate_empty_response");
            return translated;
        } catch (WorkerApi.RateLimited rateLimited) {
            // DeepL quota exhausted (or any 429): fall back to MyMemory.
            // If MyMemory also fails, the ORIGINAL RateLimited is
            // rethrown (mkoai 2.0.66) so the caller still sees the
            // DeepL quota error and can hide the translate button —
            // swallowing it as MyMemory's IOException would make the
            // quota invisible and the button would never hide.
            try {
                return translateViaMyMemory(text);
            } catch (IOException fallbackFailed) {
                throw rateLimited;
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("deepl_quota_exhausted")) {
                try {
                    return translateViaMyMemory(text);
                } catch (IOException fallbackFailed) {
                    throw e;
                }
            }
            throw e;
        }
    }

    /**
     * mkoai 2.0.62: truncate to the DeepL 5000-char limit (null -> ""). Pure,
     * so the JVM checks can verify the truncation boundary without network.
     */
    static String truncate(String text) {
        if (text == null) return "";
        return text.length() > DEEPL_MAX_CHARS ? text.substring(0, DEEPL_MAX_CHARS) : text;
    }

    /** MyMemory fallback: split into ≤500-char chunks, translate each, join in order. */
    private static String translateViaMyMemory(String text) throws IOException {
        List<String> chunks = splitChunks(text, MYMEMORY_MAX_CHARS);
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            String path =
                    "/api/mymemory?text="
                            + WorkerApi.encode(chunk)
                            + "&target_lang="
                            + TARGET_LANG;
            JSONObject res = WorkerApi.getJson(path);
            String translation = res == null ? null : res.optString("translation", null);
            if (translation == null) throw new IOException("mymemory_empty_response");
            out.append(translation);
        }
        return out.toString();
    }

    /**
     * Split text into chunks of at most {@code maxChars}, breaking after a sentence-ending mark
     * (。！？； or a line break) when possible. A single sentence longer than the limit is hard-split
     * so no chunk ever exceeds it (MyMemory rejects >500 chars).
     */
    static List<String> splitChunks(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        // Split into sentences, keeping the ending mark on each sentence.
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                sentences.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) sentences.add(text.substring(start));

        StringBuilder cur = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.length() > maxChars) {
                // Long sentence: flush what we have, then hard-split it.
                if (cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    chunks.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + sentence.length() > maxChars) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(sentence);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        if (chunks.isEmpty()) chunks.add("");
        return chunks;
    }
}
