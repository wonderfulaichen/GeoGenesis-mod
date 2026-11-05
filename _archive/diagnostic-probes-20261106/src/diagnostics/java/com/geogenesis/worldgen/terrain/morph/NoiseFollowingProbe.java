package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion;
import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import com.geogenesis.standalone.NoiseFollowingEvidence;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** S0/S1 fixed four-seed experiment, never a world discovery or production entry point. */
public final class NoiseFollowingProbe {
    private static final long SEED = MorphCatalog.CANONICAL_SEED;
    private static final int N = 769;
    private static final int[][] SEEDS = {{-29,-29},{-24,-29},{-29,-24},{-24,-24}};
    private static final Path BASE = Path.of("build/curved-window-seam-20261103-final/data");
    private final MorphCatalog catalog = new MorphCatalog(SEED);
    private final MorphRecipe mountain = new MorphLandRecipe(catalog.entry(MorphCatalog.IDX_MOUNTAIN));
    private final MorphNoise.Node direction;
    private final CompleteCurveQuery old;
    private final Settings settings = Settings.defaults();
    private final List<Trace> traces = new ArrayList<>();
    private final double length, step, originX, originZ;
    private Cut cut;
    private double traceMs, gridMs, stabilityMax;
    private int replayChecks;

    private NoiseFollowingProbe() throws Exception {
        MorphKernel kernel = new MorphKernel(MorphLandRecipe.all(catalog), SEED, true);
        Field df = MorphKernel.class.getDeclaredField("direction");
        df.setAccessible(true);
        direction = (MorphNoise.Node) df.get(kernel);
        Field ef = MorphKernel.class.getDeclaredField("erosion");
        ef.setAccessible(true);
        old = new CompleteCurveQuery(new CurvedSegmentErosion((MorphNoise.Erosion) ef.get(kernel), .15));
        var warp = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        var body = (MorphNoise.Mul) warp.input();
        var ridge = (MorphNoise.PerlinRidge) body.a();
        var scaler = (MorphNoise.Perlin) ((MorphNoise.AlphaMix) body.b()).input();
        require(ridge.octaves() == 4 && scaler.octaves() == 4, "complete noise octaves");
        length = 1.0 / ridge.frequency();
        step = length / 256;
        originX = ((int) Math.round(-3242.5 / step) - N / 2) * step;
        originZ = ((int) Math.round(-3365.5 / step) - N / 2) * step;
    }

    private double[] domain(double x, double z) {
        double t = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
        return new double[]{x + Math.sin(t) * 2, z + Math.cos(t) * 2};
    }

    private double raw(double x, double z) {
        double[] q = domain(x, z);
        return mountain.raw(q[0], q[1]);
    }

    private double height(double x, double z) {
        double[] q = domain(x, z);
        return mountain.raw(q[0], q[1]) * mountain.amplitude(q[0], q[1])
                + mountain.bias(q[0], q[1]);
    }

    private double oldHeight(double x, double z) {
        double[] q = domain(x, z);
        double source = mountain.raw(q[0], q[1]), texture = old.eval(q[0], q[1]);
        return (texture + (source - texture) * (1 - .65)) * mountain.amplitude(q[0], q[1])
                + mountain.bias(q[0], q[1]);
    }

    private void trace(Path out) throws Exception {
        long start = System.nanoTime();
        for (int[] cell : SEEDS) {
            String id = SEED + ":s1:" + cell[0] + ":" + cell[1];
            traces.add(NoiseFollowingErosion.trace(this::height, id,
                    (cell[0] + .5) * 128, (cell[1] + .5) * 128, settings));
        }
        traceMs = ms(start);
        cut = new Cut(this::height, traces, settings);
        StringBuilder json = new StringBuilder("{\"selectionRule\":\"four predetermined world grid cell centers, pitch128, caller order; no result-based rejection or reselection\",\"traces\":[");
        for (int i = 0; i < traces.size(); i++) {
            Trace t = traces.get(i);
            if (i != 0) json.append(',');
            json.append("{\"id\":\"").append(t.id()).append("\",\"status\":\"").append(t.status())
                    .append("\",\"seedWu\":").append(point(t.points().get(0)))
                    .append(",\"lengthWu\":").append(t.length()).append(",\"traceSamples\":")
                    .append(t.points().size()).append(",\"points\":[");
            for (int j = 0; j < t.points().size(); j++) {
                if (j != 0) json.append(',');
                json.append(point(t.points().get(j)));
            }
            json.append("]}");
            System.out.printf("TRACE %s status=%s length=%.9g vertices=%d%n",
                    t.id(), t.status(), t.length(), t.points().size());
        }
        Files.writeString(out.resolve("traces.json"), json.append("]}\n"));
    }

