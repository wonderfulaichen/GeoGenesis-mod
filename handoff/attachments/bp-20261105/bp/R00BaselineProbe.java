package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Baseline capture only: current production methods, no candidate algorithms or MC world writes. */
public final class R00BaselineProbe {
    private static Path out;
    private static final StringBuilder rows = new StringBuilder("case,bytes,sha256,repeatEqual,nonzero\n");
    private static int checks;
    public static void main(String[] args) throws Exception {
        out = Path.of(args[2]);
        Files.createDirectory(out);
        boolean loaded = args[0].equals("loaded");
        try (CommentedFileConfig config = CommentedFileConfig.of(Path.of(args[1]), TomlFormat.instance())) {
            if (loaded) {
                config.load();
                require(GeoGenesisConfig.SPEC.isCorrect(config), "invalid input config");
                GeoGenesisConfig.SPEC.setConfig(config);
                require(GeoGenesisConfig.SPEC.isLoaded(), "config not loaded");
            }
            Files.writeString(out.resolve("environment.txt"),
                    "loaded=" + GeoGenesisConfig.SPEC.isLoaded()
                    + "\njvmArgs=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                    + "\njava=" + System.getProperty("java.runtime.version")
                    + "\nproperties=" + new TreeMap<>(System.getProperties())
                    + "\nconfigFingerprint=" + GeoGenesisConfig.configFingerprint() + "\n");
            TerrainParams base = loaded ? GeoGenesisConfig.INSTANCE.buildParams() : TerrainParams.defaults();
            Files.writeString(out.resolve("terrain-params.txt"), base.toString());
            for (double hs : new double[]{1.0, 2.0}) {
                TerrainParams params = withHs(base, hs);
                for (int[] tile : new int[][]{{-960, 3840}, {960, 4320}}) {
                    CellGenerator gen = new CellGenerator(params, -64, 320);
                    gen.seed(9139912035078620160L);
                    String id = "hs" + (int) hs + "_" + tile[0] + "_" + tile[1];
                    ridge(gen, id, tile[0], tile[1]);
                    particle(gen, id, tile[0], tile[1]);
                    if (loaded) tiles(params, id, tile[0], tile[1]);
                }
            }
            Files.writeString(out.resolve("arrays.csv"), rows);
            System.out.println("R00 BASELINE PASS loaded=" + loaded + " checks=" + checks);
        }
    }
    private static TerrainParams withHs(TerrainParams p, double hs) throws Exception {
        var comps = TerrainParams.class.getRecordComponents();
        Object[] vals = new Object[comps.length]; Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            vals[i] = comps[i].getName().equals("horizontalScale") ? hs : comps[i].getAccessor().invoke(p);
        }
        return TerrainParams.class.getDeclaredConstructor(types).newInstance(vals);
    }
    private static void ridge(CellGenerator gen, String id, int cx, int cz) throws Exception {
        var cfg = com.geogenesis.config.RidgeConfigAdapter.fromConfig(GeoGenesisConfig.INSTANCE);
        cfg.horizontalScale = (float) gen.params().horizontalScale();
        // Exact production skeleton-grid geometry: origin=tile-40, spacing=2, extra=8, extent=121.
        int n = 121, ox = cx - 56, oz = cz - 56;
        float[][] input = new float[n][n];
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++)
            input[z][x] = (float) Math.max(gen.terrainEQuick(ox + 2*x, oz + 2*z), -0.05);
        StringBuilder settings = new StringBuilder();
        for (var field : cfg.getClass().getFields()) {
            Object v = field.get(cfg);
            settings.append(field.getName()).append('=').append(v instanceof float[] a ? Arrays.toString(a) : v).append('\n');
        }
        Files.writeString(out.resolve(id + "-ridge-settings.txt"), settings);
        float sea = (float) gen.heightCurve().seaE();
        float[][] a = RidgeValleyErosion.computeCoarseDelta(input,n,2,ox,oz,sea,cfg);
        float[][] b = RidgeValleyErosion.computeCoarseDelta(input,n,2,ox,oz,sea,cfg);
        pair(id + "-ridge-input", flatten(input), flatten(input));
        pair(id + "-ridge-delta", flatten(a), flatten(b));
        cfg.strength = 0;
        float[] zero = flatten(RidgeValleyErosion.computeCoarseDelta(input,n,2,ox,oz,sea,cfg));
        require(nonzero(zero)==0, "zero-strength ridge control");
        require(!Arrays.equals(bits(flatten(a)),bits(zero)), "ridge control failed to change output");
        single(id + "-ridge-zero-control", zero);
    }
    private static void particle(CellGenerator gen, String id, int cx, int cz) throws Exception {
        int size=128, pad=9, n=size+2*pad, ox=cx-40, oz=cz-40;
        float[] input=new float[n*n];
        for(int z=0;z<n;z++) for(int x=0;x<n;x++)
            input[z*n+x]=(float)Math.max(gen.terrainEQuick(ox+x-pad,oz+z-pad),-0.05);
        pair(id+"-particle-input",input,input);
        for(boolean hardness : new boolean[]{false,true}) {
            float[][] results=new float[4][];
            for(int repeat=0;repeat<2;repeat++) {
                ErosionEngine e=com.geogenesis.config.ParticleErosionConfigAdapter.create(false,CellGenerator.TILE_PARALLELISM);
                if(hardness)e.setHardnessProvider(gen::rockResistanceAt);
                float[] flat=input.clone(), discharge=new float[flat.length];
                e.runErosionOnFlat(flat,input.clone(),n,size,ox,oz,(float)gen.heightCurve().seaE(),1f,
                        discharge,(float)gen.params().horizontalScale());
                results[repeat*2]=flat;results[repeat*2+1]=discharge;
            }
            String key=id+"-particle-hardness"+hardness;
            pair(key+"-flat",results[0],results[2]);
            pair(key+"-discharge",results[1],results[3]);
            require(!Arrays.equals(bits(input),bits(results[0])), "particle path did not execute");
        }
        float[] early=input.clone();
        com.geogenesis.config.ParticleErosionConfigAdapter.create(false,CellGenerator.TILE_PARALLELISM).runErosionOnFlat(early,input.clone(),n,size,ox,oz,0f,0f);
        require(Arrays.equals(bits(input),bits(early)),"zero-strength particle control");
    }
    private static void tiles(TerrainParams p,String id,int cx,int cz)throws Exception {
        GeoGenesisConfig c=GeoGenesisConfig.INSTANCE;
        boolean savedE=c.erosionEnabled.get(),savedR=c.erosionRidgeEnabled.get();
        try {
            for(String mode:new String[]{"full","skel","off"}) {
                c.erosionEnabled.set(mode.equals("full"));c.erosionRidgeEnabled.set(!mode.equals("off"));
                float[][][] values=new float[2][][];
                for(int repeat=0;repeat<2;repeat++) {
                    CellGenerator gen=new CellGenerator(p,-64,320);gen.seed(9139912035078620160L);
                    var t=gen.getErosionTileResultForProbe(cx,cz);
                    values[repeat]=new float[][]{flatten(t.base),flatten(t.delta),flatten(t.postErosion),
                            t.discharge==null?new float[0]:flatten(t.discharge)};
                    require(mode.equals("full")== (t.discharge!=null), "tile particle execution gate");
                }
                String[] fields={"base","delta","post","discharge"};
                for(int i=0;i<fields.length;i++)pair(id+"-tile-"+mode+"-"+fields[i],values[0][i],values[1][i]);
            }
        } finally {c.erosionEnabled.set(savedE);c.erosionRidgeEnabled.set(savedR);}
    }
    private static float[] flatten(float[][] a){
        float[] v=new float[a.length*a[0].length];int k=0;
        for(float[] row:a)for(float f:row)v[k++]=f;
        return v;
    }
    private static byte[] bits(float[] values)throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var d=new DataOutputStream(bytes)){for(float f:values){require(Float.isFinite(f),"nonfinite");d.writeInt(Float.floatToRawIntBits(f));}}
        return bytes.toByteArray();
    }
    private static long nonzero(float[] values){long n=0;for(float f:values)if(f!=0f)n++;return n;}
    private static void pair(String name,float[] a,float[] b)throws Exception {
        require(Arrays.equals(bits(a),bits(b)),"repeat mismatch: "+name);single(name,a);
    }
    private static void single(String name,float[] a)throws Exception {
        byte[] b=bits(a);Files.write(out.resolve(name+".f32be"),b,StandardOpenOption.CREATE_NEW);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        rows.append(name).append(',').append(b.length).append(',').append(hash).append(",true,").append(nonzero(a)).append('\n');
    }
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
