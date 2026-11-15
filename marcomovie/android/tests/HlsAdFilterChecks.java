package app.mkoai.player;
import java.util.*;
public final class HlsAdFilterChecks {
    static int total;
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); total++; System.out.println("PASS " + name); }
    static HlsAdFilter.Policy policy(String[] hosts, String[] prefixes) {return new HlsAdFilter.Policy(Arrays.asList(hosts),Arrays.asList(prefixes));}
    static final HlsAdFilter.Policy EMPTY=policy(new String[]{},new String[]{});
    static String segment(String name) { return "#EXTINF:10,\n"+name+"\n"; }
    static String vod(String ad,int position) {
        StringBuilder s=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:99\n");
        for(int i=0;i<20;i++){if(i==position)s.append(ad);s.append(segment("main"+i+".ts"));}
        if(position==20)s.append(ad);
        return s.append("#EXT-X-ENDLIST\n").toString();
    }
    static HlsAdFilter.Result filter(String input,HlsAdFilter.Policy p){return HlsAdFilter.filter("https://cdn.example/video/index.m3u8",input,p);}
    static void unchanged(String input,String name){check(!filter(input,EMPTY).changed()&&filter(input,EMPTY).text.equals(input),name);}
    public static void main(String[] args) {
        String pod="#EXT-X-CUE-OUT:20\n"+segment("advert1.ts")+segment("advert2.ts")+"#EXT-X-CUE-IN\n";
        for(int pos:new int[]{0,7,20}){
            HlsAdFilter.Result r=filter(vod(pod,pos),EMPTY);
            check(r.removedSegments==2&&r.removedSeconds==20&&!r.text.contains("advert"),"remove marked ad at position "+pos);
            for(int i=0;i<20;i++)if(!r.text.contains("main"+i+".ts"))throw new AssertionError("lost main segment");
            check(r.text.contains("#EXT-X-MEDIA-SEQUENCE:99")&&r.text.endsWith("#EXT-X-ENDLIST\n"),"preserve playlist identity/end at "+pos);
        }
        check(filter(vod(pod,7),EMPTY).text.contains("#EXT-X-DISCONTINUITY\n#EXTINF:10,\nmain7.ts"),"keep content splice boundary");
        unchanged(vod("#EXT-X-DISCONTINUITY\n"+segment("other-cdn.ts"),4),"ordinary discontinuity is not an advertisement");
        unchanged(vod(segment("https://another-cdn.example/a.ts"),4),"CDN change alone is not an advertisement");
        String hostAd=vod(segment("https://ads.example/a.ts"),3);
        check(filter(hostAd,policy(new String[]{"ads.example"},new String[]{})).removedSegments==1,"confirmed host rule at variable position");
        check(!filter(vod(segment("https://not-ads.example/a.ts"),3),policy(new String[]{"ads.example"},new String[]{})).changed(),"host matching never uses substrings");
        check(!filter(vod(segment("https://cdn.example/main.ts?redirect=https://ads.example/a.ts"),3),policy(new String[]{"ads.example"},new String[]{})).changed(),"query text cannot impersonate an ad host");
        HlsAdFilter.Policy prefix=policy(new String[]{},new String[]{"https://cdn.example/spots/"});
        check(filter(vod(segment("../spots/a.ts"),2),prefix).removedSegments==1,"confirmed URL-directory prefix resolves relative paths");
        check(!filter(vod(segment("https://cdn.example/spots-other/a.ts"),2),prefix).changed(),"URL prefix respects directory boundaries");
        check(!filter(hostAd,policy(new String[]{"ads.example/path"},new String[]{"https://cdn.example/"})).changed(),"reject overly broad or malformed rules");
        unchanged(vod(pod,3).replace("#EXT-X-ENDLIST\n",""),"live playlist untouched");
        for(String tag:new String[]{"#EXT-X-KEY:METHOD=AES-128,URI=\"key\"","#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"","#EXT-X-BYTERANGE:100@0","#EXT-X-MAP:URI=\"init.mp4\"","#EXT-X-PART:DURATION=1,URI=\"p\"","#EXT-X-SKIP:SKIPPED-SEGMENTS=1"})
            unchanged(vod(pod,3).replace("#EXT-X-VERSION:3",tag+"\n#EXT-X-VERSION:3"),"special playlist retained: "+tag.split(":")[0]);
        check(filter(vod(pod.replace("CUE-OUT:20", "CUE-OUT:DURATION=\"20\",ID=\"ad1\""),3),EMPTY).removedSegments==2,"cue duration attribute with ID");
        unchanged(vod(pod.replace("#EXT-X-CUE-IN\n",""),3),"unclosed cue retained");
        unchanged(vod(pod.replace("CUE-OUT:20","CUE-OUT:40"),3),"mismatched cue duration retained");
        unchanged(vod(pod.replace("CUE-OUT:20","CUE-OUT:NaN"),3),"non-finite cue retained");
        unchanged(vod("#EXT-X-CUE-IN\n",3),"orphan cue retained");
        unchanged(vod(pod.replace("#EXTINF:10,","#EXTINF:-1,"),3),"invalid segment duration retained");
        StringBuilder largePod=new StringBuilder("#EXT-X-CUE-OUT:180\n");for(int i=0;i<18;i++)largePod.append(segment("ad"+i));largePod.append("#EXT-X-CUE-IN\n");
        unchanged(vod(largePod.toString(),2),"refuse excessive deletion percentage");
        unchanged(vod(pod,3).replace("#EXTINF:10,", "#EXTINF:1e308,"),"unreasonable numeric durations retained");
        String crlf=vod(pod,4).replace("\n","\r\n");check(filter(crlf,EMPTY).removedSegments==2,"CRLF supported");
        check(filter("\ufeff"+vod(pod,4),EMPTY).removedSegments==2,"UTF-8 BOM supported");
        unchanged(vod("#EXT-X-DATERANGE:CLASS=\"chapter\",ID=\"part2\"\n",5),"chapter metadata retained");
        unchanged("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1234\nlow/index.m3u8\n", "master playlist untouched");
        HlsAdFilter.Result twice=filter(filter(vod(pod,4),EMPTY).text,EMPTY);check(!twice.changed(),"idempotent rewrite");
        System.out.println(total+" HLS ad-filter checks passed");
    }
}
