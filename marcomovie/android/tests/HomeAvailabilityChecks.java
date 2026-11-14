package app.mkoai.player;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import org.json.*;
public final class HomeAvailabilityChecks {
 static int checks;
 static void check(boolean b,String name) { if(!b)throw new AssertionError(name); checks++;System.out.println("PASS "+name); }
 static Cms.Video video() { Cms.Video v=new Cms.Video();v.title="艾倫的宇宙";v.id="msearch:艾倫的宇宙";return v; }
 static void cache(File dir,String region,long ts) throws Exception {
  JSONObject entry=new JSONObject().put("r",region).put("ts",ts).put("id","real-42").put("po","https://example.test/cover.jpg");
  JSONObject disk=new JSONObject().put("https://source.test/api",new JSONObject().put("艾倫的宇宙",entry));
  Files.write(new File(dir,"awwrated-availability-v3.json").toPath(),disk.toString().getBytes("UTF-8"));
 }
 public static void main(String[] args) throws Exception {
  File dir=Files.createTempDirectory("mkoai-home-test").toFile();
  List<String> keys=Collections.singletonList("https://source.test/api");
  List<Cms.Video> input=Collections.singletonList(video());
  // mkoai 2.0.51: fail open — an unknown title stays on the first paint as
  // pending (refined by the live check in phase 2) instead of blanking the home.
  List<Cms.Video> unknown=AwwratedHome.prefilterVisibleDiskCross(input,keys,dir);
  check(unknown.size()==1,"unknown recommendation kept pending on first paint");
  check(unknown.get(0).sourceApi==null,"pending title carries no confirming source");
  check("msearch:艾倫的宇宙".equals(unknown.get(0).id),"pending title keeps its search id");
  check(!AwwratedHome.prefilterVisibleDiskCross(input,Collections.emptyList(),dir).isEmpty(),"no sources configured fails open, never blanks the home");
  cache(dir,"TW",System.currentTimeMillis());
  List<Cms.Video> found=AwwratedHome.prefilterVisibleDiskCross(Collections.singletonList(video()),keys,dir);
  check(found.size()==1,"confirmed source title visible");
  check("real-42".equals(found.get(0).id),"card uses real source detail ID");
  check(keys.get(0).equals(found.get(0).sourceApi),"card retains confirming source");
  check(found.get(0).poster.endsWith("cover.jpg"),"source cover fills missing metadata cover");
  // Removed source: the title is kept pending, but the other source's cache
  // must not authorize the card (no sourceApi / real id rewrite).
  List<Cms.Video> removed=AwwratedHome.prefilterVisibleDiskCross(input,Collections.singletonList("https://different.test/api"),dir);
  check(removed.size()==1,"removed source title kept pending");
  check(removed.get(0).sourceApi==null && "msearch:艾倫的宇宙".equals(removed.get(0).id),"removed source cache cannot authorize a card");
  cache(dir,"TW",System.currentTimeMillis()-90000000L);
  List<Cms.Video> expired=AwwratedHome.prefilterVisibleDiskCross(input,keys,dir);
  check(expired.size()==1,"expired source confirmation kept pending for recheck");
  check(expired.get(0).sourceApi==null && "msearch:艾倫的宇宙".equals(expired.get(0).id),"expired confirmation does not authorize the card");
  cache(dir,"ABSENT",System.currentTimeMillis());
  // mkoai 2.0.51: even a fully-absent list fails open — the safety net keeps
  // the unfiltered titles so the home never goes blank.
  check(!AwwratedHome.prefilterVisibleDiskCross(input,keys,dir).isEmpty(),"all absent fails open to the unfiltered list");
  cache(dir,"UNKNOWN",System.currentTimeMillis());
  List<Cms.Video> timedOut=AwwratedHome.prefilterVisibleDiskCross(input,keys,dir);
  check(timedOut.size()==1,"timeout title kept pending");
  check(timedOut.get(0).sourceApi==null,"timeouts are not confirmation");
  // mkoai 2.0.51: dead engines fail open to the unfiltered list (the phase-2
  // empty return used to re-blank the home after the first paint).
  check(AwwratedHome.applySourceFiltersCross(input,Collections.emptyList(),keys,dir).size()==1,"dead engines fail open to the unfiltered list");
  Files.write(new File(dir,"awwrated-availability-v3.json").toPath(),"broken json".getBytes("UTF-8"));
  check(!AwwratedHome.prefilterVisibleDiskCross(input,keys,dir).isEmpty(),"corrupt availability cache fails open, keeps titles pending");
  check(ImageLoader.detailCandidates("https://image.tmdb.org/t/p/w1280/a.jpg","https://image.tmdb.org/t/p/w500/a.jpg",true).length==4,"detail artwork has resolution fallback chain");
  check(ImageLoader.upgradePosterUrl("https://image.tmdb.org/t/p/w92/a.jpg",false).contains("w780"),"small TMDB artwork upgraded for cards");
  SourceEngine sparse = new SourceEngine() {
   public String home(){return "";} public String category(String id,int page){return "";}
   public boolean requiresExternalRuntime(){return false;}
   public String search(String keyword,int page){return "{\"list\":[{\"vod_id\":\"42\",\"vod_name\":\"Example\"}]}";}
   public String detail(String id){return "{\"list\":[{\"vod_id\":\"42\",\"vod_name\":\"Example\",\"vod_pic\":\"https://example.test/detail-cover.jpg\"}]}";}
  };
  AwwratedHome.PosterResult enriched=AwwratedHome.fetchPosterLiveResult(sparse,"https://fixture.test/api","Example");
  check(enriched.poster != null && enriched.poster.endsWith("detail-cover.jpg"),"search without cover falls back to source detail cover");
  check(!enriched.absent,"present title with detail cover is not marked absent");
  SourceEngine noCover = new SourceEngine() {
   public String home(){return "";} public String category(String id,int page){return "";}
   public boolean requiresExternalRuntime(){return false;}
   public String search(String keyword,int page){return "{\"list\":[{\"vod_id\":\"43\",\"vod_name\":\"Example\"}]}";}
   public String detail(String id){return "{\"list\":[{\"vod_id\":\"43\",\"vod_name\":\"Example\"}]}";}
  };
  AwwratedHome.PosterResult missing=AwwratedHome.fetchPosterLiveResult(noCover,"https://fixture.test/no-cover","Example");
  check(missing.poster == null && !missing.absent,"missing artwork never poisons title availability");
  java.lang.reflect.Field failedField=ImageLoader.class.getDeclaredField("FAILED"); failedField.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Long> failed=(Map<String,Long>)failedField.get(null);
  String failedUrl="https://fixture.test/recoverable.jpg";
  failed.put(failedUrl,System.currentTimeMillis());
  check(ImageLoader.failed(failedUrl),"recent artwork failure backs off");
  failed.put(failedUrl,System.currentTimeMillis()-60001L);
  check(!ImageLoader.failed(failedUrl),"temporary artwork failure expires and may recover");
  check(!failed.containsKey(failedUrl),"expired artwork failure entry is pruned");
  check(!ImageLoader.failed(null),"null artwork is safe to inspect");
  try (RandomAccessFile oversized=new RandomAccessFile(new File(dir,"awwrated-availability-v3.json"),"rw")) { oversized.setLength(9L*1024*1024); }
  check(AwwratedHome.loadAvailDisk(dir).length()==0,"oversized availability cache is rejected before allocation");
  System.out.println(checks+" home availability and artwork checks passed");
 }
}
