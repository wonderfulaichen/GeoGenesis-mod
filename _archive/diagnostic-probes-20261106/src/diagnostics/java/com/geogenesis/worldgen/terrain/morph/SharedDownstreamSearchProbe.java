package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.SharedDescentGraph;
import com.geogenesis.standalone.SharedDownstreamSearch;
import com.geogenesis.standalone.SharedDownstreamSearch.Graph;
import com.geogenesis.standalone.SharedDownstreamSearch.Node;
import com.geogenesis.standalone.SharedDownstreamSearch.Result;
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

/** Standalone diagnostic continuation ONLY. No renderer, production wiring, carving or flow. */
public final class SharedDownstreamSearchProbe {
    private static final Path DEFAULT_INPUT = Path.of("build/descent-graph-repair-20261104-final-v2/data");
    private static final String NODE_HEADER = "id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu";
    private static final String SEGMENT_HEADER = "source,target,segment,x0,z0,x1,z1,H0_source,H0_target";
    private record Source(Object instance, MethodHandle height, MethodHandle raw) implements HeightField {
        static Source create() throws Exception {
            Class<?> type = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
            var ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            return new Source(instance,method(type,instance,"height"),method(type,instance,"raw"));
        }
        private static MethodHandle method(Class<?> type, Object instance, String name) throws Exception {
            var m = type.getDeclaredMethod(name,double.class,double.class); m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m).bindTo(instance);
        }
        private static double invoke(MethodHandle m, double x, double z) {
            try { return (double)m.invokeExact(x,z); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }
        public double height(double x, double z) { return invoke(height,x,z); }
        double raw(double x, double z) { return invoke(raw,x,z); }
        double parameter(String name) throws Exception {
            var f = instance.getClass().getDeclaredField(name); f.setAccessible(true);
            return f.getDouble(instance);
        }
    }
    private final Path input, out, repo, log;
    private final Source field, independent;
    private final Map<Path,String> frozen = new TreeMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final List<int[]> indices = new ArrayList<>();
    private final Map<Integer,List<Point>> paths = new TreeMap<>();
    private final Map<Integer,Result> added = new TreeMap<>();
    private final Map<Integer,Boolean> finerValid = new TreeMap<>();
    private Graph graph;
    private int[] roots, indegrees;
    private double[] lengths;
    private int sources, successes, exhausted, budgets, beforeBoundary, afterBoundary, failures, finerFailures;
    private int expectedSources, noEligible, stateBudgets, restartBudgets;
    private long callbacks, fallbackCallbacks;
    private int fallbackAttempts, fallbackSuccesses, coarseStateBudgets, fallbackStateBudgets;
    private int coarseRestartBudgets, fallbackRestartBudgets;
    private record Tier(Result coarse, Result result, boolean fallback) {
        double lattice() { return fallback ? .25 : .5; }
        long fallbackCallbacks() { return fallback ? result.callbacks() : 0; }
    }
    private static Tier searchTiers(Graph graph, int id, HeightField field, HeightField independent) {
        Result coarse = SharedDownstreamSearch.search(graph,id,field,independent,
                new SharedDownstreamSearch.Settings());
        if (!coarse.reason().equals("NO_MONOTONE_ROUTE_UNKNOWN")) return new Tier(coarse,coarse,false);
        Result fine = SharedDownstreamSearch.search(graph,id,field,independent,
                new SharedDownstreamSearch.Settings(.25,12,32,4096));
        return new Tier(coarse,fine,true);
    }
    private void tierCounts(Tier tier) {
        callbacks += tier.coarse().callbacks()+tier.fallbackCallbacks();
        fallbackCallbacks += tier.fallbackCallbacks();
        if (tier.coarse().reason().equals("STATE_BUDGET_UNKNOWN")) coarseStateBudgets++;
        if (tier.coarse().reason().equals("RESTART_BUDGET_UNKNOWN")) coarseRestartBudgets++;
        if (!tier.fallback()) return;
        fallbackAttempts++; if (tier.result().success()) fallbackSuccesses++;
        if (tier.result().reason().equals("STATE_BUDGET_UNKNOWN")) fallbackStateBudgets++;
        if (tier.result().reason().equals("RESTART_BUDGET_UNKNOWN")) fallbackRestartBudgets++;
    }

