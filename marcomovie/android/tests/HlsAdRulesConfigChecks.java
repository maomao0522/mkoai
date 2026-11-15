package app.mkoai.player;
import java.util.*;
public final class HlsAdRulesConfigChecks {
    public static void main(String[] args) throws Exception {
        Cms.Config c=Cms.parseConfig("{\"sites\":[{\"key\":\"fixture\",\"name\":\"fixture\",\"type\":1,\"api\":\"https://example.com/api\",\"hlsAdRules\":{\"hosts\":[\"ads.example\"],\"urlPrefixes\":[\"https://cdn.example/spots/\"]}}]}","https://example.com/rules.json");
        if(c.sites.size()!=1||c.sites.get(0).hlsAdRules==null)throw new AssertionError("rules lost");
        if(!"ads.example".equals(c.sites.get(0).hlsAdRules.getJSONArray("hosts").getString(0)))throw new AssertionError("host changed");
        if(!Arrays.asList(Prefs.KEYS).contains(Prefs.FILTER_HLS_ADS))throw new AssertionError("not backed up");
        if(!Boolean.TRUE.equals(Prefs.sanitizeSetting(Prefs.FILTER_HLS_ADS,Boolean.TRUE)))throw new AssertionError("true invalid");
        if(!Boolean.FALSE.equals(Prefs.sanitizeSetting(Prefs.FILTER_HLS_ADS,Boolean.FALSE)))throw new AssertionError("false invalid");
        if(Prefs.sanitizeSetting(Prefs.FILTER_HLS_ADS,"false")!=null)throw new AssertionError("wrong backup type allowed");
        System.out.println("6 HLS source-rule and preference checks passed");
    }
}
