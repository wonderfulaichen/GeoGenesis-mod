package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.SharedDescentGraph;
import com.geogenesis.standalone.SharedDownstreamSearch.Graph;
import com.geogenesis.standalone.SharedDownstreamSearch.Node;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Diagnostic Stage A overlay only. Sampled witnesses are NOT continuous or global proofs. */
public final class RemainingRouteApplyProbe {
    private static final Path DEFAULT_BASE = Path.of("build/shared-downstream-stage-a-20261104-final-v2/data");
    private static final String NODES = "id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu";
    private static final String SEGMENTS = "source,target,segment,x0,z0,x1,z1,H0_source,H0_target";
    private static final List<String> CLASSIFICATION_FIELDS = List.of("source","x","z","sourceH0",
            "classification","target","prefix_descended","fineAllVerified","terminal_reachable_verified");
    private static final List<String> CONNECTOR_FIELDS = List.of("source","target","vertex","x","z","H0","kind");
    private static final String KIND = "CONTINUOUS_DESCENT_CONNECTOR";
    private record Source(Object instance, MethodHandle height, MethodHandle raw) implements HeightField {
        static Source create() throws Exception {
            Class<?> type = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
            var ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            return new Source(instance,method(type,instance,"height"),method(type,instance,"raw"));
        }
        private static MethodHandle method(Class<?> type, Object o, String name) throws Exception {
            var m = type.getDeclaredMethod(name,double.class,double.class); m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m).bindTo(o);
        }
        private static double call(MethodHandle m, double x, double z) {
            try { return (double)m.invokeExact(x,z); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }
        public double height(double x, double z) { return call(height,x,z); }
        double rawValue(double x, double z) { return call(raw,x,z); }
        double step() throws Exception {
            var f = instance.getClass().getDeclaredField("step"); f.setAccessible(true);
            return f.getDouble(instance);
        }
    }
    private record Vertex(Point point, double h) {}
    private static final class Route {
        final int source, target;
        final List<Vertex> vertices = new ArrayList<>();
        boolean ordered = true;
        Route(int source, int target) { this.source = source; this.target = target; }
        List<Point> points() { return vertices.stream().map(Vertex::point).toList(); }
    }
    private record Check(boolean pass, long samples, double minDrop) {}
    private final Path base, evidence, out, log;
    private final Source field, independent;
    private final List<Node> nodes = new ArrayList<>();
    private final List<String[]> originalRows = new ArrayList<>();
    private final Map<Integer,List<Point>> paths = new TreeMap<>();
    private final Map<Integer,Integer> candidates = new TreeMap<>();
    private final Map<Integer,Route> routes = new TreeMap<>(), added = new TreeMap<>();
    private final Map<Integer,Boolean> finalFiner = new TreeMap<>();
    private final Map<Path,String> frozen = new TreeMap<>();
    private final List<Path> lockedDirs = new ArrayList<>();
    private Graph graph;
    private int[] roots, indegrees;
    private double[] lengths;
    private int applied, skipped, rejected, beforeBoundary, afterBoundary, residual;
    private int finalEdgesChecked, newFinerChecked, finalFailures, finerFailures;

