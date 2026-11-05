package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.SharedDescentGraph;
import java.io.BufferedWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Evidence-only Stage A repair overlay. No carving, H0 edits, or production graph changes. */
public final class DescentGraphRepairProbe {
    private static final Path GRAPH = Path.of("build/shared-descent-stage-a-20261104/data");
    private static final Path AUDIT = Path.of("build/descent-terminal-audit-20261104/data");
    private static final int[][] OFFSETS = {{-1,-1},{0,-1},{1,-1},{-1,0},{1,0},
        {-1,1},{0,1},{1,1},{-1,-2},{1,-2},{-2,-1},{2,-1},{-2,1},{2,1},{-1,2},{1,2}};
    private static final Set<Integer> NAMED = Set.of(2351,49608,69868,181771,190270);
    private record Source(Object instance, MethodHandle handle) implements HeightField {
        static Source create() throws Exception {
            var type = Class.forName("com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe");
            var ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            var method = type.getDeclaredMethod("height",double.class,double.class);
            method.setAccessible(true);
            return new Source(instance,MethodHandles.lookup().unreflect(method).bindTo(instance));
        }
        public double height(double x, double z) {
            try { return (double)handle.invokeExact(x,z); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }
        double parameter(String name) throws Exception {
            var f = instance.getClass().getDeclaredField(name); f.setAccessible(true);
            return f.getDouble(instance);
        }
    }
    private record Node(int id,int ix,int iz,double x,double z,double h,int oldNext,
                        String state,int oldRoot) {
        Point point() { return new Point(x,z); }
    }
    private record Candidate(int id,double slope,int order) {}
    private record Check(boolean pass,int samples,double minDrop) {}
    private record Action(int id,String kind,String reason) {}
    private final Path input,audit;
    private final Source field,independent;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<Integer,List<Point>> curves = new HashMap<>();
    private final Map<Integer,Action> actions = new TreeMap<>();
    private final Map<Path,String> locks = new TreeMap<>();
    private final Set<Integer> fixed = new TreeSet<>();
    private final Map<Integer,Check> oldFine = new TreeMap<>();
    private SharedDescentGraph original;
    private int[] next,root,indegree;
    private double[] lengths;
    private int originalFailures,bridgeCandidates,bridged,finalFailures,finerFailures;
    private long callbacks;

