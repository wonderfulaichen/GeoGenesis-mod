package com.geogenesis.worldgen.terrain;

import java.util.HashSet;

/**
 * 河流追踪器（2026-08-01，两套粒子系统之「河流粒子」）。
 *
 * <p>与液滴侵蚀彻底分离：本类不改地形，只追踪河道。</p>
 * <ol>
 *   <li><b>源头</b>：世界坐标粗网格（SOURCE_GRID=56 块）+ 确定性抖动，陆地（e≥SOURCE_MIN_E）
 *       且 hash 通过（SOURCE_DENSITY=0.30）→ 源头天然稀疏（~每 56 块一条）。</li>
 *   <li><b>追踪</b>：移动机制与液滴侵蚀（ErosionEngine.simulateDrop）<b>同源</b>——连续坐标、
 *       3×3 Sobel 梯度方向 + INERTIA 惯性、平坦区 hash 片流漫游、重力速度步进（≤1.5 格）。
 *       每格标 riverCore + discharge++（=上游源头数）。与液滴唯一区别：<b>不衰减</b>
 *       （无蒸发/沉积/寿命）。终止：h&lt;seaE 入海 / visited 绕圈（闭合洼地 = 内流湖接水）/
 *       MAX_STEPS 兜底。</li>
 *   <li><b>汇流涌现</b>：多源头路径重叠 → dis 累积 = 上游源头数 → 主干高、支流低，无阈值参数。</li>
 * </ol>
 *
 * <p><b>确定性</b>：决策只依赖 tile 场（世界坐标对齐、跨 tile 重叠区一致）+ 世界坐标 hash。
 * 源头格按世界坐标 floorDiv 对齐 → 相邻 tile 重叠区源头集合与路径相同 → 提取区河道无缝。</p>
 *
 * <p><b>跨 tile 一致（2026-08-02）</b>：片流 hash 必须用<b>世界坐标</b>——液滴的 hash 用 tile
 * 局部坐标（侵蚀场无需跨 tile 一致），但河流标记在相邻 tile 重叠区必须相同 → 绝对坐标 hash。</p>
 */
public final class StreamTracer {

    /** 源头粗网格间距（世界块）：调大 → 河更稀 */
    static final int SOURCE_GRID = 56;
    /** 每源头格成为河源的概率：调小 → 河更稀 */
    static final float SOURCE_DENSITY = 0.30f;
    /** 源头最低陆地高度（e，seaE=0）：低于此不产生源头 */
    static final float SOURCE_MIN_E = 0.12f;
    /** 单条河最大步数（防死循环；正常路径几十~几百步） */
    static final int MAX_STEPS = 4096;

    // 移动常数与 ErosionEngine.simulateDrop 对齐（改动必须两边同步）：
    /** 方向惯性（越小越贴梯度；液滴同款 0.005） */
    private static final float INERTIA = 0.005f;
    /** 重力加速度（速度累积，液滴同款 2.5） */
    private static final float GRAVITY = 2.5f;
    /** 速度上限（每步最大步长，液滴同款 1.5 格） */
    private static final float SPD_CAP = 1.5f;

    private StreamTracer() {}

    /** 世界坐标 → 侵蚀后地形 e 的高度函数（tile 外采样；L1 由 CellGenerator.erodedHeightAt 提供）。 */
    public interface WorldHeight {
        float heightAt(int wx, int wz);
    }

