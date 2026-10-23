package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;

/** Minimax witness; known receiving channels are terminals, unknown domain edges are not. */
final class OrderedSpillSearch {
    enum Status { ESCAPE, JOIN, EXHAUSTED, DOMAIN_PENDING, NO_ESCAPE }
    record Result(Status status, int[] path, double crest, double boundaryBound, int expanded) {
        boolean accepted() { return status == Status.ESCAPE || status == Status.JOIN; }
    }
    interface Guards {
        boolean blocked(int cell);
        boolean receiver(int cell);
        boolean blockedEdge(int from, int to);
    }
    private record Node(int cell, double crest) { }
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private final double[] bed, best;
    private final int[] parent;
    private final boolean[] settled;
    private final int nx, nz, start, totalLimit;
    private final double cap;
    private final Guards guards;
    private final PriorityQueue<Node> queue;
    private Node candidate;
    private int[] candidatePath = new int[0], bfsParent;
    private java.util.ArrayDeque<Integer> bfs;
    private boolean[] seen;
    private int layerRemaining, chosen = -1;
    private boolean touchesUnknown;
    private Result completed;
    private double boundary = Double.POSITIVE_INFINITY;
    private int expanded;

    OrderedSpillSearch(double[] bed, int nx, int nz, int start, double rise,
                       int ox, int oz, int step, int totalLimit, Guards guards) {
        this.bed = bed; this.nx = nx; this.nz = nz; this.start = start;
        this.cap = bed[start] + rise; this.guards = guards;
        this.totalLimit = Math.min(totalLimit, bed.length);
        best = new double[bed.length]; Arrays.fill(best, Double.POSITIVE_INFINITY);
        parent = new int[bed.length]; Arrays.fill(parent, -1);
        settled = new boolean[bed.length];
        queue = new PriorityQueue<>(Comparator.comparingDouble(Node::crest)
                .thenComparingInt(n -> ox + n.cell() / nz * step)
                .thenComparingInt(n -> oz + n.cell() % nz * step));
        best[start] = bed[start]; queue.add(new Node(start, bed[start]));
    }

    Result find() {
        while (true) {
            Result done = batch(4096);
            if (done != null) return done;
        }
    }

    Result batch(int batchLimit) {
        if (completed != null) return completed;
        int count = 0;
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            Node node = queue.peek();
            if (settled[node.cell()] || node.crest() != best[node.cell()]) { queue.poll(); continue; }
            if (node.crest() > cap) return conclude(batchLimit - count);
            if (candidate != null) return shortestAtCrest(batchLimit - count);
            if (expanded >= totalLimit) return result(Status.EXHAUSTED);
            if (count >= batchLimit) return null;
            queue.poll(); settle(node); count++;
            if (candidate != null && boundary < candidate.crest()) return result(Status.DOMAIN_PENDING);
        }
        return conclude(batchLimit - count);
    }

    private void settle(Node node) {
        int k = node.cell(); settled[k] = true; expanded++;
        boolean knownReceiver = k != start && guards.receiver(k);
        boolean lower = k != start && bed[k] < bed[start] - 1e-9;
        if (candidate == null && (knownReceiver || lower)) {
            candidate = node; candidatePath = pathTo(k, parent);
        }
        // A known receiving channel owns all further flow. A lower bed alone does not.
        if (knownReceiver) return;
        int i = k / nz, j = k % nz;
        if (i == 0 || j == 0 || i == nx - 1 || j == nz - 1) boundary = Math.min(boundary, node.crest());
        for (int[] d : DIRS) {
            int a = i + d[0], b = j + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int to = a * nz + b;
            if (settled[to] || guards.blocked(to) || guards.blockedEdge(k, to)) continue;
            double crest = Math.max(node.crest(), bed[to]);
            if (crest > cap || crest >= best[to]) continue;
            best[to] = crest; parent[to] = k; queue.add(new Node(to, crest));
        }
    }

    private Result shortestAtCrest(int batchLimit) {
        double crest = candidate.crest();
        // A strictly cheaper unknown prefix can hide a cheaper external exit.
        if (boundary < crest) return result(Status.DOMAIN_PENDING);
        if (bfs == null) {
            bfs = new java.util.ArrayDeque<>(); seen = new boolean[bed.length];
            bfsParent = new int[bed.length]; Arrays.fill(bfsParent, -1);
            bfs.add(start); seen[start] = true; layerRemaining = 1;
        }
        int count = 0;
        while (!bfs.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (expanded >= totalLimit) return result(Status.EXHAUSTED);
            if (count >= batchLimit) return null;
            int k = bfs.removeFirst(), i = k / nz, j = k % nz;
            expanded++; count++;
            boolean goal = k != start && (guards.receiver(k) || bed[k] < bed[start] - 1e-9);
            boolean edge = i == 0 || j == 0 || i == nx - 1 || j == nz - 1;
            if (edge && !guards.receiver(k)) touchesUnknown = true;
            if (goal) { if (chosen < 0 || k < chosen) chosen = k; }
            else for (int[] d : DIRS) {
                int a = i + d[0], b = j + d[1];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int to = a * nz + b;
                if (seen[to] || bed[to] > crest || guards.blocked(to) || guards.blockedEdge(k, to)) continue;
                seen[to] = true; bfsParent[to] = k; bfs.addLast(to);
            }
            if (--layerRemaining != 0) continue;
            // Finish the whole layer, including ties, before accepting any endpoint.
            if (touchesUnknown) return result(Status.DOMAIN_PENDING);
            if (chosen >= 0) {
                candidate = new Node(chosen, crest); candidatePath = pathTo(chosen, bfsParent);
                return result(guards.receiver(chosen) ? Status.JOIN : Status.ESCAPE);
            }
            layerRemaining = bfs.size();
        }
        return result(Status.DOMAIN_PENDING);
    }

    private Result conclude(int batchLimit) {
        if (candidate != null) return shortestAtCrest(batchLimit);
        return result(boundary <= cap ? Status.DOMAIN_PENDING : Status.NO_ESCAPE);
    }

    private int[] pathTo(int cell, int[] parents) {
        ArrayList<Integer> reversed = new ArrayList<>();
        for (int k = cell; k >= 0; k = parents[k]) reversed.add(k);
        int[] path = new int[reversed.size()];
        for (int i = 0; i < path.length; i++) path[i] = reversed.get(path.length - 1 - i);
        return path;
    }

    private Result result(Status status) {
        completed = new Result(status, candidatePath, candidate == null ? Double.NaN : candidate.crest(),
                boundary, expanded);
        return completed;
    }
}