    private SharedDownstreamSearchProbe(Path input, Path out, Path repo) throws Exception {
        this.input = input; this.out = out; this.repo = repo;
        log = repo.resolve("debug-agent.log").toAbsolutePath().normalize();
        freeze(repo.resolve("forge-1.20.1-47.4.10-mdk/src/main")); freeze(input);
        // Preserve the verified old run's DATA, not its historical active-source hashes.
        freeze(Path.of("build/shared-downstream-stage-a-20261104-final/data"));
        freeze(Path.of("src/diagnostics/java"));
        field = Source.create(); independent = Source.create();
        readGraph(); graph = new Graph(nodes);
    }
    private static void csv(Path file, String header, Consumer<String[]> row) throws Exception {
        try (var lines = Files.lines(file)) {
            var it = lines.iterator(); require(it.hasNext() && it.next().equals(header),"schema "+file);
            while (it.hasNext()) row.accept(it.next().split(",",-1));
        }
    }
    private static int integer(String s) { return Integer.parseInt(s); }
    private static double number(String s) { return Double.parseDouble(s); }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private void readGraph() throws Exception {
        csv(input.resolve("graph-nodes.csv"),NODE_HEADER,c -> {
            int id = integer(c[0]); require(id == nodes.size(),"stable row-major IDs");
            Node n = new Node(id,number(c[3]),number(c[4]),number(c[5]),integer(c[6]),c[7]);
            require(bits(n.h(),field.height(n.x(),n.z())),"original callback H0 "+id);
            int ix = integer(c[1]), iz = integer(c[2]);
            require(n.x() == ix*4.0 && n.z() == iz*4.0,"4wu lattice alignment");
            nodes.add(n); indices.add(new int[]{ix,iz});
        });
        csv(input.resolve("polyline-segments.csv"),SEGMENT_HEADER,c -> {
            int id = integer(c[0]); Node n = nodes.get(id);
            require(n.next() == integer(c[1]) && n.next() >= 0,"segment target");
            List<Point> p = paths.computeIfAbsent(id,k -> new ArrayList<>());
            Point a = new Point(number(c[3]),number(c[4])), b = new Point(number(c[5]),number(c[6]));
            if (p.isEmpty()) p.add(a);
            require(integer(c[2]) == p.size()-1 && p.get(p.size()-1).equals(a),"continuous authoritative geometry");
            require(bits(number(c[7]),field.height(a.x(),a.z()))
                    && bits(number(c[8]),field.height(b.x(),b.z())),"segment H0 unchanged");
            p.add(b);
        });
        for (Node n : nodes) if (n.next() >= 0) {
            List<Point> p = paths.get(n.id());
            require(p != null && p.get(0).equals(n.point())
                    && p.get(p.size()-1).equals(nodes.get(n.next()).point()),"complete old edge "+n.id());
        } else require(!paths.containsKey(n.id()),"stop has no old geometry");
    }
    private static double length(List<Point> p) {
        double sum = 0;
        for (int i = 1; i < p.size(); i++) sum += Math.hypot(p.get(i).x()-p.get(i-1).x(),p.get(i).z()-p.get(i-1).z());
        return sum;
    }
    private void frozenGrid() throws Exception {
        int n = 769;
        double step = field.parameter("step");
        int ox = (int)Math.round(-3242.5/step)-n/2, oz = (int)Math.round(-3365.5/step)-n/2;
        require(Files.size(input.resolve("base-raw.f64be")) == n*n*8L
                && Files.size(input.resolve("carrier-height.f64be")) == n*n*8L,"frozen769 sizes");
        try (var raw = binary(input.resolve("base-raw.f64be"));
             var h = binary(input.resolve("carrier-height.f64be"))) {
            for (int i = 0; i < n*n; i++) {
                double x = (ox+i%n)*step, z = (oz+i/n)*step;
                require(bits(raw.readDouble(),field.raw(x,z)),"full frozen raw "+i);
                require(bits(h.readDouble(),field.height(x,z)),"full frozen H0 "+i);
            }
        }
    }
    private static DataInputStream binary(Path p) throws Exception {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(p)));
    }
    private void debug(String message) throws Exception {
        // #region Stage A runtime hypothesis evidence (append only; never clear prior diagnostics)
        Files.writeString(log,Instant.now()+" SharedDownstreamSearch "+message+System.lineSeparator(),
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        // #endregion
    }
    private void search() throws Exception {
        debug("hypothesis=.5wu lattice can miss narrow monotone valleys, NOT proof of real basins; "
                +"test=.25wu fallback ONLY for .5 NO_MONOTONE_ROUTE_UNKNOWN; radius12 length32 states4096 PER TIER; "
                +"NO_ELIGIBLE and .5success not retried; output="+out);
        beforeBoundary = boundaryReach();
        expectedSources = (int)nodes.stream().filter(n -> n.next() < 0 && !n.state().equals("DOMAIN_BOUNDARY")).count();
        try (var w = Files.newBufferedWriter(out.resolve("search-sources.csv"));
             var geometry = Files.newBufferedWriter(out.resolve("search-paths.csv"))) {
            w.write("source,success,fail,budget,reason,target,explored,discovered,blocked,endpoint_rejects,coarse_rejects,finer_rejects,radius_rejects,length_rejects,same_component_targets,candidates,restarts,callbacks,length,target_root_at_join,target_reaches_boundary_at_join,eligibleNodesInDisk,lattice_wu,coarse_reason,coarse_explored,fallback_callbacks,coarse_state_budget,fallback_state_budget,coarse_callbacks,fallback_explored,coarse_restart_budget,fallback_restart_budget\n");
            geometry.write("source,target,vertex,x,z,H0,s_wu\n");
            for (Node n : nodes) {
                if (n.next() >= 0 || n.state().equals("DOMAIN_BOUNDARY")) continue;
                sources++;
                Tier tier = searchTiers(graph,n.id(),field,independent); tierCounts(tier);
                Result r = tier.result(); int targetRoot = r.success() ? graph.root(r.target()) : -1;
                boolean boundary = targetRoot >= 0 && graph.node(targetRoot).state().equals("DOMAIN_BOUNDARY");
                if (r.success()) {
                    graph.connect(n.id(),r.target()); added.put(n.id(),r); paths.put(n.id(),r.points()); successes++;
                    writeGeometry(geometry,n.id(),r);
                } else if (r.budget()) {
                    budgets++;
                    if (r.reason().equals("RESTART_BUDGET_UNKNOWN")) restartBudgets++; else stateBudgets++;
                } else if (r.reason().equals("NO_ELIGIBLE_UNKNOWN")) noEligible++;
                else exhausted++;
                String stats = stats(n.id(),r,targetRoot,boundary)+tierStats(tier);
                w.write(stats+"\n"); debug("sourceStats="+stats);
            }
        }
        require(sources == expectedSources,"all dynamically enumerated input internal stops searched");
        debug("rejectionSummary noEligible="+noEligible+" noMonotoneRoute="+exhausted
                +" stateBudget="+stateBudgets+" restartBudget="+restartBudgets+" sharedJoins="+successes
                +"; failures are UNKNOWN, not basins; hypothesis is not a global connectivity claim");
    }
    private static String tierStats(Tier tier) {
        Result c = tier.coarse(), r = tier.result(); boolean f = tier.fallback();
        return ","+tier.lattice()+","+c.reason()+","+c.explored()+","+tier.fallbackCallbacks()
                +","+c.reason().equals("STATE_BUDGET_UNKNOWN")+","+(f && r.reason().equals("STATE_BUDGET_UNKNOWN"))
                +","+c.callbacks()+","+(f ? r.explored() : 0)
                +","+c.reason().equals("RESTART_BUDGET_UNKNOWN")+","+(f && r.reason().equals("RESTART_BUDGET_UNKNOWN"));
    }
    private static String stats(int id, Result r, int root, boolean boundary) {
        return id+","+r.success()+","+!r.success()+","+r.budget()+","+r.reason()+","+r.target()+","+r.explored()
                +","+r.discovered()+","+r.blocked()+","+r.endpointRejects()+","+r.coarseRejects()+","+r.finerRejects()
                +","+r.radiusRejects()+","+r.lengthRejects()+","+r.sameComponentTargets()+","+r.candidates()+","+r.restarts()
                +","+r.callbacks()+","+r.length()+","+root+","+boundary+","+r.eligibleNodesInDisk();
    }
    private void writeGeometry(BufferedWriter w, int id, Result r) throws Exception {
        double arc = 0; Point previous = r.points().get(0);
        for (int i = 0; i < r.points().size(); i++) {
            Point p = r.points().get(i); arc += Math.hypot(p.x()-previous.x(),p.z()-previous.z());
            w.write(id+","+r.target()+","+i+","+p.x()+","+p.z()+","+field.height(p.x(),p.z())+","+arc+"\n"); previous = p;
        }
    }
    private int boundaryReach() {
        int count = 0;
        for (Node n : nodes) if (graph.node(graph.root(n.id())).state().equals("DOMAIN_BOUNDARY")) count++;
        return count;
    }
    private void finish() {
        roots = new int[graph.size()]; Arrays.fill(roots,-1);
        indegrees = new int[graph.size()]; lengths = new double[graph.size()];
        Integer[] order = new Integer[graph.size()];
        for (Node n : nodes) { order[n.id()] = n.id(); if (graph.next(n.id()) >= 0) indegrees[graph.next(n.id())]++; }
        Arrays.sort(order,Comparator.<Integer>comparingDouble(i -> nodes.get(i).h()).thenComparingInt(i -> i));
        for (int id : order) {
            int to = graph.next(id);
            if (to < 0) { roots[id] = id; continue; }
            require(nodes.get(to).h() < nodes.get(id).h() && roots[to] >= 0,"strict final DAG");
            roots[id] = roots[to]; lengths[id] = length(paths.get(id))+lengths[to];
        }
        afterBoundary = 0;
        for (int root : roots) if (nodes.get(root).state().equals("DOMAIN_BOUNDARY")) afterBoundary++;
    }
    private void finalChecks() throws Exception {
        try (var w = Files.newBufferedWriter(out.resolve("final-edge-checks.csv"))) {
            w.write("source,target,pitch,offset,passes,samples,min_drop\n");
            for (Node n : nodes) {
                if (graph.next(n.id()) < 0) continue;
                boolean old = n.next() >= 0;
                require(!old || graph.next(n.id()) == n.next(),"old successor retained");
                if (!check(w,n.id(),field,.03125,0)) failures++;
                if (added.containsKey(n.id())) {
                    boolean pass = check(w,n.id(),independent,.0078125,.37);
                    finerValid.put(n.id(),pass); if (!pass) finerFailures++;
                }
            }
        }
        debug("finalChecks allEdgeFailures="+failures+" addedFinerFailures="+finerFailures);
    }
    private boolean check(BufferedWriter w, int id, HeightField f, double pitch, double offset) throws Exception {
        List<Point> p = paths.get(id);
        require(p != null,"edge geometry present "+id);
        boolean pass = p.size() > 1; int samples = 0; double min = Double.POSITIVE_INFINITY;
        for (int i = 1; i < p.size(); i++) {
            Point a = p.get(i-1), b = p.get(i);
            var v = SharedDescentGraph.validate(f,a.x(),a.z(),b.x(),b.z(),pitch,offset);
            pass &= v.accepted(); samples += v.samples(); min = Math.min(min,v.minDrop());
        }
        w.write(id+","+graph.next(id)+","+pitch+","+offset+","+pass+","+samples+","+min+"\n");
        return pass;
    }
    private String state(Node n) {
        return graph.next(n.id()) >= 0 ? "DESCENT" : n.state();
    }
    private void exportGraph() throws Exception {
        try (var ns = Files.newBufferedWriter(out.resolve("graph-nodes.csv"));
             var es = Files.newBufferedWriter(out.resolve("edges.csv"));
             var ds = Files.newBufferedWriter(out.resolve("edge-descriptors.csv"));
             var actions = Files.newBufferedWriter(out.resolve("repair-actions.csv"));
             var residual = Files.newBufferedWriter(out.resolve("residual-roots.csv"))) {
            ns.write(NODE_HEADER+"\n"); es.write("source,target,x0,z0,x1,z1,H0_source,H0_target,samples,min_drop\n");
            ds.write("source,target,kind,vertices,length\n");
            actions.write("id,old_next,new_next,kind,reason,vertexcount,length,finervalid\n");
            residual.write("id,old_next,next,state,evidence\n");
            for (Node n : nodes) {
                int id = n.id(), to = graph.next(id); int[] ix = indices.get(id);
                ns.write(id+","+ix[0]+","+ix[1]+","+n.x()+","+n.z()+","+n.h()+","+to+","+state(n)
                        +","+indegrees[id]+","+roots[id]+","+lengths[id]+"\n");
                if (to < 0) {
                    if (!n.state().equals("DOMAIN_BOUNDARY")) residual.write(id+",-1,-1,"+n.state()+",UNKNOWN_NOT_PROVEN_BASIN\n");
                    continue;
                }
                Node b = nodes.get(to); List<Point> p = paths.get(id);
                es.write(id+","+to+","+n.x()+","+n.z()+","+b.x()+","+b.z()+","+n.h()+","+b.h()+",0,NaN\n");
                ds.write(id+","+to+","+(p.size()>2 ? "POLYLINE" : "STRAIGHT")+","+p.size()+","+length(p)+"\n");
                if (added.containsKey(id)) actions.write(id+",-1,"+to+",SHARED_DESCENT_SEARCH,EXACT_LOWER_CURRENT_COMPONENT_NODE,"
                        +p.size()+","+length(p)+","+finerValid.get(id)+"\n");
            }
        }
        exportSegments();
    }
    private void exportSegments() throws Exception {
        // Preserve every original geometry row verbatim; append only newly accepted polylines.
        Path file = out.resolve("polyline-segments.csv"); Files.copy(input.resolve("polyline-segments.csv"),file);
        require(Files.readString(file).endsWith("\n"),"baseline segment newline");
        try (var w = Files.newBufferedWriter(file,StandardOpenOption.APPEND)) {
            for (var e : added.entrySet()) {
                List<Point> p = e.getValue().points();
                for (int i = 1; i < p.size(); i++) {
                    Point a = p.get(i-1), b = p.get(i);
                    w.write(e.getKey()+","+e.getValue().target()+","+(i-1)+","+a.x()+","+a.z()+","+b.x()+","+b.z()
                            +","+field.height(a.x(),a.z())+","+field.height(b.x(),b.z())+"\n");
                }
            }
        }
    }
    private void context() throws Exception {
        for (String name : List.of("base-raw.f64be","carrier-height.f64be","sampling.json",
                "project-palette.csv","fixed-junction-windows.csv")) {
            Path from = input.resolve(name);
            if (!Files.exists(from)) continue;
            Files.copy(from,out.resolve(name)); require(hash(from).equals(hash(out.resolve(name))),"context exact");
        }
        try (var w = Files.newBufferedWriter(out.resolve("junctions.csv"))) {
            String header = "region,node,x,z,indegree,next,root_id,downstream_length_wu"; w.write(header+"\n");
            List<String[]> rows = new ArrayList<>(); csv(input.resolve("junctions.csv"),header,rows::add);
            for (String[] c : rows) {
                int id = integer(c[1]);
                if (id < 0) w.write(String.join(",",c)+"\n");
                else w.write(c[0]+","+id+","+c[2]+","+c[3]+","+indegrees[id]+","+graph.next(id)+","+roots[id]+","+lengths[id]+"\n");
            }
        }
    }
    private static String hash(Path p) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] buffer = new byte[65536]; for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void freeze(Path dir) throws Exception {
        require(Files.isDirectory(dir),"frozen input directory "+dir);
        try (var files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) frozen.put(p.toAbsolutePath().normalize(),hash(p));
        }
    }
    private void metadata(double seconds) throws Exception {
        try (var w = Files.newBufferedWriter(out.resolve("locked-inputs.csv"))) {
            w.write("path,sha256\n");
            for (var e : frozen.entrySet()) {
                require(e.getValue().equals(hash(e.getKey())),"frozen input drift "+e.getKey());
                w.write("\""+e.getKey().toString().replace(java.io.File.separator,"/")+"\","+e.getValue()+"\n");
            }
        }
        Files.writeString(out.resolve("checks.json"),"{\"stage\":\"A only\",\"sources\":"+sources
                +",\"sharedJoins\":"+successes+",\"rootReduction\":"+successes+",\"componentReduction\":"+successes
                +",\"expectedSources\":"+expectedSources+",\"noEligibleUnknown\":"+noEligible
                +",\"noMonotoneRouteAtLocalResolutionUnknown\":"+exhausted+",\"budgetUnknown\":"+budgets
                +",\"stateBudgetUnknown\":"+stateBudgets+",\"restartBudgetUnknown\":"+restartBudgets
                +",\"maxRestarts\":"+SharedDownstreamSearch.MAX_RESTARTS+",\"wholeSuffixIndependentFineChecked\":false"
                +",\"nodesReachingBoundaryBefore\":"+beforeBoundary+",\"nodesReachingBoundaryAfter\":"+afterBoundary
                +",\"finalFineFailures\":"+failures+",\"addedIndependentFailures\":"+finerFailures
                +",\"allFinalEdgeSpacing\":0.03125,\"addedIndependentSpacing\":0.0078125,\"independentOffset\":0.37"
                +",\"latticeWu\":0.5,\"fallbackLatticeWu\":0.25,\"radiusWu\":12,\"maxLengthWu\":32"
                +",\"maxStatesIncludingRestartsPerTier\":4096,\"fallbackOnlyCoarseNoMonotoneRoute\":true"
                +",\"noEligibleRechecked\":false,\"coarseSuccessRetried\":false,\"twoTierSearchIsLocalNotGlobal\":true"
                +",\"fallbackAttempts\":"+fallbackAttempts+",\"fallbackSuccesses\":"+fallbackSuccesses
                +",\"coarseOnlyUnknownRepairNetGain\":"+fallbackSuccesses
                +",\"netGainScope\":\"same-run current-DSU coarse UNKNOWN resolved by fallback; not total difference versus prior run\""
                +",\"coarseStateBudgetUnknown\":"+coarseStateBudgets+",\"fallbackStateBudgetUnknown\":"+fallbackStateBudgets
                +",\"coarseRestartBudgetUnknown\":"+coarseRestartBudgets+",\"fallbackRestartBudgetUnknown\":"+fallbackRestartBudgets
                +",\"fallbackCallbacks\":"+fallbackCallbacks
                +",\"searchSourcesStatistics\":\"base columns describe selected final tier; coarse and fallback columns preserve each tier\""
                +",\"fullFrozen769RawAndH0CallbackEquality\":true,\"frozenHashesVerified\":true,\"oldEdgesUnchanged\":true"
                +",\"strictDAG\":true,\"unknownIsNotBasin\":true,\"heightUnchanged\":true,\"noCarve\":true,\"phaseB\":false,\"phaseC\":false"
                +",\"edgeGeometry\":\"polyline-segments.csv authoritative; edges endpoint inventory only\""
                +",\"samplingJson\":\"verbatim frozen context; old geometry description superseded here\""
                +",\"continuousMonotonicityProven\":false,\"wholeWorldConnectivityProven\":false"
                +",\"searchCallbacks\":"+callbacks+",\"seconds\":"+seconds+"}\n");
        debug("summary componentReduction="+successes+" boundaryNodesBefore="+beforeBoundary+" boundaryNodesAfter="+afterBoundary
                +" coarseOnlyUnknownRepairNetGain="+fallbackSuccesses+" fallbackAttempts="+fallbackAttempts
                +"; same-run current-DSU gain, not total difference versus prior run");
        require(failures == 0 && finerFailures == 0,"final sampled edge checks failed; no old-edge edits permitted");
    }
    private static void selfTest() {
        HeightField narrow = (x,z) -> -x+8*Math.pow(z-.25*Math.sin(2*Math.PI*x),2);
        Graph g = new Graph(List.of(new Node(0,0,0,narrow.height(0,0),-1,"BOUND_NO_DESCENT"),
                new Node(1,4,0,narrow.height(4,0),-1,"DOMAIN_BOUNDARY")));
        Tier t = searchTiers(g,0,narrow,narrow);
        require(t.coarse().reason().equals("NO_MONOTONE_ROUTE_UNKNOWN") && t.fallback()
                && t.result().success() && t.result().target() == 1,"narrow valley fallback joins exact target");
        require(t.coarse().explored() <= 4096 && t.result().explored() <= 4096,"separate tier state budgets");
        require(g.next(0) == -1,"tier searches never mutate DSU before validated acceptance");
        g.connect(0,t.result().target()); require(g.root(0) == 1,"canonical accepted fallback suffix");
        HeightField flat = (x,z) -> 0;
        Graph none = new Graph(List.of(new Node(0,0,0,0,-1,"BOUND_NO_DESCENT")));
        Tier n = searchTiers(none,0,flat,flat);
        require(!n.fallback() && n.coarse().reason().equals("NO_ELIGIBLE_UNKNOWN")
                && n.fallbackCallbacks() == 0,"NO_ELIGIBLE never rechecked");
        HeightField slope = (x,z) -> -x;
        Graph down = new Graph(List.of(new Node(0,0,0,slope.height(0,0),-1,"BOUND_NO_DESCENT"),
                new Node(1,4,0,-4,-1,"DOMAIN_BOUNDARY")));
        Tier d = searchTiers(down,0,slope,slope);
        require(d.result().success() && !d.fallback() && d.lattice() == .5,"coarse success untouched");
        System.out.println("PASS: probe narrow-valley .25 fallback, per-tier4096, exact suffix, no-eligible skip, coarse-success skip");
    }
    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
            require(args.length == 1 || args.length == 2,"usage: SharedDownstreamSearchProbe NEWout/data [baseline/data]; cwd=MDK");
            Path mdk = Path.of("").toAbsolutePath().normalize(), repo = mdk.getParent();
            require(Files.isRegularFile(repo.resolve("AGENTS.md")) && Files.isDirectory(mdk.resolve("src/main")),"run from MDK");
            Path input = (args.length == 2 ? Path.of(args[1]) : DEFAULT_INPUT).toAbsolutePath().normalize();
            Path out = Path.of(args[0]).toAbsolutePath().normalize();
            require(out.startsWith(mdk.resolve("build")) && !Files.exists(out) && !out.startsWith(input)
                    && !input.startsWith(out),"fresh separate build output only");
            long start = System.nanoTime();
            var p = new SharedDownstreamSearchProbe(input,out,repo);
            Files.createDirectories(out); p.frozenGrid(); p.search(); p.finish(); p.finalChecks();
            p.exportGraph(); p.context(); p.metadata((System.nanoTime()-start)/1e9);
            System.out.println("Stage A ONLY complete; shared joins="+p.successes+"; UNKNOWN="+(p.noEligible+p.exhausted+p.budgets));
        } catch (Throwable t) { t.printStackTrace(System.err); System.exit(1); }
    }
}
