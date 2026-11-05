package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;

/** Diagnostics only: common catalog raw height; independently selected derivative sources. */
public final class FastErosionCorrectedProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769, CAL = 8192, PAD = 5;
    static final float COMP = 4e-4f;
    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    final MorphNoise.Node production, dFull, dTwo, hHistorical, scalerLow, wx, wz, direction;
    final MorphNoise.PerlinRidge low;
    final double L, step, outStep, warpPow;
    final int ox, oz, ex, ez, W = 2 * (N - 1) + 1 + 2 * PAD;
    final double[][] points = new double[CAL][2];
    double r, maxDn, maxFoldSlopeChange;
    String mode;
    double[] source;
    double[] common, historical, two;
    final List<String> reports = new ArrayList<>();

    FastErosionCorrectedProbe() {
        production = catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Warped w = (MorphNoise.Warped) production;
        MorphNoise.Mul b = (MorphNoise.Mul) w.input();
        MorphNoise.PerlinRidge full = (MorphNoise.PerlinRidge) b.a();
        MorphNoise.AlphaMix s = (MorphNoise.AlphaMix) b.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) s.input();
        require(full.octaves() == 4 && p.octaves() == 4, "production source octave assertion");
        wx = w.wx(); wz = w.wz(); warpPow = w.pow();
        L = 1.0 / full.frequency(); step = L / 512; outStep = 2 * step;
        ox = (int) Math.round(-3242.5 / outStep) - N / 2;
        oz = (int) Math.round(-3365.5 / outStep) - N / 2;
        ex = 2 * ox - PAD; ez = 2 * oz - PAD;
        low = new MorphNoise.PerlinRidge(full.seed(), full.frequency(), 1, full.lacunarity(), full.gain());
        MorphNoise.Node s2 = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 2,
                p.lacunarity(), p.gain()), s.alpha());
        scalerLow = new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(), p.frequency(), 1,
                p.lacunarity(), p.gain()), s.alpha());
        dFull = MorphNoise.warp(new MorphNoise.Mul(full, s2), wx, wz, (float) warpPow);
        hHistorical = dFull;
        dTwo = MorphNoise.warp(new MorphNoise.Mul(new MorphNoise.PerlinRidge(full.seed(), full.frequency(),
                2, full.lacunarity(), full.gain()), s2), wx, wz, (float) warpPow);
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED + 77) + 3, 10, 1);
        Random rng = new Random((long) (double) 0x4641535445524f53L);
        for (double[] q : points) {
            double x = 16 * L + rng.nextInt((int) (64 * L));
            double z = 16 * L + rng.nextInt((int) (64 * L));
            double[] v = domain(x, z); q[0] = v[0]; q[1] = v[1];
        }
    }

    double[] warped(double x, double z) {
        return new double[]{x + (MorphNoiseEval.eval(wx, x, z) - .5) * warpPow,
                z + (MorphNoiseEval.eval(wz, x, z) - .5) * warpPow};
    }

    double noise(double x, double z) {
        double[] q = warped(x, z);
        return MorphNoiseEval.sample(low.seed(), q[0] * low.frequency(), q[1] * low.frequency());
    }

    double lowSource(double x, double z, boolean rounded) {
        double[] q = warped(x, z);
        double ridge;
        if (!rounded) ridge = MorphNoiseEval.eval(low, q[0], q[1]);
        else {
            double n = MorphNoiseEval.sample(low.seed(), q[0] * low.frequency(), q[1] * low.frequency());
            // Preserve production operation order, spectral float and declared max. No new remap.
            double a = r == 0 ? Math.abs(n) : Math.sqrt(n * n + r * r) - r;
            double s = 1 - a; s *= s; s *= 1.0;
            double value = 0; value += s * low.spectral()[0];
            ridge = MorphNoiseEval.map(value, 0, low.max());
        }
        return ridge * MorphNoiseEval.eval(scalerLow, q[0], q[1]);
    }

    double at(double x, double z) {
        return switch (mode) {
            case "J0" -> MorphNoiseEval.eval(dFull, x, z);
            case "D2" -> MorphNoiseEval.eval(dTwo, x, z);
            case "J1c" -> lowSource(x, z, false);
            default -> lowSource(x, z, true);
        };
    }

    double sample(int px, int pz) {
        int i = px - ex, j = pz - ez;
        if (source != null && i >= 0 && j >= 0 && i < W && j < W) return source[j * W + i];
        return at(px * step, pz * step);
    }

    void fields(int px, int pz, float[] out) {
        double c = sample(px, pz), xp = sample(px + 1, pz), xm = sample(px - 1, pz);
        double zp = sample(px, pz + 1), zm = sample(px, pz - 1);
        out[0] = (float) (((xp - xm) / 2) * COMP);
        out[1] = (float) (((zp - zm) / 2) * COMP);
        out[2] = (float) ((xp + xm + zp + zm - 4 * c) * COMP);
    }

    double[] domain(double x, double z) {
        double a = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(a) * 2, z + Math.cos(a) * 2};
    }

    /** Pre-render radius selection. All axis-adjacent pairs in padded render + calibration stencils. */
    void deriveRadius(Path out) throws Exception {
        double[] n = new double[W * W];
        IntStream.range(0, W).parallel().forEach(j -> {
            for (int i = 0; i < W; i++) n[j * W + i] = noise((ex + i) * step, (ez + j) * step);
        });
        for (int j = 0; j < W - 1; j++) for (int i = 0; i < W - 1; i++) {
            double c = n[j * W + i];
            maxDn = Math.max(maxDn, Math.max(Math.abs(n[j * W + i + 1] - c), Math.abs(n[(j + 1) * W + i] - c)));
        }
        for (double[] q : points) {
            int px = (int) Math.round(q[0] / step), pz = (int) Math.round(q[1] / step);
            for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
                double c = noise((px + i) * step, (pz + j) * step);
                maxDn = Math.max(maxDn, Math.max(Math.abs(noise((px+i+1)*step,(pz+j)*step)-c),
                        Math.abs(noise((px+i)*step,(pz+j+1)*step)-c)));
            }
        }
        r = maxDn / .20;
        for (int j = 0; j < W - 1; j++) for (int i = 0; i < W - 1; i++) {
            double c = foldSlope(n[j*W+i]);
            maxFoldSlopeChange = Math.max(maxFoldSlopeChange, Math.max(Math.abs(foldSlope(n[j*W+i+1])-c),
                    Math.abs(foldSlope(n[(j+1)*W+i])-c)));
        }
        require(maxFoldSlopeChange <= .20, "fold slope change criterion");
        Files.writeString(out.resolve("radius.txt"), String.format(Locale.ROOT,
                "stepWu=%.17g\nridgeFrequency=%.17g\nmaxDn=%.17g\nr=maxDn/0.20=%.17g\n"
                + "maxMeasuredFoldSlopeChange=%.17g\noldR=%.17g\n"
                + "q(n)=sqrt(n*n+r*r)-r; q''<=1/r; |delta q'|<=maxDn/r=0.20\n"
                + "Finite-domain bound includes original warp; scaler180 multiplies ridge but is not folded.\n"
                + "This is not a global bound on continuum spatial slope; no image-dependent tuning.\n",
                step, (double)low.frequency(), maxDn, r, maxFoldSlopeChange,
                5 * 1.5 * step * low.lacunarity() / 610));
    }

    double foldSlope(double n) { return n / Math.sqrt(n*n+r*r); }

    double[] calibrate() {
        source = null;
        double[] target = new double[CAL]; double[][] sums = new double[3][CAL]; float[] e = new float[3];
        for (int i = 0; i < CAL; i++) {
            double[] q = points[i]; target[i] = mountain.raw(q[0], q[1]);
            FastErosionFilter.erosion((int)Math.round(q[0]/step),(int)Math.round(q[1]/step),
                    this::fields, FastErosionFilter.ORIGINAL, e);
            sums[0][i] = target[i] + .25 * e[0];
            sums[1][i] = MorphNoiseEval.eval(hHistorical,q[0],q[1]) + .25 * e[0];
            sums[2][i] = MorphNoiseEval.eval(dTwo,q[0],q[1]) + .25 * e[0];
        }
        double[] k = new double[3];
        for (int i=0;i<3;i++) k[i]=std(target)/std(sums[i]);
        reports.add(String.format(Locale.ROOT,"%s %.17g %.17g %.17g %.17g %.17g",mode,
                k[0],k[1],k[2],std(target),std(sums[0])));
        return k;
    }

    void bases(Path out) throws Exception {
        common = new double[N*N]; historical = new double[N*N]; two = new double[N*N];
        for (int j=0;j<N;j++) for(int i=0;i<N;i++) {
            int a=j*N+i; double[] q=domain((ox+i)*outStep,(oz+j)*outStep);
            common[a]=mountain.raw(q[0],q[1]);
            require(bits(common[a],MorphNoiseEval.eval(production,q[0],q[1])),"production base identity");
            historical[a]=MorphNoiseEval.eval(hHistorical,q[0],q[1]);
            two[a]=MorphNoiseEval.eval(dTwo,q[0],q[1]);
        }
        write(out.resolve("common-production-raw.f64be"),common);
        write(out.resolve("historical-4x2-raw.f64be"),historical);
        write(out.resolve("historical-2x2-raw.f64be"),two);
    }

    double interp(double[] a,double x,double z) {
        int px=(int)Math.floor(x),pz=(int)Math.floor(z),i=px-ex,j=pz-ez;
        require(i>=1 && j>=1 && i+2<W && j+2<W,"true halo");
        double tx=x-px,tz=z-pz;
        double lo=a[j*W+i]+(a[j*W+i+1]-a[j*W+i])*tx;
        double hi=a[(j+1)*W+i]+(a[(j+1)*W+i+1]-a[(j+1)*W+i])*tx;
        return lo+(hi-lo)*tz;
    }

    void render(Path out,double[] k) throws Exception {
        source=new double[W*W];
        IntStream.range(0,W).parallel().forEach(j->{
            for(int i=0;i<W;i++)source[j*W+i]=at((ex+i)*step,(ez+j)*step);
        });
        double[] eg=new double[W*W];
        IntStream.range(2,W-2).parallel().forEach(j->{
            float[] e=new float[3];
            for(int i=2;i<W-2;i++) {
                FastErosionFilter.erosion(ex+i,ez+j,this::fields,FastErosionFilter.ORIGINAL,e);
                eg[j*W+i]=e[0];
            }
        });
        double[] h=new double[N*N],inc=new double[N*N],mask=new double[N*N],unit=new double[N*N];
        float[] d=new float[3];
        for(int j=0;j<N;j++)for(int i=0;i<N;i++) {
            int a=j*N+i;double[] q=domain((ox+i)*outStep,(oz+j)*outStep);
            double e=interp(eg,q[0]/step,q[1]/step); unit[a]=e;
            h[a]=k[0]*(common[a]+.25*e)*MorphCatalog.MOUNTAIN_AMP;
            inc[a]=k[0]*.25*e*MorphCatalog.MOUNTAIN_AMP;
            fields((int)Math.floor(q[0]/step),(int)Math.floor(q[1]/step),d);
            double s=StrictMath.hypot(d[0],d[1])*262144.0*2.0;
            double v=s<.1?.5*s*s/.1:s-.05,t=1-Math.max(0,Math.min(1,v));mask[a]=1-t*t;
        }
        write(out.resolve(mode+"-height.f64be"),h); write(out.resolve(mode+"-erosion.f64be"),inc);
        write(out.resolve(mode+"-mask.f64be"),mask);write(out.resolve(mode+"-unitE.f64be"),unit);
        source=null;
    }

    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);Path out=Path.of(args[0]);
        if(Files.exists(out))throw new IllegalArgumentException("refuse overwrite "+out);
        Files.createDirectories(out); FastErosionCorrectedProbe p=new FastErosionCorrectedProbe();
        p.deriveRadius(out);double radius=p.r;p.bases(out);
        for(String mode:new String[]{"J0","J1c","V0","V2","D2"}) {
            p.mode=mode;p.r=mode.equals("V0")?0:radius;
            double[] k=p.calibrate();p.render(out,k);System.out.println(p.reports.get(p.reports.size()-1));
        }
        Files.write(out.resolve("calibration.txt"),p.reports);
        Files.writeString(out.resolve("sampling.txt"),"N="+N+" step="+p.step+" outputStep="+p.outStep
                +" origin="+(p.ox*p.outStep)+","+(p.oz*p.outStep)+" native="+p.W+"x"+p.W
                +"\nCommon height=catalog raw (ridge4 scaler4 warp); J0 deriv=ridge4 scaler2 warp\n"
                +"calibration:8192 points [16L,80L), RNG=(long)(double)0x4641535445524f53L;"
                +"nearest native E calibration, bilinear native E rendering; no window fit.\n");
    }

    static double std(double[] a){double m=0;for(double v:a)m+=v;m/=a.length;
        double s=0;for(double v:a)s+=(v-m)*(v-m);return StrictMath.sqrt(s/a.length);}
    static boolean bits(double a,double b){return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
    static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
    static void write(Path p,double[] a)throws Exception{
        try(DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))){
            for(double v:a){require(Double.isFinite(v),"finite output");s.writeDouble(v);}
        }
    }
}