    private void grid(Path out) throws Exception {
        double[] raw = new double[N*N], carrier = new double[N*N];
        double[] depth = new double[N*N], eroded = new double[N*N];
        double[] referenceRaw = read(BASE.resolve("base-raw.f64be"));
        double[] oldValues = read(BASE.resolve("after-height.f64be"));
        long start = System.nanoTime();
        IntStream.range(0, N).parallel().forEach(row -> {
            for (int col = 0; col < N; col++) {
                int at = row * N + col;
                double x = originX + col * step, z = originZ + row * step;
                // Use the frozen integer-grid arithmetic, not origin + index, to preserve bits.
                x = (((int) Math.round(-3242.5 / step) - N/2) + col) * step;
                z = (((int) Math.round(-3365.5 / step) - N/2) + row) * step;
                raw[at] = raw(x, z);
                carrier[at] = height(x, z);
                require(bits(raw[at], referenceRaw[at]), "frozen raw exact");
                require(bits(carrier[at], raw[at] * MorphCatalog.MOUNTAIN_AMP + 0.0), "shell exact");
                depth[at] = cut.displacement(x, z);
                eroded[at] = cut.height(x, z);
                checkPoint(x, z, carrier[at], eroded[at], depth[at]);
            }
        });
        gridMs = ms(start);
        for (int i = 0; i < 96; i++) {
            int at = Math.floorMod(i * 7919 + 31, raw.length);
            int ox = (int) Math.round(-3242.5 / step) - N/2, oz = (int) Math.round(-3365.5 / step) - N/2;
            require(bits(oldHeight((ox + at%N)*step, (oz + at/N)*step), oldValues[at]), "old source replay");
        }
        write(out, "base-raw", raw); write(out, "carrier-height", carrier);
        write(out, "old-height", oldValues); write(out, "cut-height", eroded); write(out, "cut-depth", depth);
        System.out.printf("GRID %d %.3fms raw/carrier exact, budget valid%n", raw.length, gridMs);
    }

    private void checkPoint(double x, double z, double h0, double h, double d) {
        require(d >= 0 && d <= settings.depth(), "D budget");
        require(h <= h0 && h0-h <= settings.depth()+4*Math.ulp(h0), "subtractive H budget");
        require(bits(h, d == 0 ? h0 : h0-d), "composition");
        require(bits(cut.height(x,z,0),h0), "zero depth exact");
    }

    private void replay(Path out) throws Exception {
        Settings half = new Settings(8, .02/1.5, .5, .0625, 160, .25);
        StringBuilder csv = new StringBuilder("trace,s_wu,coarse_x,coarse_z,half_x,half_z,distance_wu\n");
        for (int i = traces.size()-1; i >= 0; i--) {
            Trace a = traces.get(i); Point seed = a.points().get(0);
            Trace b = NoiseFollowingErosion.trace(this::height,a.id(),seed.x(),seed.z(),settings);
            require(a.equals(b), "reverse cold trace rebuild"); replayChecks++;
            Trace finer = NoiseFollowingErosion.trace(this::height,a.id(),seed.x(),seed.z(),half);
            for (double s = 0; s < Math.min(a.length(),finer.length()); s += .37) {
                Point p = NoiseFollowingErosion.pointAt(a,s), q = NoiseFollowingErosion.pointAt(finer,s);
                double d = Math.hypot(p.x()-q.x(),p.z()-q.z()); stabilityMax = Math.max(stabilityMax,d);
                csv.append(a.id()).append(',').append(s).append(',').append(p.x()).append(',')
                        .append(p.z()).append(',').append(q.x()).append(',').append(q.z()).append(',').append(d).append('\n');
            }
        }
        IntStream.range(0,96).parallel().forEach(i -> {
            double x = originX+(i*17.37)%1700, z = originZ+(i*31.19)%1700;
            Cut reverse = new Cut(this::height, List.of(traces.get(3),traces.get(2),traces.get(1),traces.get(0)),settings);
            require(bits(cut.height(x,z),reverse.height(x,z)), "parallel reverse cut exact");
        });
        Files.writeString(out.resolve("shrink-stability.csv"),csv);
        json(out,"checks.json","\"passed\":true,\"zeroDepthGridChecks\":"+(N*N)
                +",\"reverseColdTraceReplays\":"+replayChecks+",\"parallelReverseCutChecks\":96"
                +",\"rawExact\":true,\"oldDirectReplayChecks\":96,\"halfStepMaxArcDistanceWu\":"+stabilityMax
                +",\"stabilityIsReportedNotAssumed\":true,\"productionWiring\":false");
    }

