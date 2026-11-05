package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.DescentTerminalAudit;
import com.geogenesis.standalone.DescentTerminalAudit.Evidence;
import com.geogenesis.standalone.DescentTerminalAudit.Pool;
import com.geogenesis.standalone.DescentTerminalAudit.Profile;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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
import static com.geogenesis.standalone.DescentTerminalAudit.require;

/** Stage A only: frozen graph CSV reader; complete H0 callback, no graph mutation or carving. */
public final class DescentTerminalAuditProbe {
    private static final Path DEFAULT = Path.of("build/shared-descent-stage-a-20261104/data");
    private record Node(int id, int ix, int iz, double x, double z, double height,
                        int next, String state, int root) {
        Point point() { return new Point(x,z); }
    }
    private record Edge(Node a, Node b) {}
    private record Bridge(int root, double distance, int node, String status) {}
    private final List<Node> nodes = new ArrayList<>(), roots = new ArrayList<>();
    private final Map<Integer,Node> byId = new HashMap<>();
    private final Map<String,Node> grid = new HashMap<>();
    private final Map<Path,String> locks = new TreeMap<>();
    private final Map<String,Integer> counts = new TreeMap<>();
    private final Path input;
    private final Object source;
    private final HeightField field;
    private final double minX, minZ, maxX, maxZ, step;

