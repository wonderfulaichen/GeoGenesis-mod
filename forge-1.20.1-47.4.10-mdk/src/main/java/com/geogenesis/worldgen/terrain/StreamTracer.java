package com.geogenesis.worldgen.terrain;

import java.util.HashSet;

/**
 * 河流追踪器（2026-08-01，两套粒子系统之「河流粒子」）。
 *
 * <p>与液滴侵蚀彻底分离：本类不改地形，只追踪河道。</p>
 * <ol>
 *   <li><b>源头</b>：世界坐标粗网格（SOURCE_GRID=40 块）+ 确定性抖动，相对高点判定
 *       （e≥SOURCE_MIN_E 且 中心−8 邻均值 ≥SOURCE_PROMINENCE，邻格采样间距
 *       SOURCE_SAMPLE_R=10 块 → 10 块尺度凸起，宏观坡面免疫；只用世界坐标高度函数）
 *       → 源头由地形曲率决定，山地密/平原稀，且跨 tile 逐格一致。</li>
 *   <li><b>追踪</b>：移动机制与液滴侵蚀（ErosionEngine.simulateDrop）<b>同源</b>——连续坐标、
 *       3×3 Sobel 梯度方向 + 惯性、平坦区 hash 片流漫游、重力速度步进（≤1.5 格）。
 *       与液滴唯一区别：<b>不衰减</b>（无蒸发/沉积/寿命）且<b>方向惯性大</b>
 *       （RIVER_INERTIA=0.35：水动量保留，冲过洼地爬盆周 → 溢出成真河；液滴 0.005
 *       是侵蚀刻画需要）。每格标 riverCore + discharge++（=上游源头数）。
 *       终止：h&lt;seaE 入海 / visited 绕圈（闭合无出口盆地 = 真内流湖）/ MAX_STEPS 兜底。</li>
 *   <li><b>汇流涌现</b>：多源头路径重叠 → dis 累积 = 上游源头数 → 主干高、支流低，无阈值参数。</li>
 * </ol>
 *
 * <p><b>确定性</b>：决策只依赖 tile 场（世界坐标对齐、跨 tile 重叠区一致）+ 世界坐标 hash。
 * 源头格按世界坐标 floorDiv 对齐 → 相邻 tile 重叠区源头集合与路径相同 → 提取区河道无缝。</p>
 *
 * <p><b>跨 tile 一致（2026-08-02）</b>：片流 hash 必须用<b>世界坐标</b>——液滴的 hash 用 tile
 * 局部坐标（侵蚀场无需跨 tile 一致），但河流标记在相邻 tile 重叠区必须相同 → 绝对坐标 hash。</p>
 *
 * <p><b>源头粒子制 v2（2026-08-02，方案已确认 + 探针两轮调参）</b>：撒点改为<b>地形决定的
 * 源头位置</b>——世界坐标粗网格上判定「相对高点」（只依赖世界坐标高度函数，不依赖 tile
 * 数据区）→ 撒点集合跨 tile 天然一致，汇聚场随之一致。
 * 实测迭代：严格 3×3 局部最大在平滑低地世界概率≈0（源头 0/tile）→ 改为「相对高程门槛」
 * （中心 − 8 邻均值 ≥SOURCE_PROMINENCE，采样间距 10 块），源头密度由曲率自然决定。
 * 源头资格 = 汇聚阈值：trace 完成后沿路径找第一个 dis≥CONFLUENCE_THRESHOLD 的格，
 * 从那里开始标记 riverCore（上游细流不显，河从「汇聚成立处」开始）。</p>
 */
public final class StreamTracer {

    /** 源头粗网格间距（世界块）：调大 → 河更稀（2026-08-02 实测 56 在低地世界源头 ~0-1/tile 过稀 → 40） */
    static final int SOURCE_GRID = 40;
    /** 汇聚阈值（路径重叠计数 = 上游源头数）：≥此才标 riverCore 成河（1=全标太密，3=折中） */
    static final float CONFLUENCE_THRESHOLD = 3f;
    /** 源头最低陆地高度（e，seaE=0）：低于此不产生源头（2026-08-02 实测 0.12 滤掉大半低地 → 0.05） */
    static final float SOURCE_MIN_E = 0.05f;
    /** 源头相对高程门槛（e）：中心 − 8 邻均值 ≥ 此值才算源头（凸曲率判定，宏观坡面免疫） */
    static final float SOURCE_PROMINENCE = 0.005f;
    /** 源头判定邻格采样间距（块）：1 块间距被宏观坡面淹没（平滑场局部最大≈0 概率）→ 10 块尺度 */
    static final int SOURCE_SAMPLE_R = 10;
    /**
     * 源头最小间距（块）：抖动（0~0.7×GRID 单向偏移）让相邻网格源头最近只差
     * 40−28=12 块 → 两条河几乎同点 → 路径重合 + dis 虚高（汇聚计数失真）。
     * 已选源头两两距离 < 此值 → 合并（丢弃后到者；循环顺序固定 → 跨 tile 一致）。
     */
    static final float SOURCE_MIN_GAP = 14f;
    /** 单条河最大步数（防死循环；正常路径几十~几百步） */
    static final int MAX_STEPS = 4096;

