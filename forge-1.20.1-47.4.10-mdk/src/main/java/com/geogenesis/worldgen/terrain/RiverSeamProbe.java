package com.geogenesis.worldgen.terrain;

/**
 * 河流跨 tile 无缝诊断探针（2026-08-02，分级流水线 L1 验证）。
 *
 * <p>验证 StreamTracer 的关键属性：<b>相邻 tile 重叠区（世界坐标对齐），河道中心线
 * riverCore 逐格一致</b>（用户报告"河走着走着停"的断流根因）。</p>
 *
 * <p><b>判定用 riverCore 而非 riverMask</b>：mask 是 3×3 膨胀，在 tile 数据区边界截断
 * （A 最后一行/列缺数据区外 core 的膨胀、B 有）→ 数组边缘 mask 比较产生假断裂；
 * core 由 markCell/markSegment 界内判定写入，无边界截断，比较安全。</p>
 *
 * <p>比较范围 = 两 tile 数据区重叠（世界坐标对齐，A.local ↔ B.local 差 48）：
 * <ul>
 *   <li>X-seam：world x ∈ [B.originX, A.originX+128) ↔ A.local [48,128) / B.local [0,80)</li>
 *   <li>Z-seam：world z ∈ [B.originZ, A.originZ+128) ↔ A.local [48,128) / B.local [0,80)</li>
 * </ul></p>
 *
 * <p>正控扫描：轻量预筛（terrainE 采样）找陆地占比 ≥25% 的 tile 再触发侵蚀+河流，
 * 取第一个提取区含 riverCore 的 tile 做双轴接缝测量。低地世界（eLand 中值 0.10~0.18）
 * 中 SOURCE_MIN_E=0.12 会滤掉大半源头 → 某些种子全区域无河（空测无意义），
 * main 默认依次尝试 12345/777/999/2024 直到找到含河种子。</p>
 */
public final class RiverSeamProbe {

    // 常量复制（与 CellGenerator 一致；探针独立运行不 import 私有成员）
    private static final int ERODE_TILE_CHUNKS = 3;
    private static final int ERODE_TILE_BORDER = 40;
    private static final int ERODE_TILE_SIZE = 128;

    private RiverSeamProbe() {}

    public static void main(String[] args) {
        // 12345 是世界偏矮的"低地种子"，SOURCE_MIN_E=0.12 门槛滤掉大半陆地源头 → 无河；
        // 依次尝试多个种子，找到含河道的世界做接缝验证。
        long[] seeds = args.length > 0
            ? new long[]{Long.parseLong(args[0])}
            : new long[]{12345L, 777L, 999L, 2024L};
        for (long s : seeds) {
            if (runProbe(s)) break;
        }
    }

