package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.Arrays;

/** 固定坐标、固定遍历顺序的纯 Java 水文网格。内部坐标包含 halo，输出只使用核心区。 */
public final class HydrologyGrid {
    public static final int CARDINAL_COUNT = 8;
    private final int coreSize;
    private final int halo;
    private final int size;
    private final int originX;
    private final int originZ;
    private final int spacing;
    private final double[] elevation;
    private final double[] filledElevation;
    private final double[] rainfall;
    private final double[] contributingArea;
    private final byte[] flow;
    private final boolean[] ocean;
    private final boolean[] lake;
    private final boolean[] river;

    private HydrologyGrid(int coreSize, int halo, int originX, int originZ, int spacing) {
        this.coreSize = coreSize;
        this.halo = halo;
        this.size = coreSize + halo * 2;
        this.originX = originX - halo * spacing;
        this.originZ = originZ - halo * spacing;
        this.spacing = spacing;
        int count = size * size;
        elevation = new double[count];
        filledElevation = new double[count];
        rainfall = new double[count];
        contributingArea = new double[count];
        flow = new byte[count];
        ocean = new boolean[count];
        lake = new boolean[count];
        river = new boolean[count];
        Arrays.fill(flow, FlowDirectionSolver.OUTLET);
    }

    public static HydrologyGrid sample(CellGenerator terrain, long seed, int originX, int originZ,
                                       int coreSize, int halo, int spacing) {
        if (coreSize < 1 || halo < 1 || spacing < 1) throw new IllegalArgumentException("invalid grid dimensions");
        HydrologyGrid grid = new HydrologyGrid(coreSize, halo, originX, originZ, spacing);
        for (int z = 0; z < grid.size; z++) {
            for (int x = 0; x < grid.size; x++) {
                int i = grid.index(x, z);
                double wx = grid.worldX(x), wz = grid.worldZ(z);
                double e = terrain.terrainEQuick(wx, wz);
                grid.elevation[i] = e;
                grid.filledElevation[i] = e;
                grid.ocean[i] = e < 0.0;
                grid.rainfall[i] = rainfall(seed, wx, wz);
            }
        }
        return grid;
    }

    private static double rainfall(long seed, double x, double z) {
        long h = seed ^ Double.doubleToLongBits(x * 0.03125) ^ Long.rotateLeft(Double.doubleToLongBits(z * 0.03125), 21);
        h ^= h >>> 30; h *= 0xbf58476d1ce4e5b9L; h ^= h >>> 27;
        return 0.65 + (h & 0xffff) / 65535.0 * 0.7;
    }

    public int coreSize() { return coreSize; }
    public int halo() { return halo; }
    public int size() { return size; }
    public int spacing() { return spacing; }
    public int worldX(int x) { return originX + x * spacing; }
    public int worldZ(int z) { return originZ + z * spacing; }
    public int index(int x, int z) { return z * size + x; }
    public int x(int index) { return index % size; }
    public int z(int index) { return index / size; }
    public boolean isCore(int x, int z) { return x >= halo && x < size - halo && z >= halo && z < size - halo; }
    public boolean isCore(int index) { return isCore(x(index), z(index)); }
    public double elevation(int i) { return elevation[i]; }
    public double filledElevation(int i) { return filledElevation[i]; }
    public double rainfall(int i) { return rainfall[i]; }
    public double contributingArea(int i) { return contributingArea[i]; }
    public byte flow(int i) { return flow[i]; }
    public boolean ocean(int i) { return ocean[i]; }
    public boolean lake(int i) { return lake[i]; }
    public boolean river(int i) { return river[i]; }
    public double[] elevations() { return elevation; }
    public double[] filledElevations() { return filledElevation; }
    public double[] rainfallValues() { return rainfall; }
    public double[] contributingAreas() { return contributingArea; }
    public byte[] flows() { return flow; }
    public boolean[] lakes() { return lake; }
    public boolean[] rivers() { return river; }
}