    // 移动常数与 ErosionEngine.simulateDrop 对齐（改动必须两边同步）：
    /** 方向惯性（越小越贴梯度；液滴同款 0.005） */
    private static final float INERTIA = 0.005f;
    /**
     * 河流方向惯性基准（2026-08-02 第四轮调参终值：速度相关，见 traceOne 方向更新）。
     * 迭代史：0.005（液滴同款）→ 洼地紧贴梯度震荡 → visited 绕圈困死（用户实机
     * 「困死小局部」）；0.35 恒定 → 冲过洼地 ✓ 但下坡恒动量 → 跑直线 + 翻越鞍部/山脊
     * → 河道中段爬升鼓包（用户实机「河道鼓起」）。
     * 物理正解 = 动量守恒：惯性 ∝ 速度（ie = RIVER_INERTIA·spd/SPD_CAP）——冲得快保留
     * 动量（冲过洼地，VISITED=0），爬坡减速惯性衰减 → 贴梯度转向（不翻顶不鼓包）。
     * 探针实测（seed 12345 提取区，鼓包=河道局部最大格数）：
     *   0.30→5 / 0.20→3 / 0.15→1 / 0.08→0，VISITED 恒 0（n=110 全入海），avgSteps≈126。
     * 0.08 河最贴地形（蜿蜒）、鼓包归零——用户实机拍板定稿。
     */
    static final float RIVER_INERTIA = 0.08f;
    /** 重力加速度（速度累积，液滴同款 2.5） */
    private static final float GRAVITY = 2.5f;
    /** 速度上限（每步最大步长，液滴同款 1.5 格） */
    private static final float SPD_CAP = 1.5f;

    private StreamTracer() {}

    // 终止原因回归统计（探针监控用）：0=入海 1=visited 绕圈 2=MAX_STEPS 3=spd0。
    // 2026-08-02 RIVER_INERTIA=0.35 后 VISITED 应≈0（动量冲过洼地 → 爬盆周 → 入海长河；
    // 仅闭合无出口盆地 = 真内流湖残留）。若回归 → 检查惯性/visited 逻辑。
    private static final int[] TERM = new int[4];
    private static int TERM_STEPS = 0, TERM_N = 0;
    static void resetTermStats() { for (int i = 0; i < 4; i++) TERM[i] = 0; TERM_STEPS = 0; TERM_N = 0; }
    static String termStats() {
        return String.format("SEA=%d VISITED=%d MAX_STEPS=%d SPD0=%d | avgSteps=%d (n=%d)",
                TERM[0], TERM[1], TERM[2], TERM[3],
                TERM_N > 0 ? TERM_STEPS / TERM_N : 0, TERM_N);
    }

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

        // 源头格范围覆盖 tile 全域 + 5×SOURCE_GRID：源头集合由世界坐标判定（局部高点），
        // 对任何 tile 完全相同——K 只决定「河长覆盖」：源头距对方提取区 ≤K×40 块时，
        // 两侧都追踪同一源头 → 提取区标记一致。K=5=200 块 ≈ 超长河上限，实机不可见。
        int gx0 = Math.floorDiv(originX - 5 * SOURCE_GRID, SOURCE_GRID);
        int gx1 = Math.floorDiv(originX + N - 1 + 5 * SOURCE_GRID, SOURCE_GRID);
        int gz0 = Math.floorDiv(originZ - 5 * SOURCE_GRID, SOURCE_GRID);
        int gz1 = Math.floorDiv(originZ + N - 1 + 5 * SOURCE_GRID, SOURCE_GRID);

