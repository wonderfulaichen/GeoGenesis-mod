package com.geogenesis.worldgen.process;

import com.geogenesis.worldgen.core.*;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Scoped, assertion-bearing runtime checks. Any failed assertion exits nonzero. */
public final class ProcessRuntimeCheck {
    static ProcessContext context(ProcessContext.Mode mode) {
        return new ProcessContext(new ProcessContext.Definition(9139912035078620160L,
                "P1-frozen-20261105", TerrainParams.defaults(), BaseWorldSettings.defaults(), -64, 320,
                mode, new ParticleErosionParams(1, 1, .5, 1, 2, .1), false, 1, true, "spec-loaded=.5"));
    }
    static void require(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static void same(double a, double b, String message) {
        require(Double.doubleToLongBits(a) == Double.doubleToLongBits(b), message + " " + a + " != " + b);
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(60, TimeUnit.SECONDS), "latch timeout");
    }
    static void rejected(ProcessRuntime.Subscription s) throws Exception {
        try { s.await(); throw new AssertionError("expected failure"); }
        catch (ExecutionException | CancellationException expected) { System.out.println("expected " + expected); }
    }
    public static void main(String[] args) throws Exception {
        geometryAndOff();
        trueInputAndBlend();
        hsAndMaterial();
        realAndOrder();
        subscriberCancellation();
        lateAndRetry();
        failuresAndBudget();
        System.out.println("PROCESS RUNTIME ALL PASS");
    }
    static void geometryAndOff() throws Exception {
        var ctx = context(ProcessContext.Mode.OFF);
        var direct = new BaseWorldSampler(9139912035078620160L, TerrainParams.defaults(), -64, 320,
                BaseWorldSettings.defaults());
        try (var r = new ProcessRuntime(ctx, new ProcessRuntime.Budget(1, 4, 4))) {
            for (double x : new double[]{-96.0001, -48.0001, -48, -.001, 0, .25, 47.9999, 48, 48.1}) {
                for (double z : new double[]{-48.001, -48, -.25, 0, 47.999, 48}) {
                    var f = TileGeometry.footprint(x, z);
                    require(x >= f.main().x() && x < f.main().x() + 48, "half-open x");
                    require(z >= f.main().z() && z < f.main().z() + 48, "half-open z");
                    for (var o : f.required()) {
                        require(x >= o.originX() && x < o.originX() + 127, "halo x");
                        require(z >= o.originZ() && z < o.originZ() + 127, "halo z");
                        require(o.hardnessLastX() == o.inputX() + 160, "hardness extent");
                    }
                    var p = r.exactP(x, z);
                    same(p.base().eB(), direct.sampleE(x, z), "B exact");
                    same(p.yP(), direct.height(x, z), "OFF Y");
                    same(p.eP(), p.base().eB(), "OFF e");
                    require(p.deltaE() == 0 && p.dischargeAvailability().equals("OFF"), "OFF diagnostic");
                }
            }
            require(r.metrics().builds() == 0 && r.metrics().completed() == 0, "OFF zero tiles");
        }
        same(ParticleTile.input(-.7), (float) -.05, "floor explicit");
        same(ParticleTile.input(.123456789), (float) .123456789, "float conversion");
        same(ParticleTile.limitedDelta(.9f, .2f), (.2f + .22f) - .2f, "positive cap order");
        same(ParticleTile.limitedDelta(-.9f, .2f), (.2f - .22f) - .2f, "negative cap order");
        var on = context(ProcessContext.Mode.PARTICLE);
        for (double e : new double[]{-2, -.2, 0, .2, .9215, .95, 2}) {
            same(on.height(e), new com.geogenesis.worldgen.terrain.HeightCurve(TerrainParams.defaults(), -64, 320)
                    .heightFromE(e), "height mapping");
            double p = on.assemble(e, .01);
            require(Double.isFinite(p) && (e <= -.01 || p < .95), "softcap mapping");
        }
        require(!ctx.identity().equals(on.identity()), "OFF stage identity");
        System.out.println("PASS geometry/OFF/B/floor/cap/units");
    }
    static void realAndOrder() throws Exception {
        var ctx = context(ProcessContext.Mode.PARTICLE);
        double[][] points = {{1657.5, 2289.5}, {1632, 2304}, {1631.9999, 2304.0001},
                {1632.0001, 2303.9999}, {1640.25, 2310.5}, {-48.0001, -.0001},
                {-48, 0}, {-47.9999, .0001}};
        double[] values = new double[points.length];
        try (var r = new ProcessRuntime(ctx, new ProcessRuntime.Budget(2, 16, 32))) {
            require(r.peek(points[0][0], points[0][1]) instanceof ProcessRuntime.Unavailable, "cold peek");
            for (int i = 0; i < points.length; i++) {
                var p = r.exactP(points[i][0], points[i][1]); values[i] = p.eP();
                same(p.yP(), ctx.height(p.eP()), "YP=h(eP)");
                same(p.deltaY(), ctx.height(p.eP()) - p.base().yB(), "deltaY nonlinear");
            }
            boolean nonzero = false;
            for (int i = points.length - 1; i >= 0; i--) {
                var p = r.exactP(points[i][0], points[i][1]);
                same(p.eP(), values[i], "warm reverse"); nonzero |= Math.abs(p.deltaE()) > 1e-8;
            }
            require(nonzero, "real nonzero P negative control");
            require(r.peek(1632, 2304) instanceof ProcessRuntime.Ready, "whole-footprint peek");
            require(Math.abs(values[2] - values[3]) < 1e-4, "corner two-sided limit");
            System.out.println("real " + r.metrics());
        }
        try (var r = new ProcessRuntime(ctx, new ProcessRuntime.Budget(1, 16, 1))) {
            for (int i : new int[]{7, 1, 4, 2, 0, 6, 3, 5}) {
                same(r.exactP(points[i][0], points[i][1]).eP(), values[i], "eviction cold shuffled");
            }
            require(r.metrics().completed() <= 1, "cache bound");
            require(r.peek(1632, 2304) instanceof ProcessRuntime.Unavailable, "missing blend neighbor");
        }
        try (var r = new ProcessRuntime(ctx, new ProcessRuntime.Budget(3, 32, 32))) {
            List<ProcessRuntime.Subscription> subs = new ArrayList<>();
            for (var p : points) subs.add(r.requestExact(p[0], p[1]));
            for (int i = 0; i < points.length; i++) same(subs.get(i).await().eP(), values[i], "concurrent order");
        }
        System.out.println("PASS real/nonzero/cold/warm/reverse/concurrent/eviction/peek");
    }
    static void trueInputAndBlend() throws Exception {
        var ctx = context(ProcessContext.Mode.PARTICLE);
        var owner = new TileGeometry.Owner(-2160, -3168);
        float[] input = ParticleTile.buildInput(ctx, owner, () -> false);
        var base = new BaseWorldSampler(9139912035078620160L, TerrainParams.defaults(), -64, 320,
                BaseWorldSettings.defaults());
        for (int z = 0; z < 146; z++) for (int x = 0; x < 146; x++) {
            same(input[z * 146 + x], (float) Math.max(base.sampleE(owner.inputX()+x, owner.inputZ()+z), -.05), "true 146 input");
        }
        AtomicInteger hardReads = new AtomicInteger();
        var kernel = new com.geogenesis.worldgen.erosion.ErosionEngine(false, () -> ctx.definition().particle(), 8);
        kernel.setHardnessProvider((x,z) -> {
            require(x >= owner.inputX() && x <= owner.hardnessLastX(), "hard x beyond input");
            require(z >= owner.inputZ() && z <= owner.hardnessLastZ(), "hard z beyond input");
            hardReads.incrementAndGet(); return ctx.resistance(x,z);
        });
        float[] flat = input.clone();
        kernel.runErosionOnFlat(flat, input.clone(), 146,128,owner.originX(),owner.originZ(),ctx.seaE(),1,null,
                (float) ctx.definition().terrain().horizontalScale());
        require(hardReads.get() == 121, "actual hardgrid 11 squared");
        var tile = ParticleTile.build(ctx, owner, () -> false);
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int i = (z+9)*146+x+9;
            same(tile.at(owner.originX()+x,owner.originZ()+z), ParticleTile.limitedDelta(flat[i],input[i]), "kernel tile assembly");
        }
        try (var runtime = new ProcessRuntime(ctx,new ProcessRuntime.Budget(2,16,16))) {
            double x0=-2112, z0=-3120;
            for (double[] point : new double[][]{{x0-.0001,z0+24},{x0+.0001,z0+24},
                    {x0+24,z0-.0001},{x0+24,z0+.0001},{x0-.0001,z0-.0001},
                    {x0+.0001,z0+.0001},{x0+2.25,z0+3.75}}) {
                double x=point[0],z=point[1]; var f=TileGeometry.footprint(x,z);
                Map<TileGeometry.Owner,ParticleTile> tiles=new HashMap<>();
                for(var o:f.required()) tiles.put(o,ParticleTile.build(ctx,o,()->false));
                double a=tiles.get(f.main()).at(x,z);
                double b=f.fx()>0?tiles.get(f.sideX()).at(x,z):a;
                double c=f.fz()>0?tiles.get(f.sideZ()).at(x,z):a;
                double d=f.fx()>0&&f.fz()>0?tiles.get(f.corner()).at(x,z):f.fx()>0?b:c;
                double legacy=a*(1-f.fx())*(1-f.fz())+b*f.fx()*(1-f.fz())+c*(1-f.fx())*f.fz()+d*f.fx()*f.fz();
                same(runtime.exactP(x,z).eP(),ctx.assemble(ctx.baseE(x,z),legacy),"two-sided legacy arithmetic");
            }
        }
        System.out.println("PASS real146/hard121/independent kernel assembly/side and corner blend");
    }
    static void hsAndMaterial() throws Exception {
        TerrainParams original = TerrainParams.defaults();
        var components = TerrainParams.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i=0;i<components.length;i++) {
            types[i]=components[i].getType(); values[i]=components[i].getAccessor().invoke(original);
        }
        double[] result = new double[4]; int index=0;
        for (double hs : new double[]{1,2}) for (boolean material : new boolean[]{false,true}) {
            for(int i=0;i<components.length;i++) if(components[i].getName().equals("horizontalScale")) values[i]=hs;
            TerrainParams terrain=TerrainParams.class.getDeclaredConstructor(types).newInstance(values);
            var ctx=new ProcessContext(new ProcessContext.Definition(9139912035078620160L,"P1-frozen-20261105",
                    terrain,BaseWorldSettings.defaults(),-64,320,ProcessContext.Mode.PARTICLE,
                    new ParticleErosionParams(1,1,.5,1,2,.1),false,1,material,"test explicit HS/material"));
            try(var r=new ProcessRuntime(ctx,new ProcessRuntime.Budget(1,8,8))) {
                result[index++]=r.exactP(-2137.5,-3165.5).eP();
            }
        }
        require(result[0]!=result[1] && result[2]!=result[3],"material coupling effective");
        require(result[0]!=result[2],"HS historical kernel effective");
        System.out.println("PASS HS1/2 material on/off effective; no F source");
    }
    static void subscriberCancellation() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var observer = new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner owner) throws Exception { entered.countDown(); await(resume); }
        };
        try (var r = new ProcessRuntime(context(ProcessContext.Mode.PARTICLE),
                new ProcessRuntime.Budget(1, 8, 8), observer)) {
            var a = r.requestExact(1656, 2280); await(entered);
            var b = r.requestExact(1656, 2280); a.cancel(); resume.countDown();
            require(Double.isFinite(b.await().eP()), "B subscriber survives A cancel");
            rejected(a); require(r.metrics().builds() == 1, "singleflight once");
        }
        System.out.println("PASS two subscribers/one cancelled");
    }
    static void lateAndRetry() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var count = new AtomicInteger();
        var observer = new ProcessRuntime.Observer() {
            public void beforePublish(TileGeometry.Owner owner) {
                if (count.getAndIncrement() != 0) return;
                entered.countDown();
                // Deliberately model an uncooperative late kernel; the outer token must reject it.
                while (resume.getCount() > 0) {
                    try { resume.await(); } catch (InterruptedException ignored) { /* fault injection */ }
                }
                Thread.interrupted();
            }
        };
        var ctx = context(ProcessContext.Mode.PARTICLE);
        try (var r = new ProcessRuntime(ctx, new ProcessRuntime.Budget(2, 8, 8), observer)) {
            var old = r.requestExact(1656, 2280); await(entered); old.cancel();
            var fresh = r.requestExact(1656, 2280); var value = fresh.await();
            resume.countDown(); Thread.sleep(100);
            same(r.exactP(1656, 2280).eP(), value.eP(), "late old cannot remove/replace fresh");
            require(r.metrics().successful() == 1 && r.metrics().builds() == 2, "cancel retry fresh flight");
        } finally { resume.countDown(); }
        try (var old = new ProcessRuntime(ctx, new ProcessRuntime.Budget(1, 4, 4));
             var fresh = new ProcessRuntime(context(ProcessContext.Mode.OFF), new ProcessRuntime.Budget(1, 4, 4))) {
            var s = old.requestExact(1656, 2280); old.close(); rejected(s);
            require(fresh.exactP(1656, 2280).deltaE() == 0 && fresh.metrics().completed() == 0,
                    "old context not shared");
        }
        System.out.println("PASS all-cancel/fresh-retry/late-flight/context isolation");
    }
    static void failuresAndBudget() throws Exception {
        for (boolean publication : new boolean[]{false, true}) {
            AtomicBoolean fail = new AtomicBoolean(true);
            var observer = new ProcessRuntime.Observer() {
                public void beforeBuild(TileGeometry.Owner o) { if (!publication && fail.getAndSet(false)) throw new IllegalStateException("input fault"); }
                public void beforePublish(TileGeometry.Owner o) { if (publication && fail.getAndSet(false)) throw new IllegalStateException("validation fault"); }
            };
            try (var r = new ProcessRuntime(context(ProcessContext.Mode.PARTICLE), new ProcessRuntime.Budget(1, 4, 4), observer)) {
                rejected(r.requestExact(1656, 2280));
                require(r.metrics().completed() == 0, "failed no cache");
                require(Double.isFinite(r.exactP(1656, 2280).eP()), "explicit retry success");
                require(r.metrics().failures() == 1, "failure counted");
            }
        }
        ProcessRuntimeLifecycleProbe.queueInline();
        System.out.println("PASS input/validation failures/no cache/retry/inline backpressure");
    }
}
