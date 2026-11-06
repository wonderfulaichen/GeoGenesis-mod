package com.geogenesis.worldgen.process;

import com.geogenesis.worldgen.core.*;
import com.geogenesis.worldgen.generator.WorldSeedLifecycle;
import com.geogenesis.worldgen.terrain.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Deterministic cancellation/retirement races; all external waits have deadlines. */
public final class BInputBlockCacheRaceProbe {
    static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static void await(CountDownLatch latch) {
        try { require(latch.await(10,TimeUnit.SECONDS),"latch timeout"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new CancellationException(); }
    }
    static void until(BooleanSupplier predicate) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!predicate.getAsBoolean()) { require(System.nanoTime()<end,"condition timeout");Thread.sleep(2); }
    }
    static void cancelled(Future<?> future) throws Exception {
        try { future.get(10,TimeUnit.SECONDS);throw new AssertionError("expected cancellation"); }
        catch(ExecutionException ex) { require(ex.getCause() instanceof CancellationException,"wrong failure "+ex); }
    }
    static void smallCancel(int capacity) throws Exception {
        var pool=Executors.newFixedThreadPool(capacity+3);
        var release=new CountDownLatch(1);var entered=new CountDownLatch(capacity);
        try(var cache=new BInputBlockCache(capacity)) {
            var holders=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<capacity;i++) {
                final int x=i*32;
                holders.add(pool.submit(()->cache.copy(x,0,1,1,(a,b)->{
                    entered.countDown();await(release);return .25;
                },(a,b,v)->{},()->false)));
            }
            await(entered);var token=new AtomicBoolean();
            var capacityWait=pool.submit(()->cache.copy(capacity*32,0,1,1,(a,b)->.5,(a,b,v)->{},token::get));
            until(()->cache.stats().capacityWaits()>0);token.set(true);cancelled(capacityWait);
            var blockToken=new AtomicBoolean();
            var blockWait=pool.submit(()->cache.copy(0,0,1,1,(a,b)->{throw new AssertionError("duplicate");},
                    (a,b,v)->{},blockToken::get));
            until(()->cache.stats().blockWaits()>0);blockToken.set(true);cancelled(blockWait);
            require(cache.stats().allocated()==capacity,"capacity overflow");
            release.countDown();for(var holder:holders) holder.get(10,TimeUnit.SECONDS);
            require(cache.stats().leased()==0,"cancel leaked lease");
            cache.copy(capacity*32,0,1,1,(a,b)->.75,(a,b,v)->{},()->false);
        } finally {release.countDown();pool.shutdownNow();}
        System.out.println("capacity="+capacity+" capacity/block token cancellation PASS");
    }
    static void sameContext() throws Exception {
        var ctx=ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE);
        var budget=new ProcessRuntime.Budget(1,2,8);
        try(var a=new ProcessRuntime(ctx,budget,new ProcessRuntime.Observer(){},4);
            var b=new ProcessRuntime(ctx,budget,new ProcessRuntime.Observer(){},4)) {
            var av=a.exactP(-2137.5,-3165.5);var bv=b.exactP(-2137.5,-3165.5);
            ProcessRuntimeCheck.same(av.eP(),bv.eP(),"same context output");
            require(a.inputCacheStats().evaluations()>0 && b.inputCacheStats().evaluations()>0,"private caches");
            long before=b.inputCacheStats().evaluations();a.close();
            require(a.inputCacheStats().allocated()==0,"retired cache payload");
            b.exactP(-2089.5,-3165.5);
            require(b.inputCacheStats().evaluations()>before,"other runtime survived close");
        }
        var off=ProcessRuntimeCheck.context(ProcessContext.Mode.OFF);
        try(var r=new ProcessRuntime(off,budget,new ProcessRuntime.Observer(){},4096)) {
            require(r.inputCacheStats()==null,"OFF allocated cache");r.exactP(0,0);
        }
        System.out.println("same context independent runtime ownership/OFF PASS");
    }
    static void retirement() throws Exception {
        var life=new WorldSeedLifecycle<ProcessRuntime>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var observer=new ProcessRuntime.Observer() {
            public void beforePublish(TileGeometry.Owner owner) {
                entered.countDown();
                while(release.getCount()>0) try {release.await();} catch(InterruptedException ignored) {}
                Thread.interrupted();
            }
        };
        var old=new ProcessRuntime(ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE),
                new ProcessRuntime.Budget(1,2,8),observer,2);
        life.bind(9139912035078620160L);life.getOrBuild(seed->old);long version=life.version();
        var request=old.requestExact(1656,2280);await(entered);
        ProcessRuntime detached;
        synchronized(life) {detached=life.current();life.bind(9139);}
        var callback=new AtomicBoolean();
        request.future().whenComplete((v,e)->{
            require(!Thread.holdsLock(life),"retirement callback under lifecycle monitor");callback.set(true);
        });
        detached.close();require(callback.get(),"close did not finish subscriber");
        var def=ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE).definition();
        var freshContext=new ProcessContext(new ProcessContext.Definition(9139,def.baseAlgorithm(),def.terrain(),
                def.settings(),def.minY(),def.maxY(),def.mode(),def.particle(),def.xs(),def.strength(),def.material(),def.parameterSource()));
        try(var fresh=life.getOrBuild(seed->new ProcessRuntime(freshContext,new ProcessRuntime.Budget(1,2,8),
                new ProcessRuntime.Observer(){},2))) {
            require(!life.matches(version,old),"stale identity accepted");
            release.countDown();until(()->old.metrics().active()==0);
            require(old.metrics().successful()==0 && old.inputCacheStats().allocated()==0,"late retired publication");
            var value=fresh.exactP(1656,2280);
            ProcessRuntimeCheck.same(value.base().eB(),freshContext.baseE(1656,2280),"new seed raw B");
            require(fresh.inputCacheStats().evaluations()>0,"fresh runtime not enabled");
            synchronized(life) {detached=life.current();life.invalidate();}
            detached.close();require(fresh.inputCacheStats().allocated()==0,"invalidate retention");
            life.unbind();
        } finally {release.countDown();old.close();}
        System.out.println("bind/invalidate/unbind seed retirement late-publication/callback race PASS");
    }
    static void terrainBridge() throws Exception {
        var gen=new CellGenerator(TerrainParams.defaults(),-64,320,true);
        var terrain=new GeoGenesisTerrain(gen);terrain.seed(9139);
        var old=gen.processRuntime();old.exactP(1656,2280);
        require(old.inputCacheStats()!=null && old.inputCacheStats().evaluations()>0,"production default cache disabled");
        terrain.seed(0);require(old.peek(0,0) instanceof ProcessRuntime.Unavailable,"old runtime query");
        require(old.inputCacheStats().allocated()==0,"reseed cache payload retention");
        require(old.metrics().completed()==0,"reseed retained completed tiles");
        var current=gen.processRuntime();current.exactP(1656,2280);terrain.closeProcessErosion();
        require(current.metrics().completed()==0 && !gen.processErosionEnabled(),"terrain close bridge");
        System.out.println("real terrain reseed/close bridge PASS");
    }
    public static void main(String[] args) throws Exception {
        for(int capacity:new int[]{1,2,4}) smallCancel(capacity);
        sameContext();retirement();terrainBridge();
        System.out.println("B INPUT RACES ALL PASS");
    }
}