    private RemainingRouteApplyProbe(Path base, Path evidence, Path out, Path repo) throws Exception {
        this.base = base; this.evidence = evidence; this.out = out;
        log = repo.resolve("debug-agent.log").toAbsolutePath().normalize();
        lock(repo.resolve("forge-1.20.1-47.4.10-mdk/src/main")); lock(base); lock(evidence);
        lock(repo.resolve("forge-1.20.1-47.4.10-mdk/src/diagnostics/java"));
        field = Source.create(); independent = Source.create();
        readGraph(); graph = new Graph(nodes); readEvidence();
    }
    private static int integer(String s) { return Integer.parseInt(s); }
    private static double number(String s) { return Double.parseDouble(s); }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static void csv(Path file, String header, Consumer<String[]> row) throws Exception {
        try (var lines = Files.lines(file)) {
            var it = lines.iterator(); require(it.hasNext() && it.next().equals(header),"schema "+file);
            int columns = header.split(",",-1).length;
            while (it.hasNext()) {
                String[] c = it.next().split(",",-1); require(c.length == columns,"columns "+file); row.accept(c);
            }
        }
    }
    private void readGraph() throws Exception {
        csv(base.resolve("graph-nodes.csv"),NODES,c -> {
            int id = integer(c[0]), ix = integer(c[1]), iz = integer(c[2]);
            Node n = new Node(id,number(c[3]),number(c[4]),number(c[5]),integer(c[6]),c[7]);
            require(id == nodes.size() && id < 241081,"stable node IDs");
            require(bits(n.h(),field.height(n.x(),n.z())),"old H0 bitmatch "+id);
            require(n.x() == ix*4.0 && n.z() == iz*4.0,"old 4wu lattice");
            if (id > 0) {
                String[] p = originalRows.get(id-1);
                require(iz == integer(p[2]) ? ix == integer(p[1])+1
                        : iz == integer(p[2])+1 && ix == integer(originalRows.get(0)[1]),"row-major order");
            }
            nodes.add(n); originalRows.add(c);
        });
        require(nodes.size() == 241081,"stable 241081 nodes");
        require(nodes.stream().filter(n -> n.next() < 0 && !boundary(n)).count() == 3959,"original 3959 stops");
        csv(base.resolve("polyline-segments.csv"),SEGMENTS,c -> {
            int id = integer(c[0]); Node n = nodes.get(id);
            require(n.next() >= 0 && n.next() == integer(c[1]),"old target");
            List<Point> p = paths.computeIfAbsent(id,k -> new ArrayList<>());
            Point a = new Point(number(c[3]),number(c[4])), b = new Point(number(c[5]),number(c[6]));
            if (p.isEmpty()) p.add(a);
            require(integer(c[2]) == p.size()-1 && p.get(p.size()-1).equals(a),"old segment continuity");
            require(bits(number(c[7]),field.height(a.x(),a.z()))
                    && bits(number(c[8]),field.height(b.x(),b.z())),"old vertex H0 bitmatch");
            p.add(b);
        });
        for (Node n : nodes) if (n.next() >= 0) {
            List<Point> p = paths.get(n.id());
            require(p != null && p.get(0).equals(n.point())
                    && p.get(p.size()-1).equals(nodes.get(n.next()).point()),"complete old geometry");
        } else require(!paths.containsKey(n.id()),"old stop has no edge");
    }
    private static boolean boundary(Node n) { return n.state().equals("DOMAIN_BOUNDARY"); }
    private static boolean flag(String value, String name) {
        require(value.equals("true") || value.equals("false"),"boolean flag "+name);
        return value.equals("true");
    }
    private static Map<String,Integer> columns(String header, List<String> required) {
        Map<String,Integer> indices = new TreeMap<>();
        String[] names = header.split(",",-1);
        for (int i = 0; i < names.length; i++)
            require(!names[i].isEmpty() && indices.put(names[i],i) == null,"unique named columns");
        for (String name : required) require(indices.containsKey(name),"required column "+name);
        return indices;
    }
    private static Map<String,String> namedRow(String line, Map<String,Integer> indices) {
        String[] values = line.split(",",-1);
        require(values.length == indices.size(),"named CSV column count");
        Map<String,String> row = new TreeMap<>();
        indices.forEach((name,index) -> row.put(name,values[index]));
        return row;
    }
    private static void namedCsv(Path file, List<String> required, Consumer<Map<String,String>> row)
            throws Exception {
        try (var lines = Files.lines(file)) {
            var it = lines.iterator(); require(it.hasNext(),"header "+file);
            Map<String,Integer> indices = columns(it.next(),required);
            while (it.hasNext()) row.accept(namedRow(it.next(),indices));
        }
    }
    private static void validateEvidenceFlags(Map<String,String> c) {
        flag(c.get("prefix_descended"),"prefix_descended");
        flag(c.get("fineAllVerified"),"fineAllVerified");
        flag(c.get("terminal_reachable_verified"),"terminal_reachable_verified");
        // Flags describe the full TRACE, not the candidate prefix+connector route.
        // Validate their encoding only; apply() independently validates the whole candidate.
    }
    private void readEvidence() throws Exception {
        Map<Integer,Boolean> seen = new TreeMap<>();
        namedCsv(evidence.resolve("classifications.csv"),CLASSIFICATION_FIELDS,c -> {
            validateEvidenceFlags(c);
            int id = integer(c.get("source"));
            require(id >= 0 && id < nodes.size() && seen.put(id,true) == null,"unique classification source");
            Node n = nodes.get(id);
            require(n.next() < 0 && !boundary(n),"classifications only original internal stops");
            require(bits(number(c.get("x")),n.x()) && bits(number(c.get("z")),n.z())
                    && bits(number(c.get("sourceH0")),n.h()),"classification original source identity");
            if (c.get("classification").equals("CONNECTABLE_CONTINUOUS_WITNESS"))
                candidates.put(id,integer(c.get("target")));
        });
        require(seen.size() == 3959,"all original stop classifications");
        namedCsv(evidence.resolve("connector-paths.csv"),CONNECTOR_FIELDS,c -> {
            int source = integer(c.get("source")), target = integer(c.get("target"));
            if (!candidates.containsKey(source)) return;
            Route r = routes.computeIfAbsent(source,k -> new Route(source,target));
            r.ordered &= target == r.target && integer(c.get("vertex")) == r.vertices.size();
            r.vertices.add(new Vertex(new Point(number(c.get("x")),number(c.get("z"))),number(c.get("H0"))));
        });
    }
    private static Check check(HeightField f, List<Point> p, double pitch, double offset) {
        boolean pass = p.size() > 1; long samples = 0; double min = Double.POSITIVE_INFINITY;
        for (int i = 1; i < p.size(); i++) {
            Point a = p.get(i-1), b = p.get(i);
            double d = Math.hypot(b.x()-a.x(),b.z()-a.z());
            if (!(d > 0) || !Double.isFinite(d)) { pass = false; continue; }
            var v = SharedDescentGraph.validate(f,a.x(),a.z(),b.x(),b.z(),pitch,offset);
            pass &= v.accepted(); samples += v.samples(); min = Math.min(min,v.minDrop());
        }
        return new Check(pass,samples,min);
    }
    private String routeIdentity(int id, int target, Route r) {
        if (target < 0 || target >= graph.size()) return "REJECT_INVALID_TARGET";
        if (r == null || !r.ordered || r.target != target || r.vertices.size() < 2) return "REJECT_INCOMPLETE_ROUTE";
        List<Point> p = r.points();
        if (!p.get(0).equals(nodes.get(id).point())
                || !p.get(p.size()-1).equals(nodes.get(target).point())) return "REJECT_ENDPOINT_IDENTITY";
        for (Vertex v : r.vertices) {
            Point q = v.point();
            if (!Double.isFinite(q.x()) || !Double.isFinite(q.z()) || !Double.isFinite(v.h())
                    || !bits(v.h(),field.height(q.x(),q.z()))
                    || !bits(v.h(),independent.height(q.x(),q.z()))) return "REJECT_VERTEX_H0_BITMATCH";
        }
        return "IDENTITY_OK";
    }
    private String apply(int id, int target, Route r) {
        if (target >= 0 && target < graph.size() && graph.component(id) == graph.component(target))
            return "SKIP_ALREADY_SHARED";
        String identity = routeIdentity(id,target,r);
        if (!identity.equals("IDENTITY_OK")) return identity;
        if (graph.next(id) >= 0 || boundary(nodes.get(id))) return "REJECT_NOT_INTERNAL_STOP";
        if (!(nodes.get(target).h() < nodes.get(id).h()) || !graph.eligible(id,target)) return "REJECT_NOT_ELIGIBLE";
        // Both checks traverse the COMPLETE supplied source->target path, never just the connector.
        Check coarse = check(field,r.points(),.03125,0);
        Check fine = check(independent,r.points(),.0078125,.37);
        if (!coarse.pass() || !fine.pass()) return "REJECT_FULL_ROUTE_SAMPLED_DESCENT";
        graph.connect(id,target); paths.put(id,r.points()); added.put(id,r);
        return "APPLIED";
    }
    private void applyRoutes() throws Exception {
        beforeBoundary = boundaryReach();
        debug("begin originalStops=3959 candidates="+candidates.size()+" out="+out
                +"; labels are selection ONLY; independent full-route callback checks mandatory; no carving");
        try (var w = Files.newBufferedWriter(out.resolve("route-apply-checks.csv"))) {
            w.write("source,target,status,vertices,length,target_root_after_decision\n");
            for (var e : candidates.entrySet()) {
                int id = e.getKey(), target = e.getValue(); Route r = routes.get(id);
                String result = apply(id,target,r);
                if (result.equals("APPLIED")) applied++;
                else if (result.equals("SKIP_ALREADY_SHARED")) skipped++;
                else rejected++;
                int root = target >= 0 && target < graph.size() ? graph.root(target) : -1;
                String line = id+","+target+","+result+","+(r == null ? 0 : r.vertices.size())+","
                        +(r == null ? "NaN" : length(r.points()))+","+root;
                w.write(line+"\n"); debug(line);
            }
        }
        require(candidates.size() == applied+skipped+rejected,"candidate accounting");
    }
    private static double length(List<Point> p) {
        double total = 0;
        for (int i = 1; i < p.size(); i++) total += Math.hypot(p.get(i).x()-p.get(i-1).x(),p.get(i).z()-p.get(i-1).z());
        return total;
    }
    private int boundaryReach() {
        int count = 0;
        for (Node n : nodes) if (boundary(graph.node(graph.root(n.id())))) count++;
        return count;
    }
    private void finishGraph() {
        roots = new int[graph.size()]; Arrays.fill(roots,-1);
        indegrees = new int[graph.size()]; lengths = new double[graph.size()];
        Integer[] order = new Integer[graph.size()];
        for (Node n : nodes) {
            int id = n.id(), to = graph.next(id); order[id] = id;
            require(n.next() < 0 || to == n.next(),"old successor unchanged");
            require(bits(n.h(),field.height(n.x(),n.z())),"final old H0 unchanged");
            if (to >= 0) indegrees[to]++;
        }
        Arrays.sort(order,Comparator.<Integer>comparingDouble(i -> nodes.get(i).h()).thenComparingInt(i -> i));
        for (int id : order) {
            int to = graph.next(id);
            if (to < 0) { roots[id] = id; if (!boundary(nodes.get(id))) residual++; continue; }
            require(nodes.get(to).h() < nodes.get(id).h() && roots[to] >= 0,"strict final DAG");
            roots[id] = roots[to]; lengths[id] = length(paths.get(id))+lengths[to];
        }
        afterBoundary = boundaryReach();
        require(residual == 3959-applied && afterBoundary >= beforeBoundary,"root and boundary accounting");
    }
    private static void writeCheck(BufferedWriter w, int id, int target, double pitch, double offset, Check c)
            throws Exception {
        w.write(id+","+target+","+pitch+","+offset+","+c.pass()+","+c.samples()+","+c.minDrop()+"\n");
    }
    private void finalChecks() throws Exception {
        try (var w = Files.newBufferedWriter(out.resolve("final-edge-checks.csv"))) {
            w.write("source,target,pitch,offset,passes,samples,min_drop\n");
            for (Node n : nodes) {
                int id = n.id(), target = graph.next(id); if (target < 0) continue;
                Check c = check(field,paths.get(id),.03125,0); finalEdgesChecked++;
                writeCheck(w,id,target,.03125,0,c); if (!c.pass()) finalFailures++;
                if (added.containsKey(id)) {
                    Check fine = check(independent,paths.get(id),.0078125,.37); newFinerChecked++;
                    finalFiner.put(id,fine.pass());
                    writeCheck(w,id,target,.0078125,.37,fine); if (!fine.pass()) finerFailures++;
                }
            }
        }
        require(newFinerChecked == applied,"all new edges finer checked");
    }
    private void copyPrefix(String name) throws Exception {
        Path from = base.resolve(name), to = out.resolve(name);
        require(Files.readString(from).endsWith("\n"),"verbatim prefix needs final newline "+name);
        Files.copy(from,to);
        require(hash(from).equals(hash(to)),"old prefix copied verbatim "+name);
    }
    private void exportGraph() throws Exception {
        for (String name : List.of("edges.csv","edge-descriptors.csv","polyline-segments.csv")) copyPrefix(name);
        try (var ns = Files.newBufferedWriter(out.resolve("graph-nodes.csv"));
             var es = Files.newBufferedWriter(out.resolve("edges.csv"),StandardOpenOption.APPEND);
             var ds = Files.newBufferedWriter(out.resolve("edge-descriptors.csv"),StandardOpenOption.APPEND);
             var ps = Files.newBufferedWriter(out.resolve("polyline-segments.csv"),StandardOpenOption.APPEND);
             var actions = Files.newBufferedWriter(out.resolve("repair-actions.csv"));
             var rs = Files.newBufferedWriter(out.resolve("residual-roots.csv"))) {
            ns.write(NODES+"\n"); actions.write("id,old_next,new_next,kind,reason,vertexcount,length,finervalid\n");
            rs.write("id,old_next,next,state,evidence\n");
            for (Node n : nodes) {
                int id = n.id(), to = graph.next(id); String[] c = originalRows.get(id).clone();
                c[6] = Integer.toString(to); c[7] = to >= 0 ? "DESCENT" : n.state();
                c[8] = Integer.toString(indegrees[id]); c[9] = Integer.toString(roots[id]); c[10] = Double.toString(lengths[id]);
                ns.write(String.join(",",c)+"\n");
                if (to < 0 && !boundary(n)) rs.write(id+",-1,-1,"+n.state()+",UNKNOWN_NOT_PROVEN_BASIN\n");
                if (!added.containsKey(id)) continue;
                Node b = nodes.get(to); List<Point> p = paths.get(id);
                es.write(id+","+to+","+n.x()+","+n.z()+","+b.x()+","+b.z()+","+n.h()+","+b.h()+",0,NaN\n");
                ds.write(id+","+to+",POLYLINE,"+p.size()+","+length(p)+"\n");
                actions.write(id+",-1,"+to+","+KIND+",FULL_ROUTE_CALLBACK_VALIDATED_EXISTING_CANONICAL_TARGET,"
                        +p.size()+","+length(p)+","+finalFiner.get(id)+"\n");
                for (int i = 1; i < p.size(); i++) {
                    Point a = p.get(i-1), z = p.get(i);
                    ps.write(id+","+to+","+(i-1)+","+a.x()+","+a.z()+","+z.x()+","+z.z()+","
                            +field.height(a.x(),a.z())+","+field.height(z.x(),z.z())+"\n");
                }
            }
        }
    }
    private void context() throws Exception {
        for (String name : List.of("base-raw.f64be","carrier-height.f64be","sampling.json","project-palette.csv",
                "fixed-junction-windows.csv")) {
            Path from = base.resolve(name);
            if (!name.equals("fixed-junction-windows.csv")) require(Files.isRegularFile(from),"frozen context "+name);
            if (!Files.exists(from)) continue;
            Files.copy(from,out.resolve(name)); require(hash(from).equals(hash(out.resolve(name))),"exact context");
        }
        String header = "region,node,x,z,indegree,next,root_id,downstream_length_wu";
        try (var w = Files.newBufferedWriter(out.resolve("junctions.csv"))) {
            w.write(header+"\n");
            List<String[]> rows = new ArrayList<>(); csv(base.resolve("junctions.csv"),header,rows::add);
            for (String[] c : rows) {
                int id = integer(c[1]);
                if (id >= 0) {
                    c[4] = Integer.toString(indegrees[id]); c[5] = Integer.toString(graph.next(id));
                    c[6] = Integer.toString(roots[id]); c[7] = Double.toString(lengths[id]);
                }
                w.write(String.join(",",c)+"\n");
            }
        }
    }
    private void frozenGrid() throws Exception {
        int n = 769; double step = field.step();
        int ox = (int)Math.round(-3242.5/step)-n/2, oz = (int)Math.round(-3365.5/step)-n/2;
        Path rawFile = base.resolve("base-raw.f64be"), hFile = base.resolve("carrier-height.f64be");
        require(Files.size(rawFile) == n*n*8L && Files.size(hFile) == n*n*8L,"full769 file sizes");
        try (var raw = new DataInputStream(new BufferedInputStream(Files.newInputStream(rawFile)));
             var h = new DataInputStream(new BufferedInputStream(Files.newInputStream(hFile)))) {
            for (int i = 0; i < n*n; i++) {
                double x = (ox+i%n)*step, z = (oz+i/n)*step;
                require(bits(raw.readDouble(),field.rawValue(x,z)),"full769 raw bitmatch "+i);
                require(bits(h.readDouble(),field.height(x,z)),"full769 H0 bitmatch "+i);
            }
        }
    }
    private static String hash(Path p) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] b = new byte[65536]; for (int n; (n = in.read(b)) != -1;) digest.update(b,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Map<Path,String> snapshot(List<Path> dirs) throws Exception {
        Map<Path,String> result = new TreeMap<>();
        for (Path dir : dirs) {
            require(Files.isDirectory(dir),"locked directory "+dir);
            try (var files = Files.walk(dir)) {
                for (Path p : files.filter(Files::isRegularFile).sorted().toList())
                    result.put(p.toAbsolutePath().normalize(),hash(p));
            }
        }
        return result;
    }
    private void lock(Path dir) throws Exception {
        lockedDirs.add(dir); frozen.putAll(snapshot(List.of(dir)));
    }
    private void debug(String message) throws Exception {
        Files.writeString(log,Instant.now()+" RemainingRouteApply "+message+System.lineSeparator(),
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private void metadata() throws Exception {
        require(frozen.equals(snapshot(lockedDirs)),"src/main + input/evidence data + active sources frozen");
        try (var w = Files.newBufferedWriter(out.resolve("locked-inputs.csv"))) {
            w.write("path,sha256\n");
            for (var e : frozen.entrySet()) w.write("\""+e.getKey().toString().replace('\\','/')+"\","+e.getValue()+"\n");
        }
        Files.writeString(out.resolve("checks.json"),"{\"stage\":\"A diagnostic only\",\"nodes\":241081,\"originalStops\":3959"
                +",\"candidate\":"+candidates.size()+",\"applied\":"+applied+",\"skip\":"+skipped+",\"rejected\":"+rejected
                +",\"residual\":"+residual+",\"strictDAG\":true,\"allFinalEdgesChecked\":"+finalEdgesChecked
                +",\"allFinalEdgeSpacing\":0.03125,\"allFinalEdgeOffset\":0,\"allFinalEdgeFailures\":"+finalFailures
                +",\"allNewFinerChecked\":"+newFinerChecked+",\"newFinerSpacing\":0.0078125,\"newFinerOffset\":0.37"
                +",\"newFinerFailures\":"+finerFailures+",\"nodesReachingBoundaryBefore\":"+beforeBoundary
                +",\"nodesReachingBoundaryAfter\":"+afterBoundary+",\"wholeSuffixFineChecked\":false"
                +",\"wholeSuffixIndependentFineChecked\":false,\"oldH0Unchanged\":true,\"oldNodesUnchanged\":true"
                +",\"oldEdgesUnchanged\":true,\"oldGeometryVerbatimPrefix\":true,\"full769RawAndH0Bitwise\":true"
                +",\"frozenHashesVerified\":true,\"noCarve\":true,\"productionWiring\":false"
                +",\"edgeGeometry\":\"polyline-segments.csv authoritative; edges.csv endpoint inventory only\""
                +",\"samplingJson\":\"verbatim frozen context; geometry description superseded here\""
                +",\"sourceScope\":\"experimental NoiseFollowingProbe callback; not production terrain\""
                +",\"oldNodesScope\":\"IDs coordinates H0 retained; derived topology fields updated\""
                +",\"notproof\":true,\"continuousMonotonicityProven\":false,\"wholeWorldConnectivityProven\":false"
                +",\"basinProven\":false}\n");
        debug("complete candidate="+candidates.size()+" applied="+applied+" skip="+skipped+" rejected="+rejected
                +" residual="+residual+" boundary="+beforeBoundary+"->"+afterBoundary
                +" coarseFailures="+finalFailures+" finerFailures="+finerFailures+"; wholeSuffixFineChecked=false; NOT PROOF");
        require(finalFailures == 0 && finerFailures == 0,"final sampled checks failed; old edges never repaired here");
    }
    private static void selfTest() {
        Map<String,Integer> schema = columns("terminal_reachable_verified,target,classification,sourceH0,z,x,source,fineAllVerified,prefix_descended",
                CLASSIFICATION_FIELDS);
        Map<String,String> partial = namedRow("false,7,CONNECTABLE_CONTINUOUS_WITNESS,0.5,2,1,3,false,true",schema);
        validateEvidenceFlags(partial);
        require(integer(partial.get("target")) == 7 && integer(partial.get("source")) == 3
                && partial.get("classification").equals("CONNECTABLE_CONTINUOUS_WITNESS"),
                "named reordered fields preserve partial-trace candidate");
        boolean invalid = false;
        try { flag("TRUE","self-test"); } catch (AssertionError expected) { invalid = true; }
        require(invalid,"reject malformed boolean flag");
        require(columns("source,target,vertex,x,z,H0,kind",CONNECTOR_FIELDS).get("kind") == 6,"actual connector7 schema");
        HeightField slope = (x,z) -> -x;
        List<Node> n = List.of(new Node(0,0,0,0,-1,"BOUND_NO_DESCENT"),
                new Node(1,1,0,-1,2,"DESCENT"),new Node(2,2,0,-2,-1,"DOMAIN_BOUNDARY"));
        Graph g = new Graph(n); List<Point> p = List.of(n.get(0).point(),n.get(1).point());
        require(g.eligible(0,1) && check(slope,p,.03125,0).pass()
                && check(slope,p,.0078125,.37).pass(),"whole sampled descent");
        g.connect(0,1); require(g.next(0) == 1 && g.next(1) == 2 && g.root(0) == 2,"reuse canonical suffix");
        require(g.component(0) == g.component(1) && !g.eligible(0,1),"already shared is skip not merge");
        HeightField ridge = (x,z) -> -x+20*Math.exp(-Math.pow((x-.5)/.1,2));
        require(!check(ridge,p,.03125,0).pass() && !check(ridge,p,.0078125,.37).pass(),"reject interior rise");
        Point tail = new Point(.9,0);
        require(check(ridge,List.of(tail,p.get(1)),.0078125,.37).pass()
                && !check(ridge,List.of(p.get(0),tail,p.get(1)),.0078125,.37).pass(),
                "passing connector never excuses invalid source prefix");
        require(!check(slope,List.of(p.get(0),p.get(0),p.get(1)),.03125,0).pass(),"reject duplicate vertex");
        require(!check(slope,List.of(p.get(1),p.get(0)),.0078125,.37).pass(),"reject uphill route");
        require(!bits(-0.0,0.0),"raw bit equality");
        System.out.println("PASS RemainingRouteApply: full-route checks, canonical suffix, DSU skip, ridge/uphill/duplicate rejection");
    }
    private static void separate(Path out, Path input) {
        require(!out.startsWith(input) && !input.startsWith(out),"separate output and locked inputs");
    }
    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
            require(args.length == 2 || args.length == 3,"usage: RemainingRouteApplyProbe NEWout/data evidence/data [baseline/data]; cwd=MDK");
            Path mdk = Path.of("").toAbsolutePath().normalize(), repo = mdk.getParent();
            require(Files.isDirectory(mdk.resolve("src/main")) && Files.isRegularFile(repo.resolve("AGENTS.md")),"cwd MDK");
            Path out = Path.of(args[0]).toAbsolutePath().normalize();
            Path evidence = Path.of(args[1]).toAbsolutePath().normalize();
            Path base = (args.length == 3 ? Path.of(args[2]) : DEFAULT_BASE).toAbsolutePath().normalize();
            require(out.startsWith(mdk.resolve("build")) && !Files.exists(out),"fresh build output only");
            separate(out,base); separate(out,evidence); separate(out,mdk.resolve("src"));
            var p = new RemainingRouteApplyProbe(base,evidence,out,repo);
            Files.createDirectories(out); p.frozenGrid(); p.applyRoutes(); p.finishGraph(); p.finalChecks();
            p.exportGraph(); p.context(); p.metadata();
            System.out.println("Stage A diagnostic ONLY: applied="+p.applied+" skip="+p.skipped+" rejected="+p.rejected+" residual="+p.residual);
        } catch (Throwable t) { t.printStackTrace(System.err); System.exit(1); }
    }
}
