package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Diagnostic Stage A only: bounded sampled descent, never a basin or global drainage proof. */
public final class SharedDownstreamSearch {
    private SharedDownstreamSearch() {}
    public static final int MAX_RESTARTS = 64;
    public record Settings(double step, double radius, double maxLength, int maxStates) {
        public Settings {
            require(Double.isFinite(step) && step > 0 && Double.isFinite(radius) && radius >= step
                    && Double.isFinite(maxLength) && maxLength > 0 && maxStates > 0, "search bounds");
            require(radius / step <= 256, "bounded lattice allocation");
        }
        public Settings() { this(.5, 12, 32, 4096); }
    }
    public record Node(int id, double x, double z, double h, int next, String state) {
        public Point point() { return new Point(x,z); }
    }
    /** Canonical IDs and one mutable successor per node: a join reuses the exact stored suffix. */
    public static final class Graph {
        private final List<Node> nodes;
        private final Map<Point,Integer> ids = new HashMap<>();
        private final int[] next, parent, rank;
        public Graph(List<Node> input) {
            nodes = List.copyOf(input); next = new int[nodes.size()];
            parent = new int[next.length]; rank = new int[next.length];
            for (Node n : nodes) {
                require(n.id() >= 0 && n.id() < next.length && nodes.get(n.id()) == n, "canonical ID order");
                require(Double.isFinite(n.h()) && ids.put(n.point(),n.id()) == null, "unique finite node");
                next[n.id()] = n.next(); parent[n.id()] = n.id();
            }
            for (Node n : nodes) if (n.next() >= 0) {
                require(n.next() < size() && node(n.next()).h() < n.h(), "original strict height DAG");
                union(n.id(),n.next());
            }
        }
        public int size() { return nodes.size(); }
        public Node node(int id) { return nodes.get(id); }
        public int next(int id) { return next[id]; }
        public int idAt(Point p) { return ids.getOrDefault(p,-1); }
        public int component(int id) {
            int root = id;
            while (parent[root] != root) root = parent[root];
            while (parent[id] != id) { int p = parent[id]; parent[id] = root; id = p; }
            return root;
        }
        private void union(int a, int b) {
            a = component(a); b = component(b); if (a == b) return;
            if (rank[a] < rank[b]) parent[a] = b;
            else { parent[b] = a; if (rank[a] == rank[b]) rank[a]++; }
        }
        public boolean eligible(int source, int target) {
            return target >= 0 && node(target).h() < node(source).h()
                    && component(source) != component(target);
        }
        public void connect(int source, int target) {
            require(next[source] < 0 && !node(source).state().equals("DOMAIN_BOUNDARY"), "internal stop only");
            require(eligible(source,target), "lower target in different CURRENT component");
            next[source] = target; union(source,target);
        }
        public int root(int id) {
            int count = 0;
            while (next[id] >= 0) { id = next[id]; require(++count <= size(), "acyclic suffix"); }
            return id;
        }
    }
    public record Result(int target, List<Point> points, double length, String reason,
                         int explored, int discovered, int blocked, int endpointRejects,
                         int coarseRejects, int finerRejects, int radiusRejects, int lengthRejects,
                         int sameComponentTargets, int candidates, int restarts, long callbacks,
                         int eligibleNodesInDisk) {
        public Result { points = List.copyOf(points); }
        public boolean success() { return target >= 0; }
        public boolean budget() { return reason.contains("BUDGET_UNKNOWN"); }
    }
    private record Entry(int id, double length) {}
    private static final int[][] DIRECTIONS = {{-1,-1},{0,-1},{1,-1},{-1,0},{1,0},
        {-1,1},{0,1},{1,1}};
    private static final class Work {
        final Graph graph;
        final int source, side, center;
        final HeightField field, independent;
        final Settings settings;
        final double[] heights;
        final boolean[] seen;
        final Set<Long> blocked = new HashSet<>(), coarseOK = new HashSet<>();
        int explored, discovered, endpoint, coarse, finer, radius, length, same, candidates, restarts;
        int eligibleNodes;
        long callbacks;
        Work(Graph graph, int source, HeightField field, HeightField independent, Settings settings) {
            this.graph = graph; this.source = source; this.field = field;
            this.independent = independent; this.settings = settings;
            center = (int)Math.floor(settings.radius()/settings.step()); side = 2*center+1;
            heights = new double[side*side]; seen = new boolean[heights.length];
        }
        Point point(int id) {
            Point p = graph.node(source).point();
            return new Point(p.x()+(id%side-center)*settings.step(),
                    p.z()+(id/side-center)*settings.step());
        }
        double height(int id) {
            if (!seen[id]) {
                Point p = point(id); heights[id] = sample(field,p.x(),p.z());
                seen[id] = true; discovered++;
            }
            return heights[id];
        }
        double sample(HeightField f, double x, double z) {
            callbacks++; return SharedDescentGraph.sample(f,x,z);
        }
        boolean valid(int a, int b, boolean fine) {
            Point p = point(a), q = point(b);
            HeightField f = fine ? independent : field;
            return SharedDescentGraph.validate((x,z)->sample(f,x,z),p.x(),p.z(),q.x(),q.z(),
                    fine ? .0078125 : .03125, fine ? .37 : 0).accepted();
        }
        Result result(int target, List<Point> p, double distance, String reason) {
            return new Result(target,p,distance,reason,explored,discovered,blocked.size(),endpoint,
                    coarse,finer,radius,length,same,candidates,restarts,callbacks,eligibleNodes);
        }
        boolean edge(int a, int b) {
            long key = key(a,b);
            if (blocked.contains(key)) return false;
            if (!(height(b) < height(a))) { endpoint++; blocked.add(key); return false; }
            if (coarseOK.contains(key)) return true;
            if (!valid(a,b,false)) { coarse++; blocked.add(key); return false; }
            coarseOK.add(key); return true;
        }
    }
    private static long key(int a, int b) { return ((long)a << 32) | (b & 0xffffffffL); }
    public static Result search(Graph graph, int source, HeightField field, HeightField independent,
                                Settings settings) {
        require(graph.next(source) < 0 && !graph.node(source).state().equals("DOMAIN_BOUNDARY"),
                "search internal stops, never boundary exits");
        Work w = new Work(graph,source,field,independent,settings);
        // Enumerate the small disk through exact coordinates; use stored grid H0, never callbacks.
        for (int id = 0; id < w.heights.length; id++) {
            if (Math.hypot(id%w.side-w.center,id/w.side-w.center)*settings.step() > settings.radius()) continue;
            int target = graph.idAt(w.point(id));
            if (graph.eligible(source,target)) w.eligibleNodes++;
        }
        if (w.eligibleNodes == 0) return w.result(-1,List.of(),0,"NO_ELIGIBLE_UNKNOWN");
        require(Double.doubleToRawLongBits(w.height(w.center*w.side+w.center))
                == Double.doubleToRawLongBits(graph.node(source).h()), "source original H0");
        while (w.explored < settings.maxStates()) {
            Result result = attempt(w);
            if (result != null) return result;
            // A restart never resets explored: all attempts share the same maxStates allowance.
            if (w.explored >= settings.maxStates()) return w.result(-1,List.of(),0,"STATE_BUDGET_UNKNOWN");
            if (w.restarts >= MAX_RESTARTS) return w.result(-1,List.of(),0,"RESTART_BUDGET_UNKNOWN");
            w.restarts++;
        }
        return w.result(-1,List.of(),0,"STATE_BUDGET_UNKNOWN");
    }
    private static Result attempt(Work w) {
        double[] distance = new double[w.heights.length]; Arrays.fill(distance,Double.POSITIVE_INFINITY);
        int[] previous = new int[distance.length]; Arrays.fill(previous,-1);
        var queue = new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::length).thenComparingInt(Entry::id));
        int start = w.center*w.side+w.center; distance[start] = 0; queue.add(new Entry(start,0));
        while (!queue.isEmpty()) {
            Entry entry = queue.remove(); int at = entry.id();
            if (entry.length() != distance[at]) continue;
            if (w.explored >= w.settings.maxStates()) return w.result(-1,List.of(),0,"STATE_BUDGET_UNKNOWN");
            w.explored++;
            int target = w.graph.idAt(w.point(at));
            if (target >= 0 && at != start) {
                if (w.graph.eligible(w.source,target)) {
                    w.candidates++;
                    List<Integer> path = reconstruct(previous,at);
                    if (!independentPath(w,path)) return null;
                    return w.result(target,path.stream().map(w::point).toList(),entry.length(),"SHARED_DOWNSTREAM");
                }
                if (w.graph.component(w.source) == w.graph.component(target)) w.same++;
            }
            expand(w,entry,distance,previous,queue);
        }
        return w.result(-1,List.of(),0,"NO_MONOTONE_ROUTE_UNKNOWN");
    }
    private static void expand(Work w, Entry entry, double[] distance, int[] previous,
                               PriorityQueue<Entry> queue) {
        int at = entry.id(), col = at%w.side, row = at/w.side;
        for (int[] d : DIRECTIONS) {
            int x = col+d[0], z = row+d[1];
            if (x < 0 || z < 0 || x >= w.side || z >= w.side
                    || Math.hypot(x-w.center,z-w.center)*w.settings.step() > w.settings.radius()) {
                w.radius++; continue;
            }
            int to = z*w.side+x;
            double length = entry.length()+Math.hypot(d[0],d[1])*w.settings.step();
            if (length > w.settings.maxLength()) { w.length++; continue; }
            if (length >= distance[to] || !w.edge(at,to)) continue;
            distance[to] = length; previous[to] = at; queue.add(new Entry(to,length));
        }
    }
    private static List<Integer> reconstruct(int[] previous, int at) {
        List<Integer> p = new ArrayList<>();
        while (at >= 0) { p.add(at); at = previous[at]; }
        java.util.Collections.reverse(p); return p;
    }
    private static boolean independentPath(Work w, List<Integer> path) {
        // Check the COMPLETE candidate. Never retain a passing prefix as an invented outlet.
        for (int i = 1; i < path.size(); i++) {
            int a = path.get(i-1), b = path.get(i);
            if (!w.valid(a,b,true)) {
                w.finer++; w.blocked.add(key(a,b)); w.coarseOK.remove(key(a,b)); return false;
            }
        }
        return true;
    }
}
