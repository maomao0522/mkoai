package app.mkoai.player;

/** Account history deletion barrier; independent of Android/network for deterministic checks. */
final class HistoryClearPolicy {
    static boolean keep(long updated, long cleared) { return cleared == 0 || updated > cleared; }
    static String generation(long cleared) { return cleared == 0 ? "" : "__history_generation__" + cleared; }
    static boolean remoteGeneration(String site, long cleared) { return cleared == 0 || generation(cleared).equals(site); }
    static boolean localTime(long updated, long cleared, long now) {
        return keep(updated, cleared) && (cleared == 0 || updated <= now + 300000L);
    }
    static String utc(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }
    static long parseUtc(String ts) throws Exception {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        long seconds = f.parse(ts.substring(0, 19)).getTime();
        if (ts.length() > 19 && ts.charAt(19) == '.') {
            String fraction = ts.substring(20).replaceFirst("[^0-9].*$", "");
            if (!fraction.isEmpty()) seconds += Integer.parseInt((fraction + "000").substring(0, 3));
        }
        // PostgREST returns UTC; accept explicit offsets as well.
        String offset = ts.substring(19).replaceFirst("^\\.[0-9]+", "");
        if (offset.matches("[+-][0-9]{2}:[0-9]{2}")) {
            int minutes = Integer.parseInt(offset.substring(1,3))*60 + Integer.parseInt(offset.substring(4,6));
            seconds -= (offset.charAt(0) == '+' ? 1 : -1)*minutes*60000L;
        }
        return seconds;
    }
    private HistoryClearPolicy() {}
}
