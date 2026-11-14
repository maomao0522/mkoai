package app.mkoai.player;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
public final class MetadataSchedulingChecks {
 static int n;
 static void check(boolean b,String name){if(!b)throw new AssertionError(name);n++;System.out.println("PASS "+name);}
 public static void main(String[] args)throws Exception {
  AtomicInteger active=new AtomicInteger(),max=new AtomicInteger(); CountDownLatch four=new CountDownLatch(4);
  List<Callable<Integer>> jobs=new ArrayList<>();
  for(int i=0;i<12;i++){final int index=i;jobs.add(()->{int current=active.incrementAndGet();max.accumulateAndGet(current,Math::max);four.countDown();try{if(!four.await(2,TimeUnit.SECONDS))throw new AssertionError("no concurrency");return index;}finally{active.decrementAndGet();}});}
  List<Integer> values=BoundedBatch.run(jobs,20,3000);
  check(max.get()==4,"metadata concurrency capped at four even when callers request twenty");
  check(values.size()==12,"every input keeps an output slot");
  for(int i=0;i<12;i++)check(values.get(i)!=null && values.get(i)==i,"stable category ordering "+i);
  List<Integer> partial=BoundedBatch.run(Arrays.asList(()->{throw new java.io.IOException("provider failed");},()->7),2,1000);
  check(partial.get(0)==null && partial.get(1)==7,"one failed provider does not remove successful shelves");
  CountDownLatch cancelled=new CountDownLatch(1);
  List<Integer> timed=BoundedBatch.run(Arrays.asList(()->{try{new CountDownLatch(1).await();return 0;}catch(InterruptedException e){cancelled.countDown();throw e;}},()->42),2,150);
  check(timed.get(0)==null && timed.get(1)==42,"deadline retains fast result while slow provider is cancelled");
  check(cancelled.await(1,TimeUnit.SECONDS),"timed-out worker receives cancellation");
  check(BoundedBatch.run(Collections.<Callable<Integer>>emptyList(),4,100).isEmpty(),"empty category list completes immediately");
  JSONObject latest=new JSONObject().put("lists",new JSONObject().put("newer",new JSONObject().put("ts",200).put("value","new")));
  JSONObject stale=new JSONObject().put("lists",new JSONObject().put("newer",new JSONObject().put("ts",100).put("value","old")).put("other",new JSONObject().put("ts",150))).put("items",new JSONObject().put("poster",new JSONObject().put("ts",123)));
  JSONObject merged=MetadataCachePolicy.merge(latest,stale);
  check("new".equals(merged.getJSONObject("lists").getJSONObject("newer").getString("value")),"late stale writer cannot overwrite fresher metadata");
  check(merged.getJSONObject("lists").has("other"),"concurrent unrelated category retained");
  check(merged.getJSONObject("items").has("poster"),"poster searches and category lists both retained");
  JSONObject replacement=new JSONObject().put("lists",new JSONObject().put("newer",new JSONObject().put("ts",300).put("value","latest")));
  check("latest".equals(MetadataCachePolicy.merge(merged,replacement).getJSONObject("lists").getJSONObject("newer").getString("value")),"newer metadata replaces old entry");
  System.out.println(n+" scheduling and cache checks passed");
 }
}
