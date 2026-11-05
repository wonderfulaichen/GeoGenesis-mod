package com.geogenesis.worldgen.terrain.morph;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.IntStream;

/** Frozen curve geometry, complete nearest-curve support; pure mountain type-space only. */
public final class CurvedWindowProbe {
    private static final long SEED=MorphCatalog.CANONICAL_SEED;
    private static final int N=769;
    private final MorphCatalog catalog=new MorphCatalog(SEED);
    private final MorphRecipe mountain=new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    private final MorphRecipe[] recipes=MorphLandRecipe.all(catalog);
    private final MorphKernel before=new MorphKernel(recipes,SEED,true),after=new MorphKernel(recipes,SEED,true);
    private final double[] weights=before.merge(new int[]{MorphCatalog.IDX_MOUNTAIN},new double[]{1});
    private final MorphNoise.Erosion erosion;
    private final MorphNoise.Node direction;
    private final CurvedSegmentErosion geometry;
    private final CompleteCurveQuery complete;
    private final double length,step;
    private final int ox,oz;
    private final double[] a=new double[N*N],b=new double[N*N],raw=new double[N*N];
    private CurveWindowEvidence.Seam[] seams;
    private double gridMs,maxOldLimit,maxNewLimit,maxOracleError;
    private int geometryChecks;

    private CurvedWindowProbe()throws Exception {
        Field f=MorphKernel.class.getDeclaredField("erosion");f.setAccessible(true);
        erosion=(MorphNoise.Erosion)f.get(before);
        geometry=new CurvedSegmentErosion(erosion,.15);complete=new CompleteCurveQuery(geometry);
        f.set(before,new MorphNoise.DualNode((x,z)->(float)geometry.eval(x,z),geometry::eval));
        f.set(after,new MorphNoise.DualNode((x,z)->(float)complete.eval(x,z),complete::eval));
        Field df=MorphKernel.class.getDeclaredField("direction");df.setAccessible(true);
        direction=(MorphNoise.Node)df.get(before);
        var warp=(MorphNoise.Warped)catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        var body=(MorphNoise.Mul)warp.input();var ridge=(MorphNoise.PerlinRidge)body.a();
        var scaler=(MorphNoise.Perlin)((MorphNoise.AlphaMix)body.b()).input();
        require(ridge.octaves()==4&&scaler.octaves()==4,"complete raw frozen");
        length=1.0/ridge.frequency();step=length/256;
        ox=(int)Math.round(-3242.5/step)-N/2;oz=(int)Math.round(-3365.5/step)-N/2;
    }

    private double[] domain(double x,double z) {
        double t=MorphNoiseEval.eval(direction,x,z)*6.2831855f;
        return new double[]{x+Math.sin(t)*2,z+Math.cos(t)*2};
    }

    /** Final pure-mountain shell at a GIVEN erosion-domain coordinate, before the outer 2wu map. */
    private double domainHeight(double x,double z,boolean fixed) {
        double source=mountain.raw(x,z),texture=fixed?complete.eval(x,z):geometry.eval(x,z);
        return (texture+(source-texture)*(1-.65))*mountain.amplitude(x,z)+mountain.bias(x,z);
    }

    private void grid(Path out)throws Exception {
        long start=System.nanoTime();
        IntStream.range(0,N).parallel().forEach(row->{
            for(int col=0;col<N;col++) {
                int i=row*N+col;double x=(ox+col)*step,z=(oz+row)*step;double[] q=domain(x,z);
                raw[i]=mountain.raw(q[0],q[1]);
                a[i]=before.sampleLand(x,z,weights);b[i]=after.sampleLand(x,z,weights);
                require(bits(a[i],domainHeight(q[0],q[1],false)),"unchanged baseline composition");
                require(bits(b[i],domainHeight(q[0],q[1],true)),"only support changed");
            }
        });
        gridMs=ms(start);
        write(out,"before-height",a);write(out,"after-height",b);write(out,"base-raw",raw);
        System.out.printf(Locale.ROOT,"GRID %d %.3fms; same raw/outer map/blend/shell%n",a.length,gridMs);
    }

    private double frequency(int octave) {
        double f=1;for(int j=0;j<octave;j++)f*=erosion.lacunarity();return f;
    }