    private DescentGraphRepairProbe(Path input,Path audit) throws Exception {
        this.input=input; this.audit=audit;
        lock(input); lock(audit); lock(Path.of("src/main")); lock(Path.of("src/diagnostics"));
        field=Source.create(); independent=Source.create();
        readNodes(); rebuildOriginal();
        next=new int[nodes.size()]; root=new int[next.length]; indegree=new int[next.length];
        lengths=new double[next.length];
        List<Integer> edges=new ArrayList<>();
        for(Node n:nodes) { next[n.id()]=n.oldNext(); if(n.oldNext()>=0) edges.add(n.id()); }
        require(edges.size()>=512,"fixed 512 requires sufficient original edges");
        for(int i=0;i<512;i++) fixed.add(edges.get((int)((long)i*edges.size()/512)));
        fixed.addAll(NAMED);
    }
    private List<String[]> csv(Path file,String header) throws Exception {
        try(var lines=Files.lines(file)) {
            var it=lines.iterator(); require(it.hasNext() && it.next().equals(header),"CSV schema "+file);
            List<String[]> rows=new ArrayList<>();
            while(it.hasNext()) rows.add(it.next().split(",",-1));
            return rows;
        }
    }
    private void readNodes() throws Exception {
        for(String[] c:csv(input.resolve("graph-nodes.csv"),
                "id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu")) {
            Node n=new Node(Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]),
                    Double.parseDouble(c[3]),Double.parseDouble(c[4]),Double.parseDouble(c[5]),
                    Integer.parseInt(c[6]),c[7],Integer.parseInt(c[9]));
            require(n.id()==nodes.size(),"stable row-major id"); nodes.add(n);
            require(bits(n.h(),field.height(n.x(),n.z())),"frozen node H0 "+n.id());
        }
    }
    private void rebuildOriginal() {
        Node a=nodes.get(0),b=nodes.get(nodes.size()-1);
        original=new SharedDescentGraph(field,a.ix(),a.iz(),b.ix(),b.iz());
        require(original.size()==nodes.size(),"original size");
        for(Node n:nodes) {
            var old=original.node(n.id());
            require(old.nextId()==n.oldNext() && old.rootId()==n.oldRoot()
                    && bits(old.h0(),n.h()) && old.state().name().equals(n.state()),"original reconstruction "+n.id());
        }
        System.out.println("Rebuilt original graph unchanged: "+nodes.size()+" nodes");
    }
    private static boolean bits(double a,double b) {
        return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);
    }
    private static double distance(Point a,Point b) { return Math.hypot(b.x()-a.x(),b.z()-a.z()); }
    private static double length(List<Point> p) {
        double d=0; for(int i=1;i<p.size();i++) d+=distance(p.get(i-1),p.get(i)); return d;
    }
    private static Check check(HeightField f,List<Point> p,double pitch,double offset) {
        boolean pass=p.size()>1; int samples=0; double min=Double.POSITIVE_INFINITY;
        for(int i=1;i<p.size();i++) {
            Point a=p.get(i-1),b=p.get(i);
            if(distance(a,b)==0) return new Check(false,samples,Double.NaN);
            var v=SharedDescentGraph.validate(f,a.x(),a.z(),b.x(),b.z(),pitch,offset);
            pass &= v.accepted(); samples+=v.samples(); min=Math.min(min,v.minDrop());
        }
        return new Check(pass,samples,min);
    }
    private Check measured(HeightField source,List<Point> p,double pitch,double offset) {
        return check((x,z)->{ callbacks++; return source.height(x,z); },p,pitch,offset);
    }
    private List<Point> path(int id) {
        if(next[id]<0) return List.of();
        return curves.getOrDefault(id,List.of(nodes.get(id).point(),nodes.get(next[id]).point()));
    }
    private boolean fineBoth(List<Point> p) {
        return measured(field,p,.03125,0).pass() && measured(independent,p,.0078125,.37).pass();
    }
    private void replace(int id,String reason) {
        Node n=nodes.get(id); List<Candidate> choices=new ArrayList<>();
        for(int order=0;order<OFFSETS.length;order++) {
            int target=original.idAt(n.ix()+OFFSETS[order][0],n.iz()+OFFSETS[order][1]);
            if(target<0 || nodes.get(target).h()>=n.h()) continue;
            Node b=nodes.get(target);
            choices.add(new Candidate(target,(n.h()-b.h())/distance(n.point(),b.point()),order));
        }
        choices.sort(Comparator.comparingDouble(Candidate::slope).reversed().thenComparingInt(Candidate::order));
        next[id]=-1; curves.remove(id);
        for(Candidate c:choices) {
            if(!fineBoth(List.of(n.point(),nodes.get(c.id()).point()))) continue;
            next[id]=c.id(); break;
        }
        actions.put(id,new Action(id,next[id]<0?"DISCONNECT":"FINE_NEIGHBOR",reason));
    }
    private void fixEdges(Path out) throws Exception {
        try(var w=Files.newBufferedWriter(out.resolve("original-edge-checks.csv"))) {
            w.write("source,target,pitch,offset,passes,samples,min_drop\n");
            for(Node n:nodes) {
                if(n.oldNext()<0) continue;
                List<Point> p=List.of(n.point(),nodes.get(n.oldNext()).point());
                Check c=measured(field,p,.03125,0); writeCheck(w,n.id(),n.oldNext(),.03125,0,c);
                boolean failed=!c.pass();
                if(fixed.contains(n.id())) {
                    Check f=measured(independent,p,.0078125,.37); oldFine.put(n.id(),f);
                    writeCheck(w,n.id(),n.oldNext(),.0078125,.37,f); failed |= !f.pass();
                }
                if(failed) { originalFailures++; replace(n.id(),"ORIGINAL_FINE_FAIL"); }
                if(n.id()%20000==0) System.out.println("Original edges checked through "+n.id());
            }
        }
        require(oldFine.keySet().containsAll(NAMED),"five named sources checked");
        for(int id:NAMED) require(!oldFine.get(id).pass(),"named old edge failure "+id);
    }
    private static void writeCheck(BufferedWriter w,int id,int target,double pitch,double offset,Check c)
            throws Exception {
        w.write(id+","+target+","+pitch+","+offset+","+c.pass()+","+c.samples()+","+c.minDrop()+"\n");
    }
    private Map<Integer,List<Point>> witnesses() throws Exception {
        Set<Integer> valid=new HashSet<>();
        for(String[] c:csv(audit.resolve("audit.csv"),"root,x,z,traceLength,traceStatus,fineRises,endx,endz,nearestTargetRoot,targetDistance,classification,bridgeStatus,targetNode,window")) {
            if(c[10].equals("DESCENT_WITNESS_GRID_STOP") && Integer.parseInt(c[5])==0) valid.add(Integer.parseInt(c[0]));
        }
        Map<Integer,List<Point>> paths=new HashMap<>();
        for(String[] c:csv(audit.resolve("witness-paths.csv"),"root,vertex,x,z,H0,s_wu")) {
            int id=Integer.parseInt(c[0]); require(valid.contains(id),"valid audit witness "+id);
            List<Point> p=paths.computeIfAbsent(id,k->new ArrayList<>());
            require(Integer.parseInt(c[1])==p.size(),"witness vertex order");
            Point q=new Point(Double.parseDouble(c[2]),Double.parseDouble(c[3]));
            require(bits(Double.parseDouble(c[4]),field.height(q.x(),q.z())),"witness real H0"); p.add(q);
        }
        return paths;
    }
    private void repairRoots(Path out) throws Exception {
        Map<Integer,List<Point>> witnesses=witnesses();
        Map<Integer,List<Integer>> candidates=new TreeMap<>();
        for(String[] c:csv(audit.resolve("bridge-candidates.csv"),
                "root,targetNode,targetRoot,endx,endz,targetx,targetz,distance,endHigher,fineRises,candidateBridge,connected")) {
            if(!Boolean.parseBoolean(c[10])) continue;
            require(!Boolean.parseBoolean(c[11]),"audit candidates not already connected");
            int id=Integer.parseInt(c[0]),target=Integer.parseInt(c[1]); bridgeCandidates++;
            require(nodes.get(target).oldRoot()==Integer.parseInt(c[2]),"candidate root schema");
            candidates.computeIfAbsent(id,k->new ArrayList<>()).add(target);
        }
        try(var w=Files.newBufferedWriter(out.resolve("bridge-checks.csv"))) {
            w.write("root,target,vertices,length,passes,reason\n");
            for(var entry:candidates.entrySet()) {
                int id=entry.getKey(); Node n=nodes.get(id); List<Point> witness=witnesses.get(id);
                require(n.state().equals("BOUND_NO_DESCENT") && n.oldNext()<0,"only original internal roots");
                require(witness!=null && witness.get(0).equals(n.point()),"witness starts at root");
                // Reuse the frozen trace verbatim: its summed geometric length can round above 12.
                // Verify the generating budget, not a tolerance or an altered/clipped witness.
                require(com.geogenesis.standalone.DescentTerminalAudit.SETTINGS.maxLength()==12,
                        "unchanged audit generating max trace length 12");
                List<List<Point>> accepted=new ArrayList<>(); Map<List<Point>,Integer> targets=new HashMap<>();
                for(int target:entry.getValue()) {
                    Node b=nodes.get(target); List<Point> p=new ArrayList<>(witness);
                    require(n.oldRoot()!=b.oldRoot() && b.h()<n.h(),"different component and strict root drop");
                    if(!p.get(p.size()-1).equals(b.point())) p.add(b.point());
                    boolean pass=fineBoth(p);
                    w.write(id+","+target+","+p.size()+","+length(p)+","+pass+",AUDIT_REAL_WITNESS\n");
                    if(pass) { accepted.add(p); targets.put(p,target); }
                }
                accepted.sort(Comparator.comparingDouble(DescentGraphRepairProbe::length)
                        .thenComparingInt(targets::get));
                if(accepted.isEmpty()) continue;
                List<Point> p=accepted.get(0); next[id]=targets.get(p); curves.put(id,List.copyOf(p)); bridged++;
                actions.put(id,new Action(id,"AUDIT_WITNESS_POLYLINE","FINE_VALIDATED_REAL_AUDIT_CANDIDATE"));
            }
        }
        System.out.println("Audit true candidates="+bridgeCandidates+"; connected roots="+bridged);
    }
    private void finishGraph() {
        Arrays.fill(root,-1); Arrays.fill(indegree,0); Arrays.fill(lengths,0);
        Integer[] order=new Integer[nodes.size()];
        for(Node n:nodes) { order[n.id()]=n.id(); if(next[n.id()]>=0) indegree[next[n.id()]]++; }
        Arrays.sort(order,Comparator.<Integer>comparingDouble(i->nodes.get(i).h()).thenComparingInt(i->i));
        for(int id:order) {
            int target=next[id];
            if(target<0) { root[id]=id; continue; }
            require(nodes.get(target).h()<nodes.get(id).h() && root[target]>=0,"strict DAG final "+id);
            root[id]=root[target]; lengths[id]=length(path(id))+lengths[target];
        }
    }
    private void finalChecks(Path out) throws Exception {
        try(var w=Files.newBufferedWriter(out.resolve("final-edge-checks.csv"))) {
            w.write("source,target,pitch,offset,passes,samples,min_drop\n");
            for(Node n:nodes) {
                int id=n.id(); if(next[id]<0) continue;
                Check c=measured(field,path(id),.03125,0); if(!c.pass()) finalFailures++;
                writeCheck(w,id,next[id],.03125,0,c);
                if(fixed.contains(id) || actions.containsKey(id)) {
                    Check f=measured(independent,path(id),.0078125,.37); if(!f.pass()) finerFailures++;
                    writeCheck(w,id,next[id],.0078125,.37,f);
                }
            }
        }
        finishGraph();
    }
    private String state(Node n) {
        return next[n.id()]>=0?"DESCENT":actions.containsKey(n.id())?"BOUND_NO_DESCENT":n.state();
    }
    private void exportGraph(Path out) throws Exception {
        try(var ns=Files.newBufferedWriter(out.resolve("graph-nodes.csv"));
            var es=Files.newBufferedWriter(out.resolve("edges.csv"));
            var ps=Files.newBufferedWriter(out.resolve("polyline-segments.csv"));
            var ds=Files.newBufferedWriter(out.resolve("edge-descriptors.csv"))) {
            ns.write("id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu\n");
            es.write("source,target,x0,z0,x1,z1,H0_source,H0_target,samples,min_drop\n");
            ps.write("source,target,segment,x0,z0,x1,z1,H0_source,H0_target\n");
            ds.write("source,target,kind,vertices,length\n");
            for(Node n:nodes) {
                int id=n.id(),target=next[id];
                ns.write(id+","+n.ix()+","+n.iz()+","+n.x()+","+n.z()+","+n.h()+","+target+","+state(n)
                        +","+indegree[id]+","+root[id]+","+lengths[id]+"\n");
                if(target<0) continue;
                Node b=nodes.get(target); List<Point> p=path(id);
                // Endpoint inventory ONLY. Renderer must use descriptors + segment geometry.
                es.write(id+","+target+","+n.x()+","+n.z()+","+b.x()+","+b.z()+","+n.h()+","+b.h()+",0,NaN\n");
                ds.write(id+","+target+","+(curves.containsKey(id)?"POLYLINE":"STRAIGHT")+","+p.size()+","+length(p)+"\n");
                for(int i=1;i<p.size();i++) {
                    Point a=p.get(i-1),z=p.get(i);
                    ps.write(id+","+target+","+(i-1)+","+a.x()+","+a.z()+","+z.x()+","+z.z()+","
                            +field.height(a.x(),a.z())+","+field.height(z.x(),z.z())+"\n");
                }
            }
        }
    }
    private void exportActions(Path out) throws Exception {
        try(var w=Files.newBufferedWriter(out.resolve("repair-actions.csv"));
            var five=Files.newBufferedWriter(out.resolve("five-old-failures.csv"));
            var residual=Files.newBufferedWriter(out.resolve("residual-roots.csv"))) {
            w.write("id,old_next,new_next,kind,reason,vertexcount,length,finervalid\n");
            five.write("id,oldtarget,oldfine,newtarget,newfine,status\n");
            residual.write("id,old_next,next,state,evidence\n");
            for(Action a:actions.values()) {
                int id=a.id(); List<Point> p=path(id);
                String pass=next[id]<0?"NOT_APPLICABLE_DISCONNECTED":Boolean.toString(measured(independent,p,.0078125,.37).pass());
                w.write(id+","+nodes.get(id).oldNext()+","+next[id]+","+a.kind()+","+a.reason()+","+p.size()+","+length(p)+","+pass+"\n");
            }
            for(int id:new TreeSet<>(NAMED)) {
                String pass=next[id]<0?"NOT_APPLICABLE_DISCONNECTED":Boolean.toString(measured(independent,path(id),.0078125,.37).pass());
                five.write(id+","+nodes.get(id).oldNext()+","+oldFine.get(id).pass()+","+next[id]+","+pass+","+actions.get(id).kind()+"\n");
            }
            for(Node n:nodes) if(next[n.id()]<0 && !state(n).equals("DOMAIN_BOUNDARY"))
                residual.write(n.id()+","+n.oldNext()+",-1,"+state(n)+",UNRESOLVED_NOT_PROVEN_BASIN\n");
        }
    }
    private void copyContext(Path out) throws Exception {
        for(String name:List.of("carrier-height.f64be","base-raw.f64be","sampling.json")) {
            Files.copy(input.resolve(name),out.resolve(name));
            require(hash(input.resolve(name)).equals(hash(out.resolve(name))),"context unchanged "+name);
        }
        List<String[]> rows=csv(input.resolve("junctions.csv"),"region,node,x,z,indegree,next,root_id,downstream_length_wu");
        try(var w=Files.newBufferedWriter(out.resolve("junctions.csv"))) {
            w.write("region,node,x,z,indegree,next,root_id,downstream_length_wu\n");
            for(String[] c:rows) {
                int id=Integer.parseInt(c[1]);
                if(id<0) { w.write(String.join(",",c)+"\n"); continue; }
                w.write(c[0]+","+c[1]+","+c[2]+","+c[3]+","+indegree[id]+","+next[id]+","+root[id]+","+lengths[id]+"\n");
            }
        }
    }
    private static String hash(Path p) throws Exception {
        var d=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(p)) {
            byte[] b=new byte[65536]; for(int n;(n=in.read(b))!=-1;) d.update(b,0,n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private void lock(Path dir) throws Exception {
        require(Files.isDirectory(dir),"missing locked input "+dir);
        try(var files=Files.walk(dir)) {
            for(Path p:files.filter(Files::isRegularFile).sorted().toList()) locks.put(p,hash(p));
        }
    }
    private void metadata(Path out,double seconds) throws Exception {
        int residual=0,edges=0,originalRoots=0;
        for(Node n:nodes) {
            if(n.state().equals("BOUND_NO_DESCENT")) originalRoots++;
            if(next[n.id()]>=0) edges++;
            else if(!state(n).equals("DOMAIN_BOUNDARY")) residual++;
        }
        Files.writeString(out.resolve("checks.json"),"{\"stage\":\"A only\",\"nodes\":"+nodes.size()
                +",\"edges\":"+edges+",\"originalFineFailures\":"+originalFailures+",\"auditTrueCandidates\":"+bridgeCandidates
                +",\"connectedAuditRoots\":"+bridged+",\"originalBoundNoDescentRoots\":"+originalRoots
                +",\"residualInternalRoots\":"+residual+",\"allFinalEdgeSpacing\":0.03125,\"independentSpacing\":0.0078125,\"independentOffset\":0.37,"
                +"\"fixedOriginalEdges\":512,\"fiveNamedAndAllRepairedAlsoChecked\":true,\"finalFineFailures\":"+finalFailures
                +",\"independentFailures\":"+finerFailures+",\"strictDAG\":true,\"callbacks\":"+callbacks+",\"seconds\":"+seconds
                +",\"noCarve\":true,\"heightUnchanged\":true,\"phaseB\":false,\"phaseC\":false,\"maxWitnessTraceLength\":12,"
                +"\"searchScope\":\"true audit candidates only; no local Dijkstra; unresolved roots NOT proven basins\","
                +"\"edgeGeometry\":\"polyline-segments.csv authoritative; edges.csv endpoint inventory NOT straight geometry proof\","
                +"\"samplingJson\":\"verbatim old coordinate context; old edgeGeometry description superseded here\","
                +"\"continuousMonotonicityProven\":false,\"wholeWorldConnectivityProven\":false}\n");
        try(var w=Files.newBufferedWriter(out.resolve("locked-inputs.csv"))) {
            w.write("path,sha256\n");
            for(var e:locks.entrySet()) {
                require(e.getValue().equals(hash(e.getKey())),"locked file changed "+e.getKey());
                w.write(e.getKey().toString().replace('\\','/')+","+e.getValue()+"\n");
            }
        }
        System.out.println("Stage A complete: originalFineFailures="+originalFailures+", repairedRoots="+bridged
                +", residualInternalRoots="+residual+", finalFailures="+finalFailures+", independentFailures="+finerFailures
                +", seconds="+seconds);
        require(finalFailures==0 && finerFailures==0,"final measured validation failures (see checks.json)");
    }
    private static void selfTest() {
        HeightField curved=(x,z)->-x+10*Math.pow(z-x*x/4,2);
        List<Point> p=new ArrayList<>();
        for(int i=0;i<=64;i++) { double x=i/16.0; p.add(new Point(x,x*x/4)); }
        require(!check(curved,List.of(p.get(0),p.get(p.size()-1)),.03125,0).pass(),"straight curved valley shortcut refused");
        require(check(curved,p,.03125,0).pass() && check(curved,p,.0078125,.37).pass(),"real curved valley bridge");
        HeightField ridge=(x,z)->-x+20*Math.exp(-Math.pow((x-2)/.3,2));
        require(!check(ridge,List.of(new Point(0,0),new Point(4,0)),.03125,0).pass(),"inner ridge refused");
        require(!check((x,z)->x*x+z*z,List.of(new Point(0,0),new Point(1,0)),.0078125,.37).pass(),"no fake-height basin escape");
        System.out.println("SELFTEST PASS: curved bridge / refused straight shortcut / ridge / unchanged true heights");
    }
    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT); selfTest();
            if(args.length==1 && args[0].equals("--self-test")) return;
            require(args.length==1 || args.length==3,"usage: DescentGraphRepairProbe NEWout/data [graphData auditData]");
            Path out=Path.of(args[0]).toAbsolutePath().normalize();
            Path input=(args.length==3?Path.of(args[1]):GRAPH).toAbsolutePath().normalize();
            Path audit=(args.length==3?Path.of(args[2]):AUDIT).toAbsolutePath().normalize();
            require(!Files.exists(out) && !out.startsWith(input) && !out.startsWith(audit),"fresh separate output required");
            long start=System.nanoTime(); var probe=new DescentGraphRepairProbe(input,audit);
            Files.createDirectories(out); probe.fixEdges(out); probe.repairRoots(out); probe.finalChecks(out);
            probe.exportGraph(out); probe.exportActions(out); probe.copyContext(out);
            probe.metadata(out,(System.nanoTime()-start)/1e9);
        } catch(Throwable t) { t.printStackTrace(System.err); System.exit(1); }
    }
}
