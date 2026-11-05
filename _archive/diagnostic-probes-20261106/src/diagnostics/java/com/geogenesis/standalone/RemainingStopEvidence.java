package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import com.geogenesis.standalone.SharedDownstreamSearch.Graph;
import com.geogenesis.standalone.SharedDownstreamSearch.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Read-only finite sampled witnesses. Neither failed tracing nor sampled rings prove a basin. */
public final class RemainingStopEvidence {
    private RemainingStopEvidence() {}
    public static final NoiseFollowingErosion.Settings SETTINGS =
            new NoiseFollowingErosion.Settings(8,0,.25,.015625,24,.0625);
    public static final double[] RADII = {.03125,.125,.5,2,4};
    public record Ring(double radius, double minBarrier, boolean allHigher, int lowestAngle,
                       List<Double> heights) {}
    public record Connector(int target, double arc, Point from, Point to) {}
    public record Evidence(Trace trace, int firstFailed, double verifiedAdvance,
                           Connector connector, List<Ring> rings, String classification) {
        public boolean prefixDescended() { return verifiedAdvance > 0; }
        public boolean fineAllVerified() { return firstFailed < 0; }
        public boolean terminalReachableVerified() { return fineAllVerified(); }
    }

    public static Evidence inspect(Graph graph, int source, HeightField field, HeightField independent) {
        Node n = graph.node(source);
        Trace t = NoiseFollowingErosion.trace(field,Integer.toString(source),n.x(),n.z(),SETTINGS);
        int failed = -1; double advance = 0;
        for (int i = 1; i < t.points().size(); i++) {
            boolean ok = valid(independent,t.points().get(i-1),t.points().get(i),.0078125,.37);
            if (!ok && failed < 0) failed = i-1;
            if (failed < 0) advance = t.arc().get(i);
        }
        Connector connector = findConnector(graph,source,field,independent,t,advance);
        Point end = t.points().get(t.points().size()-1);
        List<Ring> rings = rings(field,end);
        boolean descent = failed < 0 && advance > 0 && field.height(end.x(),end.z()) < n.h();
        return new Evidence(t,failed,advance,connector,rings,classify(connector,descent,advance > 0,rings));
    }
    private static String classify(Connector c, boolean descent, boolean prefix, List<Ring> rings) {
        if (c != null) return "CONNECTABLE_CONTINUOUS_WITNESS";
        if (!descent) return prefix ? "VERIFIED_PREFIX_ONLY_TERMINAL_UNKNOWN" : "NO_VERIFIED_DESCENT_UNKNOWN";
        if (rings.stream().anyMatch(Ring::allHigher)) return "FINITE_TERMINAL_RING_BARRIER_CANDIDATE";
        return "DESCENT_WITNESS_NO_RECEIVER";
    }
    private static boolean valid(HeightField f, Point a, Point b, double pitch, double shift) {
        return SharedDescentGraph.validate(f,a.x(),a.z(),b.x(),b.z(),pitch,shift).accepted();
    }
    /** All verified vertices plus exact half-wu arc points and original source, not END-only. */
    private static Connector findConnector(Graph g, int source, HeightField f, HeightField independent,
                                            Trace t, double advance) {
        TreeSet<Double> arcs = new TreeSet<>(); arcs.add(0.0); arcs.add(advance);
        for (double s : t.arc()) if (s <= advance) arcs.add(s);
        for (double s = .5; s < advance; s += .5) arcs.add(s);
        for (double s : arcs) {
            Point p = NoiseFollowingErosion.pointAt(t,s);
            int preceding = java.util.Collections.binarySearch(t.arc(),s);
            if (preceding < 0) {
                preceding = -preceding-2;
                debugCoincidence(source,s,preceding,t,p);
                if (!p.equals(t.points().get(preceding))
                        && !valid(independent,t.points().get(preceding),p,.0078125,.37)) continue;
            }
            List<Node> targets = targets(g,source,p);
            for (Node n : targets) {
                double distance = Math.hypot(n.x()-p.x(),n.z()-p.z());
                if (distance == 0 || distance > 4 || s+distance > 24
                        || !(n.h() < f.height(p.x(),p.z()))) continue;
                if (valid(f,p,n.point(),.03125,0) && valid(independent,p,n.point(),.0078125,.37)
                        && wholePath(t,s,p,n.point(),f,independent)) {
                    return new Connector(n.id(),s,p,n.point());
                }
            }
        }
        return null;
    }
    private static void debugCoincidence(int source, double s, int preceding, Trace t, Point p) {
        Point a = t.points().get(preceding);
        if (!p.equals(a)) return;
        String message = java.time.Instant.now()+" RemainingStopEvidence COINCIDENT source="+source
                +" s="+s+" preceding="+preceding+" arc="+t.arc().get(preceding)
                +" nextArc="+t.arc().get(preceding+1)+" p="+p+" a="+a
                +" sBits="+Long.toHexString(Double.doubleToRawLongBits(s))
                +" arcBits="+Long.toHexString(Double.doubleToRawLongBits(t.arc().get(preceding)))
                +" pBits="+pointBits(p)+" aBits="+pointBits(a)+System.lineSeparator();
        System.err.print(message);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("").toAbsolutePath().getParent().resolve("debug-agent.log"),
                    message,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    private static String pointBits(Point p) {
        return Long.toHexString(Double.doubleToRawLongBits(p.x()))+":"
                +Long.toHexString(Double.doubleToRawLongBits(p.z()));
    }
    private static boolean wholePath(Trace t, double arc, Point join, Point target,
                                     HeightField f, HeightField independent) {
        List<Point> route = route(t,arc,join,target); double length = 0;
        for (int i = 1; i < route.size(); i++) {
            Point a = route.get(i-1), b = route.get(i);
            length += Math.hypot(b.x()-a.x(),b.z()-a.z());
            if (!valid(f,a,b,.03125,0) || !valid(independent,a,b,.0078125,.37)) return false;
        }
        return length <= 24;
    }
    public static List<Point> route(Trace t, double arc, Point join, Point target) {
        List<Point> route = new ArrayList<>();
        for (int i = 0; i < t.points().size() && t.arc().get(i) < arc; i++) appendExact(route,t.points().get(i));
        appendExact(route,join); appendExact(route,target); return List.copyOf(route);
    }
    private static void appendExact(List<Point> route, Point p) {
        if (route.isEmpty() || !route.get(route.size()-1).equals(p)) route.add(p);
    }
    private static void coincidentSelfTest() {
        Point start = new Point(-3504,-4340);
        Point mid = new Point(-3503.984705793991,-4340.249531735983);
        Point a = new Point(-3503.9626593409457,-4340.498557746488);
        Point b = new Point(-3503.9341479018035,-4340.746926621023);
        double arc = .4999999999997762, endArc = .749999999999379;
        Trace t = new Trace("rounding",List.of(start,mid,a,b),
                List.of(0.0,.24999999999987196,arc,endArc),"TEST",endArc);
        Point p = NoiseFollowingErosion.pointAt(t,.5);
        require(arc < .5 && p.equals(a),"half-wu arc distinct but geometry exact duplicate");
        List<Point> route = route(t,.5,p,b);
        require(route.equals(List.of(start,mid,a,b)),"only adjacent exact duplicate removed");
        Point near = new Point(Math.nextUp(a.x()),a.z());
        require(route(t,.5,near,b).size() == 5,"near points never approximately merged");
    }
    private static List<Node> targets(Graph g, int source, Point p) {
        List<Node> result = new ArrayList<>();
        int ix = (int)Math.floor(p.x()/4), iz = (int)Math.floor(p.z()/4);
        for (int z = iz-2; z <= iz+2; z++) for (int x = ix-2; x <= ix+2; x++) {
            int id = g.idAt(new Point(x*4.0,z*4.0));
            if (g.eligible(source,id)) {
                Node n = g.node(id);
                if (Math.hypot(n.x()-p.x(),n.z()-p.z()) <= 8) result.add(n);
            }
        }
        result.sort(Comparator.<Node>comparingDouble(n -> Math.hypot(n.x()-p.x(),n.z()-p.z()))
                .thenComparingInt(Node::id));
        return result;
    }
    public static List<Ring> rings(HeightField f, Point center) {
        List<Ring> result = new ArrayList<>(); double h = f.height(center.x(),center.z());
        for (double r : RADII) {
            List<Double> heights = new ArrayList<>(); double min = Double.POSITIVE_INFINITY; int angle = -1;
            for (int i = 0; i < 64; i++) {
                double a = i*Math.PI/32;
                double value = DescentTerminalAudit.h(f,center.x()+r*Math.cos(a),center.z()+r*Math.sin(a));
                heights.add(value);
                if (value-h < min) { min = value-h; angle = i; }
            }
            result.add(new Ring(r,min,min > 0,angle,List.copyOf(heights)));
        }
        return List.copyOf(result);
    }
    public static void selfTest() {
        coincidentSelfTest();
        HeightField narrow = (x,z) -> -x+16*Math.pow(z-.12*Math.sin(2*Math.PI*x),2);
        Graph g = new Graph(List.of(new Node(0,0,0,0,-1,"STOP"),
                new Node(1,4,0,-4,-1,"DOMAIN_BOUNDARY")));
        for (double step : new double[]{.5,.25}) {
            var grid = SharedDownstreamSearch.search(g,0,narrow,narrow,
                    new SharedDownstreamSearch.Settings(step,8,24,4096));
            require(!grid.success(),"curved narrow valley grid fail "+step);
        }
        Evidence e = inspect(g,0,narrow,narrow);
        require(wholePath(e.trace(),e.connector().arc(),e.connector().from(),e.connector().to(),narrow,narrow),
                "whole candidate dual validation");
        require(classify(null,false,true,List.of()).equals("VERIFIED_PREFIX_ONLY_TERMINAL_UNKNOWN"),
                "prefix cannot claim terminal reachable");
        Evidence partial = new Evidence(e.trace(),1,.25,null,List.of(),"VERIFIED_PREFIX_ONLY_TERMINAL_UNKNOWN");
        require(partial.prefixDescended() && !partial.fineAllVerified() && !partial.terminalReachableVerified(),
                "explicit partial flags");
        require(e.connector() != null && e.firstFailed() < 0,"curved narrow valley trace pass");
        HeightField bowl = (x,z) -> x*x+z*z;
        Graph b = new Graph(List.of(new Node(0,1,0,1,-1,"STOP")));
        Evidence be = inspect(b,0,bowl,bowl);
        require(be.classification().equals("FINITE_TERMINAL_RING_BARRIER_CANDIDATE"),"finite terminal bowl ring");
        HeightField ridge = (x,z) -> -x+20*Math.exp(-Math.pow((x-2)/.3,2));
        require(!valid(ridge,new Point(0,0),new Point(4,0),.0078125,.37),"inner ridge refusal");
        Graph flat = new Graph(List.of(new Node(0,0,0,7,-1,"STOP")));
        require(inspect(flat,0,(x,z)->7,(x,z)->7).classification().equals("NO_VERIFIED_DESCENT_UNKNOWN"),"flat unknown");
        require(g.next(0) == -1,"no graph mutation");
        System.out.println("PASS curved narrow valley gridfail/tracepass; finite bowl; inner ridge refusal; flat unknown");
    }
}