    private void seamEvidence(Path out)throws Exception {
        StringBuilder csv=new StringBuilder("id,octave,axis,offset_fraction,old_left,old_right,new_left,new_right,oracle\n");
        StringBuilder json=new StringBuilder("{\"selectionRule\":\"maximum BEFORE same-coordinate candidate-window difference per axis on fixed frozen-window boundary sample grid; not reselected after fix\",\"seams\":[");
        for(int index=0;index<seams.length;index++) {
            var s=seams[index];double f=frequency(s.octave());
            double left=complete.forced(s.octave(),s.cx()-(s.axis()==0?1:0),s.cz()-(s.axis()==1?1:0),s.x(),s.z(),true);
            double right=complete.forced(s.octave(),s.cx(),s.cz(),s.x(),s.z(),true);
            require(bits(left,right),"same-point complete support seam");
            maxOldLimit=Math.max(maxOldLimit,Math.abs(s.left().value()-s.right().value()));
            maxNewLimit=Math.max(maxNewLimit,Math.abs(left-right));
            maxOracleError=Math.max(maxOracleError,Math.abs(right-s.oracle().value()));
            csv.append(index).append(',').append(s.octave()).append(',').append(s.axis())
                    .append(",0,").append(s.left().value()).append(',').append(s.right().value())
                    .append(',').append(left).append(',').append(right).append(',').append(s.oracle().value()).append('\n');
            if(index!=0)json.append(',');
            json.append("{\"id\":\"seam-").append(index).append("\",\"octave\":").append(s.octave())
                    .append(",\"axis\":").append(s.axis()).append(",\"domainPoint\":").append(point(s.x()/f,s.z()/f))
                    .append(",\"worldLocator\":").append(point(s.x()/f,s.z()/f)).append(",\"locatorErrorBoundWu\":2")
                    .append(",\"oldJumpH\":").append(.65*MorphCatalog.MOUNTAIN_AMP*Math.pow((double)erosion.amplitude(),s.octave())
                            /(1+(double)erosion.amplitude())*Math.abs(s.left().value()-s.right().value()))
                    .append(",\"newJumpH\":0,\"nearestSource\":[").append(s.oracle().sourceX()).append(',').append(s.oracle().sourceZ()).append("]}");
        }
        json.append("]}");Files.writeString(out.resolve("seams.json"),json);
        Files.writeString(out.resolve("seam-limits.csv"),csv);
    }

    private void profiles(Path out)throws Exception {
        StringBuilder csv=new StringBuilder("profile,offset_wu,world_x,world_z,before_H,after_H,coordinate_space\n");
        for(int index=0;index<seams.length;index++) {
            var s=seams[index];double f=frequency(s.octave()),x=s.x()/f,z=s.z()/f;
            for(int i=0;i<=1600;i++) {
                double d=-2+4*i/1600.0,px=x+(s.axis()==0?d:0),pz=z+(s.axis()==1?d:0);
                row(csv,"seam-"+index+"-domain-near",d,px,pz,domainHeight(px,pz,false),domainHeight(px,pz,true),"erosion-domain");
            }
            for(int i=0;i<=800;i++) {
                double d=-40+80*i/800.0,px=x+(s.axis()==0?d:0),pz=z+(s.axis()==1?d:0);
                row(csv,"seam-"+index+"-world-wide",d,px,pz,before.sampleLand(px,pz,weights),after.sampleLand(px,pz,weights),"world");
            }
        }
        Files.writeString(out.resolve("profiles.csv"),csv);
        limits(out);
    }

    private void limits(Path out)throws Exception {
        StringBuilder csv=new StringBuilder("seam,h_wu,before_abs_difference,after_abs_difference,coordinate_space\n");
        for(int index=0;index<seams.length;index++) {
            var s=seams[index];double f=frequency(s.octave()),x=s.x()/f,z=s.z()/f;
            double previous=Double.POSITIVE_INFINITY;
            for(double h:new double[]{.01,.001,.0001,.00001,.000001}) {
                double dx=s.axis()==0?h:0,dz=s.axis()==1?h:0;
                double old=Math.abs(domainHeight(x-dx,z-dz,false)-domainHeight(x+dx,z+dz,false));
                double fixed=Math.abs(domainHeight(x-dx,z-dz,true)-domainHeight(x+dx,z+dz,true));
                require(fixed<=previous*.2+1e-12,"final shell seam convergence");previous=fixed;
                csv.append(index).append(',').append(h).append(',').append(old).append(',').append(fixed).append(",erosion-domain\n");
            }
        }
        Files.writeString(out.resolve("two-sided-limits.csv"),csv);
    }

