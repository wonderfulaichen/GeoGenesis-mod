package com.geogenesis.worldgen.terrain.morph;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Geometry-only diagnostic arm; no coarse-to-fine inheritance or production changes.
 * Distances are approximate: a global uniform-t polyline, NOT exact Bezier distances.
 * Query support remains the original nine source cells, even across support seams.
 * Caches are unbounded and intended for bounded diagnostic windows, not production.
 */
final class CurvedSegmentErosion {
    record Edge(double ax, double az, double bx, double bz, int receiverX, int receiverZ,
                double c1x, double c1z, double c2x, double c2z) { }
    private record Key(int octave, int x, int z) { }
    private record Node(double x, double z, double height) { }
    private record Receiver(int x, int z, Node node) { }
    // Arrays are privately owned, never exposed or modified after publication.
    private record Polyline(double[] coordinates) { }

    private final MorphNoise.Erosion original;
    private final double bend;
    private final int segments;
    private final double[] grids;
    private final ConcurrentHashMap<Key, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Receiver> receivers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Edge> edges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Edge, Polyline> vertices = new ConcurrentHashMap<>();

    CurvedSegmentErosion(MorphNoise.Erosion original, double bend) {
        this(original, bend, 64);
    }

    /** Extra resolution changes distance discretization only, never curve geometry. */
    CurvedSegmentErosion(MorphNoise.Erosion original, double bend, int segments) {
        this.original = Objects.requireNonNull(original);
        if (!Double.isFinite(bend) || bend < 0 || bend > .15 || segments < 1)
            throw new IllegalArgumentException("bend must be in [0,.15], segments >= 1");
        this.bend = bend;
        this.segments = segments;
        grids = new double[original.octaves()];
        double grid = original.gridSize();
        for (int i = 0; i < grids.length; i++) {
            grids[i] = grid;
            grid *= original.distanceFallOff();
        }
    }

    MorphNoise.Erosion original() { return original; }
    double bend() { return bend; }
    int segments() { return segments; }
    double grid(int octave) { return grids[octave]; }

    /** Same compute blend; strength=1 gives the original erosion texture convention. */
    double eval(double x, double z) {
        double strength = original.strength();
        double value = MorphNoiseEval.eval(original.input(), x, z);
        double sum = 0, max = 0, gain = 1;
        double amp = original.amplitude(), lac = original.lacunarity();
        for (int i = 0; i < grids.length; i++) {
            sum += singleErosion(i, x, z) * gain;
            max += gain;
            gain *= amp;
            x *= lac;
            z *= lac;
        }
        return MorphNoiseEval.lerp(sum / max, value, 1 - strength);
    }

