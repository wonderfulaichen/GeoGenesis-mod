package com.geogenesis.worldgen.process;

import com.geogenesis.worldgen.core.*;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.concurrent.*;

/** Independent contexts and real kernel; does not substitute a reduced particle workload. */
public final class BInputBlockCacheBitwiseProbe {
    private static void same(double a, double b, String label) {
        if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) throw new AssertionError(label);
    }
    private static ProcessContext context(long seed) {
        return context(seed, TerrainParams.defaults(), true);
    }
    private static ProcessContext context(long seed, TerrainParams terrain, boolean material) {
        return new ProcessContext(new ProcessContext.Definition(seed, "cache-bitwise",
                terrain, BaseWorldSettings.defaults(), -64, 320,
                ProcessContext.Mode.PARTICLE, new ParticleErosionParams(1,1,.5,1,2,.1),
                false, 1, material, "probe explicit original parameters"));
    }
    private static TerrainParams terrain(double hs) throws Exception {
        var components = TerrainParams.class.getRecordComponents();
        Object[] values = new Object[components.length]; Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals("horizontalScale") ? hs
                    : components[i].getAccessor().invoke(TerrainParams.defaults());
        }
        return TerrainParams.class.getDeclaredConstructor(types).newInstance(values);
    }
    private static void matrix() throws Exception {
        for (double hs : new double[]{1,2}) for (boolean material : new boolean[]{false,true}) {
            var direct = context(9139912035078620160L,terrain(hs),material);
            var candidate = context(9139912035078620160L,terrain(hs),material);
            var o = new TileGeometry.Owner(-2160,-3168);
            try (var cache = new BInputBlockCache(2)) {
                var a = ParticleTile.buildInput(direct,o,()->false);
                var b = ParticleTile.buildInput(candidate,o,()->false,"matrix",cache);
                for (int i=0;i<a.length;i++) same(a[i],b[i],"matrix raw float");
                cache.copy(o.inputX(),o.inputZ(),146,146,(x,z)->candidate.baseE(x,z),
                        (x,z,v)->same(v,direct.baseE(x,z),"matrix raw B"),()->false);
                var ta = ParticleTile.build(direct,o,()->false);
                var tb = ParticleTile.build(candidate,o,()->false,"matrix",cache);
                for(int z=0;z<128;z++) for(int x=0;x<128;x++)
                    same(ta.at(o.originX()+x,o.originZ()+z),tb.at(o.originX()+x,o.originZ()+z),"matrix delta");
            }
            try(var a=new ProcessRuntime(direct,new ProcessRuntime.Budget(2,4,16),new ProcessRuntime.Observer(){},0);
                var b=new ProcessRuntime(candidate,new ProcessRuntime.Budget(2,4,16),new ProcessRuntime.Observer(){},2)) {
                for(double[] p:new double[][]{{-2112-.0001,-3096},{-2112+.0001,-3096},
                        {-2088,-3120-.0001},{-2088,-3120+.0001},
                        {-2112-.0001,-3120-.0001},{-2112+.0001,-3120+.0001}}) {
                    var x=a.exactP(p[0],p[1]);var y=b.exactP(p[0],p[1]);
                    same(x.base().eB(),y.base().eB(),"matrix eB");same(x.base().yB(),y.base().yB(),"matrix yB");
                    same(x.eP(),y.eP(),"matrix eP");same(x.yP(),y.yP(),"matrix yP");
                }
                if(b.inputCacheStats().evaluations()==0) throw new AssertionError("candidate not exercised");
            }
            System.out.println("HS="+hs+" material="+material+" rawB/input/delta/blend matrix PASS");
        }
    }
    private static void seed(long seed) throws Exception {
        var direct = context(seed); var candidate = context(seed);
        try (var cache = new BInputBlockCache(4096)) {
            for (var owner : new TileGeometry.Owner[]{new TileGeometry.Owner(0,0),
                    new TileGeometry.Owner(-2160,-3168),new TileGeometry.Owner(100032,-99984)}) {
                var original = ParticleTile.buildInput(direct, owner, () -> false);
                var cached = ParticleTile.buildInput(candidate, owner, () -> false, "probe", cache);
                for (int i = 0; i < original.length; i++) {
                    if (Float.floatToRawIntBits(original[i]) != Float.floatToRawIntBits(cached[i])) {
                        throw new AssertionError("input " + seed + " " + owner + " " + i);
                    }
                }
                cache.copy(owner.inputX(),owner.inputZ(),146,146,(x,z) -> candidate.baseE(x,z),
                        (x,z,v) -> same(v,direct.baseE(x,z),"raw B"), () -> false);
                var a = ParticleTile.build(direct, owner, () -> false);
                var b = ParticleTile.build(candidate, owner, () -> false, "probe", cache);
                for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                    same(a.at(owner.originX()+x,owner.originZ()+z),
                            b.at(owner.originX()+x,owner.originZ()+z),"full tile delta");
                }
            }
            System.out.println("rawB/rawfloat/full128delta seed=" + seed + " PASS " + cache.stats());
        }
        try (var a = new ProcessRuntime(direct,new ProcessRuntime.Budget(2,4,16),new ProcessRuntime.Observer(){},0);
             var b = new ProcessRuntime(candidate,new ProcessRuntime.Budget(2,4,16),new ProcessRuntime.Observer(){},4096)) {
            for (double[] p : new double[][]{{-2112-.0001,-3120+24},{-2112+.0001,-3120+24},
                    {-2112+24,-3120-.0001},{-2112+24,-3120+.0001},
                    {-2112-.0001,-3120-.0001},{-2112+.0001,-3120+.0001}}) {
                var x = a.exactP(p[0],p[1]); var y = b.exactP(p[0],p[1]);
                same(x.base().eB(),y.base().eB(),"eB");same(x.base().yB(),y.base().yB(),"yB");
                same(x.eP(),y.eP(),"eP blend");same(x.yP(),y.yP(),"yP blend");
                if (!x.identity().equals(y.identity())) throw new AssertionError("identity changed");
            }
        }
        System.out.println("independent runtimes side/corner eB/yB/eP/yP seed=" + seed + " PASS");
    }
    private static void performance() {
        for (boolean enabled : new boolean[]{false,true,false,true}) {
            var ctx = context(9139912035078620160L);
            try (var cache = new BInputBlockCache(4096)) {
                long start = System.nanoTime();long checksum = 0;
                for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
                    var o = new TileGeometry.Owner(x*48,z*48);
                    var input = enabled ? ParticleTile.buildInput(ctx,o,()->false,"performance",cache)
                            : ParticleTile.buildInput(ctx,o,()->false);
                    for (float v : input) checksum = checksum * 31 + Float.floatToRawIntBits(v);
                }
                System.out.println("real-identical-serial-input enabled=" + enabled + " wallNs="
                        + (System.nanoTime()-start) + " logicalRequests=" + (9*146*146)
                        + " checksum=" + checksum + " " + cache.stats());
            }
        }
    }
    private static void emit(String path, int blocks) throws Exception {
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                java.nio.file.Files.newOutputStream(java.nio.file.Path.of(path),java.nio.file.StandardOpenOption.CREATE_NEW)))) {
            for(long seed:new long[]{0,9139,9139912035078620160L,686443174003246080L}) {
                for(double hs:new double[]{1,2}) for(boolean material:new boolean[]{false,true}) {
                    var ctx=context(seed,terrain(hs),material);var o=new TileGeometry.Owner(-2160,-3168);
                    var cache=blocks==0?null:new BInputBlockCache(blocks);
                    try {
                        var input=ParticleTile.buildInput(ctx,o,()->false,"cold-emit",cache);
                        out.writeLong(seed);out.writeDouble(hs);out.writeBoolean(material);
                        for(float v:input) out.writeInt(Float.floatToRawIntBits(v));
                        // Canonical row order regardless of cache traversal order.
                        double[] raw=new double[146*146];
                        if(cache==null) {
                            for(int z=0;z<146;z++) for(int x=0;x<146;x++) raw[z*146+x]=ctx.baseE(o.inputX()+x,o.inputZ()+z);
                        } else cache.copy(o.inputX(),o.inputZ(),146,146,(x,z)->ctx.baseE(x,z),
                                (x,z,v)->raw[(z-o.inputZ())*146+x-o.inputX()]=v,()->false);
                        for(double v:raw) out.writeLong(Double.doubleToRawLongBits(v));
                        var tile=ParticleTile.build(ctx,o,()->false,"cold-emit",cache);
                        for(int z=0;z<128;z++) for(int x=0;x<128;x++)
                            out.writeInt(Float.floatToRawIntBits(tile.at(o.originX()+x,o.originZ()+z)));
                        if(cache!=null && cache.stats().hits()==0) throw new AssertionError("cold candidate not used");
                    } finally {if(cache!=null) cache.close();}
                    try(var runtime=new ProcessRuntime(ctx,new ProcessRuntime.Budget(2,4,16),new ProcessRuntime.Observer(){},blocks)) {
                        for(double[] p:new double[][]{{-2112-.0001,-3096},{-2112+.0001,-3096},
                                {-2088,-3120-.0001},{-2088,-3120+.0001},
                                {-2112-.0001,-3120-.0001},{-2112+.0001,-3120+.0001}}) {
                            var value=runtime.exactP(p[0],p[1]);
                            for(double v:new double[]{value.base().eB(),value.base().yB(),value.eP(),value.yP()})
                                out.writeLong(Double.doubleToRawLongBits(v));
                        }
                        if(blocks>0 && runtime.inputCacheStats().evaluations()==0) throw new AssertionError("runtime not enabled");
                    }
                    System.out.println("cold emit seed="+seed+" hs="+hs+" material="+material+" blocks="+blocks);
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length==3 && args[0].equals("--emit")) {emit(args[1],Integer.parseInt(args[2]));return;}
        if (args.length > 0 && args[0].equals("--performance")) { performance(); return; }
        for (long seed : new long[]{0,9139,9139912035078620160L,686443174003246080L}) seed(seed);
        matrix();
        System.out.println("B INPUT BLOCK CACHE FOUR SEED AND HS/MATERIAL BITWISE PASS");
    }
}
