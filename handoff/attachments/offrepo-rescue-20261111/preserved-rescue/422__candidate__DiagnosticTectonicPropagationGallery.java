package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.TectonicDeformation;
import com.geogenesis.worldgen.terrain.TectonicField;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** R4驱动/原消费者实数导出，不生成地形高度、不运行Minecraft。 */
public final class DiagnosticTectonicPropagationGallery {
    private static final long SEED = 9139912035078620160L;
    private static final int N = 701;
    private static final double X0 = -3642.5, Z0 = -3365.5, STEP = 10;
    private static final String[] CHANNELS = {"stress", "distance", "envelope110", "convergent110", "divergent130", "offset"};
    private static volatile double benchmarkSink;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("newOutputDirectory passedPropagationCheckDirectory");
        String prerequisite = Files.readString(Path.of(args[1]).resolve("check.txt"));
        if (!prerequisite.startsWith("PASS")) throw new IllegalArgumentException("R3 must PASS before gallery");
        Path out = Path.of(args[0]); Files.createDirectory(out);
        export(out); profiles(out); benchmark(out);
        Files.writeString(out.resolve("meta.json"), "{\"seed\":\""+SEED+"\",\"size\":701,\"step\":10,"
                + "\"origin\":[-3642.5,-3365.5],\"center\":[-140,137],\"span\":7000,\"micro\":600,\"macro\":1200,"
                + "\"bandTotal\":240,\"landThreshold\":0.30,\"heightChainConnected\":false,\"productionChanged\":false,"
                + "\"driverVersion\":\""+DiagnosticTectonicDriver.VERSION+"\",\"cache\":\"none\","
                + "\"A\":\"original driver and original consumers\",\"B\":\"candidate S/D adapted to original consumers\","
                + "\"units\":{\"stress\":\"dimensionless\",\"distance\":\"wu proxy\",\"offset\":\"e offset, NOT final e/Y\"}}");
        System.out.println("PROPAGATION GALLERY PASS");
    }

    private static void export(Path out) throws Exception {
        var p = new DiagnosticTectonicPropagation(SEED);
        long start = System.nanoTime();
        try (var a = new Arm(out.resolve("arm-before")); var b = new Arm(out.resolve("arm-after"))) {
            for (int z = 0; z < N; z++) {
                for (int x = 0; x < N; x++) {
                    var pair = p.sample(X0 + x * STEP, Z0 + z * STEP);
                    a.write(pair.before()); b.write(pair.after());
                }
                if (z % 100 == 0) System.out.println("R4 row="+z);
            }
        }
        Files.writeString(out.resolve("sampling.txt"), "samples="+(N*N)+"\nexportNanos="+(System.nanoTime()-start)
                + "\nnoCache=true\nnotTerrainOrFPS=true\n");
    }

    private static void profiles(Path out) throws Exception {
        StringBuilder meta = new StringBuilder("{"); boolean first = true;
        try (var writer = Files.newBufferedWriter(out.resolve("profiles.csv"))) {
            writer.write("line,x,z,distance");
            for (String channel : CHANNELS) writer.write(",a_"+channel+",b_"+channel);
            writer.newLine();
            var p = new DiagnosticTectonicPropagation(SEED);
            for (int k = 0; k < 6; k++) {
                int row = (int)((k+.5)/6*N); String id = "full-"+row;
                if (!first) meta.append(','); first = false;
                appendMeta(meta, id, SEED, X0, Z0+row*STEP, 7001);
                line(writer, p, id, X0, Z0+row*STEP, 7001);
            }
            long[] seeds = {SEED,-419,12345}; String[] ids={"focus-site","focus-window","focus-bisector"};
            double[] cx={408.1912317710077,-2010.7180242616168,975.0845618990552};
            double[] cz={888.4415715833302,-1738.660769782364,1821.8342492225638};
            for(int i=0;i<ids.length;i++) {
                meta.append(','); appendMeta(meta,ids[i],seeds[i],cx[i]-300,cz[i],601);
                line(writer,new DiagnosticTectonicPropagation(seeds[i]),ids[i],cx[i]-300,cz[i],601);
            }
        }
        meta.append('}'); Files.writeString(out.resolve("profiles-meta.json"),meta.toString());
    }

    private static void appendMeta(StringBuilder b,String id,long seed,double x,double z,int count) {
        b.append('"').append(id).append("\":{\"seed\":\"").append(seed).append("\",\"x0\":").append(x)
                .append(",\"z\":").append(z).append(",\"count\":").append(count).append(",\"step\":1}");
    }

    private static void line(BufferedWriter w,DiagnosticTectonicPropagation p,String id,double x0,double z,int n) throws Exception {
        for(int i=0;i<n;i++) {
            var pair=p.sample(x0+i,z); double[] a=pair.before().array(),b=pair.after().array();
            w.write(id+","+(x0+i)+","+z+","+i);
            for(int j=0;j<a.length;j++) w.write(","+a[j]+","+b[j]);
            w.newLine();
        }
    }

    private static void benchmark(Path out) throws Exception {
        double[][] points = new double[128][2];
        for(int i=0;i<points.length;i++) { points[i][0]=X0+(i*181%7001); points[i][1]=Z0+(i*313%7001); }
        StringBuilder b=new StringBuilder("{\"points\":128,\"warmupRounds\":2,\"measuredRounds\":5,\"cache\":\"none\","
                +"\"units\":\"nanoseconds per query\",\"notFPS\":true,\"candidateSites\":36,\"distanceCoefficients\":16,\"results\":{");
        String[] names={"oldDriver","candidateDriver","oldConsumers","candidateConsumers","pairedAdapter"};
        for(int mode=0;mode<names.length;mode++) {
            if(mode>0)b.append(','); long[] costs=measure(points,mode);
            Arrays.sort(costs); long sum=0; for(long v:costs)sum+=v;
            b.append('"').append(names[mode]).append("\":{\"queries\":").append(costs.length)
                    .append(",\"mean\":").append((double)sum/costs.length).append(",\"p50\":").append(costs[costs.length/2])
                    .append(",\"p95\":").append(costs[(int)(.95*(costs.length-1))]).append(",\"max\":").append(costs[costs.length-1]).append('}');
        }
        b.append("},\"checksum\":").append(benchmarkSink).append(",\"limits\":\"fixed finite points; all measured output fields consumed; sequential microbenchmark, allocations and JIT included; no cache, no cache warm/cold comparison\"}");
        Files.writeString(out.resolve("cost.json"),b.toString());
    }

    private static long[] measure(double[][] points,int mode) {
        var old=new TectonicField(SEED); var driver=new DiagnosticTectonicDriver(SEED);
        var deformation=new TectonicDeformation(SEED); var paired=new DiagnosticTectonicPropagation(SEED);
        TectonicField.Sample[] a=new TectonicField.Sample[points.length],b=new TectonicField.Sample[points.length];
        for(int i=0;i<points.length;i++) {
            a[i]=old.sample(points[i][0],points[i][1]);var s=driver.sample(points[i][0],points[i][1]);
            b[i]=DiagnosticTectonicPropagation.adapt(a[i],s.stress(),s.distance());
        }
        long[] times=new long[5*points.length];double checksum=0;
        for(int round=-2;round<5;round++) for(int i=0;i<points.length;i++) {
            double x=points[i][0],z=points[i][1]; long start=System.nanoTime();
            checksum+=switch(mode) {
                case 0 -> oldChecksum(old.sample(x,z));
                case 1 -> candidateChecksum(driver.sample(x,z));
                case 2 -> valuesChecksum(DiagnosticTectonicPropagation.consume(a[i],deformation,x,z));
                case 3 -> valuesChecksum(DiagnosticTectonicPropagation.consume(b[i],deformation,x,z));
                default -> pairChecksum(paired.sample(x,z));
            };
            if(round>=0)times[round*points.length+i]=System.nanoTime()-start;
        }
        if(!Double.isFinite(checksum))throw new AssertionError("benchmark checksum");
        benchmarkSink = checksum;
        return times;
    }

    private static double oldChecksum(TectonicField.Sample s) {
        return s.dist()+s.stress()+s.btype()+s.rate()+s.tangentX()+s.tangentZ()+s.alongCoord();
    }
    private static double candidateChecksum(DiagnosticTectonicDriver.Snapshot s) {
        return s.stressRaw()+s.stress()+s.stressX()+s.stressZ()+s.distance()+s.distanceX()+s.distanceZ();
    }
    private static double valuesChecksum(DiagnosticTectonicPropagation.Values v) {
        return v.stress()+v.distance()+v.envelope110()+v.convergent110()+v.divergent130()+v.offset();
    }
    private static double pairChecksum(DiagnosticTectonicPropagation.Pair p) {
        return valuesChecksum(p.before())+valuesChecksum(p.after());
    }

    private static final class Arm implements AutoCloseable {
        private final DataOutputStream[] streams = new DataOutputStream[CHANNELS.length];
        Arm(Path out) throws Exception {
            Files.createDirectory(out);
            for(int i=0;i<streams.length;i++) streams[i]=new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(out.resolve(CHANNELS[i]+".f64be")),1<<16));
        }
        void write(DiagnosticTectonicPropagation.Values values) throws Exception {
            double[] v=values.array();
            for(int i=0;i<v.length;i++) { if(!Double.isFinite(v[i]))throw new AssertionError("nonfinite "+CHANNELS[i]);streams[i].writeDouble(v[i]); }
        }
        public void close() throws Exception { for(var stream:streams)if(stream!=null)stream.close(); }
    }
}
