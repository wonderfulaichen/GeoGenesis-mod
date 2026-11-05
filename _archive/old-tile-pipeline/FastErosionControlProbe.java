package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import com.geogenesis.standalone.FastErosionContinuousRotation;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** C: smooth demo base, same operator; D: same L2 base, only continuous feedback rotation. */
public final class FastErosionControlProbe {
    static final long SEED = MorphCatalog.CANONICAL_SEED;
    static final int N = 769, W = 1545, H = 1545, X0 = -3494, Z0 = -3596;
    static final double OLD_K = .8205431216912392;
    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(17));
    final MorphNoise.Node l2, direction;
    final double step, outputStep;
    final Method noise, analytic;
    double[] baseGrid;
    int mode;
    // Translate ONLY the prototype base. Preserve identical erosion hash/lattice world phase.
    // Window middle native coordinate (-2722,-2824) maps to demo centre (0,3378).
    static final int OFFSET_X = 2722, OFFSET_Z = 6202;

    FastErosionControlProbe() throws Exception {
        MorphNoise.Warped w = (MorphNoise.Warped) catalog.entry(17).rawNode();
        MorphNoise.Mul b = (MorphNoise.Mul) w.input();
        MorphNoise.PerlinRidge r = (MorphNoise.PerlinRidge) b.a();
        MorphNoise.AlphaMix s = (MorphNoise.AlphaMix) b.b();
        MorphNoise.Perlin p = (MorphNoise.Perlin) s.input();
        l2 = MorphNoise.warp(new MorphNoise.Mul(new MorphNoise.PerlinRidge(r.seed(),r.frequency(),2,r.lacunarity(),r.gain()),
                new MorphNoise.AlphaMix(new MorphNoise.Perlin(p.seed(),p.frequency(),2,p.lacunarity(),p.gain()),s.alpha())),w.wx(),w.wz(),w.pow());
        step = 1.0/r.frequency()/512; outputStep = step*2;
        direction = MorphNoise.Perlin.ofScale(MorphCatalog.base(SEED+77)+3,10,1);
        Class<?> demo = Class.forName("com.geogenesis.standalone.FastErosionOriginalDemo");
        noise = demo.getDeclaredMethod("wrappedSnoise2",int.class,int.class,int.class,float[].class);
        analytic = demo.getDeclaredMethod("baseDerivatives",int.class,int.class,float[].class);
        noise.setAccessible(true); analytic.setAccessible(true);
    }
    double prototype(int x,int z) {
        try {
            float[] a=new float[4]; noise.invoke(null,(x+OFFSET_X)&((1<<29)-1),(z+OFFSET_Z)&((1<<28)-1),9,a);
            return a[0];
        } catch(Exception e){throw new RuntimeException(e);}
    }
    double value(int x,int z) {
        if(baseGrid!=null && x>=X0 && z>=Z0 && x<X0+W && z<Z0+H) return baseGrid[(z-Z0)*W+x-X0];
        return mode==1||mode==2 ? prototype(x,z) : MorphNoiseEval.eval(l2,x*step,z*step);
    }
    void fields(int x,int z,float[] d) {
        if(mode==2) {
            try {analytic.invoke(null,(x+OFFSET_X)&((1<<29)-1),(z+OFFSET_Z)&((1<<28)-1),d);return;}
            catch(Exception e){throw new RuntimeException(e);}
        }
        double c=value(x,z),xp=value(x+1,z),xm=value(x-1,z),zp=value(x,z+1),zm=value(x,z-1);
        d[0]=(float)(((xp-xm)/2)*4e-4f); d[1]=(float)(((zp-zm)/2)*4e-4f);
        d[2]=(float)((xp+xm+zp+zm-4*c)*4e-4f);
    }
    void erosion(int x,int z,float[] e) {
        if(mode==3) FastErosionContinuousRotation.erosion(x,z,this::fields,FastErosionContinuousRotation.ORIGINAL,e);
        else FastErosionFilter.erosion(x,z,this::fields,FastErosionFilter.ORIGINAL,e);
    }
    double[] domain(double x,double z) {
        double a=MorphNoiseEval.eval(direction,x,z)*6.2831855f;
        return new double[]{x+Math.sin(a)*2,z+Math.cos(a)*2};
    }
    double calibrate() {
        baseGrid=null;
        Random r=new Random((long)(double)0x4641535445524f53L);
        double[] target=new double[8192],sum=new double[8192];float[] e=new float[3];
        double main=step*512;
        for(int i=0;i<sum.length;i++) {
            double[] p=domain(16*main+r.nextInt((int)(64*main)),16*main+r.nextInt((int)(64*main)));
            target[i]=mountain.raw(p[0],p[1]);
            erosion((int)Math.round(p[0]/step),(int)Math.round(p[1]/step),e);
            sum[i]=MorphNoiseEval.eval(l2,p[0],p[1])+.25*e[0];
        }
        return std(target)/std(sum);
    }
    static double interp(double[] a,double x,double z) {
        int px=(int)Math.floor(x),pz=(int)Math.floor(z),i=px-X0,j=pz-Z0;
        double tx=x-px,tz=z-pz;
        double lo=a[j*W+i]+(a[j*W+i+1]-a[j*W+i])*tx;
        double hi=a[(j+1)*W+i]+(a[(j+1)*W+i+1]-a[(j+1)*W+i])*tx;
        return lo+(hi-lo)*tz;
    }
    void render(Path out,String arm,double k,Path cached) throws Exception {
        baseGrid = mode==1||mode==2 ? new double[W*H] : read(cached);
        if(mode==1||mode==2) for(int j=0;j<H;j++)for(int i=0;i<W;i++)baseGrid[j*W+i]=prototype(X0+i,Z0+j);
        double[] eg=new double[W*H];
        java.util.stream.IntStream.range(2,H-2).parallel().forEach(j->{
            float[] e=new float[3]; for(int i=2;i<W-2;i++){erosion(X0+i,Z0+j,e);eg[j*W+i]=e[0];}
        });
        String[] names={"height","erosion","base","mask"}; double[][] a=new double[4][N*N];
        float[] d=new float[3];
        for(int j=0;j<N;j++)for(int i=0;i<N;i++) {
            int at=j*N+i;double x=(-1745+i)*outputStep,z=(-1796+j)*outputStep;
            double[] p=domain(x,z);double sx=p[0]/step,sz=p[1]/step;
            double b=mode==1||mode==2?interp(baseGrid,sx,sz):MorphNoiseEval.eval(l2,p[0],p[1]);
            double e=interp(eg,sx,sz);
            a[0][at]=k*(b+.25*e)*.699999988079071;
            a[1][at]=k*.25*e*.699999988079071;
            a[2][at]=k*b*.699999988079071;
            fields((int)Math.floor(sx),(int)Math.floor(sz),d);
            float s=(float)StrictMath.hypot(d[0],d[1])*262144f*2f;
            float v=s<.1f?.5f*s*s/.1f:s-.05f,t=1-Math.max(0,Math.min(1,v));
            a[3][at]=1-t*t;
        }
        for(int i=0;i<names.length;i++)write(out.resolve(arm+"-"+names[i]+".f64be"),a[i]);
        write(out.resolve(arm+"-native-E.f64be"),eg);
        if(mode==0||mode==1) write(out.resolve(arm+"-native-base.f64be"),baseGrid);
    }
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("exists");Files.createDirectories(out);
        FastErosionControlProbe p=new FastErosionControlProbe();Path cache=Path.of(args[1]);
        p.mode=0;double kb=p.calibrate();if(Double.doubleToRawLongBits(kb)!=Double.doubleToRawLongBits(OLD_K))throw new AssertionError("B calibration drift");
        p.render(out,"B",kb,cache);
        p.mode=1;p.render(out,"C",OLD_K,cache); // SAME output scaling, no statistics fitting to make C look better.
        p.mode=2;p.render(out,"C-analytic",OLD_K,cache); // Original analytic callback control, separately labelled.
        p.mode=3;double kd=p.calibrate();p.render(out,"D",kd,cache);
        Files.writeString(out.resolve("parameters.json"),String.format(Locale.ROOT,
            "{\"kB\":%.17g,\"kC\":%.17g,\"kD\":%.17g,\"epsilon\":%.17g,\"baseTranslationSamples\":[%d,%d],\"stepWu\":%.17g}\n",
            kb,OLD_K,kd,(double)FastErosionContinuousRotation.EPS_REF,OFFSET_X,OFFSET_Z,p.step));
        System.out.println("PASS B calibration exact; all C/D arrays finite; kD="+kd);
    }
    static double[] read(Path p)throws Exception {
        ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(p));double[] a=new double[b.remaining()/8];for(int i=0;i<a.length;i++)a[i]=b.getDouble();return a;
    }
    static double std(double[] a){double m=0;for(double x:a)m+=x;m/=a.length;double v=0;for(double x:a)v+=(x-m)*(x-m);return StrictMath.sqrt(v/a.length);}
    static void write(Path p,double[] a)throws Exception {
        try(DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))){
            for(double x:a){if(!Double.isFinite(x))throw new AssertionError("nonfinite");s.writeDouble(x);}
        }
    }
}
