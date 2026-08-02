package com.geogenesis.worldgen.terrain;

import java.util.HashSet;
import java.util.Set;

/**
 * 河流跨 tile 无缝诊断探针（2026-08-02，分级流水线 L1 验证）。
 *
 * <p>验证 StreamTracer 分级改造后的关键属性：<b>相邻 tile 重叠区（世界坐标对齐列/行），
 * 河道标记逐格一致</b>（用户报告"河走着走着停"的断流根因）。</p>
 * <ul>
 *   <li>同列/同行比较：A.local lxA ↔ B.local lxA−48（同一世界坐标；A.origin=16tcx−40，
 *       B.origin=16(tcx+3)−40，同 world x → lxB = lxA − 48）</li>
 *   <li>断裂点 = 同世界坐标的 riverMask 不一致（真断裂）。旧版把 A 列 335 与 B 列 336
 *       （相邻列）配对，河在 335 天然终止被误报为断流——已修正</li>
 *   <li><b>源头一致性（2026-08-02 源头粒子制）</b>：相邻 tile 各自追踪范围内的源头格点
 *       集合（局部高点判定，纯世界坐标）应完全相同——源头集合不一致是旧版随机撒点
 *       的根因（A 有源头 B 无 → 河在 seam 断流），新方案判定函数与 hash 均世界坐标 → 零差异</li>
 * </ul>
 * <p>同时输出提取区河网规模（riverCore 格数 / maxQ），间接验证平原不衰减
 * （河应更长、更完整，而不是走几步就停）。</p>
 *
 * <p>正控扫描：先轻量预筛（terrainE 采样）找陆地占比 ≥25% 的 tile 再触发侵蚀+河流，
 * 取第一个提取区含 riverCore 的 tile 做双轴接缝测量。低地世界（eLand 中值 0.10~0.18）
 * 中 SOURCE_MIN_E 会滤掉部分低地源头、凸起地形少的种子可能无河（空测无意义），
 * main 默认依次尝试 12345/777/999/2024 直到找到含河种子。</p>
 */
public final class RiverSeamProbe {

    // 常量复制（与 CellGenerator 一致；探针独立运行不 import 私有成员）
    private static final int ERODE_TILE_CHUNKS = 3;
    private static final int ERODE_TILE_BORDER = 40;
    private static final int ERODE_TILE_SIZE = 128;

    private RiverSeamProbe() {}

    public static void main(String[] args) {
        // 低地种子可能无河（源头门槛 + 凸起地形不足），依次尝试多个种子找到含河世界做接缝验证。
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
        // 源头判定含 SOURCE_MIN_E 高度门槛 + 凸起地形要求 → 低地海岸区 tile 可能无河，
        // 直接盲扫是空测。
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
            System.out.println("  （世界整体偏矮：SOURCE_MIN_E 门槛滤掉低地源头；或该种子无凸起地形）");
            System.out.println("=== RiverSeamProbe done ===");
            return false;
        }
        System.out.println("  正控 tile (" + foundX + "," + foundZ + ") 提取区 riverCore="
            + extractionCore(gen.getRiverTileData(foundX, foundZ)));
        System.out.println();

        probeXSeam(gen, foundX, foundZ);
        probeZSeam(gen, foundX, foundZ);

