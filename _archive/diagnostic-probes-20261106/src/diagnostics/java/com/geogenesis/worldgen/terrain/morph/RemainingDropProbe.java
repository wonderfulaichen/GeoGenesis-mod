package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion;
import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import com.geogenesis.standalone.SharedDepthCheck;
import com.geogenesis.standalone.SharedDepthField;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.IntStream;

/** Fixed S1 export/evaluate bridge; solver, independent statistics and renderer belong to Python.
 * Only new directories are writable. No production wiring, discovery, or effect acceptance.
 * Run from mdk; uses original NoiseFollowingProbe reflectively without copying/changing it.
 */
public final class RemainingDropProbe {
    private static final Path FROZEN = Path.of("build/noise-following-subtractive-20261103-final/data");
    private static final int N = 769;
    private static final String HEADER = "kind,trace,s_wu,world_x,world_z,H0,Dold";
    private static final String EVAL_HEADER = HEADER + ",W,Dnew,Hnew";
    private static final double DEDUP = 1e-8;
    private static final Settings SETTINGS = new Settings(8,.02/1.5,1,.0625,160,.25);
    private final Object original;
    private final Class<?> sourceClass;
    private final HeightField h0;
    private final double step;
    private final int ox, oz;
    private List<Trace> traces;
    private Cut old;
    private long dedupRemoved, candidates, sampleRows, spatialRows;
    private double traceMs, gridMs;

    private RemainingDropProbe() throws Exception {
        sourceClass = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
        Constructor<?> constructor = sourceClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        original = constructor.newInstance();
        Method method = sourceClass.getDeclaredMethod("height",double.class,double.class);
        method.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(original);
        h0 = (x,z) -> invokeHeight(handle,x,z);
        step = (double) value("step");
        require(SETTINGS.equals(value("settings")),"frozen R/depth/step/gradient settings");
        ox = (int)Math.round(-3242.5/step)-N/2;
        oz = (int)Math.round(-3365.5/step)-N/2;
    }