    /** @return 该种子是否找到含河道的 tile（找到 = 接缝验证有效） */
    public static boolean runProbe(long seed) {
        System.out.println("=== River Seam Diagnostics (分级流水线 L1) ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        // 正控扫描：先轻量预筛（terrainE 采样，不跑侵蚀）找陆地占比高的 tile，再触发侵蚀+河流。
        // 世界是"低地世界"（eLand 中值 0.10~0.18），SOURCE_MIN_E=0.12 会滤掉大半陆地源头 →
        // 低地海岸区 tile 天然无河，直接盲扫是空测。
        int foundX = -1, foundZ = -1, checked = 0, triggered = 0;
        outer:
        for (int tcz = 0; tcz <= 24; tcz += ERODE_TILE_CHUNKS) {
            for (int tcx = 0; tcx <= 24; tcx += ERODE_TILE_CHUNKS) {
                if (tileLandShare(gen, tcx, tcz) < 0.25f) continue;   // 预筛：陆地占比 <25% → 跳过
                checked++;
                gen.getErosionTile(tcx + 1, tcz + 1);   // chunk 坐标 → tile (tcx,tcz)
                triggered++;
                CellGenerator.RiverTileData rd = gen.getRiverTileData(tcx, tcz);
                if (rd != null && extractionCore(rd) > 0) { foundX = tcx; foundZ = tcz; break outer; }
            }
        }
        if (foundX < 0) {
            System.out.println("  预筛候选 " + checked + " / 触发 " + triggered + " 个 tile，均无河道");
            System.out.println("  （世界整体偏矮：SOURCE_MIN_E=0.12 门槛高于陆地中值，源头被全滤）");
            System.out.println("=== RiverSeamProbe done ===");
            return false;
        }
        System.out.println("  正控 tile (" + foundX + "," + foundZ + ") 提取区 riverCore="
            + extractionCore(gen.getRiverTileData(foundX, foundZ)));
        System.out.println();

        probeXSeam(gen, foundX, foundZ);
        probeZSeam(gen, foundX, foundZ);

        System.out.println("=== RiverSeamProbe done ===");
        System.out.println("断裂 > 0：同世界坐标的河道中心线不一致（真断裂）");
        return true;
    }

    /** 右邻交界：A=(tcx,tcz) ↔ B=(tcx+3,tcz)。重叠区 world x ∈ [B.originX, A.originX+128)。 */
    private static void probeXSeam(CellGenerator gen, int tcx, int tcz) {
        int tcxB = tcx + ERODE_TILE_CHUNKS;
        gen.getErosionTile(tcx + 1, tcz + 1);
        gen.getErosionTile(tcxB + 1, tcz + 1);

        CellGenerator.RiverTileData rdA = gen.getRiverTileData(tcx, tcz);
        CellGenerator.RiverTileData rdB = gen.getRiverTileData(tcxB, tcz);
        System.out.println("--- X-Seam: tile (" + tcx + "," + tcz + ") ↔ (" + tcxB + "," + tcz + ") ---");
        if (rdA == null || rdB == null) {
            System.out.println("  SKIP: river tile not in cache");
            return;
        }
        // core 同列比较：A.local lxA ∈ [48,128) ↔ B.local lxA−48（同 world x，重叠区全覆盖）
        int a2b = 0, b2a = 0, both = 0, none = 0;
        int worstZ = -1;
        int z0 = ERODE_TILE_BORDER, z1 = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16;
        for (int lz = z0; lz < z1; lz++) {
            for (int lxA = ERODE_TILE_CHUNKS * 16; lxA < ERODE_TILE_SIZE; lxA++) {
                int lxB = lxA - ERODE_TILE_CHUNKS * 16;
                boolean a = rdA.riverCore[lz][lxA];
                boolean b = rdB.riverCore[lz][lxB];
                if (a && b) both++;
                else if (!a && !b) none++;
                else if (a) { a2b++; worstZ = lz; }
                else { b2a++; worstZ = lz; }
            }
        }
        System.out.println(String.format(
            "  seam 同列(core): 两侧一致 both=%d none=%d | 断裂 A→B=%d B→A=%d (worst local z=%d)",
            both, none, a2b, b2a, worstZ));
        dumpStats(rdA, "A", 0, 0);
        dumpStats(rdB, "B", 0, 0);
        System.out.println();
    }

    /** 下邻交界：A=(tcx,tcz) ↔ B=(tcx,tcz+3)。重叠区 world z ∈ [B.originZ, A.originZ+128)。 */
    private static void probeZSeam(CellGenerator gen, int tcx, int tcz) {
        int tczB = tcz + ERODE_TILE_CHUNKS;
        gen.getErosionTile(tcx + 1, tcz + 1);
        gen.getErosionTile(tcx + 1, tczB + 1);

        CellGenerator.RiverTileData rdA = gen.getRiverTileData(tcx, tcz);
        CellGenerator.RiverTileData rdB = gen.getRiverTileData(tcx, tczB);
        System.out.println("--- Z-Seam: tile (" + tcx + "," + tcz + ") ↔ (" + tcx + "," + tczB + ") ---");
        if (rdA == null || rdB == null) {
            System.out.println("  SKIP: river tile not in cache");
            return;
        }
        // core 同行比较：A.local lzA ∈ [48,128) ↔ B.local lzA−48（同 world z，重叠区全覆盖）
        int a2b = 0, b2a = 0, both = 0, none = 0;
        int worstX = -1;
        int x0 = ERODE_TILE_BORDER, x1 = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16;
        for (int lx = x0; lx < x1; lx++) {
            for (int lzA = ERODE_TILE_CHUNKS * 16; lzA < ERODE_TILE_SIZE; lzA++) {
                int lzB = lzA - ERODE_TILE_CHUNKS * 16;
                boolean a = rdA.riverCore[lzA][lx];
                boolean b = rdB.riverCore[lzB][lx];
                if (a && b) both++;
                else if (!a && !b) none++;
                else if (a) { a2b++; worstX = lx; }
                else { b2a++; worstX = lx; }
            }
        }
        System.out.println(String.format(
            "  seam 同行(core): 两侧一致 both=%d none=%d | 断裂 A→B=%d B→A=%d (worst local x=%d)",
            both, none, a2b, b2a, worstX));
        dumpStats(rdA, "A", 0, 0);
        dumpStats(rdB, "B", 0, 0);
        System.out.println();
    }

    /** 预筛：tile 数据区 [tileCX×16−40, tileCX×16+88) 内 6×6 采样格心，统计 e>SOURCE_MIN_E 的陆地占比。 */
    private static float tileLandShare(CellGenerator gen, int tcx, int tcz) {
        int x0 = tcx * 16 - ERODE_TILE_BORDER, z0 = tcz * 16 - ERODE_TILE_BORDER;
        int land = 0, total = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                double e = gen.terrainE(x0 + 16 * i + 8, z0 + 16 * j + 8);
                total++;
                if (e >= 0.12) land++;
            }
        }
        return (float) land / total;
    }

    /** 提取区（中心 48×48）riverCore 标记格数。 */
    private static int extractionCore(CellGenerator.RiverTileData rd) {
        int lo = ERODE_TILE_BORDER, hi = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16;
        int n = 0;
        for (int z = lo; z < hi; z++)
            for (int x = lo; x < hi; x++)
                if (rd.riverCore[z][x]) n++;
        return n;
    }

    /** 提取区（中心 48×48）河网规模统计。 */
    private static void dumpStats(CellGenerator.RiverTileData rd, String name, int lo, int hi) {
        int lo2 = ERODE_TILE_BORDER, hi2 = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16;
        int core = 0, mask = 0;
        float maxQ = 0f;
        for (int z = lo2; z < hi2; z++) {
            for (int x = lo2; x < hi2; x++) {
                if (rd.riverCore[z][x]) core++;
                if (rd.riverMask[z][x]) mask++;
                if (rd.discharge[z][x] > maxQ) maxQ = rd.discharge[z][x];
            }
        }
        System.out.println(String.format(
            "  %s 提取区: riverCore=%d riverMask=%d maxQ=%.0f (tile maxQ=%.0f)",
            name, core, mask, maxQ, rd.maxDischarge));
    }
}