        // ★ 2026-08-09 无伤优化：源头候选预筛并行化——isLocalHigh 只读世界坐标高度函数
        //   （纯确定性），每格独立 → 并行写入 boolean[]；合并（近距去重）+ traceOne 保持
        //   主线程串行、循环顺序固定 → 结果与串行逐点一致。
        int gridW = gx1 - gx0 + 1;
        int gridH = gz1 - gz0 + 1;
        int gridCount = gridW * gridH;
        boolean[] localHigh = new boolean[gridCount];
        int lhRowsPerTask = Math.max(1, gridH / com.geogenesis.worldgen.terrain.CellGenerator.TILE_PARALLELISM);
        int lhTasks = (gridH + lhRowsPerTask - 1) / lhRowsPerTask;
        java.util.concurrent.CountDownLatch lhLatch = new java.util.concurrent.CountDownLatch(lhTasks);
        for (int lt = 0; lt < lhTasks; lt++) {
            final int lz0 = lt * lhRowsPerTask, lz1 = Math.min(gridH, lz0 + lhRowsPerTask);
            com.geogenesis.worldgen.terrain.CellGenerator.TILE_SAMPLER.execute(() -> {
                try {
                    for (int gz = gz0 + lz0; gz < gz0 + lz1; gz++) {
                        for (int gx = gx0; gx <= gx1; gx++) {
                            long gh = hash(gx * 31 + 7, gz * 73 + 13);
                            int cx = gx * SOURCE_GRID + (int) (((gh >>> 16) & 0xFFFF) / 65536f * SOURCE_GRID * 0.7f);
                            int cz = gz * SOURCE_GRID + (int) (((gh >>> 32) & 0xFFFF) / 65536f * SOURCE_GRID * 0.7f);
                            localHigh[(gz - gz0) * gridW + (gx - gx0)] = isLocalHigh(worldHeight, cx, cz);
                        }
                    }
                } finally {
                    lhLatch.countDown();
                }
            });
        }
        try { lhLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 已选源头（世界坐标）——近距合并（2026-08-02：抖动碰撞的源头两两距离可近至 12 块；
        // 合并判定纯世界坐标 + 循环顺序固定 → 跨 tile 一致）
        int[] selX = new int[512], selZ = new int[512];
        int selN = 0;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                if (!localHigh[(gz - gz0) * gridW + (gx - gx0)]) continue;
                long gh = hash(gx * 31 + 7, gz * 73 + 13);
                int cx = gx * SOURCE_GRID + (int) (((gh >>> 16) & 0xFFFF) / 65536f * SOURCE_GRID * 0.7f);
                int cz = gz * SOURCE_GRID + (int) (((gh >>> 32) & 0xFFFF) / 65536f * SOURCE_GRID * 0.7f);
                boolean dup = false;
                for (int i = 0; i < selN; i++) {
                    int dx = cx - selX[i], dz = cz - selZ[i];
                    if (dx * dx + dz * dz < SOURCE_MIN_GAP * SOURCE_MIN_GAP) { dup = true; break; }
                }
                if (dup) continue;                              // 与已选源头过近 → 合并（先到先得）
                selX[selN] = cx; selZ[selN] = cz; selN++;
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
        // 跨格路径点（世界坐标）：追踪时只累加 dis，core 标记在汇聚阈值判定后统一进行
        int[] pathX = new int[MAX_STEPS], pathY = new int[MAX_STEPS];
        int pathN = 0;
        HashSet<Long> visited = new HashSet<>(256);
        int term = 0;
        for (int st = 0; st < MAX_STEPS; st++) {
            int ix = (int) posX, iy = (int) posY;
            float h = heightAtWorld(tile, N, originX, originZ, worldHeight, ix, iy);
            if (h < seaE) { term = 0; break; }              // 入海终止（海格不标记）
            // 仅跨格时记录 visited + 标记：spd 震荡在 ~0.9-1.1（步长可能 <1 格），
            // 亚格步长下 (int)pos 不变 → 整格 visited 会把"格内蠕动"误判为绕圈 → 河几步就死。
            // 跨格才 add → 真回到旧格（盆地绕圈）仍被检测（闭合洼地 = 内流湖接水）。
            // 注意：dis 是 tile 局部索引，累加前必须转局部坐标（世界坐标会越界 128×128）
            if (ix != prevX || iy != prevY) {
                long vk = (((long) ix) << 32) | (iy & 0xFFFFFFFFL);
                if (!visited.add(vk)) { term = 1; break; }
                pathX[pathN] = ix; pathY[pathN] = iy; pathN++;
                if (pathN == 1) addDisCell(ix - originX, iy - originZ, dis);
                else addDisSegment(pathX[pathN - 2] - originX, pathY[pathN - 2] - originZ,
                        ix - originX, iy - originZ, dis);
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
                // 速度相关惯性：冲得快（spd→SPD_CAP）惯性→RIVER_INERTIA（保留动量冲过洼地）；
                // 爬坡减速（spd↓）惯性→0（贴梯度转向——翻不过的坡回头成湖，不翻顶不鼓包）
                float ie = RIVER_INERTIA * Math.min(1f, spd / SPD_CAP);
                dirX = dirX * ie - gx * (1f - ie);
                dirY = dirY * ie - gy * (1f - ie);
            }
            float dlen = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (dlen < 1e-12f) { term = 3; break; }          // 理论不可达（片流必有方向）
            dirX /= dlen; dirY /= dlen;

            // 重力速度步进（液滴同款：先抬速，再按新位落差修正，NaN 守卫）
            spd = (float) Math.sqrt(spd + Math.abs(h - h0) * GRAVITY);
            float step = Math.min(spd, SPD_CAP);
            float npx = posX + dirX * step, npy = posY + dirY * step;
            float nH0 = bilinearAt(tile, N, originX, originZ, worldHeight, npx, npy);
            float dh = nH0 - h0;
            float speedSq = spd * spd + dh * GRAVITY;
            spd = speedSq > 1e-12f ? (float) Math.sqrt(speedSq) : 0.1f;
            if (spd <= 0f) { term = 3; break; }
            posX = npx; posY = npy;
        }
        TERM[term]++; TERM_STEPS += Math.min(pathN, MAX_STEPS); TERM_N++;
        // 汇聚阈值：沿路径找第一个 dis≥阈值 的界内格，从那里起标记 core（上游细流不显）。
        // dis 已在追踪时全量累加（含全路径），V 形雕刻深度归一化不受影响。
        int k = pathN;
        for (int i = 0; i < pathN; i++) {
            int lx = pathX[i] - originX, ly = pathY[i] - originZ;
            if (lx >= 0 && lx < N && ly >= 0 && ly < N && dis[ly][lx] >= CONFLUENCE_THRESHOLD) {
                k = i; break;
            }
        }
        for (int i = k; i < pathN; i++) {
            int lx = pathX[i] - originX, ly = pathY[i] - originZ;
            if (i == k) markCoreCell(lx, ly, core);
            else markCoreSegment(pathX[i - 1] - originX, pathY[i - 1] - originZ, lx, ly, core);
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

    /**
     * 相对高点判定（源头撒点，2026-08-02 源头粒子制 v2）。
     * 只用世界坐标高度函数（L1 terrainE），不读 tile 数组 → 判定对任何 tile 逐格相同，
     * 撒点集合跨 tile 天然一致。
     *
     * <p>语义（2026-08-02 实测两次迭代）：① 严格 3×3 局部最大在平滑低地世界
     * （宏观缓坡）概率≈0 → 源头 0/tile；② 改为「相对高程门槛」：中心 − 8 邻均值
     * （采样间距 SOURCE_SAMPLE_R=10 块）≥ SOURCE_PROMINENCE → 只响应 10 块尺度的
     * 局部凸起（小丘/山脊/峰），线性坡上中心=均值天然不过（宏观坡面免疫），
     * 凹谷为负更不过。密度由地形曲率自然决定（山地密、平原稀——符合地理学），
     * 门槛可调控制密度。</p>
     */
    static boolean isLocalHigh(WorldHeight wh, int wx, int wz) {
        float h = wh.heightAt(wx, wz);
        if (h < SOURCE_MIN_E) return false;                     // 低地/海床不算源头
        float sum = 0f;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                sum += wh.heightAt(wx + dx * SOURCE_SAMPLE_R, wz + dz * SOURCE_SAMPLE_R);
            }
        }
        return h - sum / 8f >= SOURCE_PROMINENCE;               // 凸曲率判定
    }

