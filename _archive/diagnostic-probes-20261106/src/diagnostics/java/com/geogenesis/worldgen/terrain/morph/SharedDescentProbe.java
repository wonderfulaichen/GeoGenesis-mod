package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.SharedDescentCheck;
import com.geogenesis.standalone.SharedDescentCheck.Report;
import com.geogenesis.standalone.SharedDescentCheck.StopEvidence;
import com.geogenesis.standalone.SharedDescentGraph;
import com.geogenesis.standalone.SharedDescentGraph.Node;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Stage A only: frozen full H0, shared straight-line geometry, no output of a new height field. */
public final class SharedDescentProbe {
    private static final int N = 769;
    private static final double MARGIN = 64;
    private static final Path DEFAULT_STRONG = Path.of("build/strong-window-erosion-20261104-final/data");
    private static final Path DEFAULT_FROZEN = Path.of("build/terminal-depth-repair-20261104/evaluated");
    private final Path strong, frozen;
    private final Map<Path, String> locked = new TreeMap<>();
    private final ThreadLocal<Source> sources = ThreadLocal.withInitial(Source::create);
    private final double step;
    private final int ox, oz;
    private int audited, finerWitnesses, truncatedWitnesses;

    private record Source(Object probe, MethodHandle height, MethodHandle raw) implements HeightField {
        private static Source create() {
            try {
                Class<?> type = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object probe = constructor.newInstance();
                return new Source(probe, bind(type, probe, "height"), bind(type, probe, "raw"));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot reuse frozen NoiseFollowingProbe", failure);
            }
        }
        private static MethodHandle bind(Class<?> type, Object probe, String name)
                throws ReflectiveOperationException {
            Method method = type.getDeclaredMethod(name, double.class, double.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).bindTo(probe);
        }
        private double parameter(String name) throws ReflectiveOperationException {
            Field field = probe.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getDouble(probe);
        }
        @Override public double height(double x, double z) { return invoke(height, x, z); }
        private double raw(double x, double z) { return invoke(raw, x, z); }
        private static double invoke(MethodHandle handle, double x, double z) {
            try { return (double) handle.invokeExact(x, z); }
            catch (Throwable failure) { throw new IllegalStateException("Frozen callback failed", failure); }
        }
    }

    private SharedDescentProbe(Path strong, Path frozen) throws Exception {
        this.strong = strong.toAbsolutePath().normalize();
        this.frozen = frozen.toAbsolutePath().normalize();
        lockDirectory(this.strong); lockDirectory(this.frozen);
        Source source = sources.get();
        step = source.parameter("step");
        require(Double.isFinite(step) && step > 0, "frozen grid step");
        ox = (int) Math.round(source.parameter("originX") / step);
        oz = (int) Math.round(source.parameter("originZ") / step);
    }

