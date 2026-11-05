package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.erosion.RuneErosionFilter;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Isolated causal experiment. No kernel, coverage, ocean or production wiring.
 * Original private functions are read through reflection without changing their bytes.
 * RuneHashVariant is an offline build-directory copy with ONLY hash changed.
 */
public final class RuneControlledComparisonProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final double L = 1830, OX = -4157.5, OZ = -4280.5;
    private static final double FD = 2, FADE = .0375;
    private static final RuneErosionFilter.Settings AUTHOR = new RuneErosionFilter.Settings(
            .15,.22,.7,5,.5,2,.5,.5,1.5,.1,0,.1,2,1.25,1.25,.7,1);
    private static final RuneErosionFilter.Settings CANDIDATE = new RuneErosionFilter.Settings(
            .15,.22,1.1,4,.5,2,.5,.65,1.5,.1,.12,.1,2,1.25,1.25,.7,1);
    private static final String[] ARMS = {"rune-author", "rune-fd", "rune-hash-only",
            "rune-settings-only", "rune-double-candidate", "rune-double-author",
            "m1-compressed-author", "m1-compressed-candidate", "m1-native-common-fade",
            "m1-native-candidate-1830", "m1-native-candidate-610", "m1-native-candidate-610-local"};
    private final MorphRecipe m1 = MorphLandRecipe.all(new MorphCatalog(SEED))[17];
    private final Ref original = new Ref("com.geogenesis.standalone.RuneOriginalDemo");
    private final Ref split = new Ref("com.geogenesis.standalone.RuneHashVariant");

    private RuneControlledComparisonProbe() throws Exception {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        new RuneControlledComparisonProbe().run(out, Integer.parseInt(args[1]));
    }

    private double[] base(double px, double pz) throws Exception {
        float[] f = original.noise((float)px, (float)pz);
        return new double[]{f[0]*.0625, f[1]*.0625, f[2]*.0625};
    }

    private double raw(double px, double pz) { return m1.raw(OX + px*L, OZ + pz*L); }
    private static double clamp(double x) { return Math.max(-1, Math.min(1,x)); }

    private void run(Path out, int n) throws Exception {
        double[][] arrays = new double[ARMS.length][n*n];
        double[][] aux = new double[7][n*n];
        long[] nanos = new long[ARMS.length];
        double worstRepeat = 0;
        // A small fixed warmup; measured wall time below includes reflective invocation overhead.
        for (int k=0;k<512;k++) original.filter(.13,.27,base(.13,.27),AUTHOR);
        for (int j=0;j<n;j++) for (int i=0;i<n;i++) {
            int k=j*n+i;
            float px=(i+.5f)/n, pz=(j+.5f)/n;
            double[] b=base(px,pz);
            double dp=FD/L;
            double[] fd={b[0], (base(px+dp,pz)[0]-base(px-dp,pz)[0])/(2*dp),
                    (base(px,pz+dp)[0]-base(px,pz-dp)[0])/(2*dp)};
            double v=raw(px,pz)-.5;
            double gx=(raw(px+dp,pz)-raw(px-dp,pz))/(2*dp);
            double gz=(raw(px,pz+dp)-raw(px,pz-dp))/(2*dp);
            double[] mc={v*.125,gx*.125,gz*.125};
            aux[0][k]=b[0]; aux[1][k]=mc[0]; aux[2][k]=v;
            aux[3][k]=Math.hypot(b[1],b[2]); aux[4][k]=Math.hypot(fd[1],fd[2]);
            aux[5][k]=Math.hypot(mc[1],mc[2]); aux[6][k]=Math.hypot(gx,gz);
            for (int a=0;a<ARMS.length;a++) {
                long t=System.nanoTime();
                double h;
                switch (a) {
                    case 0 -> h=b[0]+original.filter(px,pz,b,AUTHOR);
                    case 1 -> h=b[0]+original.filter(px,pz,fd,AUTHOR);
                    case 2 -> h=b[0]+split.filter(px,pz,b,AUTHOR);
                    case 3 -> h=b[0]+split.filter(px,pz,b,CANDIDATE);
                    case 4 -> h=b[0]+prod(px,pz,b,CANDIDATE,FADE);
                    case 5 -> h=b[0]+prod(px,pz,b,AUTHOR,FADE);
                    case 6 -> h=mc[0]+prod(px,pz,mc,AUTHOR,FADE);
                    case 7 -> h=mc[0]+prod(px,pz,mc,CANDIDATE,FADE);
                    case 8 -> h=v+prod(px,pz,new double[]{v,gx,gz},CANDIDATE,FADE);
                    case 9 -> h=v+prod(px,pz,new double[]{v,gx,gz},CANDIDATE,.3);
                    case 10 -> h=v+prod((OX+px*L)/610,(OZ+pz*L)/610,
                            new double[]{v,gx/3,gz/3},CANDIDATE,.3);
                    default -> h=v+prod(px*3,pz*3,new double[]{v,gx/3,gz/3},CANDIDATE,.3);
                }
                nanos[a]+=System.nanoTime()-t;
                if (!Double.isFinite(h)) throw new AssertionError("nonfinite");
                arrays[a][k]=h;
            }
            // Frozen candidate formula at unwarped recipe coordinates (no kernel/shell).
            double d=RuneErosionFilter.delta((OX+px*L)/610,(OZ+pz*L)/610,gx/3,gz/3,
                    clamp(v/.3),CANDIDATE,SEED);
            worstRepeat=Math.max(worstRepeat,Math.abs(arrays[10][k]-(v+d)));
        }
        for (int a=0;a<ARMS.length;a++) write(out.resolve(ARMS[a]+".f64be"),arrays[a]);
        String[] names={"base-rune","base-m1-compressed","base-m1-native","slope-rune-analytic",
                "slope-rune-fd","slope-m1-compressed","slope-m1-native"};
        for (int a=0;a<names.length;a++) write(out.resolve(names[a]+".f64be"),aux[a]);
        StringBuilder perf=new StringBuilder("arm,samples,elapsedMs,nsPerSample\n");
        for (int a=0;a<ARMS.length;a++) perf.append(ARMS[a]).append(',').append(n*n).append(',')
                .append(nanos[a]/1e6).append(',').append(nanos[a]/(double)(n*n)).append('\n');
        Files.writeString(out.resolve("performance.csv"),perf);
        if (worstRepeat!=0) throw new AssertionError("candidate repeat drift");
        Files.writeString(out.resolve("verification-java.txt"),"PASS finite all arms; candidate formula repeat exact\n"
                +"No source main modified. Offset excluded from ALL shape arms.\n"
                +"AUTHOR="+AUTHOR+"\nCANDIDATE="+CANDIDATE+"\n");
        System.out.println("CONTROLLED PASS size="+n+" arms="+ARMS.length);
    }

    private static double prod(double x,double z,double[] b,RuneErosionFilter.Settings s,double fade) {
        return RuneErosionFilter.delta(x,z,b[1],b[2],clamp(b[0]/fade),s,SEED);
    }

    private static void write(Path path,double[] a) throws IOException {
        try (DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            for (double v:a) s.writeDouble(v);
        }
    }

    private static final class Ref {
        final Method noise,filter;
        final Constructor<?> params;
        Ref(String name) throws Exception {
            Class<?> c=Class.forName(name), p=Class.forName(name+"$Params");
            noise=c.getDeclaredMethod("fractalNoise",float.class,float.class,float.class,
                    int.class,float.class,float.class);
            filter=c.getDeclaredMethod("erosionFilter",float.class,float.class,float.class,
                    float.class,float.class,float.class,p);
            params=p.getDeclaredConstructors()[0];
            noise.setAccessible(true); filter.setAccessible(true); params.setAccessible(true);
        }
        float[] noise(float x,float z) throws Exception {
            return (float[])noise.invoke(null,x,z,3f,3,2f,.1f);
        }
        double filter(double x,double z,double[] b,RuneErosionFilter.Settings s) throws Exception {
            Object p=params.newInstance((float)s.strength(),(float)s.gullyWeight(),(float)s.detail(),
                    (float)s.ridgeRounding(),(float)s.creaseRounding(),(float)s.inputRounding(),
                    (float)s.octaveRounding(),(float)s.inputOnset(),(float)s.octaveOnset(),2.8f,1.5f,
                    (float)s.assumedSlope(),(float)s.assumedSlopeMix(),(float)s.scale(),s.octaves(),
                    (float)s.lacunarity(),(float)s.gain(),(float)s.cellScale(),(float)s.normalization());
            // Height=0 instead of .5 prevents cancellation in subtraction; baseline arm separately verified.
            float[] h=(float[])filter.invoke(null,(float)x,(float)z,0f,(float)b[1],(float)b[2],
                    (float)clamp(b[0]/FADE),p);
            return h[0];
        }
    }
}