    private void fixedProfiles(Path out) throws Exception {
        StringBuilder csv = new StringBuilder(Files.readString(out.resolve("profiles.csv")));
        for (int axis = 0; axis < 2; axis++) for (int i = 0; i <= 768; i++) {
            double s = i*step, x = originX+(axis==0?s:384*step),z = originZ+(axis==1?s:384*step);
            csv.append("fixed-center-").append(axis==0?'x':'z').append(',').append(s).append(',')
                    .append(x).append(',').append(z).append(',').append(height(x,z)).append(',')
                    .append(oldHeight(x,z)).append(',').append(cut.height(x,z)).append('\n');
        }
        Files.writeString(out.resolve("profiles.csv"),csv);
    }

    private void metadata(Path out, boolean bedPassed) throws Exception {
        json(out,"sampling.json","\"size\":769,\"seed\":"+SEED+",\"originWu\":["+originX+","+originZ
                +"],\"stepWu\":"+step+",\"spanWu\":"+(768*step)+",\"HS\":2,\"typeLengthWu\":"+length
                +",\"actualMinecraftWorld\":false,\"radiusWu\":8,\"depthCapH\":"+settings.depth()
                +",\"depthCapE\":0.02,\"maxLengthWu\":160,\"maxStepWu\":1,\"minStepWu\":0.0625"
                +",\"gradientStepWu\":0.25,\"mappingAppliedOnce\":true");
        json(out,"discovery-assessment.json","\"stage\":\"S1 STOP\",\"S2Executed\":false,\"worldCoverageProven\":false"
                +",\"fixedDescriptorsOnly\":true,\"localBounds\":\"each complete supplied polyline AABB expanded R8; all four enumerated\""
                +",\"worldSearchRadiusProven\":false,\"reuseOldRadius4\":false,\"bedPassed\":"+bedPassed
                +",\"reason\":\"S2 requires user confirmation; no discovery implementation, no inheritance, no production\"");
    }

    private void performance(Path out) throws Exception {
        double[][] times = new double[2][7]; double sink = 0;
        for (int b = 0; b < 10; b++) for (int arm = 0; arm < 2; arm++) {
            long start = System.nanoTime();
            for (int i = 0; i < 256; i++) {
                Point p = NoiseFollowingErosion.pointAt(traces.get(i%4), (i*.618%1)*traces.get(i%4).length());
                sink += arm == 0 ? height(p.x(),p.z()) : cut.height(p.x(),p.z());
            }
            if (b >= 3) times[arm][b-3] = ms(start);
        }
        require(Double.isFinite(sink),"perf finite"); Arrays.sort(times[0]); Arrays.sort(times[1]);
        json(out,"performance.json","\"batchPoints\":256,\"warmupBatches\":3,\"measuredBatches\":7"
                +",\"carrierWarmMedianMs\":"+times[0][3]+",\"cutWarmMedianMs\":"+times[1][3]
                +",\"fourTraceBuildMs\":"+traceMs+",\"gridMs\":"+gridMs
                +",\"scope\":\"pure Java fixed four-path geometry, samples ON routes; not MC or world discovery throughput\"");
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT); require(args.length==1,"usage NEWdata");
        Path out=Path.of(args[0]); require(!Files.exists(out),"refuse existing data"); Files.createDirectories(out);
        NoiseFollowingProbe p=new NoiseFollowingProbe(); p.trace(out); p.grid(out); p.replay(out);
        boolean passed=NoiseFollowingEvidence.write(out,p::height,p::oldHeight,p.cut,p.traces);
        p.fixedProfiles(out); p.metadata(out,passed); p.performance(out);
        System.out.println("S1 DATA COMPLETE; CONTRACT PASS; finalBedPassed="+passed+"; STOP (not morphological acceptance)");
    }

    private static double[] read(Path file) throws Exception {
        require(Files.size(file)==N*N*8L,"reference file size"); double[] v=new double[N*N];
        try (DataInputStream s=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for(int i=0;i<v.length;i++) v[i]=s.readDouble();
        }
        return v;
    }
    private static void write(Path out,String name,double[] v) throws Exception {
        try (DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name+".f64be"))))) {
            for(double x:v){require(Double.isFinite(x),"finite output");s.writeDouble(x);}
        }
    }
    private static String point(Point p){return "["+p.x()+","+p.z()+"]";}
    private static void json(Path out,String name,String body) throws Exception {Files.writeString(out.resolve(name),"{"+body+"}\n");}
    private static boolean bits(double a,double b){return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
    private static double ms(long start){return (System.nanoTime()-start)/1e6;}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