    private double singleErosion(int octave, double x, double z) {
        double grid = grids[octave];
        int pix = (int) Math.floor(x / grid), piz = (int) Math.floor(z / grid);
        double minDist2 = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                double d2 = geometryDistance2(edge(octave, pix + dx, piz + dz), x, z);
                if (d2 < minDist2) minDist2 = d2;
            }
        }
        return MorphNoiseEval.clamp(Math.sqrt(minDist2) / grid, 0, 1);
    }

    /** Coordinates are in this octave's original singleErosion coordinate system. */
    Edge edge(int octave, int cellX, int cellZ) {
        if (octave < 0 || octave >= grids.length) throw new IndexOutOfBoundsException(octave);
        return edges.computeIfAbsent(new Key(octave, cellX, cellZ), this::makeEdge);
    }

    private Node node(Key key) {
        return nodes.computeIfAbsent(key, k -> {
            float[] v = MorphCellTable.CELL[MorphNoise.hash2D(original.seed(), k.x, k.z) & 255];
            double x = (k.x + (double) v[0]) * grids[k.octave];
            double z = (k.z + (double) v[1]) * grids[k.octave];
            return new Node(x, z, MorphNoiseEval.eval(original.input(), x, z));
        });
    }

    private Receiver receiver(Key key) {
        return receivers.computeIfAbsent(key, k -> {
            Receiver best = new Receiver(k.x, k.z, node(k));
            double lowest = Double.MAX_VALUE;
            // Include self; strict < and dz-then-dx preserve the original tie order.
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = k.x + dx, z = k.z + dz;
                    Node candidate = node(new Key(k.octave, x, z));
                    if (candidate.height < lowest) {
                        lowest = candidate.height;
                        best = new Receiver(x, z, candidate);
                    }
                }
            }
            return best;
        });
    }

    private Edge makeEdge(Key key) {
        Node a = node(key);
        Receiver b = receiver(key);
        double dx = b.node.x - a.x, dz = b.node.z - a.z;
        double length = Math.hypot(dx, dz);
        if (length == 0) return controls(a, b, a.x, a.z, a.x, a.z);
        double c1x = a.x + dx / 3, c1z = a.z + dz / 3;
        double c2x = a.x + dx * (2.0 / 3), c2z = a.z + dz * (2.0 / 3);
        if (bend == 0) return controls(a, b, c1x, c1z, c2x, c2z);
        Receiver next = receiver(new Key(key.octave, b.x, b.z));
        double nx = next.node.x - b.node.x, nz = next.node.z - b.node.z;
        double downstream = Math.hypot(nx, nz);
        if (downstream == 0) {
            // Sink: no unique downstream tangent; retain convergence, invent no outlet.
            double shift = slopeShift(a, b.node, grids[key.octave], length);
            c1x -= dz / length * shift;
            c1z += dx / length * shift;
            c2x -= dz / length * shift;
            c2z += dx / length * shift;
        } else {
            nx /= downstream;
            nz /= downstream;
            double cosine = MorphNoiseEval.clamp((dx * nx + dz * nz) / length, -1, 1);
            // All three control-polygon edges project forward on the angle bisector.
            // This prevents loops even for obtuse turns; near reversal shrinks the handle.
            double handle = bend * length * Math.sqrt((1 + cosine) / 2);
            c2x = b.node.x - nx * handle;
            c2z = b.node.z - nz * handle;
            // Exact 180-degree reversal has no nonzero loop-free common tangent:
            // handle=0 explicitly gives a stationary endpoint rather than a fake outlet.
        }
        return controls(a, b, c1x, c1z, c2x, c2z);
    }

    private double slopeShift(Node a, Node b, double grid, double length) {
        double mx = (a.x + b.x) / 2, mz = (a.z + b.z) / 2;
        double nx = -(b.z - a.z) / length, nz = (b.x - a.x) / length;
        double epsilon = grid / 64;
        double plus = MorphNoiseEval.eval(original.input(), mx + nx * epsilon, mz + nz * epsilon);
        double minus = MorphNoiseEval.eval(original.input(), mx - nx * epsilon, mz - nz * epsilon);
        double normalSlope = (plus - minus) / (2 * epsilon);
        return -bend * length * Math.tanh(normalSlope * grid);
    }

    private static Edge controls(Node a, Receiver b, double c1x, double c1z,
                                 double c2x, double c2z) {
        return new Edge(a.x, a.z, b.node.x, b.node.z, b.x, b.z, c1x, c1z, c2x, c2z);
    }

    static double[] point(Edge e, double t) {
        if (t == 0 || (e.ax == e.bx && e.az == e.bz)) return new double[]{e.ax, e.az};
        if (t == 1) return new double[]{e.bx, e.bz};
        double u = 1 - t, aa = u * u * u, cc1 = 3 * u * u * t;
        double cc2 = 3 * u * t * t, bb = t * t * t;
        return new double[]{aa * e.ax + cc1 * e.c1x + cc2 * e.c2x + bb * e.bx,
                aa * e.az + cc1 * e.c1z + cc2 * e.c2z + bb * e.bz};
    }

    double geometryDistance2(Edge e, double x, double z) {
        if (bend == 0 || (e.ax == e.bx && e.az == e.bz))
            return segmentDistance2(x, z, e.ax, e.az, e.bx, e.bz);
        double[] v = vertices.computeIfAbsent(e, this::polyline).coordinates;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < segments; i++) {
            int j = i * 2;
            double d2 = segmentDistance2(x, z, v[j], v[j + 1], v[j + 2], v[j + 3]);
            if (d2 < best) best = d2;
        }
        return best;
    }

    private Polyline polyline(Edge edge) {
        double[] v = new double[(segments + 1) * 2];
        for (int i = 0; i <= segments; i++) {
            double[] p = point(edge, i / (double) segments);
            v[2 * i] = p[0];
            v[2 * i + 1] = p[1];
        }
        return new Polyline(v);
    }

    /** Operation order deliberately identical to MorphNoiseEval:433-439. */
    private static double segmentDistance2(double px, double pz, double ax, double az,
                                           double bx, double bz) {
        double padx = px - ax, padz = pz - az, badx = bx - ax, badz = bz - az;
        double paba = padx * badx + padz * badz, baba = badx * badx + badz * badz;
        double h = (paba == 0 && baba == 0) ? 0 : MorphNoiseEval.clamp(paba / baba, 0, 1);
        double dx = badx * h - padx, dz = badz * h - padz;
        return dx * dx + dz * dz;
    }
}
