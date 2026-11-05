package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.RemainingStopEvidence;
import com.geogenesis.standalone.RemainingStopEvidence.Evidence;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Exhaustive original-stop inventory; candidate evidence only, no graph output or mutation. */
public final class RemainingStopEvidenceProbe {
    private static final Path INPUT = Path.of("build/shared-downstream-stage-a-20261104-final-v2/data");
    private static final String OLD_FIELDS = "source,x,z,sourceH0,classification,status,first_failed_segment,trace_length,verified_advance,terminal_x,terminal_z,endpointH0,verified_terminal_x,verified_terminal_z,verified_terminalH0,target,join_arc,join_is_midtrace";
    private record Source(Object instance, MethodHandle h, MethodHandle raw) implements HeightField {
        static Source create() throws Exception {
            Class<?> c = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
            var ctor = c.getDeclaredConstructor(); ctor.setAccessible(true); Object o = ctor.newInstance();
            return new Source(o,method(c,o,"height"),method(c,o,"raw"));
        }
        private static MethodHandle method(Class<?> c, Object o, String name) throws Exception {
            var m = c.getDeclaredMethod(name,double.class,double.class); m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m).bindTo(o);
        }
        private static double call(MethodHandle m, double x, double z) {
            try { return (double)m.invokeExact(x,z); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }
        public double height(double x, double z) { return call(h,x,z); }
        double rawValue(double x, double z) { return call(raw,x,z); }
        double step() throws Exception {
            var f = instance.getClass().getDeclaredField("step"); f.setAccessible(true); return f.getDouble(instance);
        }
    }
    private final Path out, log;
    private final Map<Path,String> frozen = new TreeMap<>();
    private final Map<String,Integer> counts = new TreeMap<>();
    private final Map<String,Integer> selections = new LinkedHashMap<>();
    private final List<Node> nodes = new ArrayList<>(), stops = new ArrayList<>();
    private final Source field, independent;
    private Graph graph;
    private RemainingStopEvidenceProbe(Path mdk, Path output) throws Exception {
        out = output; log = mdk.getParent().resolve("debug-agent.log").toAbsolutePath();
        freeze(mdk.resolve("src/main")); freeze(mdk.resolve(INPUT));
        freeze(mdk.resolve("src/diagnostics/java"));
        freeze(Path.of(RemainingStopEvidenceProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        freeze(mdk.resolve("build/remaining-stop-evidence-20261104"));
        field = Source.create(); independent = Source.create(); readNodes(mdk.resolve(INPUT));
    }
    private void readNodes(Path input) throws Exception {
        try (var lines = Files.lines(input.resolve("graph-nodes.csv"))) {
            var it = lines.iterator();
            require(it.next().equals("id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu"),"node schema");
            while (it.hasNext()) {
                String[] c = it.next().split(",");
                Node n = new Node(Integer.parseInt(c[0]),Double.parseDouble(c[3]),Double.parseDouble(c[4]),
                        Double.parseDouble(c[5]),Integer.parseInt(c[6]),c[7]);
                require(n.id() == nodes.size() && bits(n.h(),field.height(n.x(),n.z())),"canonical original H0");
                nodes.add(n);
                if (n.next() < 0 && !n.state().equals("DOMAIN_BOUNDARY")) stops.add(n);
            }
        }
        require(stops.size() == 3959,"retain all 3959 original internal stops"); graph = new Graph(nodes);
        double cx = (nodes.get(0).x()+nodes.get(nodes.size()-1).x())/2;
        double cz = (nodes.get(0).z()+nodes.get(nodes.size()-1).z())/2;
        for (Node n : stops) selections.putIfAbsent("quadrant_"+(n.x()<cx?"W":"E")+(n.z()<cz?"N":"S"),n.id());
        require(selections.size() == 4,"four quadrants selected before tracing");
    }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private void grid() throws Exception {
        int n = 769; double step = field.step();
        int ox = (int)Math.round(-3242.5/step)-n/2, oz = (int)Math.round(-3365.5/step)-n/2;
        Path input = INPUT.toAbsolutePath();
        require(Files.size(input.resolve("base-raw.f64be")) == n*n*8L
                && Files.size(input.resolve("carrier-height.f64be")) == n*n*8L,"769 sizes");
        try (var raw = binary(input.resolve("base-raw.f64be")); var h = binary(input.resolve("carrier-height.f64be"))) {
            for (int i = 0; i < n*n; i++) {
                double x = (ox+i%n)*step, z = (oz+i/n)*step;
                require(bits(raw.readDouble(),field.rawValue(x,z)),"full frozen raw "+i);
                require(bits(h.readDouble(),field.height(x,z)),"full frozen H0 "+i);
            }
        }
        debug("full769RawAndH0=bitwisePASS; originalStops=3959; fixedSelections="+selections);
    }
    private static DataInputStream binary(Path p) throws Exception {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(p)));
    }
    private void debug(String s) throws Exception {
        Files.writeString(log,Instant.now()+" RemainingStopEvidence "+s+System.lineSeparator(),
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private BufferedWriter writer(String name, String header) throws Exception {
        var w = Files.newBufferedWriter(out.resolve(name+".csv")); w.write(header+"\n"); return w;
    }
    private void run() throws Exception {
        debug("hypothesis=fixed .25/.5 lattice misses narrow valleys versus no discovered original-field route; "
                +"Heun existing tracer settings=8/0/.25/.015625/24/.0625; finite sampling only; NO_DESCENT never basin; "
                +"connect every verified vertex plus .5wu samples, nearby8 short4; no graph mutation; output="+out);
        try (var summary = writer("classifications",OLD_FIELDS+",prefix_descended,fineAllVerified,terminal_reachable_verified");
             var traces = writer("trace-paths","source,vertex,x,z,H0,s_wu,prefix_verified");
             var connectors = writer("connector-paths","source,target,vertex,x,z,H0,kind");
             var samples = writer("ring-samples","source,terminal_x,terminal_z,endpointH0,radius,angle_index,angle_rad,x,z,H0,barrier");
             var rings = writer("ring-summary","source,radius,min_barrier,all_samples_higher,lowest_angle_index,lowest_angle_rad")) {
            int done = 0;
            for (Node n : stops) {
                Evidence e = RemainingStopEvidence.inspect(graph,n.id(),field,independent);
                counts.merge(e.classification(),1,Integer::sum);
                selections.putIfAbsent("classification_"+e.classification(),n.id());
                writeSummary(summary,n,e); writeTrace(traces,n,e); writeConnector(connectors,n,e); writeRings(samples,rings,n,e);
                if (++done%250 == 0) { System.out.println("PROGRESS "+done+"/3959 "+counts); debug("progress="+done+" counts="+counts); }
            }
        }
        requests();
        for (Node n : nodes) require(graph.next(n.id()) == n.next(),"all graph successors unchanged");
    }
    private void writeSummary(BufferedWriter w, Node n, Evidence e) throws Exception {
        var t = e.trace(); Point end = t.points().get(t.points().size()-1);
        Point v = com.geogenesis.standalone.NoiseFollowingErosion.pointAt(t,e.verifiedAdvance());
        var c = e.connector();
        w.write(n.id()+","+n.x()+","+n.z()+","+n.h()+","+e.classification()+","+t.status()+","+e.firstFailed()
                +","+t.length()+","+e.verifiedAdvance()+","+end.x()+","+end.z()+","+field.height(end.x(),end.z())
                +","+v.x()+","+v.z()+","+field.height(v.x(),v.z())+","+(c==null?-1:c.target())
                +","+(c==null?"NaN":c.arc())+","+(c!=null && c.arc()<t.length())
                +","+e.prefixDescended()+","+e.fineAllVerified()+","+e.terminalReachableVerified()+"\n");
    }
    private void writeTrace(BufferedWriter w, Node n, Evidence e) throws Exception {
        for (int i = 0; i < e.trace().points().size(); i++) {
            Point p = e.trace().points().get(i);
            w.write(n.id()+","+i+","+p.x()+","+p.z()+","+field.height(p.x(),p.z())+","+e.trace().arc().get(i)
                    +","+(e.firstFailed()<0 || i<=e.firstFailed())+"\n");
        }
    }
    private void writeConnector(BufferedWriter w, Node n, Evidence e) throws Exception {
        if (e.connector() == null) return;
        List<Point> route = RemainingStopEvidence.route(e.trace(),e.connector().arc(),
                e.connector().from(),e.connector().to());
        for (int i = 0; i < route.size(); i++) {
            Point p = route.get(i);
            w.write(n.id()+","+e.connector().target()+","+i+","+p.x()+","+p.z()+","+field.height(p.x(),p.z())
                    +","+(i==route.size()-1?"CANONICAL_NODE":"VERIFIED_TRACE_PREFIX")+"\n");
        }
    }
    private void writeRings(BufferedWriter samples, BufferedWriter summary, Node n, Evidence e) throws Exception {
        Point end = e.trace().points().get(e.trace().points().size()-1);
        double h = field.height(end.x(),end.z());
        for (var r : e.rings()) {
            summary.write(n.id()+","+r.radius()+","+r.minBarrier()+","+r.allHigher()+","+r.lowestAngle()+","+r.lowestAngle()*Math.PI/32+"\n");
            for (int i = 0; i < 64; i++) {
                double angle = i*Math.PI/32, value = r.heights().get(i);
                samples.write(n.id()+","+end.x()+","+end.z()+","+h+","+r.radius()+","+i+","+angle+","+(end.x()+r.radius()*Math.cos(angle))+","
                        +(end.z()+r.radius()*Math.sin(angle))+","+value+","+(value-h)+"\n");
            }
        }
    }
    private void requests() throws Exception {
        Map<Integer,String> kinds = new TreeMap<>();
        try (var lines = Files.lines(out.resolve("classifications.csv"))) {
            lines.skip(1).forEach(line -> { String[] c = line.split(","); kinds.put(Integer.parseInt(c[0]),c[4]); });
        }
        try (var w = writer("requests","selection,source,x,z,sourceH0,classification")) {
            for (var e : selections.entrySet()) {
                Node n = nodes.get(e.getValue());
                w.write(e.getKey()+","+n.id()+","+n.x()+","+n.z()+","+n.h()+","+kinds.get(n.id())+"\n");
            }
        }
    }
    private int boundaryReachBeforeFromInput() {
        int count = 0;
        for (Node n : nodes) if (graph.node(graph.root(n.id())).state().equals("DOMAIN_BOUNDARY")) count++;
        return count;
    }
    private void typicalSupplement() throws Exception {
        Path dir = out.getParent().resolve("closeups");
        require(!Files.exists(dir),"fresh closeups only");
        freeze(out); grid();
        Map<Integer,String> kinds = new TreeMap<>();
        try (var lines = Files.lines(out.resolve("classifications.csv"))) {
            var it = lines.iterator(); String[] header = it.next().split(","); int column = -1;
            for (int i = 0; i < header.length; i++) if (header[i].equals("classification")) column = i;
            require(column >= 0,"classification column");
            while (it.hasNext()) {
                String[] row = it.next().split(","); int id = Integer.parseInt(row[0]);
                String kind = row[column].replace("FINITE_RING_BARRIER_CANDIDATE","FINITE_TERMINAL_RING_BARRIER_CANDIDATE");
                require(kinds.put(id,kind) == null,"unique original source");
                selections.putIfAbsent("classification_"+kind,id);
            }
        }
        require(kinds.size() == 3959,"all original classifications preserved");
        Map<Integer,String> unique = new LinkedHashMap<>();
        selections.forEach((selection,id)->unique.merge(id,selection,(a,b)->a+";"+b));
        Files.createDirectory(dir);
        try (var fixed = Files.newBufferedWriter(dir.resolve("fixed-typical.csv"));
             var grids = Files.newBufferedWriter(dir.resolve("typical-grids.csv"))) {
            fixed.write("case,source,x,z,classification,selection\n");
            grids.write("case,source,ix,iz,x,z,H0\n"); int caseId = 0;
            for (var entry : unique.entrySet()) {
                Node n = graph.node(entry.getKey()); String name = "case-"+(++caseId);
                fixed.write(name+","+n.id()+","+n.x()+","+n.z()+","+kinds.get(n.id())+","+entry.getValue()+"\n");
                for (int iz = 0; iz < 257; iz++) for (int ix = 0; ix < 257; ix++) {
                    double x = n.x()+(ix-128)*.125, z = n.z()+(iz-128)*.125;
                    grids.write(name+","+n.id()+","+ix+","+iz+","+x+","+z+","+field.height(x,z)+"\n");
                }
            }
        }
        for (var e : frozen.entrySet()) require(e.getValue().equals(hash(e.getKey())),"unchanged "+e.getKey());
        Files.writeString(dir.resolve("metadata.json"),"{\"boundaryReachBeforeFromInput\":"+boundaryReachBeforeFromInput()
                +",\"stopVsEndpoint\":{\"stop\":\"original input internal stop; grid centered on source\",\"endpoint\":\"actual trace terminal, ring center; NOT original stop enclosure\"}"
                +",\"sourceScope\":\"pre-fancy experimental H0 via original NoiseFollowingProbe.height/raw; NOT current in-game production chain\""
                +",\"gridPitchWu\":0.125,\"gridSize\":257,\"halfWidthWu\":16,\"typicalCases\":"+unique.size()
                +",\"full769RawH0Bitwise\":true,\"inputAndOldOutputsUnchanged\":true,\"displayOnly\":true,\"searchRerun\":false"
                +",\"mapping\":\"preserve original e/colourshade mapping; no per-case normalization\",\"ringScope\":\"finite terminal sampled ring only; no continuous closure, no no-route proof\"}\n");
        debug("closeups="+dir+" cases="+unique.size()+" boundaryReachBeforeFromInput="+boundaryReachBeforeFromInput()
                +" scope=pre-fancy experimental source NOT current production; no fold inference; searchRerun=false");
        System.out.println("CLOSEUPS "+dir+" cases="+unique.size()+" gridRows="+(unique.size()*257*257));
    }
    private static String hash(Path p) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] b = new byte[65536]; for (int n; (n=in.read(b)) != -1;) digest.update(b,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void freeze(Path dir) throws Exception {
        try (var files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) frozen.put(p.toAbsolutePath().normalize(),hash(p));
        }
    }
    private void finish(double seconds) throws Exception {
        try (var w = writer("locked-inputs","path,sha256")) {
            for (var e : frozen.entrySet()) {
                require(e.getValue().equals(hash(e.getKey())),"frozen drift "+e.getKey());
                w.write("\""+e.getKey().toString().replace('\\','/')+"\","+e.getValue()+"\n");
            }
        }
        StringBuilder c = new StringBuilder("{");
        for (var e : counts.entrySet()) { if (c.length()>1) c.append(','); c.append('"').append(e.getKey()).append("\":").append(e.getValue()); }
        c.append('}');
        Files.writeString(out.resolve("checks.json"),"{\"sources\":3959,\"counts\":"+c+",\"full769RawAndH0Bitwise\":true,"
                +"\"frozenHashesVerified\":true,\"graphUnchanged\":true,\"continuousProof\":false,\"basinProven\":false,"
                +"\"ringsAtActualTraceEndpoint\":true,\"finiteRingRequiresWholeVerifiedSourceDescent\":true,"
                +"\"connectorMaxWu\":4,\"targetNeighborhoodWu\":8,\"allVerticesAndHalfWuTestedUntilFirstCandidate\":true,"
                +"\"segmentProximityComputed\":false,\"boundaryReachBeforeFromInput\":"+boundaryReachBeforeFromInput()
                +",\"stopVsEndpoint\":\"stop is original input source; terminal is actual trace end, not necessarily verified reachable; positive ring concerns terminal only\""
                +",\"sourceScope\":\"pre-fancy experimental NoiseFollowingProbe H0/raw, NOT current in-game production chain\""
                +",\"wholeConnectorPathDualVerified\":true,\"ringIsStopNoRouteExplanation\":false,\"seconds\":"+seconds+"}\n");
        debug("COMPLETE counts="+counts+" seconds="+seconds+" graphUnchanged=true frozenHashesVerified=true; finite ring not continuous enclosure; no basin conclusion");
        System.out.println("COMPLETE "+counts+" seconds="+seconds);
    }
    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            if (args.length == 1 && args[0].equals("--self-test")) { RemainingStopEvidence.selfTest(); return; }
            if (args.length == 2 && args[0].equals("--reproduce-source")) {
                var p = new RemainingStopEvidenceProbe(Path.of("").toAbsolutePath().normalize(),
                        Path.of("build/remaining-stop-reproduction-unused/data").toAbsolutePath());
                int id = Integer.parseInt(args[1]);
                System.out.println(RemainingStopEvidence.inspect(p.graph,id,p.field,p.independent)); return;
            }
            boolean typical = args.length == 2 && args[0].equals("--typical-grids");
            require(typical || args.length == 1,"usage NEWrun/data OR --typical-grids NEWrun; cwd MDK");
            Path mdk = Path.of("").toAbsolutePath().normalize();
            Path output = Path.of(args[typical?1:0]).toAbsolutePath().normalize();
            if (typical) output = output.resolve("data");
            require(Files.isDirectory(mdk.resolve("src/main")) && output.startsWith(mdk.resolve("build"))
                    && output.getFileName().toString().equals("data"),"MDK build NEWrun/data only");
            require(typical ? Files.isDirectory(output) : !Files.exists(output),"fresh output or existing typical input");
            Files.createDirectories(output.getParent());
            try (var lock = Files.newByteChannel(output.getParent().resolve(typical?"closeups.lock":"exclusive.lock"),
                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                require(lock.isOpen(),"exclusive run lock");
                long start = System.nanoTime(); var p = new RemainingStopEvidenceProbe(mdk,output);
                if (typical) { p.typicalSupplement(); return; }
                Files.createDirectory(p.out); p.grid(); p.run(); p.finish((System.nanoTime()-start)/1e9);
            }
        } catch (Throwable t) { t.printStackTrace(System.err); System.exit(1); }
    }
}