        System.out.println("=== RiverSeamProbe done ===");
        System.out.println("断裂 > 0：同世界坐标列/行的河道标记不一致（真断裂）");
        return true;
    }

    /** 右邻交界：A=(tcx,tcz) ↔ B=(tcx+3,tcz)。提取区 = 数据区中心 48 块。 */
    private static void probeXSeam(CellGenerator gen, int tcx, int tcz) {
        int tcxB = tcx + ERODE_TILE_CHUNKS;
        // 触发 L3+L1：tile 坐标 (t) 覆盖 chunk [t, t+2]，用 t+1 触发
        gen.getErosionTile(tcx + 1, tcz + 1);
        gen.getErosionTile(tcxB + 1, tcz + 1);

        CellGenerator.RiverTileData rdA = gen.getRiverTileData(tcx, tcz);
        CellGenerator.RiverTileData rdB = gen.getRiverTileData(tcxB, tcz);
        System.out.println("--- X-Seam: tile (" + tcx + "," + tcz + ") ↔ (" + tcxB + "," + tcz + ") ---");
        if (rdA == null || rdB == null) {
            System.out.println("  SKIP: river tile not in cache");
            return;
        }
        System.out.println("  [终止] " + StreamTracer.termStats());
        System.out.println("  [鼓包] A提取区河道局部最大格数 = " + bumps(gen, tcx, tcz)
                + " (理想=0：河床应单调下降)");
        StreamTracer.resetTermStats();
        printSourceDiff(gen, tcx, tcz, tcxB, tcz, "X-Seam");
        // 同世界坐标列比较（2026-08-02 修正）：A.local lxA ↔ B.local lxA−48（同 world x）。
        // 旧判定把 A 列 335 与 B 列 336（相邻列）配对 → "河在 335 天然终止"被误报断流
        // （core 重叠区 0 差异证明两侧追踪完全一致，河终止于同一点）。
        int seamA = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16 - 1;
        int seamB = ERODE_TILE_BORDER;
        int z0 = ERODE_TILE_BORDER, z1 = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16;
        int a2b = 0, b2a = 0, both = 0, none = 0;
        int worstZ = -1;
        for (int lz = z0; lz < z1; lz++) {
            for (int lxA = seamA; lxA < ERODE_TILE_SIZE; lxA++) {
                int lxB = lxA - ERODE_TILE_CHUNKS * 16;
                if (lxB < 0 || lxB >= ERODE_TILE_SIZE) continue;
                boolean a = rdA.riverMask[lz][lxA];
                boolean b = rdB.riverMask[lz][lxB];
                if (a && b) both++;
                else if (!a && !b) none++;
                else if (a) { a2b++; worstZ = lz; }
                else { b2a++; worstZ = lz; }
            }
        }
        System.out.println(String.format(
            "  seam 同列: 两侧一致 both=%d none=%d | 断裂 A→B=%d B→A=%d (worst local z=%d)",
            both, none, a2b, b2a, worstZ));
        dumpStats(rdA, "A", seamA, seamB);
        dumpStats(rdB, "B", seamA, seamB);
        // TEMP-DIAG: discharge 场测量（值域 + 重叠区跨 tile 差异）
        CellGenerator.ErosionTileResult erA = gen.getTileResult(tcx, tcz);
        CellGenerator.ErosionTileResult erB = gen.getTileResult(tcxB, tcz);
        if (erA != null && erA.discharge != null && erB != null && erB.discharge != null) {
            float[][] dA = erA.discharge, dB = erB.discharge;
            float minD = 1e9f, maxD = -1e9f, sumD = 0f;
            int n1 = 0, n5 = 0, n10 = 0, n100 = 0;
            for (int z = 0; z < 128; z++)
                for (int x = 0; x < 128; x++) {
                    float v = dA[z][x];
                    if (v < minD) minD = v;
                    if (v > maxD) maxD = v;
                    sumD += v;
                    if (v >= 1f) n1++;
                    if (v >= 5f) n5++;
                    if (v >= 10f) n10++;
                    if (v >= 100f) n100++;
                }
            // 相对阈值判定一致性（每 tile 内部背景相对化 thr = mean + (max-mean)*cfg）
            for (float cfgT : new float[]{0.02f, 0.05f, 0.1f, 0.2f}) {
                float meanA = sumD / (128 * 128);
                float thrA = meanA + (maxD - meanA) * cfgT;
                // B 场统计（只算重叠区）
                float sumB = 0f, maxB = 0f;
                for (int lz = 0; lz < 128; lz++)
                    for (int lx = 0; lx < 128; lx++) {
                        float v = dB[lz][lx];
                        sumB += v;
                        if (v > maxB) maxB = v;
                    }
                float thrB = sumB / (128 * 128) + (maxB - sumB / (128 * 128)) * cfgT;
                int both2 = 0, onlyA = 0, onlyB = 0, neither = 0;
                for (int lz = ERODE_TILE_BORDER; lz < ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16; lz++)
                    for (int lxA = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16; lxA < ERODE_TILE_SIZE; lxA++) {
                        int lxB = lxA - ERODE_TILE_CHUNKS * 16;
                        boolean a = dA[lz][lxA] >= thrA;
                        boolean b = dB[lz][lxB] >= thrB;
                        if (a && b) both2++;
                        else if (a) onlyA++;
                        else if (b) onlyB++;
                        else neither++;
                    }
                System.out.println(String.format(
                    "  [diag] 相对阈值 cfg=%.2f (thrA=%.1f thrB=%.1f): both=%d onlyA=%d onlyB=%d neither=%d",
                    cfgT, thrA, thrB, both2, onlyA, onlyB, neither));
            }
        }
        System.out.println();
    }

    /** 下邻交界：A=(tcx,tcz) ↔ B=(tcx,tcz+3)，交界 world z = 48。 */
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
        printSourceDiff(gen, tcx, tcz, tcx, tczB, "Z-Seam");
        // 同世界坐标行比较：A.local lzA ↔ B.local lzA−48（同 world z）
        int seamA = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16 - 1;
        int seamB = ERODE_TILE_BORDER;
        int x0 = ERODE_TILE_BORDER, x1 = ERODE_TILE_BORDER + ERODE_TILE_CHUNKS * 16;
        int a2b = 0, b2a = 0, both = 0, none = 0;
        int worstX = -1;
        for (int lx = x0; lx < x1; lx++) {
            for (int lzA = seamA; lzA < ERODE_TILE_SIZE; lzA++) {
                int lzB = lzA - ERODE_TILE_CHUNKS * 16;
                if (lzB < 0 || lzB >= ERODE_TILE_SIZE) continue;
                boolean a = rdA.riverMask[lzA][lx];
                boolean b = rdB.riverMask[lzB][lx];
                if (a && b) both++;
                else if (!a && !b) none++;
                else if (a) { a2b++; worstX = lx; }
                else { b2a++; worstX = lx; }
            }
        }
        System.out.println(String.format(
            "  seam 同行: 两侧一致 both=%d none=%d | 断裂 A→B=%d B→A=%d (worst local x=%d)",
            both, none, a2b, b2a, worstX));
        dumpStats(rdA, "A", seamA, seamB);
        dumpStats(rdB, "B", seamA, seamB);
        System.out.println();
    }

    /** 预筛：tile 数据区 [tileCX×16−40, tileCX×16+88) 内 6×6 采样格心，统计 e≥SOURCE_MIN_E 的陆地占比。 */
    private static float tileLandShare(CellGenerator gen, int tcx, int tcz) {
        int x0 = tcx * 16 - ERODE_TILE_BORDER, z0 = tcz * 16 - ERODE_TILE_BORDER;
        int land = 0, total = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                double e = gen.terrainE(x0 + 16 * i + 8, z0 + 16 * j + 8);
                total++;
                if (e >= StreamTracer.SOURCE_MIN_E) land++;
            }
        }
        return (float) land / total;
    }

    /**
     * 源头一致性验证（2026-08-02 源头粒子制）：相邻 tile 各自追踪范围内的源头格点集合
     * 应完全相同（判定 = StreamTracer.isLocalHigh，纯世界坐标，无 tile 依赖）。
     * 理想输出：仅A=仅B=0（两侧撒点集合一致 → 路径/汇聚场一致 → seam 无缝）。
     */
    private static void printSourceDiff(CellGenerator gen, int tcxA, int tczA, int tcxB, int tczB, String tag) {
        Set<Long> sA = sourceSet(gen, tcxA, tczA);
        Set<Long> sB = sourceSet(gen, tcxB, tczB);
        Set<Long> onlyA = new HashSet<>(sA);
        onlyA.removeAll(sB);
        Set<Long> onlyB = new HashSet<>(sB);
        onlyB.removeAll(sA);
        System.out.println(String.format("  [源头] %s: A=%d B=%d | 仅A=%d 仅B=%d (理想: 仅A=仅B=0)",
                tag, sA.size(), sB.size(), onlyA.size(), onlyB.size()));
    }

    /**
     * 河道鼓包回归监控（河床应单调下降；2026-08-02 用户实机反馈「河道鼓起」后新增）。
     * 判定：提取区 riverCore 格，沿河 8 邻 core 邻格 ≥2（排除源头/末端单邻格）且全部
     * 低于中心 → 河道局部最大 = 鼓包。高度用 base（追踪场，与 StreamTracer 一致）。
     * RIVER_INERTIA=0.15（速度相关惯性）后 ≈0-1（噪声级）；若回升 → 惯性/翻越逻辑回归。
     */
    private static int bumps(CellGenerator gen, int tcx, int tcz) {
        CellGenerator.ErosionTileResult res = gen.getTileResult(tcx, tcz);
        CellGenerator.RiverTileData rd = gen.getRiverTileData(tcx, tcz);
        if (res == null || rd == null) return -1;
        float[][] h = res.base;
        int N = ERODE_TILE_SIZE, b = 0;
        for (int z = 12; z < 76; z++) {
            for (int x = 12; x < 76; x++) {
                if (!rd.riverCore[z][x]) continue;
                int cn = 0;
                boolean allLower = true;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nx >= N || nz < 0 || nz >= N) continue;
                        if (!rd.riverCore[nz][nx]) continue;
                        cn++;
                        if (h[nz][nx] >= h[z][x]) allLower = false;
                    }
                }
                if (cn >= 2 && allLower) b++;
            }
        }
        return b;
    }

    /** 枚举 tile 追踪范围内的源头格点（世界坐标；与 StreamTracer.trace 源头循环同源）。 */
    private static Set<Long> sourceSet(CellGenerator gen, int tcx, int tcz) {
        int originX = tcx * 16 - ERODE_TILE_BORDER, originZ = tcz * 16 - ERODE_TILE_BORDER;
        StreamTracer.WorldHeight wh = (wx, wz) -> (float) Math.max(gen.terrainE(wx, wz), -0.05);
        Set<Long> set = new HashSet<>();
        int gx0 = Math.floorDiv(originX - 5 * StreamTracer.SOURCE_GRID, StreamTracer.SOURCE_GRID);
        int gx1 = Math.floorDiv(originX + ERODE_TILE_SIZE - 1 + 5 * StreamTracer.SOURCE_GRID, StreamTracer.SOURCE_GRID);
        int gz0 = Math.floorDiv(originZ - 5 * StreamTracer.SOURCE_GRID, StreamTracer.SOURCE_GRID);
        int gz1 = Math.floorDiv(originZ + ERODE_TILE_SIZE - 1 + 5 * StreamTracer.SOURCE_GRID, StreamTracer.SOURCE_GRID);
        int[] selX = new int[512], selZ = new int[512];
        int selN = 0;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                long gh = StreamTracer.hash(gx * 31 + 7, gz * 73 + 13);
                int cx = gx * StreamTracer.SOURCE_GRID + (int) (((gh >>> 16) & 0xFFFF) / 65536f * StreamTracer.SOURCE_GRID * 0.7f);
                int cz = gz * StreamTracer.SOURCE_GRID + (int) (((gh >>> 32) & 0xFFFF) / 65536f * StreamTracer.SOURCE_GRID * 0.7f);
                if (!StreamTracer.isLocalHigh(wh, cx, cz)) continue;
                boolean dup = false;
                for (int i = 0; i < selN; i++) {
                    int dx = cx - selX[i], dz = cz - selZ[i];
                    if (dx * dx + dz * dz < StreamTracer.SOURCE_MIN_GAP * StreamTracer.SOURCE_MIN_GAP) {
                        dup = true; break;
                    }
                }
                if (dup) continue;   // 与 StreamTracer.trace 相同的近距合并
                selX[selN] = cx; selZ[selN] = cz; selN++;
                set.add((((long) cx) << 32) | (cz & 0xFFFFFFFFL));
            }
        }
        return set;
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
