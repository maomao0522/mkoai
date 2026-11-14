package app.mkoai.player;

public final class HistoryClearChecks {
    private static int count;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        count++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) throws Exception {
        long t = 1763096400123L;
        check(HistoryClearPolicy.keep(0,0), "legacy rows kept before any clear");
        check(!HistoryClearPolicy.keep(t-1,t), "old remote row excluded");
        check(!HistoryClearPolicy.keep(t,t), "clear boundary excluded");
        check(HistoryClearPolicy.keep(t+1,t), "new viewing survives clear");
        check(HistoryClearPolicy.parseUtc(HistoryClearPolicy.utc(t)) == t, "milliseconds round trip");
        java.util.TimeZone old = java.util.TimeZone.getDefault();
        try {
            for (String zone : new String[]{"Asia/Taipei", "America/New_York", "UTC"}) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                check(HistoryClearPolicy.parseUtc(HistoryClearPolicy.utc(t)) == t, "timezone independent " + zone);
            }
        } finally { java.util.TimeZone.setDefault(old); }
        check(HistoryClearPolicy.parseUtc("2026-11-14T00:00:00.123456+00:00") == HistoryClearPolicy.parseUtc("2026-11-14T00:00:00.123Z"), "PostgREST microseconds truncated safely");
        check(HistoryClearPolicy.parseUtc("2026-11-14T08:00:00+08:00") == HistoryClearPolicy.parseUtc("2026-11-14T00:00:00Z"), "explicit UTC offset");
        check(HistoryClearPolicy.parseUtc("2026-11-13T19:00:00-05:00") == HistoryClearPolicy.parseUtc("2026-11-14T00:00:00Z"), "negative UTC offset");
        check(HistoryClearPolicy.remoteGeneration("old-source", 0), "legacy histories accepted before any clear");
        check(!HistoryClearPolicy.remoteGeneration("", t), "old source-free row rejected after clear even with future timestamp");
        check(!HistoryClearPolicy.remoteGeneration("old-source", t), "old source-bound row rejected after clear");
        check(HistoryClearPolicy.remoteGeneration(HistoryClearPolicy.generation(t), t), "current clear generation accepted");
        check(!HistoryClearPolicy.remoteGeneration(HistoryClearPolicy.generation(t-1), t), "older clear generation rejected");
        check(HistoryClearPolicy.generation(t+1).compareTo(HistoryClearPolicy.generation(t)) > 0, "generation ordering preserves a newer concurrent clear");
        check(!HistoryClearPolicy.localTime(t+8*3600000L,t,t), "legacy timezone-shifted future row cannot bypass clear");
        check(HistoryClearPolicy.localTime(t+1,t,t+1), "fresh local progress retained after clear");
        System.out.println(count + " history deletion checks passed");
    }
}