    private void lockDirectory(Path directory) throws Exception {
        require(Files.isDirectory(directory), "missing frozen directory: " + directory);
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) locked.put(file, hash(file));
        }
    }
    private void verifyLocked() throws Exception {
        Map<Path, String> before = new TreeMap<>(locked);
        lockDirectory(strong); lockDirectory(frozen);
        require(before.equals(locked), "frozen directory files changed or added");
        for (Map.Entry<Path, String> entry : before.entrySet()) {
            require(entry.getValue().equals(hash(entry.getKey())), "frozen input changed");
        }
    }
    private double x(int col) { return (ox + col) * step; }
    private double z(int row) { return (oz + row) * step; }

    private void verifySources() throws Exception {
        double[] raw = read(frozen.resolve("base-raw.f64be"));
        double[] h0 = read(frozen.resolve("carrier-height.f64be"));
        require(hash(frozen.resolve("base-raw.f64be")).equals(hash(strong.resolve("base-raw.f64be"))),
                "strong raw frozen identity");
        require(hash(frozen.resolve("carrier-height.f64be")).equals(hash(strong.resolve("carrier-height.f64be"))),
                "strong H0 frozen identity");
        IntStream.range(0, N).parallel().forEach(row -> {
            Source source = sources.get();
            for (int col = 0; col < N; col++) {
                int id = row * N + col;
                require(bits(source.raw(x(col), z(row)), raw[id]), "complete frozen raw bits " + id);
                require(bits(source.height(x(col), z(row)), h0[id]), "complete frozen H0 bits " + id);
            }
        });
        System.out.println("FULL 769x769 raw/H0 bit verification complete; no height formula copied");
    }

    private SharedDescentGraph buildGraph() {
        int minIX = (int) Math.floor((x(0) - MARGIN) / 4);
        int minIZ = (int) Math.floor((z(0) - MARGIN) / 4);
        int maxIX = (int) Math.ceil((x(N - 1) + MARGIN) / 4);
        int maxIZ = (int) Math.ceil((z(N - 1) + MARGIN) / 4);
        return new SharedDescentGraph(sources.get(), minIX, minIZ, maxIX, maxIZ);
    }

    private static void exportGraph(Path out, SharedDescentGraph graph) throws IOException {
        try (BufferedWriter nodes = Files.newBufferedWriter(out.resolve("graph-nodes.csv"));
             BufferedWriter edges = Files.newBufferedWriter(out.resolve("edges.csv"))) {
            nodes.write("id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu\n");
            edges.write("source,target,x0,z0,x1,z1,H0_source,H0_target,samples,min_drop\n");
            for (int id = 0; id < graph.size(); id++) {
                Node n = graph.node(id), next = graph.next(n);
                nodes.write(n.id()+","+n.ix()+","+n.iz()+","+n.x()+","+n.z()+","+n.h0()+","+n.nextId()
                        +","+n.state()+","+n.indegree()+","+n.rootId()+","+n.downstreamLengthWu()+"\n");
                if (next != null) edges.write(id+","+next.id()+","+n.x()+","+n.z()+","+next.x()+","+next.z()
                        +","+n.h0()+","+next.h0()+","+n.samples()+","+n.minDrop()+"\n");
            }
        }
    }

    private static void exportRoots(Path out, SharedDescentGraph graph) throws IOException {
        // Closure/component membership counts, NOT flow accumulation or catchment area.
        int[] counts = new int[graph.size()];
        Map<String, Integer> states = new TreeMap<>();
        double longest = 0;
        for (int id = 0; id < graph.size(); id++) {
            Node n = graph.node(id); counts[n.rootId()]++;
            states.merge(n.state().name(), 1, Integer::sum);
            longest = Math.max(longest, n.downstreamLengthWu());
        }
        try (BufferedWriter json = Files.newBufferedWriter(out.resolve("rootterminalmetrics.json"))) {
            json.write("{\"scope\":\"local bounded NOT complete halo; component counts NOT catchment area\","
                    +"\"nodeCount\":"+graph.size()+",\"longestDownstreamLengthWu\":"+longest+",\"stateCounts\":{");
            boolean comma = false;
            for (Map.Entry<String, Integer> entry : states.entrySet()) {
                if (comma) json.write(','); comma = true;
                json.write("\""+entry.getKey()+"\":"+entry.getValue());
            }
            json.write("},\"roots\":["); comma = false;
            for (int id = 0; id < graph.size(); id++) {
                if (counts[id] == 0) continue;
                if (comma) json.write(','); comma = true;
                Node n = graph.node(id);
                json.write("{\"root_id\":"+id+",\"label\":\""+n.label()+"\",\"state\":\""+n.state()
                        +"\",\"componentNodeCount\":"+counts[id]+"}");
            }
            json.write("]}\n");
        }
    }

    private record OldStop(String id, int cellX, int cellZ, String status,
                           double x, double z, double length) {}
    private List<OldStop> selectStops() throws IOException {
        Map<String, double[]> last = new HashMap<>();
        try (var lines = Files.lines(strong.resolve("routes.csv"))) {
            lines.skip(1).forEach(line -> {
                String[] c = line.split(",", -1);
                last.put(c[0], new double[]{Double.parseDouble(c[2]), Double.parseDouble(c[3])});
            });
        }
        List<OldStop> stops = new ArrayList<>();
        try (var lines = Files.lines(strong.resolve("traces.csv"))) {
            for (String line : lines.skip(1).toList()) {
                String[] c = line.split(",", -1), label = c[0].split(":");
                if (!c[3].startsWith("NO_DESCENT") && !c[3].equals("TRUNCATED")) continue;
                double[] p = last.get(c[0]); require(p != null, "old endpoint exists");
                if (!inWindow(p[0], p[1])) continue;
                stops.add(new OldStop(c[0], Integer.parseInt(label[label.length-2]),
                        Integer.parseInt(label[label.length-1]), c[3], p[0], p[1], Double.parseDouble(c[4])));
            }
        }
        stops.sort(Comparator.comparingInt(OldStop::cellZ).thenComparingInt(OldStop::cellX)
                .thenComparing(OldStop::id));
        OldStop[][] selected = new OldStop[16][2];
        for (OldStop stop : stops) {
            int col = Math.min(3, (int) ((stop.x()-x(0))/(x(N-1)-x(0))*4));
            int row = Math.min(3, (int) ((stop.z()-z(0))/(z(N-1)-z(0))*4));
            int type = stop.status().equals("TRUNCATED") ? 1 : 0;
            if (selected[row*4+col][type] == null) selected[row*4+col][type] = stop;
        }
        List<OldStop> result = new ArrayList<>();
        for (OldStop[] cell : selected) for (OldStop stop : cell) if (stop != null) result.add(stop);
        return List.copyOf(result);
    }
    private boolean inWindow(double px, double pz) {
        return px >= x(0) && px <= x(N-1) && pz >= z(0) && pz <= z(N-1);
    }

    private void exportStops(Path out, SharedDescentGraph graph, List<OldStop> selected) throws IOException {
        List<String> evidence = new ArrayList<>();
        try (BufferedWriter csv = Files.newBufferedWriter(out.resolve("stop-audit.csv"))) {
            csv.write("id,cell_x,cell_z,old_status,x,z,old_length_wu,classification,witness_length_wu,"
                    +"witness_final_rises,graph_nearest_id,graph_state\n");
            for (OldStop stop : selected) {
                StopEvidence e = SharedDescentCheck.auditStop(sources.get(), stop.id(), stop.status(), stop.x(), stop.z());
                Node n = graph.node(graph.nearest(stop.x(), stop.z()));
                csv.write(stop.id()+","+stop.cellX()+","+stop.cellZ()+","+stop.status()+","+stop.x()+","+stop.z()
                        +","+stop.length()+","+e.classification()+","+e.witnessLengthWu()+","+e.witnessFinalRises()
                        +","+n.id()+","+n.state()+"\n");
                audited++;
                if (e.finerDescent()) finerWitnesses++;
                if (stop.status().equals("TRUNCATED") && e.finerDescent()) truncatedWitnesses++;
                evidence.add("{\"id\":\""+stop.id()+"\",\"finerDescent\":"+e.finerDescent()
                        +",\"finiteCircleCandidate\":"+e.finiteCircleCandidate()+"}");
            }
        }
        Files.writeString(out.resolve("stop-evidence.json"), "{\"realOutletProof\":false,\"stops\":["
                +String.join(",", evidence)+"]}\n");
    }

    private void exportJunctions(Path out, SharedDescentGraph graph) throws IOException {
        try (BufferedWriter csv = Files.newBufferedWriter(out.resolve("junctions.csv"))) {
            csv.write("region,node,x,z,indegree,next,root_id,downstream_length_wu\n");
            for (int region = 0; region < 4; region++) {
                double cx = x(0)+(x(N-1)-x(0))*(region%2 == 0 ? .25 : .75);
                double cz = z(0)+(z(N-1)-z(0))*(region/2 == 0 ? .25 : .75);
                Node best = null; double distance = Double.POSITIVE_INFINITY;
                for (int id = 0; id < graph.size(); id++) {
                    Node n = graph.node(id);
                    if (!inWindow(n.x(), n.z()) || n.indegree() < 2
                            || n.state() == SharedDescentGraph.State.DOMAIN_BOUNDARY) continue;
                    int quadrant = (n.x() < (x(0)+x(N-1))/2 ? 0 : 1)
                            + (n.z() < (z(0)+z(N-1))/2 ? 0 : 2);
                    if (quadrant != region) continue;
                    double d = Math.hypot(n.x()-cx, n.z()-cz);
                    if (d < distance) { best = n; distance = d; }
                }
                if (best == null) csv.write(region+",-1,NaN,NaN,0,-1,-1,NaN\n");
                else csv.write(region+","+best.id()+","+best.x()+","+best.z()+","+best.indegree()+","+best.nextId()
                        +","+best.rootId()+","+best.downstreamLengthWu()+"\n");
            }
        }
    }

    private void metadata(Path out, SharedDescentGraph graph, Report report) throws Exception {
        Node first = graph.node(0), last = graph.node(graph.size()-1);
        String rule = "fixed 4x4 original window partitions row-major; endpoints in closed window; "
                +"per partition first source cell_z/cell_x row-major old NO_DESCENT and first TRUNCATED; "
                +"selected before new graph; at most 32; never result-based";
        Files.writeString(out.resolve("sampling.json"), "{\"size\":769,\"originWu\":["+x(0)+","+z(0)
                +"],\"stepWu\":"+step+",\"spanWu\":"+(768*step)+",\"graphPitchWu\":4,\"marginWu\":64,"
                +"\"graphColumns\":"+graph.columns()+",\"graphRows\":"+graph.rows()+",\"graphIndexBounds\":["
                +first.ix()+","+first.iz()+","+last.ix()+","+last.iz()+"],\"boundsRounding\":\"outward to pitch4\","
                +"\"scope\":\"local bounded NOT complete halo\",\"stage\":\"A only\",\"noCarve\":true,"
                +"\"heightUnchanged\":true,\"newHeightOutput\":false,\"flowAccumulation\":false,\"phaseB\":false,"
                +"\"phaseC\":false,\"edgeGeometry\":\"straight polyline, no Bezier or inverse domain\","
                +"\"rootLabel\":\"ix:iz node label, not global world ID\",\"stopSelectionRule\":\""+rule+"\","
                +"\"junctionRule\":\"four window quadrants; nearest quadrant center indegree>=2 internal node; row-major ties; no longest selection\"}\n");
        Files.writeString(out.resolve("checks.json"), "{\"structuralContractPassed\":true,\"rawBitChecks\":"+(N*N)
                +",\"H0BitChecks\":"+(N*N)+",\"nodes\":"+report.nodes()+",\"edges\":"+report.edges()
                +",\"rejectedCandidateEdges\":"+graph.rejectedEdges()+",\"rejectedCandidateSamples\":"+graph.rejectedSamples()
                +",\"fineChecked\":"+report.fineChecked()+",\"fine_validation_fail\":"+report.fineValidationFail()
                +",\"fineSpacingWu\":0.137,\"fineOffsetFraction\":0.37,\"fineIncludesVertices\":true,\"reverseCold\":"+report.reverseCold()
                +",\"parallelCold\":"+report.parallelCold()+",\"oldStopsAudited\":"+audited+",\"finerDescentWitnesses\":"+finerWitnesses
                +",\"truncatedWithFinerDescent\":"+truncatedWitnesses+",\"witnessIsRealOutletProof\":false,"
                +"\"continuousMonotonicityProven\":false,\"trueBasinsProven\":false,\"morphologicalAcceptance\":false}\n");
        List<String> entries = new ArrayList<>();
        for (Map.Entry<Path, String> entry : locked.entrySet()) {
            entries.add("{\"path\":\""+entry.getKey().toString().replace('\\','/')+"\",\"sha256\":\""+entry.getValue()+"\"}");
        }
        Files.writeString(out.resolve("frozen-inputs.json"), "{\"unchanged\":true,\"files\":["+String.join(",",entries)+"]}\n");
    }

    private void run(Path out) throws Exception {
        List<OldStop> selected = selectStops(); // Frozen selection BEFORE any graph result exists.
        verifySources();
        SharedDescentGraph graph = buildGraph();
        Report report = SharedDescentCheck.check(graph, Source::create);
        exportGraph(out, graph); exportRoots(out, graph);
        exportStops(out, graph, selected); exportJunctions(out, graph);
        verifyLocked();
        Files.copy(strong.resolve("carrier-height.f64be"), out.resolve("carrier-height.f64be"));
        require(hash(out.resolve("carrier-height.f64be")).equals(hash(strong.resolve("carrier-height.f64be"))),
                "carrier copied byte-exact after full verification");
        Files.copy(strong.resolve("base-raw.f64be"), out.resolve("base-raw.f64be"));
        metadata(out, graph, report);
        verifyLocked();
        System.out.println("STAGE A STRUCTURAL CONTRACT PASS; fine_validation_fail="+report.fineValidationFail()
                +"; no continuous/morphological acceptance; no carve, height unchanged");
    }

    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            require(args.length == 1 || args.length == 3,
                    "usage: SharedDescentProbe NEWout/data [strongData frozenData] (from mdk)");
            Path out = Path.of(args[0]).toAbsolutePath().normalize();
            require(!Files.exists(out), "refuse existing output");
            SharedDescentProbe probe = new SharedDescentProbe(args.length == 3 ? Path.of(args[1]) : DEFAULT_STRONG,
                    args.length == 3 ? Path.of(args[2]) : DEFAULT_FROZEN);
            require(!out.startsWith(probe.strong) && !out.startsWith(probe.frozen), "output outside frozen inputs");
            Files.createDirectories(out.getParent()); Files.createDirectory(out);
            probe.run(out);
        } catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
    private static double[] read(Path file) throws IOException {
        require(Files.size(file) == N*N*8L, "frozen full grid size");
        double[] values = new double[N*N];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for (int i = 0; i < values.length; i++) {
                values[i] = input.readDouble(); require(Double.isFinite(values[i]), "finite frozen value");
            }
        }
        return values;
    }
    private static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
}
