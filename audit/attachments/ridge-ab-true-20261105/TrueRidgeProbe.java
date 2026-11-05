package com.geogenesis.worldgen.terrain.morph;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.*;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;
import com.geogenesis.worldgen.terrain.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.function.DoubleBinaryOperator;

public final class TrueRidgeProbe {
    static final long SEED = 9139912035078620160L;
    final MorphCatalog catalog = new MorphCatalog(SEED);
    final MorphLandField land = new MorphLandField(catalog, MorphAssembly.of(SEED), 1200f);
    final MorphKernel original = new MorphKernel(MorphLandRecipe.all(catalog), SEED);
    final DiagnosticRidgeKernel a = new DiagnosticRidgeKernel(MorphLandRecipe.all(catalog), SEED);
    final RidgeValleyErosion.RidgeConfig cfg;
    final HeightCurve curve;
    final MorphOceanAssembler[] ocean = new MorphOceanAssembler[3];

    TrueRidgeProbe(TerrainParams p, RidgeValleyErosion.RidgeConfig c) {
        cfg = c; curve = new HeightCurve(p, -64, 320);
        a.ridge(c, (x,z) -> land.weights(x,z));
        ContinentField continent = new ContinentField(p); continent.seed(SEED);
        for (int k=0;k<3;k++) {
            final int arm=k;
            ocean[k] = new MorphOceanAssembler((x,z)->values(x,z)[arm],continent::sample,SEED+3)
                    .enableCoastalDetail(SEED);
        }
    }
    static double toE(double h) { return Math.max(MorphScale.GROUND_E+h*MorphScale.E_SPAN,0); }
    float delta(DoubleBinaryOperator f,double x,double z) {
        float h=(float)f.applyAsDouble(x,z), l=(float)f.applyAsDouble(x-2,z),r=(float)f.applyAsDouble(x+2,z);
        float b=(float)f.applyAsDouble(x,z-2),t=(float)f.applyAsDouble(x,z+2);
        float denominator=4f;
        if(cfg.horizontalScale>.01f && cfg.horizontalScale!=1f)denominator*=cfg.horizontalScale;
        return RidgeValleyErosion.evaluatePoint((float)x,(float)z,h,(r-l)/denominator,(t-b)/denominator,0,cfg);
    }
    double[] values(double x,double z) {
        var s=land.sample(x,z); double covered=s.alpha()==0?Double.NaN:land.oldCoverageHeight(x,z);
        double ae=toE(land.overlay(a.sampleLand(x,z,s.weight()),s.mask(),covered));
        return new double[]{s.e(),ae,s.e()+delta(land::e,x,z)};
    }
    static void require(boolean yes,String msg) {if(!yes)throw new AssertionError(msg);}
    void checks(Path out) throws Exception {
        StringBuilder report=new StringBuilder();
        double x=-2687.5,z=3328.5; double[] w=land.weights(x,z);
        float strength=cfg.strength;cfg.strength=0;
        for(int id=0;id<18;id++) {
            double[] one=new double[18];one[id]=1;
            require(Double.doubleToRawLongBits(original.sampleLand(x,z,one))==Double.doubleToRawLongBits(a.sampleLand(x,z,one)),"zero-original "+id);
        }
        require(Double.doubleToRawLongBits(original.sampleLand(x,z,w))==Double.doubleToRawLongBits(a.sampleLand(x,z,w)),"zero mixed");
        cfg.strength=strength;report.append("zero-strength all18 and mixed original bitwise PASS\n");
        a.ridge(cfg,null);
        for(int id=0;id<18;id++) {
            double[] one=new double[18];one[id]=1;
            MorphRecipe[] recipes=MorphLandRecipe.all(catalog);
            final MorphRecipe base=recipes[id];final double[] endpoint=one;
            // Independent per-recipe raw-node wrapper, evaluated in WORLD coordinates.
            // Constant endpoint weights ensure no neighbouring weight interpolation in this check.
            final DiagnosticRidgeKernel reference=new DiagnosticRidgeKernel(MorphLandRecipe.all(catalog),SEED);
            reference.ridge(cfg,null);
            recipes[id]=new MorphRecipe(){
                public double raw(double u,double v){return base.raw(u,v)+reference.rawDelta(u,v,endpoint)/(MorphScale.E_SPAN*base.amplitude(u,v));}
                public double amplitude(double u,double v){return base.amplitude(u,v);}
                public double bias(double u,double v){return base.bias(u,v);}
                public Role role(){return base.role();}
                public boolean clamped(){return base.clamped();}
            };
            // Non-mountain recipes: independent node wrapper matches exactly (no domain relocation).
            if(base.role()!=MorphRecipe.Role.MOUNTAIN) {
                double expected=new MorphKernel(recipes,SEED).sampleLand(x,z,one);
                require(Math.abs(expected-a.sampleLand(x,z,one))<1e-12,"node endpoint "+id);
            }
            float d=delta((u,v)->a.rawShell(u,v,endpoint),x,z);
            require(Float.floatToRawIntBits(d)==Float.floatToRawIntBits(a.rawDelta(x,z,one)),"raw difference "+id);
            double direct=a.sampleLand(x,z,one);
            a.sampleLand(x+20,z-40,w);
            require(Double.doubleToRawLongBits(direct)==Double.doubleToRawLongBits(a.sampleLand(x,z,one)),"repeat "+id);
        }
        report.append("raw-node endpoints non-mountain independent wrapper PASS; all18 raw finite-difference bitwise PASS; repeat/order PASS\n");
        double[] one=new double[18];one[17]=1;
        double end=a.sampleLand(x,z,one);
        var directionField=MorphKernel.class.getDeclaredField("direction");directionField.setAccessible(true);
        var erosionField=MorphKernel.class.getDeclaredField("erosion");erosionField.setAccessible(true);
        double angle=MorphNoiseEval.eval((MorphNoise.Node)directionField.get(original),x,z)*6.2831855f;
        double u=x+Math.sin(angle)*2,v=z+Math.cos(angle)*2;
        MorphRecipe mountain=MorphLandRecipe.all(catalog)[17];
        double raw=mountain.raw(u,v)+a.rawDelta(x,z,one)/(MorphScale.E_SPAN*mountain.amplitude(u,v));
        double texture=MorphNoiseEval.eval((MorphNoise.Node)erosionField.get(original),u,v);
        double expected=(texture+(raw-texture)*(1-.65))*mountain.amplitude(u,v)+mountain.bias(u,v);
        require(Math.abs(expected-end)<1e-12,"mountain one-domain independent endpoint");
        report.append("mountain endpoint independent one-domain + unchanged texture + shell formula PASS\n");
        double prev=Double.POSITIVE_INFINITY;
        for(double eps:new double[]{1e-3,1e-5,1e-7,1e-9}) {
            double[] q=one.clone();q[17]=1-eps;q[15]=eps;
            double error=Math.abs(a.sampleLand(x,z,q)-end);
            require(error<prev,"endpoint retreat convergence");prev=error;
        }
        require(prev<1e-6,"endpoint retreat residual");
        report.append("mountain endpoint retreat 15->17 eps=1e-3..1e-9 convergence PASS (world-coordinate raw node)\n");
        a.ridge(cfg,(wx,wz)->land.weights(wx,wz));
        double[] before=values(x,z);values(x+33,z+44);
        require(Arrays.equals(before,values(x,z)),"world query order");
        report.append("real-weight query order PASS\n");Files.writeString(out.resolve("checks.txt"),report);
    }
    void render(Path out,String name,double ox,double oz) throws Exception {
        Path dir=out.resolve(name);Files.createDirectories(dir);int n=129;double step=2;
        String[] names={"control","recipe","post","pre-control","pre-recipe","pre-post"};
        DataOutputStream[] streams=new DataOutputStream[6];
        try {
            for(int k=0;k<6;k++)streams[k]=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve(names[k]+".f64be"),StandardOpenOption.CREATE_NEW)));
            for(int j=0;j<n;j++)for(int i=0;i<n;i++) {
                double x=ox+i*step,z=oz+j*step;double[] v=values(x,z);
                for(int k=0;k<3;k++){streams[k+3].writeDouble(v[k]);double y=curve.heightFromE(ocean[k].e(x,z));require(Double.isFinite(y),"finite");streams[k].writeDouble(y);}
            }
        } finally {for(var stream:streams)if(stream!=null)stream.close();}
        Files.writeString(dir.resolve("window.json"),String.format(Locale.ROOT,"{\"size\":129,\"stepWu\":2,\"originWu\":[%f,%f],\"hs\":2}",ox,oz));
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[1]);
        try(var f=CommentedFileConfig.of(Path.of(args[0]),TomlFormat.instance())) {
            f.load();require(GeoGenesisConfig.SPEC.isCorrect(f),"config");GeoGenesisConfig.SPEC.setConfig(f);
            var params=GeoGenesisConfig.INSTANCE.buildParams();var c=RidgeConfigAdapter.fromConfig(GeoGenesisConfig.INSTANCE);c.horizontalScale=(float)params.horizontalScale();
            var probe=new TrueRidgeProbe(params,c);probe.checks(out);
            if(args.length<3 || !args[2].equals("checks")) {
                probe.render(out,"true-transition",-2815.5,3200.5);
                probe.render(out,"true-junction",-2943.5,2944.5);
            }
        }
    }
}
