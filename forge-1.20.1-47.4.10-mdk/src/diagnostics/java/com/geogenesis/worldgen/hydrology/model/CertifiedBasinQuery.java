package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Static ocean-reachability certificate. No finite window edge is considered an outlet. */
public final class CertifiedBasinQuery {
    public interface Terrain { double height(int x, int z); }
    public record Cell(int x, int z) { }
    public enum Status { RESOLVED, SEARCH_PENDING, CAPACITY_PENDING }
    public record Result(Status status, double escapeLevel, Cell owner,
                         double capacity, List<Cell> members, int sampledCells) {
        public Result { members = List.copyOf(members); }
    }
    private record Node(Cell cell, double cost, int steps) { }
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final Comparator<Cell> ORDER = Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z);
    public interface OceanBoundary { boolean contains(int x, int z); }
    private final Terrain terrain;
    private final OceanBoundary ocean;
    private final double seaLevel, area;

    public CertifiedBasinQuery(Terrain terrain, OceanBoundary ocean, double seaLevel, double cellSize) {
        if (!Double.isFinite(seaLevel) || !Double.isFinite(cellSize) || cellSize <= 0) {
            throw new IllegalArgumentException("invalid world units");
        }
        this.terrain = terrain;
        this.ocean = ocean;
        this.seaLevel = seaLevel;
        area = cellSize * cellSize;
    }

    public Result resolve(int x, int z, int budget) {
        if (budget <= 0) throw new IllegalArgumentException("budget");
        Map<Cell, Double> heights = new HashMap<>(), best = new HashMap<>();
        Comparator<Node> priority = Comparator.comparingDouble(Node::cost)
                .thenComparingInt(Node::steps).thenComparing(Node::cell, ORDER);
        PriorityQueue<Node> frontier = new PriorityQueue<>(priority);
        Cell start = new Cell(x, z);
        try {
            double initial = height(start, heights, budget);
            frontier.add(new Node(start, initial, 0)); best.put(start, initial);
            while (!frontier.isEmpty()) {
                checkInterrupted();
                Node node = frontier.remove();
                if (node.cost > best.get(node.cell)) continue;
                if (ocean.contains(node.cell.x, node.cell.z)) {
                    return capacity(start, Math.max(seaLevel, node.cost), heights, budget);
                }
                for (int[] d : DIRECTIONS) {
                    Cell next = neighbor(node.cell, d);
                    double cost = Math.max(node.cost, height(next, heights, budget));
                    if (cost < best.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        best.put(next, cost); frontier.add(new Node(next, cost, Math.addExact(node.steps, 1)));
                    }
                }
            }
        } catch (BudgetExceeded expected) {
            return new Result(Status.SEARCH_PENDING, Double.NaN, null, Double.NaN, List.of(), heights.size());
        }
        throw new IllegalStateException("unexpected exhausted lattice frontier");
    }

    private Result capacity(Cell start, double level, Map<Cell, Double> heights, int budget) {
        Set<Cell> visited = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        List<Cell> members = new ArrayList<>();
        try {
            if (height(start, heights, budget) >= level) {
                return new Result(Status.RESOLVED, level, null, 0, List.of(), heights.size());
            }
            visited.add(start); queue.add(start);
            while (!queue.isEmpty()) {
                checkInterrupted();
                Cell cell = queue.remove(); members.add(cell);
                for (int[] d : DIRECTIONS) {
                    Cell next = neighbor(cell, d);
                    if (visited.add(next) && height(next, heights, budget) < level) queue.add(next);
                }
            }
        } catch (BudgetExceeded expected) {
            // No partial capacity or partial owner is publishable.
            return new Result(Status.CAPACITY_PENDING, level, null, Double.NaN, List.of(), heights.size());
        }
        members.sort(ORDER);
        Cell owner = members.get(0);
        double capacity = 0;
        for (Cell cell : members) capacity += (level - heights.get(cell)) * area;
        return new Result(Status.RESOLVED, level, owner, capacity, members, heights.size());
    }

    private double height(Cell cell, Map<Cell, Double> heights, int budget) {
        Double hit = heights.get(cell);
        if (hit != null) return hit;
        if (heights.size() >= budget) throw new BudgetExceeded();
        double value = terrain.height(cell.x, cell.z);
        if (!Double.isFinite(value)) throw new IllegalStateException("nonfinite terrain height");
        heights.put(cell, value);
        return value;
    }

    private static Cell neighbor(Cell cell, int[] d) {
        return new Cell(Math.addExact(cell.x, d[0]), Math.addExact(cell.z, d[1]));
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("basin search interrupted");
    }

    private static final class BudgetExceeded extends RuntimeException { }
}
