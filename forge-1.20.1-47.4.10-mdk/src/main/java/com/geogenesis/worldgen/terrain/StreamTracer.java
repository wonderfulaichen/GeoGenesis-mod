package com.geogenesis.worldgen.terrain;

import java.util.HashSet;

/**
 * 河流追踪器（2026-08-01，两套粒子系统之「河流粒子」）。
 *
 * <p>与液滴侵蚀彻底分离：本类不改地形，只追踪河道。</p>
 * <ol>
 *   <li><b>源头</b>：侵蚀粒子汇聚升级（2026-08-02）——液滴路径汇聚场（discharge）达标格
 *       = "该段具备形成河流的条件"；达标区内取上游端点（8 邻达标格无更小 = 流域最上游）
 *       作源头，不再随机撒。陆地门槛 e≥SOURCE_MIN_E。</li>
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

    /** 源头最低陆地高度（e，seaE=0）：低于此不产生源头 */
    static final float SOURCE_MIN_E = 0.12f;
    /** 单条河最大步数（防死循环；正常路径几十~几百步） */
    static final int MAX_STEPS = 4096;
    /** 源头判定区外扩（数据区 ± 此值）：消除相邻 tile 判定区差集内的源头不对称断裂 */
    static final int SOURCE_PAD = 128;

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
     * @param chainThr 源头达标链长（上游集水链 ≥ 此值 = 具备形成河流的条件；绝对阈值
     *                 保证跨 tile 判定一致——相对统计（mean/max）随 tile 区域不同会分叉）
     * @return 河流元数据（无河 → maxDischarge=0 → 上游不雕刻）
     */
    public static CellGenerator.RiverTileData trace(float[][] tile, int N, int originX, int originZ,
                                                    float seaE, WorldHeight worldHeight, int chainThr) {
        CellGenerator.RiverTileData rd = new CellGenerator.RiverTileData();
        boolean[][] core = rd.riverCore;
        float[][] dis = rd.discharge;
        float maxQ = 0f;

        // 源头 = 汇聚升级（2026-08-02）：不再随机撒源头。先算确定性汇聚场——
        // 每格 D8 流方向（8 邻最低，出界走 worldHeight）→ 沿"上游链"回溯到流域顶点，
        // 链长 L = 该格上游集水规模（液滴汇聚的确定性等价物：液滴也是沿地形下坡流，
        // 其路径覆盖 ≈ 上游集水；但液滴场每 tile 独立模拟 → 重叠区不一致，作源头
        // 判定必断裂——链长场纯世界坐标函数 → 跨 tile 完全一致）。
        // 判定区 = 数据区 + SOURCE_PAD 外扩（相邻 tile 差集内的源头也参与判定，
        // 消除"对方提取区缺河"断裂；外扩区链段出界回溯用 worldHeight，与数据区
        // base 同值 → 任何 tile 计算结果相同）。
        // 达标格 L ≥ chainThr（绝对阈值，避免每 tile 相对统计分叉）= "该段具备形成
        // 河流的条件"；达标区内取上游端点（8 邻达标格无更小 L = 流域最上游）作源头。
        if (chainThr > 0) {
            int pad = SOURCE_PAD;
            int W = N + 2 * pad;
            // 1) 流方向（-1 = 局部最低/无下游；方向与高度单调递减 → 无环）
            byte[] flow = new byte[W * W];
            for (int z = 0; z < W; z++) {
                for (int x = 0; x < W; x++) {
                    int wx = originX + x - pad, wz = originZ + z - pad;
                    float h = heightAtWorld(tile, N, originX, originZ, worldHeight, wx, wz);
                    byte best = -1;
                    float bestH = h;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dz == 0) continue;
                            float nh = heightAtWorld(tile, N, originX, originZ, worldHeight,
                                    wx + dx, wz + dz);
                            if (nh < bestH) { bestH = nh; best = (byte) ((dz + 1) * 3 + (dx + 1)); }
                        }
                    }
                    flow[z * W + x] = best;
                }
            }
            // 2) 上游链长 L（回溯到顶点；出界链段用 worldHeight 采样 → 跨 tile 一致）
            int[] chainLen = new int[W * W];
            for (int z = 0; z < W; z++) {
                for (int x = 0; x < W; x++) {
                    int l = 0, cz = z, cx = x;
                    while (l < 1024) {
                        int upZ = -1, upX = -1;
                        for (int dz = -1; dz <= 1 && upZ < 0; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                if (dx == 0 && dz == 0) continue;
                                int nz = cz + dz, nx = cx + dx;
                                byte f = flowOf(flow, nz, nx, W, tile, N, originX, originZ, worldHeight);
                                // 邻格流向 (cz,cx) → (dz,dx) 的反方向 = (-dz,-dx)
                                if (f == (byte) ((1 - dz) * 3 + (1 - dx))) { upZ = nz; upX = nx; break; }
                            }
                        }
                        if (upZ < 0) break;   // 流域顶点（无上游来水）
                        cz = upZ; cx = upX;
                        l++;
                    }
                    chainLen[z * W + x] = l;
                }
            }
            // 3) 达标 + 上游端点 → 追踪（世界坐标起点，出界段由 traceOne 的 worldHeight 流）
            for (int z = 0; z < W; z++) {
                for (int x = 0; x < W; x++) {
                    int l = chainLen[z * W + x];
                    if (l < chainThr) continue;
                    int wx = originX + x - pad, wz = originZ + z - pad;
                    if (heightAtWorld(tile, N, originX, originZ, worldHeight, wx, wz) < SOURCE_MIN_E) continue;
                    boolean endpoint = true;
                    for (int dz = -1; dz <= 1 && endpoint; dz++) {
                        int nz = z + dz;
                        if (nz < 0 || nz >= W) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dz == 0) continue;
                            int nx = x + dx;
                            if (nx < 0 || nx >= W) continue;
                            int nl = chainLen[nz * W + nx];
                            if (nl >= chainThr && nl < l) { endpoint = false; break; }
                        }
                    }
                    if (endpoint) {
                        traceOne(tile, N, originX, originZ, wx, wz, seaE, worldHeight, core, dis);
                    }
                }
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

    /** 判定区格点流方向：判定区内读数组（快），超出判定区现算（8 邻最低，worldHeight 采样）→ 跨 tile 一致。 */
    private static byte flowOf(byte[] flow, int z, int x, int W, float[][] tile, int N,
                               int originX, int originZ, WorldHeight wh) {
        if (z >= 0 && z < W && x >= 0 && x < W) return flow[z * W + x];
        int wx = originX + x - SOURCE_PAD, wz = originZ + z - SOURCE_PAD;
        float h = wh.heightAt(wx, wz);
        byte best = -1;
        float bestH = h;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                float nh = wh.heightAt(wx + dx, wz + dz);
                if (nh < bestH) { bestH = nh; best = (byte) ((dz + 1) * 3 + (dx + 1)); }
            }
        }
        return best;
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