    /** 累加单格汇聚量（仅数据区内）。 */
    private static void addDisCell(int x, int y, float[][] dis) {
        if (x >= 0 && x < dis[0].length && y >= 0 && y < dis.length) {
            dis[y][x] += 1f;
        }
    }

    /** DDA 累加线段汇聚量（步长≤1.5 可跨整数格，补中间格保证河道连续）。 */
    private static void addDisSegment(int x0, int y0, int x1, int y1, float[][] dis) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        for (int s = 0; s <= steps; s++) {
            int x = x0 + (dx * s) / steps;
            int y = y0 + (dy * s) / steps;
            if (x >= 0 && x < dis[0].length && y >= 0 && y < dis.length) {
                dis[y][x] += 1f;
            }
        }
    }

    /** 标记单格 riverCore（仅数据区内；不碰 dis——汇聚量已在追踪时全量累加）。 */
    private static void markCoreCell(int x, int y, boolean[][] core) {
        if (x >= 0 && x < core[0].length && y >= 0 && y < core.length) {
            core[y][x] = true;
        }
    }

    /** DDA 标记 riverCore 线段（达标点起，河道连续）。 */
    private static void markCoreSegment(int x0, int y0, int x1, int y1, boolean[][] core) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        for (int s = 0; s <= steps; s++) {
            int x = x0 + (dx * s) / steps;
            int y = y0 + (dy * s) / steps;
            if (x >= 0 && x < core[0].length && y >= 0 && y < core.length) {
                core[y][x] = true;
            }
        }
    }

    static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }
}