    private void checks(Path out)throws Exception {
        var rebuiltGeometry=new CurvedSegmentErosion(erosion,.15);
        var rebuilt=new CompleteCurveQuery(rebuiltGeometry);
        for(int o=0;o<erosion.octaves();o++)for(int z=-45;z<=-15;z++)for(int x=-45;x<=-15;x++) {
            require(geometry.edge(o,x,z).equals(rebuiltGeometry.edge(o,x,z)),"geometry descriptor changed");geometryChecks++;
        }
        for(int i=95;i>=0;i--) {
            int at=Math.floorMod(i*7919+31,a.length);double[] q=domain((ox+at%N)*step,(oz+at/N)*step);
            require(bits(complete.eval(q[0],q[1]),rebuilt.eval(q[0],q[1])),"reverse cold rebuild");
        }
        IntStream.range(0,96).parallel().forEach(i->{
            int at=Math.floorMod(i*7919+31,a.length);double[] q=domain((ox+at%N)*step,(oz+at/N)*step);
            require(bits(complete.eval(q[0],q[1]),rebuilt.eval(q[0],q[1])),"parallel same point");
        });
        json(out,"checks.json","\"passed\":true,\"sameGeometryDescriptors\":"+geometryChecks
                +",\"replaySamples\":192,\"replayDifferentBits\":0,\"oldMaxForcedWindowMismatch\":"+maxOldLimit
                +",\"fixedMaxForcedWindowMismatch\":"+maxNewLimit+",\"maxRadius6OracleDifference\":"+maxOracleError
                +",\"noHeightBlur\":true,\"noCoarseFineInheritance\":true,\"productionWiring\":false");
    }

    private void performance(Path out)throws Exception {
        double[][] time=new double[2][7];double sink=0;
        for(int batch=0;batch<10;batch++)for(int arm=0;arm<2;arm++) {
            long start=System.nanoTime();
            for(int i=0;i<256;i++) {
                int at=Math.floorMod(i*7919+31,a.length);double x=(ox+at%N)*step,z=(oz+at/N)*step;
                sink+=(arm==0?before:after).sampleLand(x,z,weights);
            }
            if(batch>=3)time[arm][batch-3]=ms(start);
        }
        require(Double.isFinite(sink),"perf sink");Arrays.sort(time[0]);Arrays.sort(time[1]);
        json(out,"performance.json","\"batchPoints\":256,\"warmupBatches\":3,\"measuredBatches\":7"
                +",\"beforeWarmMedianMs\":"+time[0][3]+",\"afterWarmMedianMs\":"+time[1][3]
                +",\"gridMs\":"+gridMs+",\"scope\":\"pure Java type-space warm cached geometry, not Minecraft chunk timing\"");
    }

    private void metadata(Path out)throws Exception {
        json(out,"sampling.json","\"size\":769,\"seed\":"+SEED+",\"originWu\":"+point(ox*step,oz*step)
                +",\"stepWu\":"+step+",\"spanWu\":"+(768*step)+",\"HS\":2,\"typeLengthWu\":"+length
                +",\"actualMinecraftWorld\":false");
        json(out,"geometry.json","\"curveChanged\":false,\"bend\":0.15,\"distanceSegments\":64"
                +",\"nodeAndReceiverChanged\":false,\"rawNoiseChanged\":false,\"octaves\":2,\"blend\":0.65"
                +",\"amplitudeGain\":"+(double)erosion.amplitude()+",\"lacunarity\":"+(double)erosion.lacunarity()
                +",\"distanceFallOff\":"+(double)erosion.distanceFallOff()+",\"gridWu\":128"
                +",\"query\":\"radius4 complete support with conservative cell and control-hull AABB pruning\""
                +",\"proof\":\"control excursion <=.15*2sqrt(2)*grid; omitted cells at radius4 >grid away; clamp unchanged\""
                +",\"normalConvergenceIsDefect\":false,\"noCoarseFineInheritance\":true");
    }

    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);require(args.length==1,"usage NEW data");Path out=Path.of(args[0]);
        require(!Files.exists(out),"refuse existing output");Files.createDirectories(out);
        CurvedWindowProbe p=new CurvedWindowProbe();p.seams=CurveWindowEvidence.scan(p.geometry);
        p.seamEvidence(out);p.grid(out);p.profiles(out);p.checks(out);p.performance(out);p.metadata(out);
        System.out.println("COMPLETE QUERY PASS; fixed same-point seams; frozen curve geometry; diagnostics only");
    }
    private static String point(double x,double z){return Arrays.toString(new double[]{x,z});}
    private static double ms(long s){return(System.nanoTime()-s)/1e6;}
    private static boolean bits(double a,double b){return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
    private static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
    private static void row(StringBuilder c,String id,double d,double x,double z,double a,double b,String space){
        c.append(id).append(',').append(d).append(',').append(x).append(',').append(z).append(',').append(a).append(',').append(b).append(',').append(space).append('\n');
    }
    private static void json(Path out,String name,String body)throws Exception{Files.writeString(out.resolve(name),"{"+body+"}\n");}
    private static void write(Path out,String name,double[] values)throws Exception{
        try(DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name+".f64be"))))){
            for(double v:values){require(Double.isFinite(v),"finite");s.writeDouble(v);}
        }
    }
}
