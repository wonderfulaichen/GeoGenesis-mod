package com.geogenesis.worldgen.process;

import com.geogenesis.diagnostics.WorldGenProfiler;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Same real B input + unchanged particle kernels, four simultaneous request threads, cold process per arm. */
public final class BInputBlockCachePerformanceProbe {
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b->Math.max(0,b.getCollectionCount())).sum(); }
    private static long gcMs() { return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b->Math.max(0,b.getCollectionTime())).sum(); }
    public static void main(String[] args) throws Exception {
        boolean enabled=args.length>0 && args[0].equals("on");
        boolean kernels=args.length<2 || !args[1].equals("input-only");
        var ctx=ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE);
        var cache=enabled?new BInputBlockCache(4096):null;
        var pool=Executors.newFixedThreadPool(4);var sampler=Executors.newSingleThreadScheduledExecutor();
        var heap=ManagementFactory.getMemoryMXBean();var peak=new AtomicLong(heap.getHeapMemoryUsage().getUsed());
        var samples=new java.util.concurrent.ConcurrentLinkedQueue<String>();
        sampler.scheduleAtFixedRate(()->{long used=heap.getHeapMemoryUsage().getUsed();
            peak.accumulateAndGet(used,Math::max);samples.add(System.nanoTime()+","+used);},0,10,TimeUnit.MILLISECONDS);
        WorldGenProfiler.configure(true,200);
        long beforeCount=gcCount(),beforeMs=gcMs(),start=System.nanoTime();
        var active=new AtomicInteger();var maxActive=new AtomicInteger();
        long checksum=0;int tiles=36;
        try {
            var futures=new ArrayList<Future<Long>>();var ready=new CountDownLatch(4);var go=new CountDownLatch(1);
            for(int worker=0;worker<4;worker++) {
                final int lane=worker;
                futures.add(pool.submit(()->{
                    ready.countDown();if(!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("start barrier");
                    long hash=0;
                    for(int i=lane;i<tiles;i+=4) {
                        maxActive.accumulateAndGet(active.incrementAndGet(),Math::max);
                        try {
                            var owner=new TileGeometry.Owner((i%6)*48-2160,(i/6)*48-3168);
                            if(kernels) {
                                var tile=ParticleTile.build(ctx,owner,()->false,"performance",cache);
                                for(int z=0;z<128;z++) for(int x=0;x<128;x++)
                                    hash=hash*31+Float.floatToRawIntBits(tile.at(owner.originX()+x,owner.originZ()+z));
                            } else {
                                var input=ParticleTile.buildInput(ctx,owner,()->false,"performance",cache);
                                for(float value:input) hash=hash*31+Float.floatToRawIntBits(value);
                            }
                        } finally {active.decrementAndGet();}
                    }
                    return hash;
                }));
            }
            if(!ready.await(10,TimeUnit.SECONDS)) throw new AssertionError("workers not ready");
            go.countDown();for(var future:futures) checksum=checksum*31+future.get(120,TimeUnit.SECONDS);
            long wall=System.nanoTime()-start;var counts=ctx.baseCacheDiagnostics().tileCounts();
            var stats=cache==null?null:cache.stats();long logical=(long)tiles*146*146;
            if(maxActive.get()!=4) throw new AssertionError("not genuinely concurrent");
            if(enabled && (stats==null || stats.evaluations()==0 || stats.hits()==0
                    || stats.requests()!=logical || stats.evaluations()!=counts.calls()
                    || stats.hits()+counts.calls()!=logical)) throw new AssertionError("candidate metrics/wiring");
            if(!enabled && counts.calls()!=logical) throw new AssertionError("direct metric wiring");
            System.out.printf(Locale.ROOT,"RESULT enabled=%s kernels=%s workers=4 maxActive=%d tiles=%d logical=%d samplerCalls=%d pointHits=%d rawB=%d failures=%d wallNs=%d checksum=%d gcCount=%d gcMs=%d sampledHeapMaxBytes=%d samplePeriodMs=10 heapScope=observed_heap_used_not_full_JVM_peak block=%s%n",
                    enabled,kernels,maxActive.get(),tiles,logical,counts.calls(),counts.pointHits(),counts.rawComputations(),
                    counts.failures(),wall,checksum,gcCount()-beforeCount,gcMs()-beforeMs,peak.get(),stats);
            for(String sample:samples) System.out.println("HEAP_SAMPLE "+sample);
        } finally {
            pool.shutdownNow();sampler.shutdownNow();if(cache!=null) cache.close();WorldGenProfiler.configure(false,200);
        }
    }
}
