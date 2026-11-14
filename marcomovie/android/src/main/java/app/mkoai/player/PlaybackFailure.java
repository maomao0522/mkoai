package app.mkoai.player;

/** User-facing categories contain no URLs, response bodies or account tokens. */
final class PlaybackFailure {
    static boolean decoderFallback(int code) {
        return code == 4001
                || code == 4002
                || code == 4003
                || code == 4004
                || code == 4005
                || code == 5001
                || code == 5002;
    }

    static String message(int code, int http) {
        if (http == 401 || http == 403) return "服務拒絕訪問。可能是登入、許可權或臨時連結失效，請重新整理線路或檢查賬號。";
        if (http == 404 || http == 410) return "播放連結已失效或內容不存在，請重新整理線路。";
        if (code >= 2000 && code < 3000) return "媒體連線失敗。請檢查網路或重新整理線路重試。";
        if (code >= 3000 && code < 4000) return "返回的資料無法識別為可播放媒體，請嘗試其他線路。";
        if (code >= 4000 && code < 5000) return "當前裝置與解碼元件無法處理這段媒體。可嘗試其他編碼或較低清晰度的版本。";
        if (code >= 5000 && code < 6000) return "音訊解碼或輸出失敗。請嘗試其他音軌或播放器。";
        if (code >= 6000 && code < 7000) return "內容授權不可用，請檢查服務授權與裝置支援。";
        return "播放失敗，請重新整理重試或選擇其他線路。";
    }
}
