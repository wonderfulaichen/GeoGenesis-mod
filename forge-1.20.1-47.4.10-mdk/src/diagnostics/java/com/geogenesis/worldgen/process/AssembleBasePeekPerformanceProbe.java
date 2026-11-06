package com.geogenesis.worldgen.process;

import com.geogenesis.diagnostics.WorldGenProfiler;
import java.lang.management.ManagementFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One cold JVM per arm. Real input/kernels, bounded windows, original 4/512/1024 execution budget. */
public final class AssembleBasePeekPerformanceProbe {
    // This runner has one request thread; tile workers never write these local workload totals.
    private static long requestNs, awaitNs, requests;
    static long gc(boolean time) {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0,time?b.getCollectionTime():b.getCollectionCount())).sum();
    }
    static ProcessRuntime runtime(ProcessContext c,int capacity,boolean reuse) throws Exception {
        // Baseline compatibility without overlaying any production class. Reflection only at construction.
        try {
            var ctor=ProcessRuntime.class.getDeclaredConstructor(ProcessContext.class,ProcessRuntime.Budget.class,
                    ProcessRuntime.Observer.class,int.class,boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(c,new ProcessRuntime.Budget(4,512,1024),new ProcessRuntime.Observer(){},capacity,reuse);
        } catch(NoSuchMethodException baseline) {
            if(reuse) throw new AssertionError("baseline cannot enable peek");
            return new ProcessRuntime(c,new ProcessRuntime.Budget(4,512,1024),new ProcessRuntime.Observer(){},capacity);
        }
    }
    static long query(ProcessRuntime r,double x,double z,DataOutputStream out) throws Exception {
        var timing=r.currentSamplingTiming();long start=System.nanoTime();
        var sub=r.requestExact(x,z);long requested=System.nanoTime();
        ProcessRuntime.Sample p;
        try {p=sub.await();} finally {
            long waited=System.nanoTime()-requested;
            requestNs+=requested-start; awaitNs+=waited; requests++;
            if(timing!=null) timing.request(requested-start,waited);
            sub.close();
        }
        long hash=0;
        // No per-point log; binary whole-result evidence in canonical traversal order.
        for(double v:new double[]{p.base().eB(),p.base().yB(),p.eP(),p.yP(),p.deltaE(),p.deltaY()}) {
            long b=Double.doubleToRawLongBits(v);out.writeLong(b);hash=hash*31+b;
        }
        return hash;
    }
    static long window(ProcessRuntime r,DataOutputStream out,int cx,int cz,int radius,int step,boolean mixed) throws Exception {
        long hash=0;int n=2*radius/step+1;
        r.prewarmTiles((cx-radius)/2.0,(cz-radius)/2.0,(cx+radius)/2.0,(cz+radius)/2.0);
        for(int i=0;i<n;i++) for(int j=0;j<n;j++) {
            double x=(cx-radius+i*(double)step)/2,z=(cz-radius+j*(double)step)/2;
            hash=hash*31+query(r,x,z,out);
            if(mixed) hash=hash*31+query(r,x+.5,z+.5,out);
            if(i!=0 && j!=0 && i!=n-1 && j!=n-1)continue;
            int oi=i==0?-1:i==n-1?n:i,oj=j==0?-1:j==n-1?n:j;
            hash=hash*31+query(r,(cx-radius+oi*(double)step)/2,(cz-radius+oj*(double)step)/2,out);
        }
        return hash;
    }
    public static void main(String[] args) throws Exception {
        boolean reuse=Boolean.parseBoolean(args[0]);int capacity=Integer.parseInt(args[1]);
        boolean mixed=Boolean.parseBoolean(args[2]);Path path=Path.of(args[3]);
        System.out.println("SOURCE runtime="+ProcessRuntime.class.getProtectionDomain().getCodeSource().getLocation()
                +" cache="+BInputBlockCache.class.getProtectionDomain().getCodeSource().getLocation());
        boolean diagnostics=args.length<5 || Boolean.parseBoolean(args[4]);
        WorldGenProfiler.configure(diagnostics,200);
        long gc0=gc(false),ms0=gc(true),start=System.nanoTime(),hash=0;
        var ctx=ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE);
        System.out.println("PARAMS "+ctx.identity());
        try(var r=runtime(ctx,capacity,reuse);
            var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            for(int w=0;w<3;w++) {
                int cx=w==2?-256:256,cz=w==2?-256:256,radius=mixed?96:352;
                long begin=System.nanoTime();
                try(var timing=r.beginSamplingTiming("perf-window="+w+",capacity="+capacity+",mixed="+mixed)) {
                    hash=hash*31+window(r,out,cx,cz,radius,2,mixed);
                }
                System.out.println("WINDOW w="+w+" wallNs="+(System.nanoTime()-begin)+" hash="+hash+" cache="+r.inputCacheStats());
            }
            out.flush();
            System.out.println("RESULT reuse="+reuse+" capacity="+capacity+" mixed="+mixed+" wallNs="+(System.nanoTime()-start)
                    +" gcCount="+(gc(false)-gc0)+" gcMs="+(gc(true)-ms0)+" checksum="+hash
                    +" diagnostics="+diagnostics+" requests="+requests+" requestNs="+requestNs+" awaitNs="+awaitNs+" metrics="+r.metrics());
        } finally {WorldGenProfiler.configure(false,200);}
    }
}
