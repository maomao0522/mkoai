package app.mkoai.player;

import androidx.media3.common.C;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.decoder.av1.Gav1Library;
import androidx.media3.decoder.vp9.VpxLibrary;
import org.json.JSONObject;

/** Runtime capability checks: a packaged library is not assumed to have loaded. */
final class DecoderSupport {
    private static boolean configured;

    static synchronized void configure() {
        if (configured) return;
        // Our pinned build statically links libvpx into its JNI bridge.
        VpxLibrary.setLibraries(C.CRYPTO_TYPE_UNSUPPORTED, "vpxV2JNI");
        configured = true;
    }

    static JSONObject capabilities() {
        configure();
        JSONObject result = new JSONObject();
        try {
            result.put("ffmpeg", FfmpegLibrary.isAvailable());
            result.put("ffmpeg_version", String.valueOf(FfmpegLibrary.getVersion()));
            result.put("av1", Gav1Library.isAvailable());
            result.put("vp9", VpxLibrary.isAvailable());
            result.put("vpx_version", String.valueOf(VpxLibrary.getVersion()));
            JSONObject audio = new JSONObject();
            for (String mime :
                    new String[] {
                        "audio/ac3",
                        "audio/eac3",
                        "audio/vnd.dts",
                        "audio/vnd.dts.hd",
                        "audio/true-hd",
                        "audio/flac",
                        "audio/opus",
                        "audio/alac",
                        "audio/mp4a-latm"
                    }) audio.put(mime, FfmpegLibrary.supportsFormat(mime));
            result.put("audio", audio);
        } catch (LinkageError | Exception error) {
            try {
                result.put("error", error.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    static String description() {
        return description(true);
    }

    static String description(boolean tvDevice) {
        JSONObject c = capabilities();
        String hwLabel = tvDevice ? "電視解碼能力" : "裝置硬體解碼能力";
        return "FFmpeg 音訊擴充套件："
                + (c.optBoolean("ffmpeg") ? "已就緒 · " + c.optString("ffmpeg_version") : "未能載入")
                + "\nAV1 影片軟解碼："
                + (c.optBoolean("av1") ? "已就緒" : "未能載入")
                + "\nVP9 影片軟解碼："
                + (c.optBoolean("vp9") ? "已就緒 · " + c.optString("vpx_version") : "未能載入")
                + "\n\n預設優先使用"
                + hwLabel
                + "。擴充套件用於補充相容性；高解析度軟解碼可能較慢。H.264、H.265 仍使用裝置提供的解碼器。系統播放器不使用這些擴充套件。";
    }
}
