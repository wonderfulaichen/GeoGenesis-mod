package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 四区域拼接验证：将独立区域核心与统一大区域参考逐格比较。 */
public final class HydrologyRegionProbe {
    private HydrologyRegionProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int tileSize = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int spacing = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int halo = args.length > 3 ? Integer.parseInt(args[3]) : 32;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        int worldSize = tileSize * 2;
        HydrologyResult reference = run(terrain, seed, 0, 0, worldSize, spacing, halo);
        int mismatches = 0;
        for (int tz = 0; tz < 2; tz++) {
            for (int tx = 0; tx < 2; tx++) {
                HydrologyResult tile = run(terrain, seed, tx * tileSize * spacing,
                        tz * tileSize * spacing, tileSize, spacing, halo);
                mismatches += compare(reference.grid(), tile.grid(), tx * tileSize * spacing,
                        tz * tileSize * spacing, tileSize - 16, spacing);
            }
        }
        System.out.println("=== HydrologyRegionProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("tileSize=" + tileSize + " spacing=" + spacing + " halo=" + halo);
        System.out.println("referenceHash=" + Long.toUnsignedString(reference.hash()));
        System.out.println("mismatches=" + mismatches);
        System.out.println("status=" + (mismatches == 0 ? "PASS" : "FAIL"));
    }

    private static HydrologyResult run(CellGenerator terrain, long seed, int x, int z,
                                       int size, int spacing, int halo) {
        return HydrologySimulator.run(terrain, seed, x, z, size, halo, spacing, 128.0);
    }

    private static int compare(HydrologyGrid reference, HydrologyGrid tile,
                               int originX, int originZ, int size, int spacing) {
        int mismatch = 0;
        for (int z = 8; z < size - 8; z++) {
            for (int x = 8; x < size - 8; x++) {
                int worldX = originX + x * spacing;
                int worldZ = originZ + z * spacing;
                int a = find(reference, worldX, worldZ);
                int b = find(tile, worldX, worldZ);
                if (a < 0 || b < 0 || different(reference, tile, a, b)) mismatch++;
            }
        }
        return mismatch;
    }

    private static int find(HydrologyGrid grid, int worldX, int worldZ) {
        int x = Math.floorDiv(worldX - grid.worldX(0), grid.spacing());
        int z = Math.floorDiv(worldZ - grid.worldZ(0), grid.spacing());
        return x < 0 || z < 0 || x >= grid.size() || z >= grid.size() ? -1 : grid.index(x, z);
    }

    private static boolean different(HydrologyGrid a, HydrologyGrid b, int ia, int ib) {
        return a.flow(ia) != b.flow(ib) || a.ocean(ia) != b.ocean(ib)
                || a.lake(ia) != b.lake(ib) || a.river(ia) != b.river(ib)
                || Math.abs(a.filledElevation(ia) - b.filledElevation(ib)) > 1e-9
                || Math.abs(a.contributingArea(ia) - b.contributingArea(ib)) > 1e-9;
    }
}
