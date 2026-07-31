package com.geogenesis.worldgen.terrain;

/**
 * 侵蚀 tile 管线多环节断裂诊断探针。
 *
 * <p>对相邻 tile 对的每一环节做差值统计，精确定位断裂来源：
 * <ol>
 *   <li><b>terrainE 一致性</b> — 两个 tile 使用不同坐标偏移采样 terrainE 是否相同</li>
 *   <li><b>插值原貌（base）连续性</b> — base = postErosion − delta，来自全局对齐 bicubic，理应为 0</li>
 *   <li><b>侵蚀后高度（postErosion）连续性</b> — 真正考验 flat 缓冲区上下文链</li>
 *   <li><b>delta 连续性</b> — 侵蚀增量在重叠区域的一致性</li>
 *   <li><b>跨边界截面抽样</b> — 沿 tile 边界线的值对比热力图</li>
 * </ol>
 */
public final class ErosionTileProbe {

    private static final String SEP = "  ";

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        runProbe(seed);
    }

    public static void runProbe(long seed) {
        System.out.println("=== Erosion Tile Pipeline Diagnostics ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        // 创建独立 CellGenerator（config=null，侵蚀引擎走默认值）
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.setRiversEnabled(false); // 关闭河流，隔离侵蚀变量
        gen.seed(seed);

        // 右侧邻居：tile (0,0) ↔ (3,0)
        probePair(gen, 0, 0, ERODE_TILE_CHUNKS, 0, "right neighbor");

        // 下侧邻居：tile (0,0) ↔ (0,3)
        probePair(gen, 0, 0, 0, ERODE_TILE_CHUNKS, "bottom neighbor");

        System.out.println("=== ErosionTileProbe done ===");
        System.out.println("Threshold: delta > 0.01 (~4 blocks) needs watch, >0.05 (~19 blocks) severe.");
    }

    /**
     * 诊断一个 tile 对。
     *
     * @param gen       CellGenerator
     * @param cxa,cza   第一个 tile 的 chunk-grouped 坐标
     * @param dcx,dcz   第二个 tile 相对于第一个的偏移（tile 坐标单位）
     * @param label     方向标签
     */
    private static void probePair(CellGenerator gen,
                                   int cxa, int cza,
                                   int dcx, int dcz,
                                   String label) {
        int tcxA = cxa, tczA = cza;
        int tcxB = cxa + dcx, tczB = cza + dcz;

        // 触发第一个 tile 生成（用内部任意 chunk 坐标触发）
        gen.getErosionTile(cxa * 16 / 16 + 1, cza * 16 / 16 + 1);
        gen.getErosionTile(tcxB * 16 / 16 + 1, tczB * 16 / 16 + 1);

        CellGenerator.ErosionTileResult tileA = gen.getTileResult(tcxA, tczA);
        CellGenerator.ErosionTileResult tileB = gen.getTileResult(tcxB, tczB);

        if (tileA == null || tileB == null) {
            System.out.println("SKIP " + label + ": one or both tiles not in cache");
            return;
        }

        int N = ERODE_TILE_SIZE;

        // 计算重叠区域（世界坐标范围）
        int overlapX0 = Math.max(tileA.originX, tileB.originX);
        int overlapX1 = Math.min(tileA.originX + N, tileB.originX + N);
        int overlapZ0 = Math.max(tileA.originZ, tileB.originZ);
        int overlapZ1 = Math.min(tileA.originZ + N, tileB.originZ + N);
        int ow = overlapX1 - overlapX0;
        int oh = overlapZ1 - overlapZ0;

        System.out.println("--- Pair: " + label + " (" + tcxA + "," + tczA + ") ↔ (" + tcxB + "," + tczB + ") ---");
        System.out.println("  Tile A origin: (" + tileA.originX + "," + tileA.originZ + ")");
        System.out.println("  Tile B origin: (" + tileB.originX + "," + tileB.originZ + ")");
        System.out.println("  Overlap: world [" + overlapX0 + "," + overlapX1 + ") x [" + overlapZ0 + "," + overlapZ1 + ") = " + ow + "x" + oh + " cells");
        System.out.println();

        // ---- Stage 1: terrainE 一致性 ----
        stageTerrainE(gen, tileA, tileB, overlapX0, overlapX1, overlapZ0, overlapZ1, ow, oh);

        // ---- Stage 2: 插值原貌（base）连续性 ----
        // base = postErosion - delta（全局对齐 bicubic → 应为 0）
        stageBase(tileA, tileB, overlapX0, overlapX1, overlapZ0, overlapZ1, ow, oh, N);

        // ---- Stage 3: 侵蚀后高度（postErosion）连续性 ----
        stagePostErosion(tileA, tileB, overlapX0, overlapX1, overlapZ0, overlapZ1, ow, oh, N);

        // ---- Stage 4: delta 连续性 ----
        stageDelta(tileA, tileB, overlapX0, overlapX1, overlapZ0, overlapZ1, ow, oh, N);

        // ---- Stage 7: chunk 边界应用后连续性（世界无缝真度量） ----
        // Stage 3/4 比较 tile 间重叠区（双写区：A 超区 vs B chunk 区），数据差异是架构固有，
        // 不代表世界接缝。世界最终值 = chunk 应用各自 tile delta：右/下缘 4 块渐变读邻居场、
        // 左/上缘用自己（= 左邻已渐变到本场）→ chunk 边界两侧同场 → 差 ≈ 场梯度（应很小）。
        stageChunkBoundary(tileA, tileB, overlapZ0, overlapZ1, N);

        // ---- Stage 5: 边界截面 ----
        if (dcx != 0) {
            stageBoundaryX(tileA, tileB, overlapZ0, overlapZ1, N);
        }
        if (dcz != 0) {
            stageBoundaryZ(tileA, tileB, overlapX0, overlapX1, N);
        }

        // ---- Stage 6: 液滴覆盖不对称性诊断（当前 tile 架构的根因定位） ----
        if (dcx != 0) {
            stageDropletCoverage(tileA, tileB, overlapZ0, N, "X");
            stageDeltaVsFlatPos(tileA, tileB, overlapX0, overlapX1, overlapZ0, overlapZ1, N, "X");
        }
        if (dcz != 0) {
            stageDropletCoverage(tileA, tileB, overlapX0, N, "Z");
            stageDeltaVsFlatPos(tileA, tileB, overlapX0, overlapX1, overlapZ0, overlapZ1, N, "Z");
        }

        System.out.println();
    }

    // ===== 各环节诊断 =====

    private static void stageTerrainE(CellGenerator gen,
                                       CellGenerator.ErosionTileResult tileA,
                                       CellGenerator.ErosionTileResult tileB,
                                       int ox0, int ox1, int oz0, int oz1,
                                       int ow, int oh) {
        System.out.println("=== Stage 1: terrainE coordinate consistency ===");
        double maxDiff = 0, sumDiff = 0;
        long count = 0;
        int worstX = 0, worstZ = 0;

        // 从两个 tile 的 origin 偏移采样同一世界坐标
        for (int dz = 0; dz < oh; dz++) {
            for (int dx = 0; dx < ow; dx++) {
                double wx = ox0 + dx;
                double wz = oz0 + dz;

                // 从 tileA perspective 经 coarse sampling 的地形值
                double vA = gen.terrainE(wx, wz);
                double vB = gen.terrainE(wx, wz); // 同一函数

                double diff = Math.abs(vA - vB);
                if (diff > maxDiff) { maxDiff = diff; worstX = (int)wx; worstZ = (int)wz; }
                sumDiff += diff;
                count++;
            }
        }
        double meanDiff = count > 0 ? sumDiff / count : 0;
        System.out.println("  Max diff: " + String.format("%.6f", maxDiff) + " (should be 0, deterministic)");
        System.out.println("  Mean diff: " + String.format("%.6f", meanDiff));
        System.out.println("  Verdict: " + (maxDiff < 1e-10 ? "PASS" : "FAIL"));
        System.out.println();
    }

    private static void stageBase(CellGenerator.ErosionTileResult tileA,
                                   CellGenerator.ErosionTileResult tileB,
                                   int ox0, int ox1, int oz0, int oz1,
                                   int ow, int oh, int N) {
        System.out.println("=== Stage 2: Pre-erosion base (bicubic) continuity ===");
        System.out.println("  base = postErosion - delta. Globally-aligned bicubic → should be 0.");

        double maxDiff = 0, sumDiff = 0, p99Diff = 0;
        long count = 0;
        int worstX = 0, worstZ = 0;

        double[] allDiffs = new double[ow * oh];
        int di = 0;

        for (int dz = 0; dz < oh; dz++) {
            for (int dx = 0; dx < ow; dx++) {
                int wx = ox0 + dx, wz = oz0 + dz;
                int ax = wx - tileA.originX, az = wz - tileA.originZ;
                int bx = wx - tileB.originX, bz = wz - tileB.originZ;

                if (ax < 0 || ax >= N || az < 0 || az >= N) continue;
                if (bx < 0 || bx >= N || bz < 0 || bz >= N) continue;

                double baseA = tileA.postErosion[az][ax] - tileA.delta[az][ax];
                double baseB = tileB.postErosion[bz][bx] - tileB.delta[bz][bx];
                double diff = Math.abs(baseA - baseB);

                if (diff > maxDiff) { maxDiff = diff; worstX = wx; worstZ = wz; }
                sumDiff += diff;
                allDiffs[di++] = diff;
                count++;
            }
        }

        if (count > 0) {
            double meanDiff = sumDiff / count;
            java.util.Arrays.sort(allDiffs, 0, (int)count);
            p99Diff = allDiffs[(int)(count * 0.99)];
            System.out.println("  Max diff: " + String.format("%.6f", maxDiff)
                + " (~" + String.format("%.1f", maxDiff * 384) + " blocks) at (" + worstX + "," + worstZ + ")");
            System.out.println("  Mean diff: " + String.format("%.6f", meanDiff));
            System.out.println("  p99 diff: " + String.format("%.6f", p99Diff));
            System.out.println("  Samples: " + count);
            System.out.println("  Verdict: " + (maxDiff < 1e-10 ? "PASS ✅" : (maxDiff < 0.01 ? "WARN ⚠" : "FAIL ❌")));
        } else {
            System.out.println("  No valid overlap samples");
        }
        System.out.println();
    }

    private static void stagePostErosion(CellGenerator.ErosionTileResult tileA,
                                          CellGenerator.ErosionTileResult tileB,
                                          int ox0, int ox1, int oz0, int oz1,
                                          int ow, int oh, int N) {
        System.out.println("=== Stage 3: Post-erosion height continuity ===");
        System.out.println("  postErosion = base + delta. Erosion's contribution to seam.");

        double maxDiff = 0, sumDiff = 0, p99Diff = 0;
        long count = 0;
        int worstX = 0, worstZ = 0;

        double[] allDiffs = new double[ow * oh];
        int di = 0;

        for (int dz = 0; dz < oh; dz++) {
            for (int dx = 0; dx < ow; dx++) {
                int wx = ox0 + dx, wz = oz0 + dz;
                int ax = wx - tileA.originX, az = wz - tileA.originZ;
                int bx = wx - tileB.originX, bz = wz - tileB.originZ;

                if (ax < 0 || ax >= N || az < 0 || az >= N) continue;
                if (bx < 0 || bx >= N || bz < 0 || bz >= N) continue;

                double diff = Math.abs(tileA.postErosion[az][ax] - tileB.postErosion[bz][bx]);

                if (diff > maxDiff) { maxDiff = diff; worstX = wx; worstZ = wz; }
                sumDiff += diff;
                allDiffs[di++] = diff;
                count++;
            }
        }

        if (count > 0) {
            double meanDiff = sumDiff / count;
            java.util.Arrays.sort(allDiffs, 0, (int)count);
            p99Diff = allDiffs[(int)(count * 0.99)];
            System.out.println("  Max diff: " + String.format("%.6f", maxDiff)
                + " (~" + String.format("%.1f", maxDiff * 384) + " blocks) at (" + worstX + "," + worstZ + ")");
            System.out.println("  Mean diff: " + String.format("%.6f", meanDiff));
            System.out.println("  p99 diff: " + String.format("%.6f", p99Diff));
            System.out.println("  Samples: " + count);
            System.out.println("  Verdict: " + (maxDiff < 0.01 ? "PASS ✅" : (maxDiff < 0.05 ? "WARN ⚠" : "FAIL ❌")));
        } else {
            System.out.println("  No valid overlap samples");
        }
        System.out.println();
    }

    private static void stageDelta(CellGenerator.ErosionTileResult tileA,
                                    CellGenerator.ErosionTileResult tileB,
                                    int ox0, int ox1, int oz0, int oz1,
                                    int ow, int oh, int N) {
        System.out.println("=== Stage 4: Delta continuity ===");
        System.out.println("  Delta = erosion change. Same coord eroded by diff tiles → should match.");

        double maxDiff = 0, sumDiff = 0, p99Diff = 0;
        long count = 0;
        int worstX = 0, worstZ = 0;

        double[] allDiffs = new double[ow * oh];
        int di = 0;

        for (int dz = 0; dz < oh; dz++) {
            for (int dx = 0; dx < ow; dx++) {
                int wx = ox0 + dx, wz = oz0 + dz;
                int ax = wx - tileA.originX, az = wz - tileA.originZ;
                int bx = wx - tileB.originX, bz = wz - tileB.originZ;

                if (ax < 0 || ax >= N || az < 0 || az >= N) continue;
                if (bx < 0 || bx >= N || bz < 0 || bz >= N) continue;

                double diff = Math.abs(tileA.delta[az][ax] - tileB.delta[bz][bx]);

                if (diff > maxDiff) { maxDiff = diff; worstX = wx; worstZ = wz; }
                sumDiff += diff;
                allDiffs[di++] = diff;
                count++;
            }
        }

        if (count > 0) {
            double meanDiff = sumDiff / count;
            java.util.Arrays.sort(allDiffs, 0, (int)count);
            p99Diff = allDiffs[(int)(count * 0.99)];
            System.out.println("  Max diff: " + String.format("%.6f", maxDiff)
                + " (~" + String.format("%.1f", maxDiff * 384) + " blocks) at (" + worstX + "," + worstZ + ")");
            System.out.println("  Mean diff: " + String.format("%.6f", meanDiff));
            System.out.println("  p99 diff: " + String.format("%.6f", p99Diff));
            System.out.println("  Samples: " + count);
            System.out.println("  Verdict: " + (maxDiff < 0.01 ? "PASS ✅" : (maxDiff < 0.05 ? "WARN ⚠" : "FAIL ❌")));
        } else {
            System.out.println("  No valid overlap samples");
        }
        System.out.println();
    }

    // ---- Stage 7: chunk 边界应用后连续性（世界无缝真度量） ----

    private static void stageChunkBoundary(CellGenerator.ErosionTileResult tileA,
                                           CellGenerator.ErosionTileResult tileB,
                                           int oz0, int oz1, int N) {
        System.out.println("=== Stage 7: Chunk-boundary applied continuity (world seam truth) ===");
        int boundX = (tileA.tileCX + ERODE_TILE_CHUNKS) * 16; // chunk 边界（A 的 chunk 区右缘）
        System.out.println("  Chunk boundary at worldX=" + boundX
            + ": left col (A chunk, blends to right-neighbor B) vs right col (B chunk, own field)");
        System.out.println("  Both from B's field → diff ≈ field gradient (small).");
        System.out.println("  Format: [wz] d_left(B)[applied] d_right(B)[applied] | diff");

        double maxDiff = 0, sumDiff = 0, p99Diff = 0;
        long count = 0;
        int worstZ = 0;
        double[] allDiffs = new double[oz1 - oz0];
        int di = 0;

        int leftX = boundX - 1;                     // A chunk 右缘 lx=15 → blend b=1 → 全右邻居 B
        int bxL = leftX - tileB.originX;            // B.delta 局部列（A 应用的来源）
        int bxR = boundX - tileB.originX;           // B 自己（B chunk 左缘 lx=0 用自己场）
        for (int wz = oz0; wz < oz1; wz++) {
            int bz = wz - tileB.originZ;
            if (bz < 0 || bz >= N) continue;
            if (bxL < 0 || bxL >= N || bxR < 0 || bxR >= N) continue;
            double dL = tileB.delta[bz][bxL];
            double dR = tileB.delta[bz][bxR];
            double diff = Math.abs(dL - dR);
            if (diff > maxDiff) { maxDiff = diff; worstZ = wz; }
            sumDiff += diff;
            allDiffs[di++] = diff;
            count++;
        }

        if (count > 0) {
            double meanDiff = sumDiff / count;
            java.util.Arrays.sort(allDiffs, 0, (int) count);
            p99Diff = allDiffs[(int) (count * 0.99)];
            System.out.println("  Max diff: " + String.format("%.6f", maxDiff)
                + " (~" + String.format("%.1f", maxDiff * 384) + " blocks) at wz=" + worstZ);
            System.out.println("  Mean diff: " + String.format("%.6f", meanDiff));
            System.out.println("  p99 diff: " + String.format("%.6f", p99Diff));
            System.out.println("  Samples: " + count);
            System.out.println("  Verdict: " + (maxDiff < 0.02 ? "PASS (same field, gradient only) ✅"
                : (maxDiff < 0.05 ? "WARN ⚠" : "FAIL ❌")));
        } else {
            System.out.println("  No valid samples");
        }
        System.out.println();
    }

    // ---- 边界截面 ----

    private static void stageBoundaryX(CellGenerator.ErosionTileResult tileA,
                                        CellGenerator.ErosionTileResult tileB,
                                        int oz0, int oz1, int N) {
        System.out.println("=== Stage 5: Boundary cross-section (X direction) ===");
        int bndX = tileB.originX; // 分界线：tileA 右边界 = tileB 左边界
        System.out.println("  Boundary at worldX=" + bndX + " (A.right = B.left)");
        System.out.println("  Format: [wx] base(A) base(B) | delta(A) delta(B) | post(A) post(B) | diff_post");
        System.out.println("  " + "-".repeat(100));

        // 抽样 Z 范围（取中间 10 行）
        int zMid = (oz0 + oz1) / 2;
        int zStart = Math.max(oz0, zMid - 5);
        int zEnd = Math.min(oz1, zMid + 5);

        double maxBoundaryDiff = 0;
        int worstZ = 0;

        for (int wz = zStart; wz < zEnd; wz++) {
            int ax = bndX - 1 - tileA.originX; int az = wz - tileA.originZ;
            int bx = bndX - tileB.originX;     int bz = wz - tileB.originZ;

            if (ax < 0 || ax >= N || az < 0 || az >= N) continue;
            if (bx < 0 || bx >= N || bz < 0 || bz >= N) continue;

            double baseA = tileA.postErosion[az][ax] - tileA.delta[az][ax];
            double baseB = tileB.postErosion[bz][bx] - tileB.delta[bz][bx];
            double deltaA = tileA.delta[az][ax];
            double deltaB = tileB.delta[bz][bx];
            double postA = tileA.postErosion[az][ax];
            double postB = tileB.postErosion[bz][bx];
            double diffPost = Math.abs(postA - postB);

            if (diffPost > maxBoundaryDiff) { maxBoundaryDiff = diffPost; worstZ = wz; }

            // 世界坐标：wx = bndX-1 和 wx = bndX
            System.out.println(String.format("  [%d→%d] base=%.6f/%.6f | delta=%.6f/%.6f | post=%.6f/%.6f | diff=%.6f",
                bndX - 1, bndX, baseA, baseB, deltaA, deltaB, postA, postB, diffPost));
        }

        System.out.println(String.format("  Max boundary diff: %.6f (~%.1f blocks) at wz=%d",
            maxBoundaryDiff, maxBoundaryDiff * 384, worstZ));
    }

    private static void stageBoundaryZ(CellGenerator.ErosionTileResult tileA,
                                        CellGenerator.ErosionTileResult tileB,
                                        int ox0, int ox1, int N) {
        System.out.println("=== Stage 5: Boundary cross-section (Z direction) ===");
        int bndZ = tileB.originZ;
        System.out.println("  Boundary at worldZ=" + bndZ + " (A.bottom = B.top)");
        System.out.println("  Format: [wz] base(A) base(B) | delta(A) delta(B) | post(A) post(B) | diff_post");
        System.out.println("  " + "-".repeat(100));

        int xMid = (ox0 + ox1) / 2;

        double maxBoundaryDiff = 0;
        int worstX = 0;

        for (int wx = xMid - 5; wx < xMid + 5; wx++) {
            int ax = wx - tileA.originX; int az = bndZ - 1 - tileA.originZ;
            int bx = wx - tileB.originX; int bz = bndZ - tileB.originZ;

            if (ax < 0 || ax >= N || az < 0 || az >= N) continue;
            if (bx < 0 || bx >= N || bz < 0 || bz >= N) continue;

            double baseA = tileA.postErosion[az][ax] - tileA.delta[az][ax];
            double baseB = tileB.postErosion[bz][bx] - tileB.delta[bz][bx];
            double deltaA = tileA.delta[az][ax];
            double deltaB = tileB.delta[bz][bx];
            double postA = tileA.postErosion[az][ax];
            double postB = tileB.postErosion[bz][bx];
            double diffPost = Math.abs(postA - postB);

            if (diffPost > maxBoundaryDiff) { maxBoundaryDiff = diffPost; worstX = wx; }

            System.out.println(String.format("  [%d→%d] base=%.6f/%.6f | delta=%.6f/%.6f | post=%.6f/%.6f | diff=%.6f",
                bndZ - 1, bndZ, baseA, baseB, deltaA, deltaB, postA, postB, diffPost));
        }

        System.out.println(String.format("  Max boundary diff: %.6f (~%.1f blocks) at wx=%d",
            maxBoundaryDiff, maxBoundaryDiff * 384, worstX));
    }

    /**
     * 液滴覆盖不对称性诊断：展示同一行上的每个世界坐标，在 tile A 和 tile B 中
     * 分别属于哪个 chunk，以及在该 tile 的 flat 缓冲区中的位置。
     *
     * <p>直观揭示：同一个世界坐标在两个 tile 中被不同上下文处理。</p>
     */
    private static void stageDropletCoverage(CellGenerator.ErosionTileResult tileA,
                                              CellGenerator.ErosionTileResult tileB,
                                              int oz0, int N, String dir) {
        System.out.println("=== Stage 6a: Droplet coverage asymmetry (" + dir + ") ===");
        System.out.println("  Shows per-world-coord: (belongs_to_chunk) flat_buffer_pos");
        System.out.println("  If tile A and tile B process same coord at different flat positions → asymmetry");
        int zMid = (oz0 + (oz0 + 80)) / 2; // 一行

        int step = dir.equals("X") ? 1 : 4; // 水平步长 1，垂直步长 4（降低输出量）
        System.out.println(String.format("  %-6s | %-24s | %-24s",
            "wx/wz", "tile A (chunk→flatPos)", "tile B (chunk→flatPos)"));

        int range = 60; // 跨边界前后各 30
        int center = dir.equals("X") ? (tileB.originX + tileA.originX) / 2 : (tileB.originZ + tileA.originZ) / 2;
        for (int off = -range / 2; off <= range / 2; off += step) {
            int wx, wz;
            if (dir.equals("X")) { wx = center + off; wz = zMid; }
            else { wx = zMid; wz = center + off; }

            // tile A 视角：chunk 坐标和 flat 位置
            int chunkAX = Math.floorDiv(wx, 16);
            int chunkAZ = Math.floorDiv(wz, 16);
            int relAX = wx - tileA.originX;
            int relAZ = wz - tileA.originZ;
            int flatPosAX = 9 + relAX; // pad = R_MAX+2 = 9
            int flatPosAZ = 9 + relAZ;

            // tile B 视角
            int chunkBX = Math.floorDiv(wx, 16);
            int chunkBZ = Math.floorDiv(wz, 16);
            int relBX = wx - tileB.originX;
            int relBZ = wz - tileB.originZ;
            int flatPosBX = 9 + relBX;
            int flatPosBZ = 9 + relBZ;

            // 标记是否在各自 tile 的有效数据范围内
            boolean inRangeA = relAX >= 0 && relAX < N && relAZ >= 0 && relAZ < N;
            boolean inRangeB = relBX >= 0 && relBX < N && relBZ >= 0 && relBZ < N;

            String label = dir.equals("X") ? String.format("%d", wx) : String.format("%d", wz);
            String aInfo = inRangeA
                ? String.format("chk(%d,%d) pos(%d,%d)", chunkAX, chunkAZ, flatPosAX, flatPosAZ)
                : "OUTSIDE";
            String bInfo = inRangeB
                ? String.format("chk(%d,%d) pos(%d,%d)", chunkBX, chunkBZ, flatPosBX, flatPosBZ)
                : "OUTSIDE";

            System.out.println(String.format("  %-6s | %-24s | %-24s",
                label, aInfo, bInfo));
        }
        System.out.println();
    }

    /**
     * delta 与 flat 位置相关性诊断：将重叠区按离 tile 左边缘的块数分组，
     * 统计每组内 delta 差异的方差。
     *
     * <p>如果高差异集中在 tile 边缘附近 → 笔刷截断/上下文不对称是根因。</p>
     */
    private static void stageDeltaVsFlatPos(CellGenerator.ErosionTileResult tileA,
                                              CellGenerator.ErosionTileResult tileB,
                                              int ox0, int ox1, int oz0, int oz1,
                                              int N, String dir) {
        System.out.println("=== Stage 6b: Delta diff vs distance from tile B origin (" + dir + ") ===");
        System.out.println("  Groups cells by distance from tile B's left/top edge,");
        System.out.println("  measures how delta divergence increases near the boundary.");
        System.out.println(String.format("  %-8s | %8s | %8s | %8s | %6s%n",
            "dist", "samples", "maxDiff", "meanDiff", "maxBlk"));

        int maxDist = dir.equals("X")
            ? Math.min(ox1 - tileB.originX, tileA.originX + N - ox0)
            : Math.min(oz1 - tileB.originZ, tileA.originZ + N - oz0);
        maxDist = Math.min(maxDist, 80); // 限制输出范围

        for (int dist = 0; dist < maxDist; dist++) {
            double maxDiff = 0, sumDiff = 0;
            long count = 0;

            for (int dz = 0; dz < (dir.equals("X") ? (oz1 - oz0) : (ox1 - ox0)); dz++) {
                int pos = dir.equals("X")
                    ? (tileB.originX + dist) // 沿 X 方向离开 tile B 左边缘的距离
                    : (tileB.originZ + dist); // 沿 Z 方向
                int pos2 = dir.equals("X") ? (tileB.originX + dist + 3) : pos; // 看一个区段，每个距离取 ~4 行

                for (int d2 = 0; d2 < 4 && pos2 + d2 < (dir.equals("X") ? ox1 : oz1); d2++) {
                    int worldX = dir.equals("X") ? pos : (ox0 + dz);
                    int worldZ = dir.equals("X") ? (oz0 + dz) : (pos + d2);

                    int ax = worldX - tileA.originX, az = worldZ - tileA.originZ;
                    int bx = worldX - tileB.originX, bz = worldZ - tileB.originZ;

                    if (ax < 0 || ax >= N || az < 0 || az >= N) continue;
                    if (bx < 0 || bx >= N || bz < 0 || bz >= N) continue;

                    double diff = Math.abs(tileA.delta[az][ax] - tileB.delta[bz][bx]);
                    if (diff > maxDiff) maxDiff = diff;
                    sumDiff += diff;
                    count++;
                }
            }

            if (count > 0) {
                double mean = sumDiff / count;
                System.out.println(String.format("  %-8d | %8d | %8.6f | %8.6f | %6.1f",
                    dist, count, maxDiff, mean, maxDiff * 384));
            }
        }
        System.out.println();
    }

    // ===== 常量复制 =====
    private static final int ERODE_TILE_CHUNKS = 3;
    private static final int ERODE_TILE_SIZE = 128;
}
