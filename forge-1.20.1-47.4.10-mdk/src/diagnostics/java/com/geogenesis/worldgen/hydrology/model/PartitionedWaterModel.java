package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/** Finite-domain ownership experiment. No artificial outlets at partition edges. */
public final class PartitionedWaterModel {
    private static final class Tile {
        final int x, z, nx, nz;
        final double[] water;
        Tile(int x, int z, int nx, int nz) {
            this.x = x;
            this.z = z;
            this.nx = nx;
            this.nz = nz;
            water = new double[nx * nz];
        }
        int local(int i, int j) { return (i - x) * nz + j - z; }
    }

    private final WaterGrid g;
    private final ModelParams p;
    private final int tileSize, tilesZ;
    private final Tile[] tiles;
    private final double[] qx, qz, qb, outgoing, scale, delta;
    private double rain, infiltration, evaporation, boundaryLoss;
    private long sourceUpdates, faceUpdates, crossFaces;

    public PartitionedWaterModel(WaterGrid grid, ModelParams params, int tileSize) {
        if (tileSize <= 0 || grid.cellSize != params.cellSize()) {
            throw new IllegalArgumentException("invalid partition or grid scale");
        }
        g = grid;
        p = params;
        this.tileSize = tileSize;
        tilesZ = (g.nz + tileSize - 1) / tileSize;
        int tilesX = (g.nx + tileSize - 1) / tileSize;
        tiles = new Tile[tilesX * tilesZ];
        for (int i = 0; i < tilesX; i++) for (int j = 0; j < tilesZ; j++) {
            int x = i * tileSize, z = j * tileSize;
            tiles[i * tilesZ + j] = new Tile(x, z,
                    Math.min(tileSize, g.nx - x), Math.min(tileSize, g.nz - z));
        }
        int n = g.cells();
        qx = new double[n]; qz = new double[n]; qb = new double[n];
        outgoing = new double[n]; scale = new double[n]; delta = new double[n];
    }

    private Tile owner(int i, int j) { return tiles[(i / tileSize) * tilesZ + j / tileSize]; }
    public double depth(int i, int j) { return owner(i, j).water[owner(i, j).local(i, j)]; }
    public void setDepth(int i, int j, double depth) {
        if (!Double.isFinite(depth) || depth < 0) throw new IllegalArgumentException("depth");
        Tile t = owner(i, j);
        t.water[t.local(i, j)] = depth;
    }
    public long sourceUpdates() { return sourceUpdates; }
    public long faceUpdates() { return faceUpdates; }
    public long crossFaces() { return crossFaces; }
    public double rainVolume() { return rain; }
    public double lossVolume() { return infiltration + evaporation + boundaryLoss; }
    public double storage() {
        double sum = 0;
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) sum += depth(i, j);
        return sum * g.cellArea();
    }

    /** Read neighbor owners from the same frozen time level; publish only after face reduction. */
    public void step(double dt, boolean reverseTiles) {
        if (!(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("dt");
        Arrays.fill(outgoing, 0); Arrays.fill(delta, 0);
        produceFaces(reverseTiles);
        countOutgoing();
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            int k = g.idx(i, j);
            double available = depth(i, j) * g.cellArea();
            scale[k] = outgoing[k] * dt > available ? available / (outgoing[k] * dt) : 1;
        }
        reduceFaces(dt);
        publish(dt);
    }

    private void produceFaces(boolean reverse) {
        for (int k = 0; k < tiles.length; k++) {
            Tile t = tiles[reverse ? tiles.length - 1 - k : k];
            for (int i = t.x; i < t.x + t.nx; i++) for (int j = t.z; j < t.z + t.nz; j++) {
                int a = g.idx(i, j);
                // Canonical owner of an interior face is its negative-axis cell.
                qx[a] = i + 1 < g.nx ? flux(i, j, i + 1, j) : 0;
                qz[a] = j + 1 < g.nz ? flux(i, j, i, j + 1) : 0;
                if (i + 1 < g.nx) registerFace(t, owner(i + 1, j));
                if (j + 1 < g.nz) registerFace(t, owner(i, j + 1));
                qb[a] = externalFlux(i, j);
            }
        }
    }

    private void registerFace(Tile a, Tile b) {
        faceUpdates++;
        if (a != b) crossFaces++;
    }

    private double flux(int i, int j, int ni, int nj) {
        int a = g.idx(i, j), b = g.idx(ni, nj);
        double ea = g.bed[a] + depth(i, j), eb = g.bed[b] + depth(ni, nj);
        double difference = ea - eb;
        double h = Math.max(0, Math.max(ea, eb) - Math.max(g.bed[a], g.bed[b]));
        return Math.signum(difference) * Math.pow(h, 5.0 / 3.0) / p.manningN()
                * Math.sqrt(Math.abs(difference) / g.cellSize) * g.edgeLength();
    }

    private double externalFlux(int i, int j) {
        if (g.boundary() == WaterGrid.Boundary.CLOSED) return 0;
        int sides = g.openSides(i, j);
        double d = depth(i, j);
        double head = Math.max(0, g.bed[g.idx(i, j)] + d - g.boundaryLevel());
        return sides * Math.pow(d, 5.0 / 3.0) / p.manningN()
                * Math.sqrt(head / g.cellSize) * g.edgeLength();
    }

    private void countOutgoing() {
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            int a = g.idx(i, j);
            if (i + 1 < g.nx) count(a, g.idx(i + 1, j), qx[a]);
            if (j + 1 < g.nz) count(a, g.idx(i, j + 1), qz[a]);
            outgoing[a] += qb[a];
        }
    }
    private void count(int a, int b, double q) { outgoing[q >= 0 ? a : b] += Math.abs(q); }

    private void reduceFaces(double dt) {
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            int a = g.idx(i, j);
            if (i + 1 < g.nx) transfer(a, g.idx(i + 1, j), qx[a], dt);
            if (j + 1 < g.nz) transfer(a, g.idx(i, j + 1), qz[a], dt);
            double lost = qb[a] * scale[a] * dt;
            delta[a] -= lost;
            boundaryLoss += lost;
        }
    }
    private void transfer(int a, int b, double q, double dt) {
        double volume = q * scale[q >= 0 ? a : b] * dt;
        delta[a] -= volume;
        delta[b] += volume;
    }

    private void publish(double dt) {
        double area = g.cellArea();
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            double next = depth(i, j) + delta[g.idx(i, j)] / area;
            if (next < -1e-10) throw new IllegalStateException("negative water depth");
            double added = p.rainRate() * dt;
            double available = Math.max(0, next) + added;
            double inf = Math.min(available, p.infilRate() * dt);
            available -= inf;
            double evap = Math.min(available, (p.evapRate() + p.evapDepthCoeff() * available) * dt);
            setDepth(i, j, available - evap);
            rain += added * area; infiltration += inf * area; evaporation += evap * area;
            sourceUpdates++;
        }
    }
}
