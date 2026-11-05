package com.geogenesis.worldgen.terrain.morph;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/** Original fancy branch, geometry-only change; no new shaping layer or production mutation. */
public final class CurvedSegmentProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final int N = 769;
    private final MorphCatalog catalog = new MorphCatalog(SEED);
    private final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    private final MorphRecipe[] recipes = MorphLandRecipe.all(catalog);
    private final MorphKernel original = new MorphKernel(recipes, SEED, true);
    private final MorphKernel candidate = new MorphKernel(recipes, SEED, true);
    private final double[] weights = original.merge(new int[]{MorphCatalog.IDX_MOUNTAIN}, new double[]{1});
    private final MorphNoise.Erosion erosion;
    private final MorphNoise.Node direction;
    private final CurvedSegmentErosion straight, curved;
    private final double length, step;
    private final int ox, oz;
    private final double[] a = new double[N * N], b = new double[N * N], raw = new double[N * N];
    private final List<Group> groups = new ArrayList<>();
    private double gridMs;
    private record Source(int x, int z, CurvedSegmentErosion.Edge edge) {}
    private record Group(String id, double qx, double qz, List<Source> incoming) {}

    private CurvedSegmentProbe() throws Exception {
        Field field = MorphKernel.class.getDeclaredField("erosion");
        field.setAccessible(true);
        erosion = (MorphNoise.Erosion) field.get(original);
        straight = new CurvedSegmentErosion(erosion, 0);
        curved = new CurvedSegmentErosion(erosion, .15);
        field.set(candidate, new MorphNoise.DualNode((x,z) -> (float) curved.eval(x,z), curved::eval));
        Field df = MorphKernel.class.getDeclaredField("direction");
        df.setAccessible(true);
        direction = (MorphNoise.Node) df.get(original);
        MorphNoise.Warped warp = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) warp.input();
        MorphNoise.PerlinRidge ridge = (MorphNoise.PerlinRidge) body.a();
        MorphNoise.Perlin scaler = (MorphNoise.Perlin) ((MorphNoise.AlphaMix) body.b()).input();
        require(ridge.octaves() == 4 && scaler.octaves() == 4, "complete input");
        require(erosion.octaves() == 2 && erosion.gridSize() == 128 && erosion.strength() == 1
                && erosion.amplitude() == .15f && erosion.lacunarity() == 3.1f
                && erosion.distanceFallOff() == .8f, "original erosion parameters");
        length = 1.0 / ridge.frequency(); step = length / 256;
        ox = (int) Math.round(-3242.5 / step) - N / 2;
        oz = (int) Math.round(-3365.5 / step) - N / 2;
    }

    private double[] domain(double x, double z) {
        double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(angle) * 2, z + Math.cos(angle) * 2};
    }

    private void grid(Path out) throws Exception {
        long start=System.nanoTime();
        IntStream.range(0,N).parallel().forEach(row -> {
            for(int col=0;col<N;col++) {
                int i=row*N+col;double x=(ox+col)*step,z=(oz+row)*step;double[] q=domain(x,z);
                raw[i]=mountain.raw(q[0],q[1]);
                a[i]=original.sampleLand(x,z,weights); b[i]=candidate.sampleLand(x,z,weights);
                double texture=straight.eval(q[0],q[1]);
                require(bits(texture,MorphNoiseEval.eval(erosion,q[0],q[1])),"zero curve original texture");
                double expected=(texture+(raw[i]-texture)*(1-.65))*mountain.amplitude(q[0],q[1])
                        +mountain.bias(q[0],q[1]);
                require(bits(expected,a[i]),"straight insertion/blend shell");
                require(Double.isFinite(b[i]),"finite curved height");
            }
        });
        gridMs=(System.nanoTime()-start)/1e6;
        write(out,"straight-height",a);write(out,"curved-height",b);write(out,"base-raw",raw);
        System.out.printf(Locale.ROOT,"GRID points=%d %.3fms original/zeroCurve/raw contract PASS%n",a.length,gridMs);
    }

    /** Fixed B-independent rule: row-major coarse grid, first >=2 incoming receiver in each half. */
    private void chooseConvergences() {
        double grid=erosion.gridSize();
        Map<String,List<Source>> incoming=new LinkedHashMap<>();
        int x0=(int)Math.floor(ox*step/grid)-1,x1=(int)Math.ceil((ox+N-1)*step/grid)+1;
        int z0=(int)Math.floor(oz*step/grid)-1,z1=(int)Math.ceil((oz+N-1)*step/grid)+1;
        for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++) {
            var e=curved.edge(0,x,z);var old=straight.edge(0,x,z);
            require(bits(e.ax(),old.ax())&&bits(e.az(),old.az())&&bits(e.bx(),old.bx())
                    &&bits(e.bz(),old.bz())&&e.receiverX()==old.receiverX()
                    &&e.receiverZ()==old.receiverZ(),"original nodes/shared receiver changed");
            if(e.ax()==e.bx()&&e.az()==e.bz())continue;
            String key=e.receiverX()+":"+e.receiverZ();
            incoming.computeIfAbsent(key,k->new ArrayList<>()).add(new Source(x,z,e));
        }
        double middle=(ox+N/2)*step;
        for(int half=0;half<2;half++)for(var entry:incoming.entrySet()) {
            List<Source> edges=entry.getValue();var e=edges.get(0).edge;
            // A domain node is only an approximate world locator (original warp is <=2wu).
            boolean interior=e.bx()>ox*step+101&&e.bx()<(ox+N-1)*step-101
                    &&e.bz()>oz*step+101&&e.bz()<(oz+N-1)*step-101;
            boolean right=half==0?e.bx()<middle:e.bx()>=middle;
            if(edges.size()>=2&&interior&&right) {
                groups.add(new Group(entry.getKey(),e.bx(),e.bz(),edges));break;
            }
        }
        require(groups.size()==2,"two preselected shared receivers");
    }

    private void convergenceMetadata(Path out) throws Exception {
        StringBuilder text=new StringBuilder("{\"coordinateSystem\":\"erosion-domain wu; approximate world locator within original 2wu warp\",\"domainOnly\":true,\"convergences\":[");
        for(int index=0;index<groups.size();index++) {
            if(index!=0)text.append(',');Group g=groups.get(index);
            text.append("{\"id\":\"").append(g.id).append("\",\"junctionWu\":").append(point(g.qx,g.qz))
                    .append(",\"locatorBoundWu\":2,\"radiusWu\":100,\"edges\":[");
            for(int k=0;k<g.incoming.size();k++) {
                if(k!=0)text.append(',');Source source=g.incoming.get(k);var e=source.edge;
                text.append("{\"sourceId\":\"").append(source.x).append(':').append(source.z)
                        .append("\",\"receiverId\":\"").append(g.id).append("\",\"straight\":[")
                        .append(point(e.ax(),e.az())).append(',').append(point(e.bx(),e.bz()))
                        .append("],\"curved\":[");
                for(int j=0;j<=64;j++) {if(j!=0)text.append(',');text.append(Arrays.toString(CurvedSegmentErosion.point(e,j/64.0)));}
                text.append("]}");
            }
            text.append("]}");
        }
        text.append("]}");Files.writeString(out.resolve("convergences.json"),text);
    }

    private void profiles(Path out) throws Exception {
        StringBuilder csv=new StringBuilder("profile,s_wu,world_x,world_z,straight_H,curved_H\n");
        for(int index=0;index<groups.size();index++) {
            Group g=groups.get(index);
            for(int axis=0;axis<2;axis++)for(int j=0;j<=800;j++) {
                double s=-100+200*j/800.0;
                profileRow(csv,"convergence-"+index+(axis==0?"-world-x":"-world-z"),s,
                        g.qx+(axis==0?s:0),g.qz+(axis==1?s:0));
            }
            // Identical world cuts about the declared approximate locator; never invert a folded domain.
        }
        Files.writeString(out.resolve("profiles.csv"),csv);
    }

    private void profileRow(StringBuilder csv,String name,double s,double x,double z) {
        csv.append(name).append(',').append(s).append(',').append(x).append(',').append(z)
                .append(',').append(original.sampleLand(x,z,weights)).append(',')
                .append(candidate.sampleLand(x,z,weights)).append('\n');
    }

    private void verification(Path out) throws Exception {
        CurvedSegmentErosion rebuilt=new CurvedSegmentErosion(erosion,.15);
        CurvedSegmentErosion fine=new CurvedSegmentErosion(erosion,.15,256);
        double error=0;int compared=96;
        for(int i=compared-1;i>=0;i--) {
            int at=Math.floorMod(i*7919+31,a.length);double x=(ox+at%N)*step,z=(oz+at/N)*step;
            double[] q=domain(x,z);double value=curved.eval(q[0],q[1]);
            require(bits(value,rebuilt.eval(q[0],q[1])),"cold reverse rebuild");
            error=Math.max(error,Math.abs(value-fine.eval(q[0],q[1])));
        }
        IntStream.range(0,compared).parallel().forEach(i->{
            int at=Math.floorMod(i*7919+31,a.length);double[] q=domain((ox+at%N)*step,(oz+at/N)*step);
            require(bits(curved.eval(q[0],q[1]),rebuilt.eval(q[0],q[1])),"parallel replay");
        });
        json(out,"checks.json","\"passed\":true,\"originalGridCompared\":"+a.length
                +",\"zeroBendOriginalDifferentBits\":0,\"sameRaw\":true,\"sameNodesAndReceivers\":true"
                +",\"reverseRebuildCompared\":96,\"parallelCompared\":96,\"replayDifferentBits\":0"
                +",\"maxTexture64vs256\":"+error+",\"samePointFinalHeightErrorBound\":"+(.65*MorphCatalog.MOUNTAIN_AMP*error)
                +",\"reference256IsNotExact\":true,\"noLayerInheritance\":true,\"productionWiring\":false");
    }

    private void performance(Path out) throws Exception {
        double[][] times=new double[2][7];double sink=0;int count=256;
        for(int batch=0;batch<10;batch++)for(int arm=0;arm<2;arm++) {
            long start=System.nanoTime();
            for(int i=0;i<count;i++) {
                int at=Math.floorMod(i*7919+31,a.length);double x=(ox+at%N)*step,z=(oz+at/N)*step;
                sink+=(arm==0?original:candidate).sampleLand(x,z,weights);
            }
            if(batch>=3)times[arm][batch-3]=(System.nanoTime()-start)/1e6;
        }
        require(Double.isFinite(sink),"perf sink");Arrays.sort(times[0]);Arrays.sort(times[1]);
        json(out,"performance.json","\"batchPoints\":256,\"warmupBatches\":3,\"measuredBatches\":7"
                +",\"straightWarmMedianMs\":"+times[0][3]+",\"curvedWarmMedianMs\":"+times[1][3]
                +",\"gridMs\":"+gridMs+",\"scope\":\"CPU pure type-space; curve caches versus original uncached evaluator; not real MC chunks\"");
    }

    private void metadata(Path out) throws Exception {
        json(out,"sampling.json","\"size\":769,\"seed\":"+SEED+",\"originWu\":"+point(ox*step,oz*step)
                +",\"stepWu\":"+step+",\"spanWu\":"+(768*step)+",\"HS\":2,\"typeLengthWu\":"+length
                +",\"actualMinecraftWorld\":false,\"baseDefinition\":\"complete ridge4 x scaler4 x original warp; same 2wu outer domain\"");
        json(out,"geometry.json","\"bend\":0.15,\"distanceSegments\":64,\"curve\":\"cubic Bezier with fixed original endpoints\""
                +",\"octaves\":2,\"gridWu\":128,\"amplitudeGain\":"+(double)erosion.amplitude()
                +",\"lacunarity\":"+(double)erosion.lacunarity()+",\"distanceFallOff\":"+(double)erosion.distanceFallOff()
                +",\"blendWeight\":0.65,\"shellAmplitude\":"+MorphCatalog.MOUNTAIN_AMP
                +",\"sourceWindow\":\"unchanged 3x3 source cells per query\",\"independentOctaves\":true"
                +",\"sinkPolicy\":\"shared endpoint remains; no unique downstream tangent or fabricated outlet\""
                +",\"selectionRule\":\"coarse row-major source-cell order; first receiver with >=2 incoming and 101wu inset in each x half; not selected by result\""
                +",\"curveControl\":\"non-sink aligns end tangent to receiver-next chord; sink uses bounded raw midpoint cross-slope offset\""
                +",\"distancePolicy\":\"uniform t polyline approximation, not exact distance; min over original nine sources\""
                +",\"normalConvergenceIsDefect\":false,\"productionEdits\":false,\"noCoarseFineInheritance\":true");
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);require(args.length==1,"usage <NEW data dir>");
        Path out=Path.of(args[0]);require(!Files.exists(out),"refuse existing output");Files.createDirectories(out);
        CurvedSegmentProbe probe=new CurvedSegmentProbe();
        probe.chooseConvergences();probe.grid(out);probe.convergenceMetadata(out);probe.profiles(out);
        probe.verification(out);probe.performance(out);probe.metadata(out);
        System.out.println("CURVE GEOMETRY COMPARISON COMPLETE; normal convergence not a defect; not production");
    }

    private static String point(double x,double z){return Arrays.toString(new double[]{x,z});}
    private static boolean bits(double a,double b){return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    private static void json(Path out,String name,String body)throws Exception{Files.writeString(out.resolve(name),"{"+body+"}\n");}
    private static void write(Path out,String name,double[] values)throws Exception{
        try(DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name+".f64be"))))){
            for(double v:values){require(Double.isFinite(v),"finite output");s.writeDouble(v);}
        }
    }
}