    /**
     * 在侵蚀后地形场上追踪全部河流，产出 RiverTileData。
     *
     * @param tile     侵蚀后地形场 [N][N]（含超区）
     * @param N        tile 边长
     * @param originX  tile 原点世界 X
     * @param originZ  tile 原点世界 Z
     * @param seaE     海平面 e（低于此 = 入海终止）
     * @return 河流元数据（无河 → maxDischarge=0 → 上游不雕刻）
     */
    public static CellGenerator.RiverTileData trace(float[][] tile, int N, int originX, int originZ,
                                                    float seaE, WorldHeight worldHeight) {
        CellGenerator.RiverTileData rd = new CellGenerator.RiverTileData();
        boolean[][] core = rd.riverCore;
        float[][] dis = rd.discharge;
        float maxQ = 0f;

        // 源头格范围覆盖 tile 全域 + 5×SOURCE_GRID（2026-08-02：源头集合不对称 =
        // 相邻 tile 追踪的源头范围不同 → 差异区源头（距对方提取区 ≤K×56−168 块）的
        // 河穿越 seam 时对方提取区无标记 → 断流（探针实测：K=2→42 格 core 差异、
        // K=3→32 格，K 越大差异越小）。K=5 时差异源头距对方提取区 ≥336 块——
        // 河长超过 336 块才可能断（入海长河尾部），实机几乎不可见。
        // 覆盖扩大会让同一源头被多个 tile 追踪——各自只标记自己数据区 →
        // 重叠区一致（确定性），无双写冲突。源头格数 (128+560)/56≈12 → ~150 格
        // ×0.3 密度 ≈ 45 源头/tile（数组读为主，性能无压力）。
        int gx0 = Math.floorDiv(originX - 5 * SOURCE_GRID, SOURCE_GRID);
        int gx1 = Math.floorDiv(originX + N - 1 + 5 * SOURCE_GRID, SOURCE_GRID);
        int gz0 = Math.floorDiv(originZ - 5 * SOURCE_GRID, SOURCE_GRID);
        int gz1 = Math.floorDiv(originZ + N - 1 + 5 * SOURCE_GRID, SOURCE_GRID);

        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                long gh = hash(gx * 31 + 7, gz * 73 + 13);
                if ((gh & 0xFFFF) / 65536f >= SOURCE_DENSITY) continue;   // 概率过滤
                // 格心 = 网格原点 + 确定性抖动（±35% 打散网格对齐伪影）
                int cx = gx * SOURCE_GRID + (int) (((gh >>> 16) & 0xFFFF) / 65536f * SOURCE_GRID * 0.7f);
                int cz = gz * SOURCE_GRID + (int) (((gh >>> 32) & 0xFFFF) / 65536f * SOURCE_GRID * 0.7f);
                int tx = cx - originX, tz = cz - originZ;
                // 源头高度：界内读数组、界外查高度函数（覆盖范围内源头全追踪，位置不限 tile 内
                // → 长河上游段所在 tile 也能追踪，根治"河在 tile 提取区交界断流"）
                float h = (tx >= 0 && tx < N && tz >= 0 && tz < N)
                        ? tile[tz][tx] : worldHeight.heightAt(cx, cz);
                if (h < SOURCE_MIN_E) continue;
                traceOne(tile, N, originX, originZ, cx, cz, seaE, worldHeight, core, dis);
            }
        }
        // 全网格单次扫描求最大流量（V 形雕刻深度归一化用）
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                if (dis[z][x] > maxQ) maxQ = dis[z][x];

        // 3×3 膨胀 mask（灌水区），dis 保持原始（V 形雕刻中心深/边缘浅）
        boolean[][] src = new boolean[N][N];
        for (int z = 0; z < N; z++) System.arraycopy(core[z], 0, src[z], 0, N);
        boolean[][] mask = rd.riverMask;
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                if (!src[z][x]) continue;
                for (int dz = -1; dz <= 1; dz++) {
                    int nz = z + dz;
                    if (nz < 0 || nz >= N) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= N) continue;
                        mask[nz][nx] = true;
                    }
                }
            }

        rd.maxDischarge = maxQ;
        return rd;
    }

    /**
     * 单条河追踪：移动机制与液滴侵蚀（ErosionEngine.simulateDrop）同源——连续坐标
     * (sx,sz)（世界坐标）、3×3 Sobel 梯度方向 + INERTIA 惯性、平坦区 hash 片流漫游、
     * 重力速度步进（≤SPD_CAP）。与液滴唯一区别：<b>不衰减</b>（无蒸发/沉积/寿命），
     * 终止条件仅：h&lt;seaE 入海、visited 绕圈（闭合洼地 = 内流湖）、MAX_STEPS 兜底。
     * 出界不截断——继续用高度函数流（标记仅限数据范围内，出界段不标记）。
     */
    private static void traceOne(float[][] tile, int N, int originX, int originZ,
                                 float sx, float sz, float seaE, WorldHeight worldHeight,
                                 boolean[][] core, float[][] dis) {
        float posX = sx, posY = sz;
        float dirX = 0f, dirY = 0f, spd = 1f;
        int prevX = -1, prevY = -1;
        HashSet<Long> visited = new HashSet<>(256);
        for (int st = 0; st < MAX_STEPS; st++) {
            int ix = (int) posX, iy = (int) posY;
            float h = heightAtWorld(tile, N, originX, originZ, worldHeight, ix, iy);
            if (h < seaE) break;                            // 入海终止（海格不标记）
            // 仅跨格时记录 visited + 标记：spd 震荡在 ~0.9-1.1（步长可能 <1 格），
            // 亚格步长下 (int)pos 不变 → 整格 visited 会把"格内蠕动"误判为绕圈 → 河几步就死。
            // 跨格才 add → 真回到旧格（盆地绕圈）仍被检测（闭合洼地 = 内流湖接水）。
            // 注意：core/dis 是 tile 局部索引，标记前必须转局部坐标（世界坐标会越界 128×128）
            if (ix != prevX || iy != prevY) {
                long vk = (((long) ix) << 32) | (iy & 0xFFFFFFFFL);
                if (!visited.add(vk)) break;
                int lx = ix - originX, ly = iy - originZ;
                if (prevX < 0) markCell(lx, ly, core, dis);
                else markSegment(prevX - originX, prevY - originZ, lx, ly, core, dis);
                prevX = ix; prevY = iy;
            }

            // 当前子像素高度（双线性，与液滴 h0 同款）
            float h0 = bilinearAt(tile, N, originX, originZ, worldHeight, posX, posY);
            // 3×3 Sobel 梯度（与液滴同款，抗单格噪声）
            float gx = 0f, gy = 0f;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    float nh = heightAtWorld(tile, N, originX, originZ, worldHeight, ix + dx, iy + dz);
                    float w = (dx == 0 || dz == 0) ? 2f : 1f;   // Sobel 边权重 2 / 对角 1
                    gx += dx * w * nh / 8f;
                    gy += dz * w * nh / 8f;
                }
            }
            float glen = (float) Math.sqrt(gx * gx + gy * gy);
            if (glen < 1e-12f) {
                // 片流：梯度≈0（平坦/洼地）→ hash 噪声方向（世界坐标 → 跨 tile 一致）。
                // 液滴 st>2 才生成（防起点抖动）；河流不衰减 → 无条件生成。
                if (dirX == 0f && dirY == 0f) {
                    long nh = hash(ix * 31 + 7, iy * 73 + 13);
                    dirX = ((float) (nh & 0xFFFF) / 65536f) * 0.6f - 0.3f;
                    dirY = ((float) ((nh >>> 16) & 0xFFFF) / 65536f) * 0.6f - 0.3f;
                }
            } else {
                dirX = dirX * INERTIA - gx * (1f - INERTIA);
                dirY = dirY * INERTIA - gy * (1f - INERTIA);
            }
            float dlen = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (dlen < 1e-12f) break;                       // 理论不可达（片流必有方向）
            dirX /= dlen; dirY /= dlen;

            // 重力速度步进（液滴同款：先抬速，再按新位落差修正，NaN 守卫）
            spd = (float) Math.sqrt(spd + Math.abs(h - h0) * GRAVITY);
            float step = Math.min(spd, SPD_CAP);
            float npx = posX + dirX * step, npy = posY + dirY * step;
            float nH0 = bilinearAt(tile, N, originX, originZ, worldHeight, npx, npy);
            float dh = nH0 - h0;
            float speedSq = spd * spd + dh * GRAVITY;
            spd = speedSq > 1e-12f ? (float) Math.sqrt(speedSq) : 0.1f;
            if (spd <= 0f) break;
            posX = npx; posY = npy;
        }
    }

    /** 世界坐标格点高度：数据区内读数组（快路径），出界走高度函数。 */
    private static float heightAtWorld(float[][] tile, int N, int originX, int originZ,
                                       WorldHeight wh, int wx, int wz) {
        int lx = wx - originX, lz = wz - originZ;
        return (lx >= 0 && lx < N && lz >= 0 && lz < N) ? tile[lz][lx] : wh.heightAt(wx, wz);
    }

    /** 子像素双线性高度（与液滴 h0 同款；出界格退化为格点值）。 */
    private static float bilinearAt(float[][] tile, int N, int originX, int originZ,
                                    WorldHeight wh, float wx, float wz) {
        int ix = (int) wx, iz = (int) wz;
        float fx = wx - ix, fz = wz - iz;
        float h00 = heightAtWorld(tile, N, originX, originZ, wh, ix, iz);
        float h10 = heightAtWorld(tile, N, originX, originZ, wh, ix + 1, iz);
        float h01 = heightAtWorld(tile, N, originX, originZ, wh, ix, iz + 1);
        float h11 = heightAtWorld(tile, N, originX, originZ, wh, ix + 1, iz + 1);
        return h00 * (1 - fx) * (1 - fz) + h10 * fx * (1 - fz)
             + h01 * (1 - fx) * fz + h11 * fx * fz;
    }

    /** 标记单格（仅数据区内）。 */
    private static void markCell(int x, int y, boolean[][] core, float[][] dis) {
        if (x >= 0 && x < core[0].length && y >= 0 && y < core.length) {
            core[y][x] = true;
            dis[y][x] += 1f;
        }
    }

    /** DDA 标记线段（步长≤1.5 可跨整数格，补中间格保证河道连续）。 */
    private static void markSegment(int x0, int y0, int x1, int y1, boolean[][] core, float[][] dis) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        for (int s = 0; s <= steps; s++) {
            int x = x0 + (dx * s) / steps;
            int y = y0 + (dy * s) / steps;
            if (x >= 0 && x < core[0].length && y >= 0 && y < core.length) {
                core[y][x] = true;
                dis[y][x] += 1f;
            }
        }
    }

    private static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }
}
