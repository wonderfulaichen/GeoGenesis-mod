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
    private record Visit(int k, double cost) { }
    private static final int[][] DIRS = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};

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
