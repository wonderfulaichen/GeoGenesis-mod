package com.geogenesis.worldgen.process;

import com.geogenesis.diagnostics.WorldGenProfiler;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Exact B/assembly and lifecycle gates. No Forge startup or global optimization property. */
public final class AssembleBasePeekProbe {
    static void require(boolean b, String label) { if (!b) throw new AssertionError(label); }
    static void same(double a, double b, String label) {
        require(Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b), label);
    }
    static TerrainParams terrain(double hs) throws Exception {
        var cs = TerrainParams.class.getRecordComponents();
        Object[] v = new Object[cs.length]; Class<?>[] t = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            t[i] = cs[i].getType(); v[i] = cs[i].getName().equals("horizontalScale") ? hs
                    : cs[i].getAccessor().invoke(TerrainParams.defaults());
        }
        return TerrainParams.class.getDeclaredConstructor(t).newInstance(v);
    }
    static ProcessContext context(long seed, double hs, boolean material, ProcessContext.Mode mode) throws Exception {
        return new ProcessContext(new ProcessContext.Definition(seed, "peek-exact-baseline", terrain(hs),
                BaseWorldSettings.defaults(), -64, 320, mode,
                new ParticleErosionParams(1, 1, .5, 1, 2, .1), false, 1, material, "explicit-original-budget"));
    }
    static long[] fields(ProcessRuntime.Sample p) {
        double[] v = {p.base().eB(), p.base().yB(), p.eP(), p.yP(), p.deltaE(), p.deltaY()};
        long[] b = new long[v.length]; for (int i = 0; i < v.length; i++) b[i] = Double.doubleToRawLongBits(v[i]);
        return b;
    }
    static void cell(BInputBlockCache c, int x, int z, double value) {
        c.copy(x, z, 1, 1, (a,b) -> value, (a,b,v) -> same(v,value,"copy value"), () -> false);
    }
    static void source() throws Exception {
        try (var c = new BInputBlockCache(2)) {
            var empty = c.stats(); require(c.peekBits(0,0,null) == BInputBlockCache.NO_BLOCK,"missing");
            require(empty.equals(c.stats()),"peek allocates/mutates");
            cell(c,0,0,-0.0); require(c.peekBits(0,0,null) == Long.MIN_VALUE,"raw negative B zero");
            require(c.peekBits(1,0,null) == BInputBlockCache.INVALID_POINT,"partial valid");
            cell(c,32,0,Double.MIN_VALUE);
            require(c.peekBits(32,0,null) == Double.doubleToRawLongBits(Double.MIN_VALUE),"subnormal");
            var before = c.stats(); c.peekBits(0,0,null); require(before.equals(c.stats()),"read stats immutable");
            cell(c,64,0,Double.MAX_VALUE); require(c.peekBits(0,0,null) == BInputBlockCache.NO_BLOCK,"LRU untouched");
            c.close(); require(c.peekBits(64,0,null) == BInputBlockCache.CLOSED,"closed");
        }
        try (var c = new BInputBlockCache(4)) {
            for (int x : new int[]{-33,-32,-1,0,31,32}) {
                cell(c,x,-x,Math.nextUp(.125));
                same(Double.longBitsToDouble(c.peekBits(x,-x,null)),Math.nextUp(.125),"negative block boundary");
            }
            for (double bad : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
                try { cell(c,999,999,bad); throw new AssertionError("nonfinite accepted"); }
                catch (IllegalStateException expected) { }
                require(c.peekBits(999,999,null) == BInputBlockCache.INVALID_POINT,"failed valid bit");
            }
        }
        partialAndRace();
        System.out.println("PASS source rawbits/missing/invalid/closed/partial/eviction/no-LRU/nonfinite");
    }
    static void partialAndRace() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try (var c = new BInputBlockCache(1)) {
            var writer = pool.submit(() -> c.copy(0,0,2,1,(x,z) -> {
                if (x == 1) { entered.countDown(); try { require(release.await(10,TimeUnit.SECONDS),"release"); }
                    catch (InterruptedException e) { throw new AssertionError(e); } }
                return x + .125;
            },(x,z,v) -> {},() -> false));
            require(entered.await(10,TimeUnit.SECONDS),"partial entered");
            var reader = pool.submit(() -> {
                same(Double.longBitsToDouble(c.peekBits(0,0,null)),.125,"leased valid");
                require(c.peekBits(1,0,null) == BInputBlockCache.INVALID_POINT,"leased invalid");
            });
            reader.get(3,TimeUnit.SECONDS); c.close();
            require(c.peekBits(0,0,null) == BInputBlockCache.CLOSED,"close linearization");
            release.countDown();
            try { writer.get(10,TimeUnit.SECONDS); throw new AssertionError("late writer published"); }
            catch (ExecutionException expected) { require(expected.getCause() instanceof CancellationException,"cancel type"); }
        } finally { release.countDown(); pool.shutdownNow(); }
        try (var c = new BInputBlockCache(1)) {
            var workers = Executors.newFixedThreadPool(2);
            try {
                var w = workers.submit(() -> { for (int i=0;i<2000;i++) cell(c,(i&1)*32,0,(i&1)+.25); });
                var r = workers.submit(() -> { for (int i=0;i<20000;i++) {
                    long b=c.peekBits(0,0,null);
                    require(b==BInputBlockCache.NO_BLOCK || b==BInputBlockCache.INVALID_POINT
                            || b==Double.doubleToRawLongBits(.25),"reassigned/torn key-value");
                }});
                w.get(30,TimeUnit.SECONDS); r.get(30,TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
        }
    }
    static double[][] points() {
        return new double[][]{{-48,0},{-0.0,0},{0,-0.0},{0,0},{.5,.5},{-.5,0},{47,47},
                {48,48},{Math.nextDown(48.0),48},{Math.nextUp(48.0),48},{-32,-33},{31,32}};
    }
    static void matrix() throws Exception {
        for (long seed : new long[]{0,9139,9139912035078620160L,686443174003246080L})
            for (double hs : new double[]{1,2}) for (boolean material : new boolean[]{false,true}) {
                var a=context(seed,hs,material,ProcessContext.Mode.PARTICLE);
                var b=context(seed,hs,material,ProcessContext.Mode.PARTICLE);
                try (var direct=new ProcessRuntime(a,new ProcessRuntime.Budget(4,512,1024),new ProcessRuntime.Observer(){},4096,false);
                     var reuse=new ProcessRuntime(b,new ProcessRuntime.Budget(4,512,1024),new ProcessRuntime.Observer(){},4096,true);
                     var evict=new ProcessRuntime(b,new ProcessRuntime.Budget(4,512,1),new ProcessRuntime.Observer(){},1,true)) {
                    long[][] expected=new long[points().length][];
                    for(int i=0;i<points().length;i++) {
                        double[] p=points()[i]; expected[i]=fields(direct.exactP(p[0],p[1]));
                        var got=reuse.exactP(p[0],p[1]);
                        require(Arrays.equals(expected[i],fields(got)),"cold six fields");
                        same(got.base().x(),p[0],"x bits");same(got.base().z(),p[1],"z bits");
                        require(got.identity().equals(a.identity()) && got.base().identity().equals(a.baseIdentity()),"identity");
                    }
                    var order=new ArrayList<Integer>();for(int i=0;i<expected.length;i++)order.add(i);
                    Collections.shuffle(order,new Random(123));
                    for(int i:order) {
                        double[] p=points()[i];
                        require(Arrays.equals(expected[i],fields(reuse.exactP(p[0],p[1]))),"warm shuffled");
                        require(Arrays.equals(expected[i],fields(evict.exactP(p[0],p[1]))),"evicted shuffled");
                    }
                }
                System.out.println("PASS six-fields seed="+seed+" hs="+hs+" material="+material);
            }
    }
    static void lifecycle() throws Exception {
        var ctx=context(9139,2,true,ProcessContext.Mode.PARTICLE);
        var stop=new CountDownLatch(1);var go=new CountDownLatch(1);
        var observer=new ProcessRuntime.Observer() { public void beforeAssemble() throws Exception {
            stop.countDown();require(go.await(20,TimeUnit.SECONDS),"resume assemble");
        }};
        try(var old=new ProcessRuntime(ctx,new ProcessRuntime.Budget(1,8,8),observer);
            var fresh=new ProcessRuntime(ctx,new ProcessRuntime.Budget(1,8,8))) {
            var pending=old.requestExact(24,24);require(stop.await(20,TimeUnit.SECONDS),"paused assemble");
            old.close();go.countDown();
            try { pending.await();throw new AssertionError("closed subscription accepted"); }
            catch(ExecutionException|CancellationException expected) { }
            require(fresh.inputCacheStats().allocated()==0,"shared context cache leaked");
            require(Double.isFinite(fresh.exactP(24,24).eP()),"shared context survivor");
        } finally {go.countDown();}
        try(var r=new ProcessRuntime(context(0,2,false,ProcessContext.Mode.OFF),new ProcessRuntime.Budget(1,1,1))) {
            for(double[] p:points()) {var s=r.exactP(p[0],p[1]);same(s.eP(),s.base().eB(),"OFF B");}
            require(r.inputCacheStats()==null && r.metrics().builds()==0,"OFF allocation/tile");
            for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,1_000_000_001.0}) {
                try {r.requestExact(bad,0);throw new AssertionError("invalid point accepted");}
                catch(IllegalArgumentException expected) { }
            }
        }
        diagnostics();
        System.out.println("PASS close/shared-context/OFF/invalid-coordinates/diagnostic-categories");
    }
    static void diagnostics() throws Exception {
        WorldGenProfiler.configure(true,200);
        try(var r=new ProcessRuntime(context(0,2,false,ProcessContext.Mode.PARTICLE),new ProcessRuntime.Budget(4,512,1024))) {
            try(var t=r.beginSamplingTiming("probe-categories")) {
                r.exactP(24,24);r.exactP(.5,.5);r.exactP(-0.0,0);
                require(t.baseHits.sum()==1 && t.fractional.sum()==1 && t.negativeZero.sum()==1,"category wiring");
                require(t.rawBase.sum()+t.pointHits.sum()==2,"fallback raw outcome scope");
            }
        } finally {WorldGenProfiler.configure(false,200);}
    }
    public static void main(String[] args) {
        try {
            if(args.length>0 && args[0].equals("source")) source();
            else {source();matrix();lifecycle();}
            System.out.println("ASSEMBLE BASE PEEK ALL PASS");
        } catch(Throwable t) {t.printStackTrace();System.exit(1);}
    }
}
