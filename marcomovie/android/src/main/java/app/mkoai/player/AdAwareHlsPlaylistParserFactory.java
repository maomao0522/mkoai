package app.mkoai.player;

import android.net.Uri;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Preserves Media3's normal parser, HTTP headers, redirects, variants and segment loading. */
final class AdAwareHlsPlaylistParserFactory implements HlsPlaylistParserFactory {
    private final HlsPlaylistParserFactory delegate = new DefaultHlsPlaylistParserFactory();
    private final HlsAdFilter.Policy policy;
    AdAwareHlsPlaylistParserFactory(JSONObject rules) {
        policy = new HlsAdFilter.Policy(strings(rules, "hosts"), strings(rules, "urlPrefixes"));
    }
    private static List<String> strings(JSONObject rules, String key) {
        List<String> values = new ArrayList<>();
        JSONArray array = rules == null ? null : rules.optJSONArray(key);
        if (array != null) for (int i = 0; i < Math.min(32, array.length()); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty() && value.length() <= 2048) values.add(value);
        }
        return values;
    }
    @Override public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        return wrap(delegate.createPlaylistParser());
    }
    @Override public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            HlsMultivariantPlaylist master, HlsMediaPlaylist previous) {
        ParsingLoadable.Parser<HlsPlaylist> original = delegate.createPlaylistParser(master, previous);
        // Separate audio/subtitle renditions must share a timeline: do not trim video alone.
        if (master.variants.size() > 1 || !master.audios.isEmpty() || !master.subtitles.isEmpty()) return original;
        return wrap(original);
    }
    private ParsingLoadable.Parser<HlsPlaylist> wrap(ParsingLoadable.Parser<HlsPlaylist> original) {
        return (Uri uri, InputStream input) -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, n);
                if (bytes.size() > HlsAdFilter.MAX_CHARS) {
                    return original.parse(uri, new SequenceInputStream(
                            new ByteArrayInputStream(bytes.toByteArray()), input));
                }
            }
            byte[] raw = bytes.toByteArray();
            HlsAdFilter.Result filtered = HlsAdFilter.filter(uri.toString(),
                    new String(raw, StandardCharsets.UTF_8), policy);
            if (!filtered.changed()) return original.parse(uri, new ByteArrayInputStream(raw));
            try {
                return original.parse(uri, new ByteArrayInputStream(filtered.text.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException invalidRewrite) {
                return original.parse(uri, new ByteArrayInputStream(raw));
            }
        };
    }
}
