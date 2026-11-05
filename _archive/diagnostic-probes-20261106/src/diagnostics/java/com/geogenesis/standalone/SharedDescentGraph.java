package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Stage A only. Local bounded straight-edge graph, NOT a complete halo or drainage proof. */
public final class SharedDescentGraph {
    public static final double PITCH = 4;
    // Frozen tie order: eight immediate neighbours followed by eight knight moves.
    private static final int[][] OFFSETS = {{-1,-1},{0,-1},{1,-1},{-1,0},{1,0},
        {-1,1},{0,1},{1,1},{-1,-2},{1,-2},{-2,-1},{2,-1},
        {-2,1},{2,1},{-1,2},{1,2}};
    public enum State { DESCENT, DOMAIN_BOUNDARY, BOUND_NO_DESCENT, FLAT_SAMPLES }
    public record Node(int id, int ix, int iz, double h0, int nextId, State state,
                       int indegree, int rootId, double downstreamLengthWu,
                       int samples, double minDrop) {
        public double x() { return ix * PITCH; }
        public double z() { return iz * PITCH; }
        public String label() { return ix + ":" + iz; }
    }
    public record Validation(boolean accepted, int samples, double minDrop, int rises) {}
    public record Choice(int nextId, State state, int samples, double minDrop,
                         int rejectedEdges, long rejectedSamples) {}
    private record Candidate(int id, double slope, int order) {}
    private final int minIX, minIZ, columns, rows;
    private final double[] heights;
    private final Node[] nodes;
    private final long rejectedEdges, rejectedSamples;

    public SharedDescentGraph(HeightField field, int minIX, int minIZ, int maxIX, int maxIZ) {
        Objects.requireNonNull(field, "field");
        require(maxIX > minIX && maxIZ > minIZ, "nonempty rectangular domain");
        this.minIX = minIX; this.minIZ = minIZ;
        columns = Math.addExact(Math.subtractExact(maxIX, minIX), 1);
        rows = Math.addExact(Math.subtractExact(maxIZ, minIZ), 1);
        heights = new double[Math.multiplyExact(columns, rows)];
        nodes = new Node[heights.length];
        for (int id = 0; id < size(); id++) heights[id] = sample(field, x(id), z(id));
        Choice[] choices = new Choice[size()];
        long rejects = 0, samples = 0;
        for (int id = 0; id < size(); id++) {
            choices[id] = choose(field, id);
            rejects += choices[id].rejectedEdges(); samples += choices[id].rejectedSamples();
        }
        rejectedEdges = rejects; rejectedSamples = samples;
        finish(choices);
    }

