package app.mkoai.player;

/** Stable host-side boundary. Implementations must return CMS-shaped JSON, never UI. */
public interface SourceEngine {
    String home() throws Exception;

    String category(String categoryId, int page) throws Exception;

    default String category(String categoryId, int page, java.util.Map<String, String> filters)
            throws Exception {
        return category(categoryId, page);
    }

    String detail(String videoId) throws Exception;

    String search(String keyword, int page) throws Exception;

    boolean requiresExternalRuntime();

    default String homeVideos() throws Exception {
        return "{\"list\":[]}";
    }

    default String liveContent(String url) throws Exception {
        throw new java.io.IOException("此來源沒有提供直播目錄介面");
    }

    default String player(String flag, String id) throws Exception {
        return new org.json.JSONObject().put("parse", 0).put("url", id).toString();
    }

    default String action(String action) throws Exception {
        throw new java.io.IOException("此來源沒有提供互動介面");
    }

    default boolean supportsCustomAction() {
        return true;
    }

    default String detailAction(String id) throws Exception {
        return detail(id);
    }

    default void close() {}

    default boolean isClosed() {
        return false;
    }
}