    private static double invokeHeight(MethodHandle handle,double x,double z) {
        try { return (double)handle.invokeExact(x,z); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Original full H0 callback",failure); }
    }

    private Object value(String name) throws Exception {
        Field field = sourceClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(original);
    }

    @SuppressWarnings("unchecked")
    private void rebuild(Path out) throws Exception {
        long start = System.nanoTime();
        Method trace = sourceClass.getDeclaredMethod("trace",Path.class);
        trace.setAccessible(true);
        trace.invoke(original,out); // Original JSON formatting, IDs and point order preserved.
        traces = List.copyOf((List<Trace>)value("traces"));
        require(traces.size() == 4,"four frozen descriptors");
        old = new Cut(h0,traces,SETTINGS);
        traceMs = ms(start);
        require(Files.mismatch(out.resolve("traces.json"),FROZEN.resolve("traces.json")) == -1,
                "Frozen traces.json byte/point/order mismatch");
        try (BufferedWriter csv = writer(out,"rebuilt-traces.csv")) {
            row(csv,"trace","vertex","s_wu","world_x","world_z","x_bits","z_bits","s_bits");
            for (Trace t : traces) for (int i = 0; i < t.points().size(); i++) {
                Point p = t.points().get(i);
                row(csv,t.id(),i,t.arc().get(i),p.x(),p.z(),hex(p.x()),hex(p.z()),hex(t.arc().get(i)));
            }
        }
    }

    private void export(Path out) throws Exception {
        rebuild(out);
        copyFrozen(out);
        try (BufferedWriter csv = writer(out,"samples.csv");
             BufferedWriter counts = writer(out,"dedup-counts.csv")) {
            csv.write(HEADER); csv.newLine();
            row(counts,"kind","trace","candidate_count","kept_count","dedup_count","tolerance_wu");
            for (String kind : List.of("train","validation")) for (Trace trace : traces) {
                ArcSamples samples = along(trace,kind.equals("train") ? .037 : .0137,
                        kind.equals("train") ? .37 : .43);
                for (double s : samples.positions()) {
                    Point p = NoiseFollowingErosion.pointAt(trace,s);
                    sample(csv,kind,trace.id(),s,p.x(),p.z(),null);
                }
                row(counts,kind,trace.id(),samples.candidates(),samples.positions().size(),
                        samples.candidates()-samples.positions().size(),DEDUP);
                candidates += samples.candidates();
                dedupRemoved += samples.candidates()-samples.positions().size();
                sampleRows += samples.positions().size();
            }
        }
        spatial(out);
        basisContract(out);
        int assertions = SharedDepthCheck.verify();
        int replay = replay(new SharedDepthField(h0,old,Map.of()));
        metadata(out,"export",assertions,replay);
        performance(out,new SharedDepthField(h0,old,Map.of()));
        System.out.println("EXPORT COMPLETE " + out + "; fixed H0/traces only; no candidate effect acceptance");
    }

    private record ArcCandidate(double s,int priority) {}
    private record ArcSamples(List<Double> positions,int candidates) {}

    private static ArcSamples along(Trace trace,double spacing,double offset) {
        List<ArcCandidate> all = new ArrayList<>();
        for (double s : trace.arc()) all.add(new ArcCandidate(s,0));
        all.add(new ArcCandidate(0,0)); all.add(new ArcCandidate(trace.length(),0));
        all.add(new ArcCandidate(Math.min(8,trace.length()),1));
        all.add(new ArcCandidate(Math.max(0,trace.length()-8),1));
        for (long i = 0; (i+offset)*spacing < trace.length(); i++) {
            all.add(new ArcCandidate((i+offset)*spacing,2));
        }
        fadeBoundaries(trace,trace.points().get(0),all);
        fadeBoundaries(trace,trace.points().get(trace.points().size()-1),all);
        // Keep vertices/endpoints first, fades second, shifted grid last. Test both neighbors
        // against the retained set, so near-duplicate chains cannot leave <1e-8 spacing.
        all.sort(Comparator.comparingInt(ArcCandidate::priority).thenComparingDouble(ArcCandidate::s));
        TreeSet<Double> positions = new TreeSet<>();
        for (ArcCandidate candidate : all) {
            double s = candidate.s();
            Double left = positions.floor(s), right = positions.ceiling(s);
            if (left != null && s-left <= DEDUP) continue;
            if (right != null && right-s <= DEDUP) continue;
            positions.add(s);
        }
        return new ArcSamples(List.copyOf(positions),all.size());
    }

    /** Same endpoint Euclidean-circle intersections as old NoiseFollowingEvidence. */
    private static void fadeBoundaries(Trace trace,Point end,List<ArcCandidate> positions) {
        for (int i = 1; i < trace.points().size(); i++) {
            Point a = trace.points().get(i-1), b = trace.points().get(i);
            double dx = b.x()-a.x(), dz = b.z()-a.z();
            double ex = a.x()-end.x(), ez = a.z()-end.z();
            double aa = dx*dx+dz*dz, bb = 2*(ex*dx+ez*dz), cc = ex*ex+ez*ez-64;
            double discriminant = bb*bb-4*aa*cc;
            if (!(aa > 0) || discriminant < 0) continue;
            double root = Math.sqrt(discriminant);
            addRoot(trace,positions,i,(-bb-root)/(2*aa));
            addRoot(trace,positions,i,(-bb+root)/(2*aa));
        }
    }

    private static void addRoot(Trace trace,List<ArcCandidate> positions,int i,double t) {
        if (Double.isFinite(t) && t >= 0 && t <= 1) {
            double s = trace.arc().get(i-1)+t*(trace.arc().get(i)-trace.arc().get(i-1));
            positions.add(new ArcCandidate(Math.max(0,Math.min(trace.length(),s)),1));
        }
    }

    private void spatial(Path out) throws Exception {
        double[] b = traceBounds();
        try (BufferedWriter csv = writer(out,"spatial.csv")) {
            csv.write(HEADER); csv.newLine();
            for (long iz = (long)Math.floor(b[1]/4); iz <= (long)Math.ceil(b[3]/4); iz++) {
                for (long ix = (long)Math.floor(b[0]/4); ix <= (long)Math.ceil(b[2]/4); ix++) {
                    double x = ix*4.0, z = iz*4.0;
                    if (old.displacement(x,z) > 0) sample(csv,"spatial","space",spatialRows++,x,z,null);
                }
            }
        }
    }

    private double[] traceBounds() {
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (Trace t : traces) for (Point p : t.points()) {
            minX = Math.min(minX,p.x()); minZ = Math.min(minZ,p.z());
            maxX = Math.max(maxX,p.x()); maxZ = Math.max(maxZ,p.z());
        }
        return new double[] {minX-8,minZ-8,maxX+8,maxZ+8};
    }

    private void sample(BufferedWriter csv,String kind,String trace,double s,double x,double z,
                        SharedDepthField field) throws IOException {
        double baseline = h0.height(x,z), d = old.displacement(x,z);
        require(Double.isFinite(baseline) && d >= 0 && d <= SETTINGS.depth(),"sample input contract");
        if (field == null) row(csv,kind,trace,s,x,z,baseline,d);
        else {
            double w = field.weight(x,z), dn = field.displacement(x,z), hn = field.height(x,z);
            require(w >= 0 && w <= 1 && dn >= 0 && dn <= d,"sample subtractive contract");
            require(bits(hn,dn == 0 ? baseline : baseline-dn),"direct sample H composition");
            row(csv,kind,trace,s,x,z,baseline,d,w,dn,hn);
        }
    }

    private static void copyFrozen(Path out) throws IOException {
        for (String[] pair : new String[][] {{"base-raw","base-raw"},
                {"carrier-height","carrier-height"},{"cut-height","old-height"},
                {"cut-depth","old-depth"}}) {
            Path source = FROZEN.resolve(pair[0]+".f64be");
            require(Files.size(source) == N*N*8L,"frozen array size " + source);
            Files.copy(source,out.resolve(pair[1]+".f64be"));
        }
    }

    private static void basisContract(Path out) throws Exception {
        json(out,"basis-contract.json","\"spacingWu\":4,\"worldOrigin\":[0,0],"
                + "\"floor\":\"floor(world/4), including negative coordinates\","
                + "\"indices\":\"cx=floor(x/4)-1+i, cz=floor(z/4)-1+j; i,j=0..3\","
                + "\"order\":\"j outer, i inner\",\"defaultCoefficient\":1,"
                + "\"basis\":[\"(1-t)^3/6\",\"(3*t^3-6*t^2+4)/6\","
                + "\"(-3*t^3+3*t^2+3*t+1)/6\",\"t^3/6\"],"
                + "\"tensor\":\"bx[i]*bz[j]\",\"queryNormalization\":false,"
                + "\"rangeClamping\":false,\"evaluation\":\"convex de Boor, constants 0/1 exact; "
                + "polynomial dot product may differ a few ULP\",\"supportRadiusCells\":2");
        try (BufferedWriter csv = writer(out,"basis-check.csv")) {
            row(csv,"world_x","world_z","cx","cz","basis");
            for (double[] p : new double[][] {{0,0},{-4,-8},{-4.0000001,.137},
                    {-3.9999999,-.137},{-3660.0137,-3659.43},{7.9999999,8.0000001}}) {
                for (var b : SharedDepthField.basis(p[0],p[1])) {
                    row(csv,p[0],p[1],b.cell().cx(),b.cell().cz(),b.value());
                }
            }
        }
    }

    private void evaluate(Path data,Path weights,Path out) throws Exception {
        require(!out.equals(data.toAbsolutePath().normalize()),"evaluate cannot replace export");
        rebuild(out);
        require(Files.mismatch(out.resolve("traces.json"),data.resolve("traces.json")) == -1,
                "export descriptor mismatch");
        SharedDepthField field = new SharedDepthField(h0,old,SharedDepthField.readWeights(weights));
        Files.copy(weights,out.resolve("weights.csv"));
        Files.copy(data.resolve("dedup-counts.csv"),out.resolve("dedup-counts.csv"));
        for (Trace trace : traces) for (String kind : List.of("train","validation")) {
            ArcSamples samples = along(trace,kind.equals("train") ? .037 : .0137,
                    kind.equals("train") ? .37 : .43);
            candidates += samples.candidates();
            dedupRemoved += samples.candidates()-samples.positions().size();
        }
        resample(data.resolve("samples.csv"),out,"eval-samples.csv",field);
        resample(data.resolve("spatial.csv"),out,"eval-spatial.csv",field);
        grid(data,out,field);
        profiles(out,field);
        crosses(out,field);
        endpointGrid(out,field);
        basisContract(out);
        int assertions = SharedDepthCheck.verify();
        int replay = replay(field);
        metadata(out,"evaluate",assertions,replay);
        performance(out,field);
        System.out.println("EVALUATE COMPLETE " + out + "; contract pass; effect NOT assumed; S1 STOP");
    }

    private void resample(Path input,Path out,String name,SharedDepthField field) throws Exception {
        try (BufferedReader in = Files.newBufferedReader(input,StandardCharsets.UTF_8);
             BufferedWriter csv = writer(out,name)) {
            require(HEADER.equals(in.readLine()),"export sample header");
            csv.write(EVAL_HEADER); csv.newLine();
            String line;
            while ((line = in.readLine()) != null) {
                String[] c = line.split(",",-1);
                require(c.length == 7,"sample row");
                double x = Double.parseDouble(c[3]), z = Double.parseDouble(c[4]);
                require(bits(Double.parseDouble(c[5]),h0.height(x,z)),"frozen H0 sample bits");
                require(bits(Double.parseDouble(c[6]),old.displacement(x,z)),"frozen Dold sample bits");
                sample(csv,c[0],c[1],Double.parseDouble(c[2]),x,z,field);
                if (c[0].equals("spatial")) spatialRows++; else sampleRows++;
            }
        }
    }

    private void grid(Path data,Path out,SharedDepthField field) throws Exception {
        for (String name : List.of("carrier-height","old-height","old-depth","base-raw")) {
            Path input = data.resolve(name+".f64be");
            require(Files.size(input) == N*N*8L,"export array size");
            String frozenName = name.equals("old-height") ? "cut-height" :
                    name.equals("old-depth") ? "cut-depth" : name;
            require(Files.mismatch(input,FROZEN.resolve(frozenName+".f64be")) == -1,"frozen array bits");
            Files.copy(input,out.resolve(name+".f64be"));
        }
        double[] carrier = read(data.resolve("carrier-height.f64be"));
        double[] oldDepth = read(data.resolve("old-depth.f64be"));
        double[] hn = new double[N*N], dn = new double[N*N], w = new double[N*N];
        long start = System.nanoTime();
        IntStream.range(0,N).parallel().forEach(row -> {
            for (int col = 0; col < N; col++) {
                int at = row*N+col;
                // MUST multiply the integer world-grid index; origin+index*step changes bits.
                double x = (ox+col)*step, z = (oz+row)*step;
                w[at] = field.weight(x,z);
                dn[at] = field.displacement(x,z);
                hn[at] = field.height(x,z);
                require(bits(carrier[at],h0.height(x,z)),"original H0 full-grid bits");
                require(bits(oldDepth[at],old.displacement(x,z)),"old D full-grid bits");
                require(w[at] >= 0 && w[at] <= 1 && dn[at] >= 0 && dn[at] <= oldDepth[at],"grid D/W range");
                require(bits(hn[at],dn[at] == 0 ? carrier[at] : carrier[at]-dn[at]),"frozen carrier composition");
            }
        });
        gridMs = ms(start);
        writeArray(out,"new-height",hn); writeArray(out,"new-depth",dn); writeArray(out,"weight",w);
    }

    private void profiles(Path out,SharedDepthField field) throws Exception {
        try (BufferedWriter csv = writer(out,"profiles.csv")) {
            row(csv,"profile","s_wu","world_x","world_z","carrier_H","old_H","cut_H");
            for (Trace trace : traces) for (double s : along(trace,.0137,.43).positions()) {
                Point p = NoiseFollowingErosion.pointAt(trace,s);
                profileRow(csv,trace.id(),s,p.x(),p.z(),field);
            }
            for (int axis = 0; axis < 2; axis++) for (int i = 0; i < N; i++) {
                double x = (ox+(axis == 0 ? i : N/2))*step;
                double z = (oz+(axis == 1 ? i : N/2))*step;
                profileRow(csv,"fixed-center-"+(axis == 0 ? "x" : "z"),i*step,x,z,field);
            }
        }
    }

    private void profileRow(BufferedWriter csv,String id,double s,double x,double z,
                            SharedDepthField field) throws IOException {
        row(csv,id,s,x,z,h0.height(x,z),old.height(x,z),field.height(x,z));
    }

    private record Frame(double nx,double nz,double turn,boolean defined,boolean corner,String kind) {}

    private static Frame frame(Trace trace,double s) {
        double leftS = Math.max(0,s-.02), rightS = Math.min(trace.length(),s+.02);
        Point a = NoiseFollowingErosion.pointAt(trace,leftS);
        Point p = NoiseFollowingErosion.pointAt(trace,s);
        Point b = NoiseFollowingErosion.pointAt(trace,rightS);
        double dx = b.x()-a.x(), dz = b.z()-a.z(), norm = Math.hypot(dx,dz);
        if (!(norm > 0)) return new Frame(0,0,0,false,false,"DEGENERATE_NO_NORMAL");
        double ln = Math.hypot(p.x()-a.x(),p.z()-a.z());
        double rn = Math.hypot(b.x()-p.x(),b.z()-p.z());
        double turn = ln > 0 && rn > 0 ? Math.atan2((p.x()-a.x())*(b.z()-p.z())
                -(p.z()-a.z())*(b.x()-p.x()),(p.x()-a.x())*(b.x()-p.x())
                +(p.z()-a.z())*(b.z()-p.z())) : 0;
        boolean corner = false, spans = false;
        for (int i = 1; i+1 < trace.points().size(); i++) {
            double arc = trace.arc().get(i);
            if (Math.abs(arc-s) > .02) continue;
            Point aa = trace.points().get(i-1), bb = trace.points().get(i), cc = trace.points().get(i+1);
            double l = trace.arc().get(i)-trace.arc().get(i-1);
            double r = trace.arc().get(i+1)-trace.arc().get(i);
            boolean turns = Math.hypot((bb.x()-aa.x())/l-(cc.x()-bb.x())/r,
                    (bb.z()-aa.z())/l-(cc.z()-bb.z())/r) > 1e-10;
            spans |= turns;
            corner |= turns && Math.abs(arc-s) <= 8*Math.ulp(Math.max(1,s));
        }
        String kind = corner ? "CORNER_SAMPLE_NONUNIQUE_NORMAL" : spans ? "SECANT_SPANS_CORNER" :
                s <= 0 || s >= trace.length() ? "ENDPOINT_ONE_SIDED" : "POLYLINE_ARC_SECANT";
        return new Frame(-dz/norm,dx/norm,turn,true,corner,kind);
    }

    private void crosses(Path out,SharedDepthField field) throws Exception {
        try (BufferedWriter csv = writer(out,"cross-sections.csv");
             BufferedWriter normals = writer(out,"normal-checks.csv")) {
            row(csv,"trace","s_wu","offset_wu","world_x","world_z","carrier_H","old_H","cut_H");
            row(normals,"trace","s_wu","normal_kind","normal_x","normal_z","defined","eligible",
                    "turn_radians","secant_delta_wu");
            for (Trace trace : traces) {
                TreeSet<Double> stations = new TreeSet<>();
                for (double f : new double[] {.1,.25,.5,.75,.9}) stations.add(f*trace.length());
                stations.add(Math.max(0,trace.length()-4));
                for (double s : stations) {
                    Frame frame = frame(trace,s);
                    Point p = NoiseFollowingErosion.pointAt(trace,s);
                    row(normals,trace.id(),s,frame.kind(),frame.nx(),frame.nz(),frame.defined(),
                            frame.defined() && !frame.corner() && s > 0 && s < trace.length(),frame.turn(),.02);
                    for (int i = frame.defined() ? -96 : 0; i <= (frame.defined() ? 96 : 0); i++) {
                        double offset = i*.125, x = p.x()+offset*frame.nx(), z = p.z()+offset*frame.nz();
                        row(csv,trace.id(),s,offset,x,z,h0.height(x,z),old.height(x,z),field.height(x,z));
                    }
                }
            }
        }
    }

    private void endpointGrid(Path out,SharedDepthField field) throws Exception {
        try (BufferedWriter csv = writer(out,"endpoint-grid.csv")) {
            row(csv,"trace","world_x","world_z","H0","Dold","W","Dnew","Hnew");
            for (Trace trace : traces) {
                Point p = trace.points().get(trace.points().size()-1);
                for (int iz = -48; iz <= 48; iz++) for (int ix = -48; ix <= 48; ix++) {
                    double x = p.x()+ix*.25, z = p.z()+iz*.25;
                    row(csv,trace.id(),x,z,h0.height(x,z),old.displacement(x,z),field.weight(x,z),
                            field.displacement(x,z),field.height(x,z));
                }
            }
        }
    }

    private int replay(SharedDepthField field) throws Exception {
        RemainingDropProbe cold = new RemainingDropProbe();
        List<Trace> reverse = new ArrayList<>();
        for (int i = traces.size()-1; i >= 0; i--) {
            Trace t = traces.get(i);
            Point seed = t.points().get(0);
            Trace rebuilt = NoiseFollowingErosion.trace(cold.h0,t.id(),seed.x(),seed.z(),SETTINGS);
            require(rebuilt.equals(t),"reverse cold original full-H0 trace");
            reverse.add(rebuilt);
        }
        SharedDepthField reversed = new SharedDepthField(cold.h0,new Cut(cold.h0,reverse,SETTINGS),
                field.coefficients());
        Point[] queries = onRoute();
        double[] heights = new double[queries.length], depths = new double[queries.length];
        for (int i = 0; i < queries.length; i++) {
            Point p = queries[i];
            heights[i] = field.height(p.x(),p.z()); depths[i] = field.displacement(p.x(),p.z());
        }
        for (int i = queries.length-1; i >= 0; i--) {
            Point p = queries[i];
            require(bits(heights[i],reversed.height(p.x(),p.z())),"reverse cold height bits");
            require(bits(depths[i],reversed.displacement(p.x(),p.z())),"reverse cold depth bits");
            require(bits(field.weight(p.x(),p.z()),reversed.weight(p.x(),p.z())),"reverse weight bits");
        }
        IntStream.range(0,queries.length).parallel().forEach(i -> {
            Point p = queries[i];
            require(bits(heights[i],reversed.height(p.x(),p.z())),"parallel cold query bits");
            require(bits(depths[i],reversed.displacement(p.x(),p.z())),"parallel depth bits");
        });
        return queries.length;
    }

    private Point[] onRoute() {
        Point[] points = new Point[256];
        for (int i = 0; i < points.length; i++) {
            Trace t = traces.get(i%4);
            points[i] = NoiseFollowingErosion.pointAt(t,(i*.618%1)*t.length());
        }
        return points;
    }

    private void performance(Path out,SharedDepthField field) throws Exception {
        Point[] points = onRoute();
        double[][] times = new double[3][7];
        double sink = 0;
        for (int batch = 0; batch < 10; batch++) for (int arm = 0; arm < 3; arm++) {
            long start = System.nanoTime();
            for (Point p : points) sink += arm == 0 ? h0.height(p.x(),p.z()) :
                    arm == 1 ? old.height(p.x(),p.z()) : field.height(p.x(),p.z());
            if (batch >= 3) times[arm][batch-3] = ms(start);
        }
        require(Double.isFinite(sink),"performance sink");
        for (double[] arm : times) Arrays.sort(arm);
        json(out,"performance.json","\"batchPoints\":256,\"warmupBatches\":3,\"measuredBatches\":7,"
                + "\"carrierWarmMedianMs\":"+times[0][3]+",\"oldCutWarmMedianMs\":"+times[1][3]
                + ",\"newWarmMedianMs\":"+times[2][3]+",\"fourTraceBuildMs\":"+traceMs
                + ",\"gridMs\":"+gridMs+",\"scope\":\"same 256 on-route direct callbacks; "
                + "fixed arm order, not a robust microbenchmark, MC throughput or world discovery\"");
    }

    private void metadata(Path out,String mode,int assertions,int replay) throws Exception {
        String frozenSampling = Files.readString(FROZEN.resolve("sampling.json"),StandardCharsets.UTF_8).trim();
        require(frozenSampling.startsWith("{") && frozenSampling.endsWith("}"),"frozen sampling object");
        String body = frozenSampling.substring(1,frozenSampling.length()-1);
        json(out,"sampling.json",body+",\"mode\":\""+mode+"\",\"basisSpacingWu\":4,"
                + "\"gridIntegerOrigin\":["+ox+","+oz+"],\"gridArithmetic\":\"(integerOrigin+index)*step\","
                + "\"dtype\":\">f8\",\"arrayOrder\":\"row=z,col=x\",\"trainSpacingWu\":0.037,"
                + "\"trainOffsetFraction\":0.37,\"validationSpacingWu\":0.0137,"
                + "\"validationOffsetFraction\":0.43,\"arcDedupWu\":1e-8,\"spatialPitchWu\":4,"
                + "\"spatialOrigin\":[0,0],\"oldHeightMeaning\":\"frozen failed cut-height, not legacy texture\"");
        json(out,"checks.json","\"contractPassed\":true,\"syntheticAssertions\":"+assertions
                + ",\"traceJsonBytesExact\":true,\"traceJsonSha256\":\""+sha(out.resolve("traces.json"))+"\","
                + "\"reverseColdTraceReplays\":4,\"reverseColdQueries\":"+replay
                + ",\"parallelColdQueries\":"+replay+",\"sampleRows\":"+sampleRows
                + ",\"spatialRows\":"+spatialRows+",\"arcCandidates\":"+candidates
                + ",\"arcDedupRemoved\":"+dedupRemoved+",\"productionWiring\":false,"
                + "\"finiteSamplesOnly\":true,\"effectAccepted\":false,\"globalPassed\":false");
        json(out,"report.json","\"stage\":\"S1 STOP\",\"mode\":\""+mode+"\","
                + "\"effectStatus\":\"NOT_ASSUMED; independent Python statistics and rendering required\","
                + "\"scope\":\"four frozen descriptors only; no outlet, sink, between-sample or world proof\","
                + "\"composition\":\"Dnew=Dold*shared_C2_W, Hnew=H0-Dnew; no nearest-depth interpolation\","
                + "\"baselineSource\":\""+FROZEN.toString().replace('\\','/')+"\","
                + "\"fullGridContractChecked\":"+mode.equals("evaluate")+",\"arraysCopiedNeverApproximated\":true");
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        require(args.length >= 2,"usage: export NEWdata | evaluate EXISTdata weights.csv [NEWevaluated]");
        if (args[0].equals("export")) {
            require(args.length == 2,"export NEWdata");
            Path out = fresh(Path.of(args[1]));
            new RemainingDropProbe().export(out);
        } else if (args[0].equals("evaluate")) {
            require(args.length == 3 || args.length == 4,"evaluate EXISTdata weights.csv [NEWevaluated]");
            Path data = Path.of(args[1]).toAbsolutePath().normalize();
            require(Files.isDirectory(data),"existing export directory required");
            Path weights = Path.of(args[2]).toAbsolutePath().normalize();
            require(Files.isRegularFile(weights),"weights.csv required");
            Path output = args.length == 4 ? Path.of(args[3]) : data.resolve("evaluated");
            require(!output.toAbsolutePath().normalize().equals(data),"cannot replace export");
            Path out = fresh(output);
            new RemainingDropProbe().evaluate(data,weights,out);
        } else throw new IllegalArgumentException("Unknown mode " + args[0]);
    }

    private static Path fresh(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        require(!path.startsWith(FROZEN.toAbsolutePath().normalize()),"frozen baseline is read only");
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.createDirectory(path); // Atomic refusal of existing directory; no overwrite option.
        return path;
    }

    private static BufferedWriter writer(Path out,String name) throws IOException {
        return Files.newBufferedWriter(out.resolve(name),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }

    private static void json(Path out,String name,String body) throws IOException {
        try (BufferedWriter csv = writer(out,name)) { csv.write("{"+body+"}\n"); }
    }

    private static void row(BufferedWriter out,Object... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i != 0) out.write(',');
            String text = String.valueOf(cells[i]);
            if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0) {
                out.write('"'); out.write(text.replace("\"","\"\"")); out.write('"');
            } else out.write(text);
        }
        out.newLine();
    }

    private static double[] read(Path file) throws IOException {
        require(Files.size(file) == N*N*8L,"array size");
        double[] values = new double[N*N];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for (int i = 0; i < values.length; i++) values[i] = in.readDouble();
        }
        return values;
    }

    private static void writeArray(Path out,String name,double[] values) throws IOException {
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(out.resolve(name+".f64be"),StandardOpenOption.CREATE_NEW)))) {
            for (double value : values) {
                require(Double.isFinite(value),"finite output"); stream.writeDouble(value);
            }
        }
    }

    private static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hex(double value) { return Long.toHexString(Double.doubleToRawLongBits(value)); }
    private static boolean bits(double a,double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static double ms(long start) { return (System.nanoTime()-start)/1e6; }
    private static void require(boolean ok,String message) {
        if (!ok) throw new AssertionError(message);
    }
}