    /** Re-evaluates endpoints too; useful with an independently constructed cold H0 callback. */
    public Choice choose(HeightField field, int id) {
        require(id >= 0 && id < size(), "node id");
        if (boundary(id)) return new Choice(-1, State.DOMAIN_BOUNDARY, 0, Double.NaN, 0, 0);
        double source = sample(field, x(id), z(id));
        List<Candidate> candidates = new ArrayList<>(16);
        boolean equal = true;
        for (int order = 0; order < OFFSETS.length; order++) {
            int ix = ix(id) + OFFSETS[order][0], iz = iz(id) + OFFSETS[order][1];
            int target = idAt(ix, iz);
            if (target < 0) continue;
            double destination = sample(field, ix * PITCH, iz * PITCH);
            equal &= destination == source;
            if (destination < source) candidates.add(new Candidate(target,
                    (source - destination) / Math.hypot(x(target) - x(id), z(target) - z(id)), order));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::slope).reversed()
                .thenComparingInt(Candidate::order));
        int rejected = 0; long samples = 0;
        for (Candidate candidate : candidates) {
            Validation v = validate(field, x(id), z(id), x(candidate.id()), z(candidate.id()), .25, 0);
            if (v.accepted()) return new Choice(candidate.id(), State.DESCENT,
                    v.samples(), v.minDrop(), rejected, samples);
            rejected++; samples += v.samples();
        }
        return new Choice(-1, equal ? State.FLAT_SAMPLES : State.BOUND_NO_DESCENT,
                0, Double.NaN, rejected, samples);
    }

    /** Includes both vertices; no tolerance and no clamping of any measured drop. */
    public static Validation validate(HeightField field, double ax, double az,
                                      double bx, double bz, double spacing, double offset) {
        require(Double.isFinite(spacing) && spacing > 0, "positive spacing");
        require(offset >= 0 && offset < 1, "fractional offset");
        double length = Math.hypot(bx - ax, bz - az);
        require(length > 0 && Double.isFinite(length), "finite nonzero edge");
        double first = sample(field, ax, az), previous = first, min = Double.POSITIVE_INFINITY;
        int count = 1, rises = 0;
        int intervals = (int) Math.ceil(length / spacing);
        double ds = length / intervals;
        for (double s = offset == 0 ? ds : offset * ds; s < length; s += ds) {
            double t = s / length;
            double h = sample(field, ax + t * (bx - ax), az + t * (bz - az));
            double drop = previous - h; min = Math.min(min, drop);
            if (h > previous) rises++;
            previous = h; count++;
        }
        double last = sample(field, bx, bz);
        min = Math.min(min, previous - last);
        if (last > previous) rises++;
        return new Validation(last < first && rises == 0, count + 1, min, rises);
    }

    private void finish(Choice[] choices) {
        Integer[] order = new Integer[size()];
        int[] indegree = new int[size()], roots = new int[size()];
        double[] lengths = new double[size()];
        Arrays.fill(roots, -1);
        for (int id = 0; id < size(); id++) {
            order[id] = id;
            if (choices[id].nextId() >= 0) indegree[choices[id].nextId()]++;
        }
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> heights[i]).reversed()
                .thenComparingInt(Integer::intValue));
        // Reverse of descending-height topological order: downstream information is ready first.
        for (int i = order.length - 1; i >= 0; i--) {
            int id = order[i], next = choices[id].nextId();
            if (next < 0) roots[id] = id;
            else {
                require(heights[next] < heights[id] && roots[next] >= 0, "strict DAG order");
                roots[id] = roots[next];
                lengths[id] = Math.hypot(x(next) - x(id), z(next) - z(id)) + lengths[next];
            }
        }
        for (int id = 0; id < size(); id++) {
            Choice c = choices[id];
            nodes[id] = new Node(id, ix(id), iz(id), heights[id], c.nextId(), c.state(),
                    indegree[id], roots[id], lengths[id], c.samples(), c.minDrop());
        }
    }

    public Node node(int id) { return nodes[id]; }
    /** Canonical reference: converging branches return the SAME stored object, not copied paths. */
    public Node next(Node node) {
        require(node == nodes[node.id()], "canonical node required");
        return node.nextId() < 0 ? null : nodes[node.nextId()];
    }
    public int size() { return heights.length; }
    public int columns() { return columns; }
    public int rows() { return rows; }
    public long rejectedEdges() { return rejectedEdges; }
    public long rejectedSamples() { return rejectedSamples; }
    public int idAt(int ix, int iz) {
        int col = ix - minIX, row = iz - minIZ;
        return col < 0 || row < 0 || col >= columns || row >= rows ? -1 : row * columns + col;
    }
    public int nearest(double x, double z) {
        int ix = (int) Math.round(x / PITCH), iz = (int) Math.round(z / PITCH);
        return idAt(Math.max(minIX, Math.min(minIX + columns - 1, ix)),
                Math.max(minIZ, Math.min(minIZ + rows - 1, iz)));
    }
    private int ix(int id) { return minIX + id % columns; }
    private int iz(int id) { return minIZ + id / columns; }
    private double x(int id) { return ix(id) * PITCH; }
    private double z(int id) { return iz(id) * PITCH; }
    private boolean boundary(int id) {
        return id % columns == 0 || id % columns == columns - 1 || id / columns == 0
                || id / columns == rows - 1;
    }
    public static double sample(HeightField field, double x, double z) {
        double h = field.height(x, z);
        require(Double.isFinite(h), "finite full H0");
        return h;
    }
    public static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
