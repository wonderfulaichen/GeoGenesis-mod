package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;

/** Symmetric boundary capacity tree plus known-ocean costs; no frame parents, water levels or flow directions. */
final class SharedBoundaryTransfer {
    record Vertex(SharedBoundaryDrainage.WorldCell point, double bed, double seaCapacity) { }
    record Edge(int a, int b, double barrier) { }
    record Product(List<Vertex> vertices, List<Edge> edges, int sourceNodes, double seaLevel,
                   int ox, int oz, int nx, int nz, int step, int parts) {
        Product { vertices = List.copyOf(vertices); edges = List.copyOf(edges); }
    }
    record GridTransfer(List<Vertex> vertices, List<Edge> edges, List<Integer> ports, int nx, int nz, int step) {
        GridTransfer { vertices = List.copyOf(vertices); edges = List.copyOf(edges); ports = List.copyOf(ports); }
    }
    record GridAssembly(List<Vertex> vertices, List<Edge> edges, List<Integer> ports, int step, int parts) {
        GridAssembly { vertices = List.copyOf(vertices); edges = List.copyOf(edges); ports = List.copyOf(ports); }
    }
    /** Terminal cost and best possible escape into missing input are separate; neither is a water surface. */
    record GridCapacity(double terminal, double unknown) { }
    record ExteriorBounds(Map<SharedBoundaryDrainage.WorldEdge, Double> barriers) {
        ExteriorBounds { barriers = Map.copyOf(barriers); }
    }
    enum ExteriorState { OUTSIDE_INPUT, INPUT_PENDING, BASIN_PENDING, OCEAN_CANDIDATE, REENTERS_DOMAIN, CYCLE, LIMIT, INVALID_SUCCESSOR }
    record ExteriorObservation(SharedBoundaryDrainage.WorldCell point, ProductionDrainageAudit.Region owner,
                               double bed, double level, boolean resolved, boolean lake, boolean sea,
                               SharedBoundaryDrainage.WorldCell next) { }
    record ExteriorTrace(SharedBoundaryDrainage.WorldEdge edge, List<ExteriorObservation> path, ExteriorState state) {
        ExteriorTrace { path = List.copyOf(path); }
    }
    record ExteriorFrame(SharedBoundaryDrainage.WorldPolicy policy, List<SharedBoundaryDrainage.WorldEdge> edges,
                         Map<SharedBoundaryDrainage.WorldEdge, ExteriorTrace> traces) {
        ExteriorFrame { edges = List.copyOf(edges); traces = Map.copyOf(traces); }
    }
    record ConditionalRouting(SharedBoundaryDrainage.WorldPolicy policy,
                              Map<SharedBoundaryDrainage.WorldCell, SharedBoundaryDrainage.WorldNode> nodes) {
        ConditionalRouting { nodes = Map.copyOf(nodes); }
    }
    private record Visit(int k, double cost) { }
    private static final int[][] DIRS = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};

    enum SampledCutState { UNIQUE_FIRST_EDGE, MULTIPLE_FIRST_EDGES, UNKNOWN_INCIDENT_INPUT, NO_FINITE_CUT }
    record SampledCutEdge(int from, int to, double barrier) { }
    record SampledCut(SampledCutState state, List<Integer> members, List<SampledCutEdge> rim,
                      List<SampledCutEdge> firstEdges) {
        SampledCut { members=List.copyOf(members);rim=List.copyOf(rim);firstEdges=List.copyOf(firstEdges); }
    }

    /** First departure from a closed strict sublevel component of this sampled four-neighbour graph only.
     * Unknown downstream, tailwater, ocean connection and positive release are deliberately NOT certified. */
    static SampledCut sampledFirstCut(int nx,int nz,double[] bed,double[] east,double[] south,int seed,double barrier) {
        cancelled();long count=(long)nx*nz;
        if(nx<2 || nz<2 || count>131072 || bed.length!=count || east.length!=count || south.length!=count
                || seed<0 || seed>=count || !Double.isFinite(barrier) || !Double.isFinite(bed[seed]) || bed[seed]>=barrier)
            throw new IllegalArgumentException("bounded sampled strict component required");
        var members=new java.util.BitSet(bed.length);var queue=new java.util.ArrayDeque<Integer>();
        queue.add(seed);members.set(seed);boolean unknown=false;
        while(!queue.isEmpty()) {
            cancelled();int k=queue.remove(),x=k/nz,z=k%nz;
            for(int next:new int[]{x>0?k-nz:-1,x+1<nx?k+nz:-1,z>0?k-1:-1,z+1<nz?k+1:-1}) {
                if(next<0){unknown=true;continue;}
                double edge=Math.abs(k-next)==nz?east[Math.min(k,next)]:south[Math.min(k,next)];
                if(!Double.isFinite(bed[next]) || !Double.isFinite(edge)){unknown=true;continue;}
                if(edge<Math.max(bed[k],bed[next]))throw new IllegalArgumentException("edge below endpoint bed");
                if(edge<barrier && bed[next]<barrier && !members.get(next)){members.set(next);queue.add(next);}
            }
        }
        var rim=new ArrayList<SampledCutEdge>();var first=new ArrayList<SampledCutEdge>();var cells=new ArrayList<Integer>();
        for(int k=members.nextSetBit(0);k>=0;k=members.nextSetBit(k+1)) {
            cancelled();cells.add(k);int x=k/nz,z=k%nz;
            for(int next:new int[]{x>0?k-nz:-1,x+1<nx?k+nz:-1,z>0?k-1:-1,z+1<nz?k+1:-1}) {
                if(next<0 || members.get(next))continue;
                double edge=Math.abs(k-next)==nz?east[Math.min(k,next)]:south[Math.min(k,next)];
                if(!Double.isFinite(bed[next]) || !Double.isFinite(edge))continue;
                var e=new SampledCutEdge(k,next,edge);rim.add(e);if(edge==barrier)first.add(e);
            }
        }
        var state=unknown?SampledCutState.UNKNOWN_INCIDENT_INPUT:first.isEmpty()?SampledCutState.NO_FINITE_CUT:
                first.size()==1?SampledCutState.UNIQUE_FIRST_EDGE:SampledCutState.MULTIPLE_FIRST_EDGES;
        return new SampledCut(state,cells,rim,first);
    }

    /** All sampled D8 connections, without selecting any external water condition or downstream direction. */
    static GridTransfer grid(int ox, int oz, int nx, int nz, int step, double[] bed) {
        cancelled();
        if (nx < 2 || nz < 2 || step < 1 || (long) nx * nz > 131072 || bed.length != nx * nz
                || Math.floorMod(ox, step) != 0 || Math.floorMod(oz, step) != 0)
            throw new IllegalArgumentException("bounded aligned D8 transfer");
        var vertices = new ArrayList<Vertex>();var ports = new ArrayList<Integer>();var candidates = new ArrayList<Edge>();
        for (int k=0;k<bed.length;k++) {
            cancelled();if (!Double.isFinite(bed[k])) throw new IllegalArgumentException("nonfinite transfer bed");
            int x=k/nz,z=k%nz;
            vertices.add(new Vertex(new SharedBoundaryDrainage.WorldCell(Math.addExact(ox,Math.multiplyExact(x,step)),
                    Math.addExact(oz,Math.multiplyExact(z,step))),bed[k],Double.POSITIVE_INFINITY));
            if (x==0 || z==0 || x==nx-1 || z==nz-1) ports.add(k);
            for (int[] d:new int[][]{{0,1},{1,-1},{1,0},{1,1}}) {
                int a=x+d[0],b=z+d[1];if(a<0 || b<0 || a>=nx || b>=nz)continue;
                int next=a*nz+b;candidates.add(new Edge(k,next,Math.max(bed[k],bed[next])));
            }
        }
        candidates.sort(Comparator.comparingDouble(Edge::barrier).thenComparingInt(Edge::a).thenComparingInt(Edge::b));
        int[] parent=new int[bed.length];for(int k=0;k<parent.length;k++)parent[k]=k;
        var tree=new ArrayList<Edge>();
        for (var edge:candidates) {
            cancelled();int a=root(parent,edge.a),b=root(parent,edge.b);if(a==b)continue;
            parent[b]=a;tree.add(edge);
        }
        return new GridTransfer(vertices,tree,ports,nx,nz,step);
    }

    static Map<SharedBoundaryDrainage.WorldCell,Double> portCosts(GridTransfer transfer, SharedBoundaryDrainage.WorldCell entry) {
        cancelled();var ids=new HashMap<SharedBoundaryDrainage.WorldCell,Integer>();
        for(int k=0;k<transfer.vertices.size();k++)ids.put(transfer.vertices.get(k).point,k);
        Integer start=ids.get(entry);if(start==null)throw new IllegalArgumentException("entry outside transfer");
        var graph=new ArrayList<List<Edge>>();for(int k=0;k<transfer.vertices.size();k++)graph.add(new ArrayList<>());
        for(var e:transfer.edges){graph.get(e.a).add(e);graph.get(e.b).add(e);}
        var result=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        int[] parent=new int[graph.size()];Arrays.fill(parent,-1);parent[start]=start;
        double[] costs=new double[graph.size()];costs[start]=transfer.vertices.get(start).bed;
        var queue=new java.util.ArrayDeque<Integer>();queue.add(start);
        while(!queue.isEmpty()) {
            cancelled();int k=queue.remove();
            for(var edge:graph.get(k)) {int next=other(edge,k);if(parent[next]>=0)continue;
                parent[next]=k;costs[next]=Math.max(costs[k],edge.barrier);queue.add(next);}
        }
        for(int k:transfer.ports)result.put(transfer.vertices.get(k).point,costs[k]);
        return Map.copyOf(result);
    }

    /** Compose already sampled coverage. Internal frames disappear; missing D8 neighbors remain unknown ports. */
    static GridAssembly assemble(List<GridTransfer> inputs) {
        cancelled();
        if (inputs.isEmpty() || inputs.size() > 4 || inputs.stream().mapToLong(t -> t.vertices.size()).sum() > 131072)
            throw new IllegalArgumentException("bounded explicit D8 inputs required");
        int step = inputs.get(0).step;
        var merged = new java.util.TreeMap<SharedBoundaryDrainage.WorldCell, Vertex>(Comparator
                .comparingInt(SharedBoundaryDrainage.WorldCell::x).thenComparingInt(SharedBoundaryDrainage.WorldCell::z));
        var membership = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int part = 0; part < inputs.size(); part++) {
            var input = inputs.get(part);
            if (input.step != step) throw new IllegalArgumentException("inconsistent D8 step");
            for (var v : input.vertices) {
                cancelled(); var old = merged.putIfAbsent(v.point, v);
                if (old != null && Double.doubleToRawLongBits(old.bed) != Double.doubleToRawLongBits(v.bed))
                    throw new IllegalArgumentException("inconsistent overlapping D8 bed");
                membership.merge(v.point, 1 << part, (a, b) -> a | b);
            }
        }
        var vertices = new ArrayList<>(merged.values());
        var ids = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int k = 0; k < vertices.size(); k++) ids.put(vertices.get(k).point, k);
        var candidates = new ArrayList<Edge>(); var ports = new ArrayList<Integer>();
        for (var input : inputs) for (var e : input.edges) {
            int a = ids.get(input.vertices.get(e.a).point), b = ids.get(input.vertices.get(e.b).point);
            candidates.add(new Edge(Math.min(a, b), Math.max(a, b), e.barrier));
        }
        for (int k = 0; k < vertices.size(); k++) {
            cancelled(); var v = vertices.get(k); boolean exposed = false;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var at = gridNeighbor(v.point, dx, dz, step); Integer next = at == null ? null : ids.get(at);
                if (next == null) { exposed = true; continue; }
                // A tile tree replaces all internal D8 edges, but not edges joining distinct input tiles.
                if (k < next && (membership.get(v.point) & membership.get(at)) == 0)
                    candidates.add(new Edge(k, next, Math.max(v.bed, vertices.get(next).bed)));
            }
            if (exposed) ports.add(k);
        }
        candidates.sort(Comparator.comparingDouble(Edge::barrier).thenComparingInt(Edge::a).thenComparingInt(Edge::b));
        int[] components = new int[vertices.size()]; for (int k = 0; k < components.length; k++) components[k] = k;
        var tree = new ArrayList<Edge>();
        for (var e : candidates) {
            cancelled(); int a = root(components, e.a), b = root(components, e.b);
            if (a != b) { components[b] = a; tree.add(e); }
        }
        return new GridAssembly(vertices, tree, ports, step, inputs.size());
    }

    private static SharedBoundaryDrainage.WorldCell gridNeighbor(SharedBoundaryDrainage.WorldCell at, int dx, int dz, int step) {
        long x = (long) at.x() + (long) dx * step, z = (long) at.z() + (long) dz * step;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return null;
        return new SharedBoundaryDrainage.WorldCell((int) x, (int) z);
    }

    static List<SharedBoundaryDrainage.WorldEdge> exteriorEdges(GridAssembly assembly) {
        cancelled(); var covered = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (var v : assembly.vertices) covered.add(v.point);
        var edges = new ArrayList<SharedBoundaryDrainage.WorldEdge>();
        for (int k : assembly.ports) {
            var at = assembly.vertices.get(k).point;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                cancelled(); if (dx == 0 && dz == 0) continue;
                var next = gridNeighbor(at, dx, dz, assembly.step);
                if (next == null) throw new IllegalArgumentException("exterior coordinate overflow");
                if (!covered.contains(next)) edges.add(new SharedBoundaryDrainage.WorldEdge(at, next));
            }
        }
        edges.sort(Comparator.comparingInt((SharedBoundaryDrainage.WorldEdge e) -> e.from().x())
                .thenComparingInt(e -> e.from().z()).thenComparingInt(e -> e.to().x()).thenComparingInt(e -> e.to().z()));
        return List.copyOf(edges);
    }

    /** Explicit observations only. The resolver must not fetch missing fine results or treat missing data as terrain. */
    static ExteriorFrame exterior(SharedBoundaryDrainage.WorldPolicy policy, GridAssembly assembly,
            java.util.function.Function<SharedBoundaryDrainage.WorldCell, ExteriorObservation> input, int maxSteps) {
        cancelled();
        if (policy.step() != assembly.step || maxSteps < 1 || maxSteps > 4096)
            throw new IllegalArgumentException("bounded exterior policy required");
        var covered = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (var v : assembly.vertices) covered.add(v.point);
        var observations = new HashMap<SharedBoundaryDrainage.WorldCell, ExteriorObservation>();
        var missing = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        java.util.function.Function<SharedBoundaryDrainage.WorldCell, ExteriorObservation> frozen = at -> {
            if (missing.contains(at)) return null;
            var hit = observations.get(at); if (hit != null) return hit;
            if (observations.size() + missing.size() == 131072) throw new IllegalArgumentException("exterior observation budget");
            var value = input.apply(at);
            if (value == null) { missing.add(at); return null; }
            var owner = new ProductionDrainageAudit.Region(Math.floorDiv(at.x(), policy.tract()), Math.floorDiv(at.z(), policy.tract()));
            if (!at.equals(value.point) || !owner.equals(value.owner) || Math.floorMod(at.x(), policy.step()) != 0
                    || Math.floorMod(at.z(), policy.step()) != 0 || !Double.isFinite(value.bed) || !Double.isFinite(value.level))
                throw new IllegalArgumentException("invalid exterior provenance");
            observations.put(at, value); return value;
        };
        var edges = exteriorEdges(assembly); var traces = new HashMap<SharedBoundaryDrainage.WorldEdge, ExteriorTrace>();
        int pairs = 0;
        for (var edge : edges) {
            var trace = exteriorTrace(edge, covered, frozen, policy.step(), maxSteps);
            pairs += trace.path.size();
            if (pairs > 1048576) throw new IllegalArgumentException("exterior path budget");
            traces.put(edge, trace);
        }
        return new ExteriorFrame(policy, edges, traces);
    }

    private static ExteriorTrace exteriorTrace(SharedBoundaryDrainage.WorldEdge edge,
            java.util.Set<SharedBoundaryDrainage.WorldCell> covered,
            java.util.function.Function<SharedBoundaryDrainage.WorldCell, ExteriorObservation> input, int step, int maxSteps) {
        var path = new ArrayList<ExteriorObservation>(); var seen = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        var at = edge.to(); ExteriorState state;
        while (true) {
            cancelled();
            if (covered.contains(at)) { state = ExteriorState.REENTERS_DOMAIN; break; }
            if (!seen.add(at)) { state = ExteriorState.CYCLE; break; }
            if (path.size() == maxSteps) { state = ExteriorState.LIMIT; break; }
            var node = input.apply(at);
            if (node == null) { state = ExteriorState.OUTSIDE_INPUT; break; }
            path.add(node);
            if (!node.resolved) { state = ExteriorState.INPUT_PENDING; break; }
            if (path.size() > 1 && node.level > path.get(path.size() - 2).level) { state = ExteriorState.INVALID_SUCCESSOR; break; }
            if (node.sea) { state = ExteriorState.OCEAN_CANDIDATE; break; }
            if (node.lake || node.bed < node.level) { state = ExteriorState.BASIN_PENDING; break; }
            if (node.next == null) { state = ExteriorState.INPUT_PENDING; break; }
            long dx = (long) node.next.x() - at.x(), dz = (long) node.next.z() - at.z();
            if (Math.abs(dx) > step || Math.abs(dz) > step || dx % step != 0 || dz % step != 0 || dx == 0 && dz == 0) {
                state = ExteriorState.INVALID_SUCCESSOR; break;
            }
            at = node.next;
        }
        return new ExteriorTrace(edge, path, state);
    }

    /** Complete same-operator adjacent beds only; no outlet, water level or external successor is inferred. */
    static ExteriorBounds exteriorBounds(GridAssembly assembly, Map<SharedBoundaryDrainage.WorldCell, Double> outsideBeds) {
        cancelled(); var edges = exteriorEdges(assembly); var expected = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (var edge : edges) expected.add(edge.to());
        if (!outsideBeds.keySet().equals(expected)) throw new IllegalArgumentException("complete exact exterior bed ring required");
        var beds = new HashMap<SharedBoundaryDrainage.WorldCell, Double>(); for (var v : assembly.vertices) beds.put(v.point, v.bed);
        var barriers = new HashMap<SharedBoundaryDrainage.WorldEdge, Double>();
        for (var edge : edges) {
            cancelled(); double bed = outsideBeds.get(edge.to());
            if (!Double.isFinite(bed)) throw new IllegalArgumentException("finite exterior bed required");
            barriers.put(edge, Math.max(beds.get(edge.from()), bed));
        }
        return new ExteriorBounds(barriers);
    }

    /** Coarse external paths are conditional candidates only; pending edges retain the zero-knowledge bed lower bound. */
    static Map<SharedBoundaryDrainage.WorldCell, GridCapacity> exteriorCapacities(GridAssembly assembly, ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell, Double> localTerminals, double seaLevel) {
        return exteriorCapacities(assembly, frame, localTerminals, seaLevel, null);
    }

    static Map<SharedBoundaryDrainage.WorldCell, GridCapacity> exteriorCapacities(GridAssembly assembly, ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell, Double> localTerminals, double seaLevel, ExteriorBounds bounds) {
        cancelled();
        if (frame.policy.step() != assembly.step || !frame.edges.equals(exteriorEdges(assembly))
                || !frame.traces.keySet().equals(new java.util.HashSet<>(frame.edges)) || !Double.isFinite(seaLevel))
            throw new IllegalArgumentException("complete matching exterior frame required");
        if (bounds != null && !bounds.barriers.keySet().equals(frame.traces.keySet()))
            throw new IllegalArgumentException("matching complete exterior bounds required");
        var seeds = exteriorTerminals(frame, localTerminals, seaLevel);
        var unknown = new HashMap<SharedBoundaryDrainage.WorldCell, Double>();
        var beds = new HashMap<SharedBoundaryDrainage.WorldCell, Double>(); for (var v : assembly.vertices) beds.put(v.point, v.bed);
        for (var edge : frame.edges) {
            var trace = frame.traces.get(edge);
            if (!edge.equals(trace.edge)) throw new IllegalArgumentException("exterior edge mismatch");
            double barrier = bounds == null ? beds.get(edge.from()) : bounds.barriers.get(edge);
            if (!Double.isFinite(barrier) || barrier < beds.get(edge.from())) throw new IllegalArgumentException("invalid exterior lower bound");
            // All exits, including coarse sea candidates and returns, remain unknown beyond the observed adjacent bed.
            unknown.merge(edge.from(), barrier, Math::min);
        }
        var graph = new ArrayList<List<Edge>>(); for (int k = 0; k < assembly.vertices.size(); k++) graph.add(new ArrayList<>());
        for (var e : assembly.edges) { graph.get(e.a).add(e); graph.get(e.b).add(e); }
        double[] observedPending = bounds == null ? null : flood(assembly.vertices, graph, v -> unknown.getOrDefault(v.point, Double.POSITIVE_INFINITY));
        var ids = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int k = 0; k < assembly.vertices.size(); k++) ids.put(assembly.vertices.get(k).point, k);
        for (var trace : frame.traces.values()) if (trace.state == ExteriorState.REENTERS_DOMAIN && !trace.path.isEmpty()) {
            cancelled(); var back = trace.path.get(trace.path.size() - 1).next;
            if (!ids.containsKey(back)) throw new IllegalArgumentException("exterior return outside coverage");
            int a = ids.get(trace.edge.from()), b = ids.get(back);
            double barrier = Math.max(beds.get(trace.edge.from()), beds.get(back));
            for (var node : trace.path) barrier = Math.max(barrier, Math.max(node.bed, node.level));
            if (a != b) { var link = new Edge(a, b, barrier); graph.get(a).add(link); graph.get(b).add(link); }
        }
        for (var e : seeds.entrySet()) if (!beds.containsKey(e.getKey()) || !Double.isFinite(e.getValue()))
            throw new IllegalArgumentException("finite covered terminal required");
        double[] candidate = flood(assembly.vertices, graph, v -> seeds.containsKey(v.point) ? Math.max(v.bed, seeds.get(v.point)) : Double.POSITIVE_INFINITY);
        double[] pending = observedPending != null ? observedPending : flood(assembly.vertices, graph, v -> unknown.getOrDefault(v.point, Double.POSITIVE_INFINITY));
        var result = new HashMap<SharedBoundaryDrainage.WorldCell, GridCapacity>();
        for (int k = 0; k < assembly.vertices.size(); k++) result.put(assembly.vertices.get(k).point, new GridCapacity(candidate[k], pending[k]));
        return Map.copyOf(result);
    }

    private static Map<SharedBoundaryDrainage.WorldCell, Double> exteriorTerminals(ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell, Double> localTerminals, double seaLevel) {
        var seeds = new HashMap<>(localTerminals);
        for (var edge : frame.edges) {
            cancelled(); var trace = frame.traces.get(edge);
            if (trace.state != ExteriorState.OCEAN_CANDIDATE) continue;
            double barrier = seaLevel;
            for (var node : trace.path) barrier = Math.max(barrier, Math.max(node.bed, node.level));
            seeds.merge(edge.from(), barrier, Math::min);
        }
        return seeds;
    }

    /** Source-free candidate on actual sampled D8 neighbors, never the capacity tree or a coarse return shortcut. */
    static ConditionalRouting conditionalRouting(GridAssembly assembly, ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell, Double> localTerminals, double seaLevel) {
        return conditionalRouting(assembly, frame, localTerminals, seaLevel, null);
    }

    static ConditionalRouting conditionalRouting(GridAssembly assembly, ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell, Double> localTerminals, double seaLevel, ExteriorBounds bounds) {
        var capacity = exteriorCapacities(assembly, frame, localTerminals, seaLevel, bounds);
        var seeds = exteriorTerminals(frame, localTerminals, seaLevel);
        int size = assembly.vertices.size(); var ids = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int k = 0; k < size; k++) ids.put(assembly.vertices.get(k).point, k);
        double[] level = new double[size]; Arrays.fill(level, Double.POSITIVE_INFINITY);
        int[] parent = new int[size]; Arrays.fill(parent, -1); boolean[] settled = new boolean[size];
        var ports = new java.util.HashSet<Integer>(assembly.ports);
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost).thenComparingInt(Visit::k));
        for (int k = 0; k < size; k++) {
            cancelled(); var v = assembly.vertices.get(k);
            if (seeds.containsKey(v.point)) level[k] = Math.max(v.bed, seeds.get(v.point));
            if (Double.isFinite(level[k])) queue.add(new Visit(k, level[k]));
        }
        while (!queue.isEmpty()) {
            cancelled(); var visit = queue.remove(); int k = visit.k;
            if (settled[k] || visit.cost != level[k]) continue; settled[k] = true;
            var at = assembly.vertices.get(k).point;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var point = gridNeighbor(at, dx, dz, assembly.step); Integer next = point == null ? null : ids.get(point);
                if (next == null || settled[next]) continue;
                double cost = Math.max(level[k], assembly.vertices.get(next).bed);
                if (cost < level[next]) { level[next] = cost; parent[next] = k; queue.add(new Visit(next, cost)); }
            }
        }
        var nodes = new HashMap<SharedBoundaryDrainage.WorldCell, SharedBoundaryDrainage.WorldNode>();
        for (int k = 0; k < size; k++) {
            cancelled(); var v = assembly.vertices.get(k); var state = SharedBoundaryDrainage.WorldState.NEXT;
            if (localTerminals.containsKey(v.point)) state = SharedBoundaryDrainage.WorldState.OCEAN_CANDIDATE;
            else if (!Double.isFinite(level[k]) || ports.contains(k) || parent[k] < 0
                    || capacity.get(v.point).unknown < level[k]) state = SharedBoundaryDrainage.WorldState.INPUT_PENDING;
            else if (v.bed < level[k]) state = SharedBoundaryDrainage.WorldState.BASIN_PENDING;
            var next = state == SharedBoundaryDrainage.WorldState.NEXT ? assembly.vertices.get(parent[k]).point : null;
            nodes.put(v.point, new SharedBoundaryDrainage.WorldNode(v.point, next, v.bed, Double.isFinite(level[k]) ? level[k] : v.bed, state));
        }
        return new ConditionalRouting(frame.policy, nodes);
    }

    /** Known terminal inputs need explicit provenance at the caller; all uncovered neighbors remain unknown. */
    static Map<SharedBoundaryDrainage.WorldCell, GridCapacity> capacities(GridAssembly assembly,
            Map<SharedBoundaryDrainage.WorldCell, Double> terminals) {
        cancelled(); var ids = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int k = 0; k < assembly.vertices.size(); k++) ids.put(assembly.vertices.get(k).point, k);
        for (var e : terminals.entrySet()) if (!ids.containsKey(e.getKey()) || !Double.isFinite(e.getValue()))
            throw new IllegalArgumentException("finite covered terminal required");
        var frame = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (int k : assembly.ports) frame.add(assembly.vertices.get(k).point);
        var graph = new ArrayList<List<Edge>>(); for (int k = 0; k < assembly.vertices.size(); k++) graph.add(new ArrayList<>());
        for (var e : assembly.edges) { graph.get(e.a).add(e); graph.get(e.b).add(e); }
        double[] known = flood(assembly.vertices, graph, v -> terminals.containsKey(v.point)
                ? Math.max(v.bed, terminals.get(v.point)) : Double.POSITIVE_INFINITY);
        double[] unknown = flood(assembly.vertices, graph, v -> frame.contains(v.point) ? v.bed : Double.POSITIVE_INFINITY);
        var result = new HashMap<SharedBoundaryDrainage.WorldCell, GridCapacity>();
        for (int k = 0; k < assembly.vertices.size(); k++)
            result.put(assembly.vertices.get(k).point, new GridCapacity(known[k], unknown[k]));
        return Map.copyOf(result);
    }

    /** Explicit complete frame conditions only. Missing input is rejected, never interpreted as a closed edge or sea. */
    static Map<SharedBoundaryDrainage.WorldCell,Double> condition(GridTransfer transfer,
            Map<SharedBoundaryDrainage.WorldCell,Double> boundary) {
        cancelled();var frame=new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for(int k:transfer.ports)frame.add(transfer.vertices.get(k).point);
        if(!frame.equals(boundary.keySet()))throw new IllegalArgumentException("complete transfer frame required");
        for(double value:boundary.values())if(Double.isNaN(value) || value==Double.NEGATIVE_INFINITY)
            throw new IllegalArgumentException("explicit finite or closed boundary condition required");
        var graph=new ArrayList<List<Edge>>();for(int k=0;k<transfer.vertices.size();k++)graph.add(new ArrayList<>());
        for(var edge:transfer.edges){graph.get(edge.a).add(edge);graph.get(edge.b).add(edge);}
        double[] costs=flood(transfer.vertices,graph,v->boundary.containsKey(v.point)?Math.max(v.bed,boundary.get(v.point)):Double.POSITIVE_INFINITY);
        var result=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(int k=0;k<costs.length;k++)result.put(transfer.vertices.get(k).point,costs[k]);
        return Map.copyOf(result);
    }

    static Product build(SharedBlockDrainage.Domain d, BiPredicate<Integer, Integer> ports) {
        if ((long) d.sizeX() * d.sizeZ() != d.nodes()) throw new IllegalArgumentException("rectangular summary required");
        for (int i = 0; i < d.nodes(); i++) if (!d.coveredAtIndex(i)) throw new IllegalArgumentException("uncovered summary interior");
        int n = d.nodes(), nz = d.sizeZ();
        var candidates = new ArrayList<Edge>();
        boolean[] keep = new boolean[n];
        for (int k = 0; k < n; k++) {
            cancelled();
            if (!d.coveredAtIndex(k)) continue;
            int x = d.originX() + k / nz, z = d.originZ() + k % nz;
            keep[k] = k / nz == 0 || k % nz == 0 || k / nz == d.sizeX() - 1 || k % nz == nz - 1 || ports.test(x, z);
            int links = d.linksAtIndex(k);
            for (int direction : new int[]{2, 3}) if ((links & (1 << direction)) != 0) {
                int p = k + DIRS[direction][0] * nz + DIRS[direction][1];
                candidates.add(new Edge(k, p, Math.max(d.bedAtIndex(k), d.bedAtIndex(p))));
            }
        }
        candidates.sort(Comparator.comparingDouble(Edge::barrier).thenComparingInt(Edge::a).thenComparingInt(Edge::b));
        int[] components = new int[n];
        for (int i = 0; i < n; i++) components[i] = i;
        var tree = new ArrayList<Edge>();
        for (var edge : candidates) {
            cancelled();
            int a = root(components, edge.a), b = root(components, edge.b);
            if (a == b) continue;
            components[b] = a; tree.add(edge);
        }
        return reduce(d, tree, keep);
    }

    private static Product reduce(SharedBlockDrainage.Domain d, List<Edge> tree, boolean[] keep) {
        int n = keep.length;
        List<List<Edge>> graph = new ArrayList<>(n);
        for (int i = 0; i < n; i++) graph.add(new ArrayList<>());
        for (var e : tree) { graph.get(e.a).add(e); graph.get(e.b).add(e); }
        int[] degree = new int[n]; boolean[] removed = new boolean[n];
        var leaves = new java.util.ArrayDeque<Integer>();
        for (int i = 0; i < n; i++) {
            degree[i] = graph.get(i).size();
            if (!keep[i] && degree[i] <= 1) leaves.add(i);
        }
        while (!leaves.isEmpty()) {
            cancelled(); int k = leaves.remove(); if (removed[k]) continue;
            removed[k] = true;
            for (var e : graph.get(k)) {
                int p = other(e, k); if (removed[p]) continue;
                if (--degree[p] <= 1 && !keep[p]) leaves.add(p);
            }
        }
        int[] id = new int[n]; Arrays.fill(id, -1);
        var vertices = new ArrayList<Vertex>();
        for (int i = 0; i < n; i++) if (!removed[i] && (keep[i] || degree[i] != 2)) {
            id[i] = vertices.size();
            var point = new SharedBoundaryDrainage.WorldCell(d.originX() + i / d.sizeZ(), d.originZ() + i % d.sizeZ());
            vertices.add(new Vertex(point, d.bedAtIndex(i), d.capacityAt(point.x(), point.z()).sea()));
        }
        var edges = new ArrayList<Edge>();
        for (int k = 0; k < n; k++) if (id[k] >= 0) for (var first : graph.get(k)) {
            cancelled(); int previous = k, p = other(first, k); double barrier = first.barrier;
            if (removed[p]) continue;
            while (id[p] < 0) {
                Edge next = null;
                for (var e : graph.get(p)) if (!removed[other(e, p)] && other(e, p) != previous) { next = e; break; }
                if (next == null) throw new IllegalStateException("capacity tree dead branch");
                barrier = Math.max(barrier, next.barrier); previous = p; p = other(next, p);
            }
            if (id[k] < id[p]) edges.add(new Edge(id[k], id[p], barrier));
        }
        edges.sort(Comparator.comparingInt(Edge::a).thenComparingInt(Edge::b));
        return new Product(vertices, edges, d.nodes(), d.seaLevel(), d.originX(), d.originZ(), d.sizeX(), d.sizeZ(), d.step(), d.parts());
    }

    /** Complete outer frame is derived from coverage; callers cannot silently omit an unknown exit. */
    static Map<SharedBoundaryDrainage.WorldCell, SharedBlockDrainage.Capacity> solve(List<Product> products) {
        if (products.isEmpty() || products.size() > 2 || products.stream().mapToInt(Product::parts).sum() > 2)
            throw new IllegalArgumentException("fixed transfer count");
        for (var p : products) if (p.step != products.get(0).step || p.seaLevel != products.get(0).seaLevel)
            throw new IllegalArgumentException("inconsistent transfer policy");
        if (products.size() == 2) {
            Product a = products.get(0), b = products.get(1);
            int overlapX = Math.min(a.ox + a.nx, b.ox + b.nx) - Math.max(a.ox, b.ox);
            int overlapZ = Math.min(a.oz + a.nz, b.oz + b.nz) - Math.max(a.oz, b.oz);
            if (!((overlapX == 1 && a.oz == b.oz && a.nz == b.nz)
                    || (overlapZ == 1 && a.ox == b.ox && a.nx == b.nx)))
                throw new IllegalArgumentException("only fully edge-sharing rectangular patches are supported");
        }
        var merged = new java.util.TreeMap<SharedBoundaryDrainage.WorldCell, Vertex>(Comparator
                .comparingInt(SharedBoundaryDrainage.WorldCell::x).thenComparingInt(SharedBoundaryDrainage.WorldCell::z));
        for (var product : products) for (var v : product.vertices) {
            cancelled();
            Vertex old = merged.get(v.point);
            if (old != null && Double.doubleToRawLongBits(old.bed) != Double.doubleToRawLongBits(v.bed))
                throw new IllegalArgumentException("inconsistent transfer port bed");
            merged.put(v.point, old == null ? v : new Vertex(v.point, v.bed, Math.min(old.seaCapacity, v.seaCapacity)));
        }
        var vertices = new ArrayList<>(merged.values());
        var sorted = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int i = 0; i < vertices.size(); i++) sorted.put(vertices.get(i).point, i);
        int next = vertices.size();
        var graph = new ArrayList<List<Edge>>(); for (int i = 0; i < next; i++) graph.add(new ArrayList<>());
        for (var product : products) for (var e : product.edges) {
            int a = sorted.get(product.vertices.get(e.a).point), b = sorted.get(product.vertices.get(e.b).point);
            var edge = new Edge(a, b, e.barrier); graph.get(a).add(edge); graph.get(b).add(edge);
        }
        double[] sea = flood(vertices, graph, Vertex::seaCapacity);
        double[] frame = flood(vertices, graph, v -> exposed(products, v.point) ? v.bed : Double.POSITIVE_INFINITY);
        var result = new HashMap<SharedBoundaryDrainage.WorldCell, SharedBlockDrainage.Capacity>();
        for (int i = 0; i < vertices.size(); i++)
            result.put(vertices.get(i).point, new SharedBlockDrainage.Capacity(sea[i], frame[i]));
        return Map.copyOf(result);
    }

    private static boolean exposed(List<Product> products, SharedBoundaryDrainage.WorldCell c) {
        for (int[] dir : DIRS) {
            int x = c.x() + dir[0], z = c.z() + dir[1];
            boolean covered = false;
            for (var p : products) covered |= x >= p.ox && z >= p.oz && x < p.ox + p.nx && z < p.oz + p.nz;
            if (!covered) return true;
        }
        return false;
    }

    private static double[] flood(List<Vertex> vertices, List<List<Edge>> graph, java.util.function.ToDoubleFunction<Vertex> seeds) {
        double[] best = new double[vertices.size()]; Arrays.fill(best, Double.POSITIVE_INFINITY);
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost).thenComparingInt(Visit::k));
        for (int i = 0; i < vertices.size(); i++) {
            best[i] = seeds.applyAsDouble(vertices.get(i));
            if (Double.isFinite(best[i])) queue.add(new Visit(i, best[i]));
        }
        while (!queue.isEmpty()) {
            cancelled(); var node = queue.remove(); if (node.cost != best[node.k]) continue;
            for (var edge : graph.get(node.k)) {
                int p = other(edge, node.k); double cost = Math.max(node.cost, edge.barrier);
                if (cost < best[p]) { best[p] = cost; queue.add(new Visit(p, cost)); }
            }
        }
        return best;
    }

    private static int other(Edge e, int k) { return e.a == k ? e.b : e.a; }
    private static int root(int[] parent, int k) {
        while (parent[k] != k) { parent[k] = parent[parent[k]]; k = parent[k]; }
        return k;
    }
    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("boundary capacity transfer");
    }
    private SharedBoundaryTransfer() { }
}