    private DescentTerminalAuditProbe(Path input) throws Exception {
        this.input = input;
        lock(input); lock(Path.of("src/main")); lock(Path.of("src/diagnostics"));
        var type = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        source = constructor.newInstance();
        var method = type.getDeclaredMethod("height",double.class,double.class); method.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(source);
        field = (x,z)->invoke(handle,x,z);
        step = parameter("step"); minX = parameter("originX"); minZ = parameter("originZ");
        maxX = minX+768*step; maxZ = minZ+768*step;
        readGraph();
    }
    private static double invoke(MethodHandle handle, double x, double z) {
        try { return (double) handle.invokeExact(x,z); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    private double parameter(String name) throws Exception {
        var f = source.getClass().getDeclaredField(name); f.setAccessible(true); return f.getDouble(source);
    }
    private void lock(Path directory) throws Exception {
        require(Files.isDirectory(directory),"missing input "+directory);
        try (var files = Files.walk(directory)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) locks.put(p,hash(p));
        }
    }
    private static String hash(Path file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n=in.read(buffer))!=-1;) d.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private void readGraph() throws Exception {
        try (var lines = Files.lines(input.resolve("graph-nodes.csv"))) {
            for (String line : lines.skip(1).toList()) {
                String[] c = line.split(",");
                Node n = new Node(Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]),
                        Double.parseDouble(c[3]),Double.parseDouble(c[4]),Double.parseDouble(c[5]),
                        Integer.parseInt(c[6]),c[7],Integer.parseInt(c[9]));
                require(byId.put(n.id(),n)==null,"unique frozen id"); nodes.add(n);
                grid.put(n.ix()+":"+n.iz(),n);
                require(bits(field.height(n.x(),n.z()),n.height()),"frozen node H0 bits "+n.id());
                if (n.state().equals("BOUND_NO_DESCENT")) {
                    require(n.next()==-1 && n.root()==n.id(),"internal terminal root"); roots.add(n);
                }
            }
        }
        require(!roots.isEmpty(),"no terminal roots");
        roots.sort(Comparator.comparingInt(Node::iz).thenComparingInt(Node::ix).thenComparingInt(Node::id));
    }
    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);
    }
    private void verifyGrid() throws Exception {
        Path carrier = input.resolve("carrier-height.f64be");
        require(Files.size(carrier)==769L*769*8,"complete frozen H0 size");
        int ox = (int)Math.round(minX/step), oz = (int)Math.round(minZ/step);
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(carrier)))) {
            for (int r=0;r<769;r++) for (int c=0;c<769;c++)
                require(bits(in.readDouble(),field.height((ox+c)*step,(oz+r)*step)),"full H0 bits");
        }
        System.out.println("FULL 769x769 H0 and all frozen graph-node bits verified");
    }
    private boolean central(Node n) {
        return n.x()>=minX && n.x()<=maxX && n.z()>=minZ && n.z()<=maxZ;
    }
    private List<Node> fixedSamples(Path out) throws Exception {
        double loX=roots.stream().mapToDouble(Node::x).min().orElseThrow();
        double loZ=roots.stream().mapToDouble(Node::z).min().orElseThrow();
        double hiX=roots.stream().mapToDouble(Node::x).max().orElseThrow();
        double hiZ=roots.stream().mapToDouble(Node::z).max().orElseThrow();
        int[] selected = new int[16]; List<Node> result = new ArrayList<>();
        try (var csv=Files.newBufferedWriter(out.resolve("fixed-samples.csv"))) {
            csv.write("root,partition,x,z,selection_order\n");
            for (Node n:roots) {
                int col=Math.min(3,(int)((n.x()-loX)/(hiX-loX)*4));
                int row=Math.min(3,(int)((n.z()-loZ)/(hiZ-loZ)*4));
                int cell=row*4+col;
                if (selected[cell]>=2) continue;
                selected[cell]++; result.add(n);
                csv.write(n.id()+","+cell+","+n.x()+","+n.z()+","+result.size()+"\n");
            }
        }
        require(result.size()==32,"32 predetermined spatial samples required");
        return List.copyOf(result);
    }

    private Bridge bridge(Node root, Evidence e, BufferedWriter csv) throws Exception {
        if (!e.valid()) return new Bridge(-1,Double.NaN,-1,"NO_VALID_WITNESS");
        Point end=e.trace().points().get(e.trace().points().size()-1);
        List<Node> candidates=new ArrayList<>();
        int ix=(int)Math.round(end.x()/4), iz=(int)Math.round(end.z()/4);
        for(int dz=-3;dz<=3;dz++) for(int dx=-3;dx<=3;dx++) {
            Node n=grid.get((ix+dx)+":"+(iz+dz));
            if(n!=null && n.root()!=root.id() && n.height()<root.height()
                    && Math.hypot(n.x()-end.x(),n.z()-end.z())<=8) candidates.add(n);
        }
        candidates.sort(Comparator.comparingDouble((Node n)->Math.hypot(n.x()-end.x(),n.z()-end.z()))
                .thenComparingInt(Node::id));
        Bridge best=new Bridge(-1,Double.NaN,-1,"WITNESS_ONLY");
        for(Node n:candidates.subList(0,Math.min(16,candidates.size()))) {
            double d=Math.hypot(n.x()-end.x(),n.z()-end.z());
            boolean lower=field.height(n.x(),n.z())<field.height(end.x(),end.z());
            Profile p=d==0 ? null : DescentTerminalAudit.profile(field,end,n.point(),.0073,0,null,"");
            boolean valid=d<4 && lower && p!=null && p.accepted() && root.height()>n.height();
            csv.write(root.id()+","+n.id()+","+n.root()+","+end.x()+","+end.z()+","+n.x()+","+n.z()
                    +","+d+","+lower+","+(p==null?0:p.rises())+","+valid+",false\n");
            if(best.node()==-1 && valid) best=new Bridge(n.root(),d,n.id(),"CANDIDATE_BRIDGE_WITNESS");
        }
        return best;
    }

    private void auditRoots(Path out, List<Node> fixed) throws Exception {
        var fixedIds=fixed.stream().map(Node::id).toList();
        try(var csv=Files.newBufferedWriter(out.resolve("audit.csv"));
            var paths=Files.newBufferedWriter(out.resolve("witness-paths.csv"));
            var pools=Files.newBufferedWriter(out.resolve("basin-summary.csv"));
            var grids=Files.newBufferedWriter(out.resolve("basin-grids.csv"));
            var bridges=Files.newBufferedWriter(out.resolve("bridge-candidates.csv"))) {
            csv.write("root,x,z,traceLength,traceStatus,fineRises,endx,endz,nearestTargetRoot,targetDistance,classification,bridgeStatus,targetNode,window\n");
            paths.write("root,vertex,x,z,H0,s_wu\n");
            pools.write("root,pitch,shift,minx,minz,minH0,spillHeight,closedDepth,depthY,walkSteps,boundaryMinimum,classification\n");
            grids.write("root,pitch,shift,vertex,x,z,H0,spillHeight,closedDepth\n");
            bridges.write("root,targetNode,targetRoot,endx,endz,targetx,targetz,distance,endHigher,fineRises,candidateBridge,connected\n");
            int done=0;
            for(Node n:roots) {
                Evidence e=DescentTerminalAudit.audit(field,Integer.toString(n.id()),n.x(),n.z());
                String classification="DESCENT_WITNESS_GRID_STOP";
                if(e.valid()) DescentTerminalAudit.writeWitness(paths,field,e);
                if(!e.valid() || fixedIds.contains(n.id())) {
                    Pool pool=DescentTerminalAudit.pool(field,n.x(),n.z(),.125,0);
                    poolSummary(pools,n,pool);
                    if(!e.valid()) classification=pool.classification();
                    if(fixedIds.contains(n.id())) {
                        DescentTerminalAudit.writePool(grids,n.id(),pool);
                        for(double shift:new double[]{0,.03125}) {
                            Pool refined=DescentTerminalAudit.pool(field,n.x(),n.z(),.0625,shift);
                            poolSummary(pools,n,refined); DescentTerminalAudit.writePool(grids,n.id(),refined);
                        }
                    }
                }
                Bridge b=bridge(n,e,bridges);
                Point end=e.trace().points().get(e.trace().points().size()-1);
                String window=central(n)?"central":"margin";
                counts.merge(window+":"+classification,1,Integer::sum);
                counts.merge(window+":"+b.status(),1,Integer::sum);
                csv.write(n.id()+","+n.x()+","+n.z()+","+e.trace().length()+","+e.trace().status()+","+e.fineRises()
                        +","+end.x()+","+end.z()+","+b.root()+","+b.distance()+","+classification+","+b.status()+","+b.node()+","+window+"\n");
                if(++done%100==0) { csv.flush(); System.out.println("AUDITED "+done+"/"+roots.size()); }
            }
        }
    }
    private static void poolSummary(BufferedWriter csv, Node n, Pool p) throws Exception {
        int at=p.minimum();
        csv.write(n.id()+","+p.pitch()+","+p.shift()+","+p.x(at)+","+p.z(at)+","+p.heights()[at]
                +","+p.spill()[at]+","+p.depth()+","+(p.depth()*354.66)+","+p.walkSteps()+","+p.boundaryMinimum()+","+p.classification()+"\n");
    }

    private List<Edge> edges() throws Exception {
        List<Edge> edges=new ArrayList<>();
        try(var lines=Files.lines(input.resolve("edges.csv"))) {
            for(String line:lines.skip(1).toList()) {
                String[] c=line.split(",");
                Node a=byId.get(Integer.parseInt(c[0])), b=byId.get(Integer.parseInt(c[1]));
                require(a.next()==b.id(),"frozen edge recurrence"); edges.add(new Edge(a,b));
            }
        }
        return edges;
    }
    private void failureEdges(Path out) throws Exception {
        List<Edge> edges=edges(); int wanted=Math.min(512,edges.size()), failures=0;
        try(var csv=Files.newBufferedWriter(out.resolve("failure-edges.csv"));
            var profile=Files.newBufferedWriter(out.resolve("failure-edge-profiles.csv"))) {
            csv.write("source,target,pitch,offset,beforePasses,rises,maxRise,argPositionWu,totalRise,samples,passes\n");
            profile.write("edge,s_wu,x,z,H0,rise\n");
            for(int i=0;i<wanted;i++) {
                Edge edge=edges.get((int)((long)i*edges.size()/wanted));
                Point a=edge.a().point(),b=edge.b().point();
                Profile fine=DescentTerminalAudit.profile(field,a,b,.137,.37,null,"");
                if(fine.accepted()) continue;
                failures++;
                Profile before=DescentTerminalAudit.profile(field,a,b,.25,0,null,"");
                require(before.accepted(),"original frozen edge must pass");
                for(double pitch:new double[]{.25,.137,.03125,.0078125}) {
                    double offset=pitch==.137?.37:0;
                    Profile p=DescentTerminalAudit.profile(field,a,b,pitch,offset,
                            pitch==.0078125?profile:null,edge.a().id()+":"+edge.b().id());
                    csv.write(edge.a().id()+","+edge.b().id()+","+pitch+","+offset+","+before.accepted()+","+p.rises()
                            +","+p.maxRise()+","+p.argWu()+","+p.totalRise()+","+p.samples()+","+p.accepted()+"\n");
                }
            }
        }
        require(failures==5,"expected five frozen sampled-edge failures; actual "+failures);
        System.out.println("PROFILED original five failed edges; unchanged");
    }
    private void finish(Path out) throws Exception {
        for(var e:locks.entrySet()) require(e.getValue().equals(hash(e.getKey())),"locked input changed "+e.getKey());
        try(var csv=Files.newBufferedWriter(out.resolve("locked-inputs.csv"))) {
            csv.write("path,sha256\n");
            for(var e:locks.entrySet()) csv.write(e.getKey().toString().replace('\\','/')+","+e.getValue()+"\n");
        }
        try(var csv=Files.newBufferedWriter(out.resolve("counts.csv"))) {
            csv.write("window_classification,count\n");
            for(var e:counts.entrySet()) csv.write(e.getKey()+","+e.getValue()+"\n");
        }
        Files.writeString(out.resolve("metadata.json"),"{\"stage\":\"A terminal audit only\",\"rootsAudited\":"+roots.size()
                +",\"allBoundNoDescentIncludingMargin\":true,\"H0FullGridBits\":591361,\"source\":\"reflection NoiseFollowingProbe.height\","
                +"\"traceSettings\":[8,0,0.25,0.015625,12,0.0625],\"witnessSegmentSpacing\":0.0073,"
                +"\"fixedSelection\":\"before tracing: all-root extent 4x4; first two roots per partition iz/ix/id order\","
                +"\"fixedSamples\":32,\"refinementNonexhaustive\":true,\"poolScope\":\"finite local grid only; boundary minimax spill, not global basin proof\","
                +"\"poolHalfWidthWu\":2,\"poolPitches\":[0.125,0.0625],\"refineShifts\":[0,0.03125],"
                +"\"shiftedWalkStart\":\"nearest center sample root+(shift,shift); not a root descent witness\","
                +"\"depthYMapping\":\"finite visualization Y proxy closedDepthH0*354.66; same as terminal-pit-evidence.py, not actual MC height\","
                +"\"bridgeQuery\":\"nearest <=16 lower graph nodes within 8wu; require distance<4 and full-H0 endpoint connector descent and sourceH>targetH, different component\","
                +"\"graphModified\":false,\"heightModified\":false,\"continuousMonotonicityProven\":false,\"lockedInputsUnchanged\":true}\n");
        System.out.println("STAGE A TERMINAL AUDIT COMPLETE "+counts);
    }
    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            require(args.length==1 || args.length==2,"usage DescentTerminalAuditProbe NEWout/data [frozenStageA/data]");
            Path out=Path.of(args[0]).toAbsolutePath().normalize();
            Path input=(args.length==2?Path.of(args[1]):DEFAULT).toAbsolutePath().normalize();
            require(!Files.exists(out) && !out.startsWith(input),"fresh output outside frozen input required");
            DescentTerminalAudit.selfTest();
            var probe=new DescentTerminalAuditProbe(input);
            Files.createDirectories(out.getParent()); Files.createDirectory(out);
            List<Node> fixed=probe.fixedSamples(out);
            probe.verifyGrid(); probe.failureEdges(out); probe.auditRoots(out,fixed); probe.finish(out);
        } catch(Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
}
