package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement.Node;

import java.util.List;

/**
 * 一个 region 的河流网络（物理范式，2026-08-28 重写）。
 *
 * <p>河网从地形汇流场"流"出来：高位布源 → 沿 D8 下坡追踪 → 汇入已有河，
 * 形成树状水系。每条河 {@link RiverPolyline} 携带节点折线 + 逐节点
 * 水面（= 当地地表谷底，Streams 范式）+ 半宽 + 河深。</p>
 */
public final class RiverLineRegion {

    /** 一条河（支流或主流）：折线节点 + 逐节点水面/半宽/河深。 */
    public static final class RiverPolyline {
        public final Node[] nodes;
        public final double[] surfaceY;   // 水面世界 Y（= 当地地表谷底）
        public final double[] width;      // 半宽（block）：由汇流面积驱动
        public final double[] depth;      // 河深（block）
        /** 逐节点跌水落差（block）：0 = 普通河段；>0 = 本节点位于跌水潭侧，供水幕填充。 */
        public final double[] fallDrop;
        /**
         * 分支层级（Strahler 式）：1 = 直接入海/入湖的河（干流）；
         * n+1 = 汇入 n 级河的支流。用于诊断"分支的分支"是否生成
         * （层级 1 占绝对多数 = 只有干流没有支系；出现 3、4 级 = 树状水系成型）。
         */
        public final int level;
        /**
         * 逐节点湖面（2026-09-07 河成湖）：NaN = 普通河节点；有限值 = 该节点在
         * 低梯度"河成湖"段内，值为湖面高程（全段水平）。null = 本河未检测。
         */
        public final double[] lakeLevel;
        /**
         * 逐节点【build 时刻】地形 Y（2026-09-07 侵蚀修复配套）：水面 cap 用的正是
         * 这份 terrainY（sampleWu 含侵蚀 tile），而侵蚀 tile 的河网雕刻层随注册时序
         * 变化，探针事后重采样无法复现（WaterfallProbe 实测：事后 sampleWu 得
         * wellViolation=47，sample() 又与生产脱节）。存下 build 时刻值，探针即可
         * 精确复核"水面 ≤ 当时的地形"。null = 旧折线（未记录）。
         */
        public final double[] terrainY;

        public RiverPolyline(Node[] nodes, double[] surfaceY, double[] width,
                             double[] depth, double[] fallDrop, int level) {
            this(nodes, surfaceY, width, depth, fallDrop, level, null, null);
        }

        public RiverPolyline(Node[] nodes, double[] surfaceY, double[] width,
                             double[] depth, double[] fallDrop, int level, double[] lakeLevel) {
            this(nodes, surfaceY, width, depth, fallDrop, level, lakeLevel, null);
        }

        public RiverPolyline(Node[] nodes, double[] surfaceY, double[] width,
                             double[] depth, double[] fallDrop, int level, double[] lakeLevel,
                             double[] terrainY) {
            this.nodes = nodes;
            this.surfaceY = surfaceY;
            this.width = width;
            this.depth = depth;
            this.fallDrop = fallDrop;
            this.level = level;
            this.lakeLevel = lakeLevel;
            this.terrainY = terrainY;
        }
    }

    /**
     * 湖泊节点：湖面展平 + 半径/淡出（PL-RGA outlet_local_minimum）。
     *
     * <p><b>2026-09-07 起改由 priority-flood 洼地提取生成</b>（此前只在"河 trace 撞
     * 内流洼地"时按河节点水面造一个，实测 0 个——本地形闭合洼地极少）：</p>
     * <ul>
     *   <li>{@code height} = 洼地溢出高程（spill），不是河节点水面——只有取 spill
     *       才能保证"湖面 = 溢出口坎高"，与下游续流河水面连续；</li>
     *   <li>{@code radius} = 按洼地面积换算的半径（wu），取代原来的全局 lakeRadius；</li>
     *   <li>{@code depth} = 最大水深（block），仅用于诊断——<b>湖不挖地</b>：
     *       洼地天然低于 spill，雕刻只铺水面、保留自然盆底。</li>
     * </ul>
     */
    public static final class LakeNode {
        public final double x, z;     // 世界坐标（wu，湖心 = 洼地格形心）
        public final double height;   // 湖面世界 Y（= 洼地溢出高程 spill，无侵蚀基线的坎高）
        public final double radius;   // 湖面半径（wu，按洼地面积换算）—— 仅诊断/包围盒，不用于命中
        public final double depth;    // 最大水深（block，诊断用）
        // ===== 逐格洼地轮廓（2026-09-09 B1：取代圆盘命中）=====
        /** 洼地格中心 wu（与 cellZ 平行数组）；null = 旧式（无轮廓，退化为圆盘）。 */
        public final double[] cellX;
        /** 洼地格中心 wu。 */
        public final double[] cellZ;
        /** 单格覆盖半宽（wu）= gridCell/2；格方块 = 中心 ± cellHalf。 */
        public final double cellHalf;
        // ===== 溢出口坎邻格（2026-09-09 侵蚀短板重算）=====
        //   rim = 湖盆外圈 8 邻中"无侵蚀 spill 高程附近"（fillE ≤ spill+0.05）的格 ——
        //   即真正挡水/溢出的【墙缺口】。侵蚀会削低这些坎 → 湖水位必须随之降低
        //   （短板效应：水从被削低的缺口漏走，旧 spill 包不住）。湖的真正水位 =
        //   min(原 spill, 这些 rim 格的侵蚀后高度)。
        /** 湖盆外圈溢出口坎格中心 wu（与 rimZ 平行）；null = 未记录（用原 spill）。 */
        public final double[] rimX;
        /** 湖盆外圈溢出口坎格中心 wu。 */
        public final double[] rimZ;
        /** 侵蚀后短板水位缓存（lazy，carver 湖分支算一次；NaN=未算）。跨 chunk 复用。 */
        public volatile double erodedSpill = Double.NaN;

        public LakeNode(double x, double z, double height, double radius, double depth) {
            this(x, z, height, radius, depth, null, null, 0.0, null, null);
        }

        public LakeNode(double x, double z, double height, double radius, double depth,
                        double[] cellX, double[] cellZ, double cellHalf) {
            this(x, z, height, radius, depth, cellX, cellZ, cellHalf, null, null);
        }

        public LakeNode(double x, double z, double height, double radius, double depth,
                        double[] cellX, double[] cellZ, double cellHalf,
                        double[] rimX, double[] rimZ) {
            this.x = x; this.z = z; this.height = height;
            this.radius = radius; this.depth = depth;
            this.cellX = cellX; this.cellZ = cellZ; this.cellHalf = cellHalf;
            this.rimX = rimX; this.rimZ = rimZ;
        }

        /** 是否有逐格溢出口坎（决定能否做侵蚀短板重算）。 */
        public boolean hasRim() { return rimX != null && rimX.length > 0; }

        /**
         * 侵蚀后短板水位：min(原 spill, 全部 rim 坎的侵蚀后高度)。
         *
         * <p>侵蚀把溢出口坎削低 → 水位随之降（水从低缺口漏走，包不住）；
         * 侵蚀把坎垫高 → 保持原 spill（水没到更高坎前不漫，内流加深）。
         * 只降不升：无侵蚀 spill 是上界。</p>
         *
         * @param erodedY 侵蚀后地面高度采样（如 CellGenerator.sampleWu(wx,wz).height）
         */
        public double erodedWaterLevel(java.util.function.ToDoubleBiFunction<Double, Double> erodedY) {
            double s = erodedSpill;
            if (!Double.isNaN(s)) return s;
            synchronized (this) {
                s = erodedSpill;
                if (!Double.isNaN(s)) return s;
                double level = height;
                if (rimX != null) {
                    for (int i = 0; i < rimX.length; i++) {
                        double rimH = erodedY.applyAsDouble(rimX[i], rimZ[i]);
                        level = Math.min(level, rimH);
                    }
                }
                erodedSpill = level;
                return level;
            }
        }

        /**
         * 该点是否落在本湖的【粗覆盖域】内（洼地格方块并集）。
         *
         * <p>这是"湖管不管这里"的粗判定，真正的淹水边界由落块侧的
         * {@code 侵蚀后 height < spill} 等高线决定 —— 因此域可以（也应该）比实际
         * 水面大：等高线落在域内部自然收束，不会在域边界出现硬切。</p>
         *
         * @param margin 格方块外扩（wu）：给岸线一点余量，避免岸线恰在格边界被截断
         */
        public boolean inDomain(double wx, double wz, double margin) {
            if (cellX == null || cellX.length == 0) return false;
            double r = cellHalf + margin;
            for (int i = 0; i < cellX.length; i++) {
                if (Math.abs(wx - cellX[i]) <= r && Math.abs(wz - cellZ[i]) <= r) return true;
            }
            return false;
        }

        /** 是否有逐格轮廓（false = 旧式圆盘湖，调用方需回退旧行为）。 */
        public boolean hasOutline() { return cellX != null && cellX.length > 0; }

        // ===== 侵蚀后淹水连通域（2026-09-09 物理级形状）=====
        //   旧的形状来自"无侵蚀洼地格方块并集"，与侵蚀后地形无关 → 水位按侵蚀后
        //   短板降下来后，盆底可能已被沉积垫高 → 水淹不到（实测整片 dry）。
        //   物理正确：湖 = 侵蚀后地形上【低于溢出坎且与盆底连通】的区域。
        //   用 BFS 从"侵蚀后盆底最低点"扩展得到（粗格分辨率，边界由落块侧
        //   block 级等高线精修）。
        /** 侵蚀后淹水粗格中心 wu；null=未算，长度 0=无淹水（湖被填平） */
        public volatile double[] floodX = null;
        public volatile double[] floodZ = null;
        /** 淹水粗格半宽（wu） */
        public volatile double floodHalf = 0.0;

        /**
         * 在【侵蚀后】地形上算湖的真正形状（BFS 连通域）。
         *
         * <p>从无侵蚀洼地格中"侵蚀后最低"者出发，4 邻扩展到所有
         * {@code 侵蚀后高度 < level − 0.5} 的粗格。这保证湖只淹【与盆底连通、
         * 且低于溢出坎】的地方 —— 不会淹到域外山谷，也不会因无侵蚀域与侵蚀后
         * 地形不匹配而整片漏淹。结果缓存（每湖只算一次，跨 chunk 复用）。</p>
         *
         * <p><b>越界判定（2026-09-09，用户"填水范围超出填充上限就不生成湖"）</b>：
         * 若 BFS 在【无侵蚀认领域 = 洼地格 + 2×gridCell】外仍探到低于水位的淹水格，
         * 说明实际洼地比提取时的无侵蚀域更大 → 若硬生成会在认领域边缘被截断
         * （用户实测"湖形不对称缺一块"）。此时返回 {@code floodOOB=true} 让调用方
         * 放弃该湖 —— 与其生成残缺湖，不如不生成。</p>
         *
         * @param erodedY 侵蚀后地面高度采样
         * @param level   侵蚀后短板水位（erodedWaterLevel 结果）
         * @param gridCell 粗格分辨率（wu）
         * @return true = 淹没区越出认领域（湖残缺，应放弃）
         */
        public boolean computeFlood(java.util.function.ToDoubleBiFunction<Double, Double> erodedY,
                                    double level, double gridCell) {
            if (floodX != null) return floodOOB;
            synchronized (this) {
                if (floodX != null) return floodOOB;
                if (cellX == null || cellX.length == 0) {
                    floodX = new double[0]; floodZ = new double[0];
                    return floodOOB = true;    // 无轮廓无从判 → 放弃
                }
                // 搜索窗：洼地格包围盒外扩 3 格（保证能走到坎外沿自然停住）
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (int i = 0; i < cellX.length; i++) {
                    minX = Math.min(minX, cellX[i]); maxX = Math.max(maxX, cellX[i]);
                    minZ = Math.min(minZ, cellZ[i]); maxZ = Math.max(maxZ, cellZ[i]);
                }
                int pad = 3;
                minX -= pad * gridCell; maxX += pad * gridCell;
                minZ -= pad * gridCell; maxZ += pad * gridCell;
                int nx = (int) Math.floor((maxX - minX) / gridCell) + 1;
                int nz = (int) Math.floor((maxZ - minZ) / gridCell) + 1;
                if (nx <= 0 || nz <= 0 || (long) nx * nz > 40000L) {
                    floodX = new double[0]; floodZ = new double[0];
                    return floodOOB = true;
                }
                // 种子 = 无侵蚀洼地格中【侵蚀后最低】者（盆底）
                double best = Double.MAX_VALUE;
                int si = 0, sj = 0;
                for (int i = 0; i < cellX.length; i++) {
                    double h = erodedY.applyAsDouble(cellX[i], cellZ[i]);
                    if (h < best) {
                        best = h;
                        si = (int) Math.round((cellX[i] - minX) / gridCell);
                        sj = (int) Math.round((cellZ[i] - minZ) / gridCell);
                    }
                }
                // 盆底都被垫到水位以上 → 湖被侵蚀填平，物理上就该消失
                if (best >= level - 0.5) {
                    floodX = new double[0]; floodZ = new double[0];
                    return floodOOB = false;    // 消失 ≠ 残缺，不必弃湖
                }
                si = Math.max(0, Math.min(nx - 1, si));
                sj = Math.max(0, Math.min(nz - 1, sj));
                boolean[] seen = new boolean[nx * nz];
                java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
                int sIdx = sj * nx + si;
                q.add(sIdx); seen[sIdx] = true;
                java.util.List<double[]> flood = new java.util.ArrayList<>();
                int[] ddx = {1, -1, 0, 0}, ddz = {0, 0, 1, -1};
                // 认领域边界（wu，绝对坐标）：洼地格外扩 2×gridCell（与 sampleRegion 一致）
                double claimLoX = Double.MAX_VALUE, claimHiX = -Double.MAX_VALUE;
                double claimLoZ = Double.MAX_VALUE, claimHiZ = -Double.MAX_VALUE;
                for (int i = 0; i < cellX.length; i++) {
                    claimLoX = Math.min(claimLoX, cellX[i]);
                    claimHiX = Math.max(claimHiX, cellX[i]);
                    claimLoZ = Math.min(claimLoZ, cellZ[i]);
                    claimHiZ = Math.max(claimHiZ, cellZ[i]);
                }
                double claimR = gridCell * 2.0;
                claimLoX -= claimR; claimHiX += claimR;
                claimLoZ -= claimR; claimHiZ += claimR;
                boolean oob = false;
                while (!q.isEmpty()) {
                    int cur = q.poll();
                    int ci = cur % nx, cj = cur / nx;
                    double wx = minX + ci * gridCell, wz = minZ + cj * gridCell;
                    // 该淹水格若落在认领域外 → 实际湖比认领域大 → 注定残缺
                    if (wx < claimLoX || wx > claimHiX || wz < claimLoZ || wz > claimHiZ) {
                        oob = true;
                    }
                    flood.add(new double[]{wx, wz});
                    for (int d = 0; d < 4; d++) {
                        int ni = ci + ddx[d], nj = cj + ddz[d];
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                        int nIdx = nj * nx + ni;
                        if (seen[nIdx]) continue;
                        double h = erodedY.applyAsDouble(minX + ni * gridCell, minZ + nj * gridCell);
                        if (h < level - 0.5) { seen[nIdx] = true; q.add(nIdx); }
                    }
                }
                double[] fx = new double[flood.size()], fz = new double[flood.size()];
                for (int i = 0; i < flood.size(); i++) {
                    fx[i] = flood.get(i)[0]; fz[i] = flood.get(i)[1];
                }
                floodHalf = gridCell * 0.5;
                floodX = fx; floodZ = fz;      // volatile 安全发布
                // ★ 覆盖比例判据（2026-09-10，用户"水没铺满整个洼地"）：淹没区若不能
                //   覆盖绝大部分【无侵蚀洼地格】，说明侵蚀把洼地一侧盆底垫高到水位以上
                //   → 湖水铺不满洼地（一侧有、一侧无的残缺湖，实测 lake0：2 格洼地只淹
                //   1 格 → wetProd 92 却缺一半）。此时放弃该湖。
                //   阈值 75%（covered*4 >= cells*3）：岸线自然内缩最多放行 25% 边缘格
                //   露出；超过（≥1/4 洼地没淹）判残缺。cells=2 时需覆盖 2/2（75%×2=1.5
                //   → 需≥2），杜绝"一半洼地没水"的残缺湖。
                if (!oob) {
                    int covered = 0;
                    for (int i = 0; i < cellX.length; i++) {
                        if (inFloodLocal(cellX[i], cellZ[i], fx, fz, floodHalf)) covered++;
                    }
                    if (covered * 4 < cellX.length * 3) oob = true;
                }
                return floodOOB = oob;
            }
        }

        /** 局部 inFlood（computeFlood 内部用，flood 尚未发布时）。 */
        private static boolean inFloodLocal(double wx, double wz,
                                            double[] fx, double[] fz, double half) {
            if (fx == null || fx.length == 0) return false;
            for (int i = 0; i < fx.length; i++) {
                if (Math.abs(wx - fx[i]) <= half && Math.abs(wz - fz[i]) <= half) return true;
            }
            return false;
        }

        /** 该点是否落在【侵蚀后淹水连通域】内（粗格覆盖；边界由落块侧等高线精修）。 */
        public boolean inFlood(double wx, double wz) {
            double[] fx = floodX, fz = floodZ;
            if (fx == null || fx.length == 0) return false;
            double r = floodHalf;
            for (int i = 0; i < fx.length; i++) {
                if (Math.abs(wx - fx[i]) <= r && Math.abs(wz - fz[i]) <= r) return true;
            }
            return false;
        }

        /** 淹没区是否越出认领域（computeFlood 结果；true=湖残缺应放弃）。 */
        public volatile boolean floodOOB = false;
    }

    /**
     * 跨 region 出口种子：本 region 一条河流到网格边（= 缝外 margin）时的交接信息。
     *
     * <p>下游邻 region 在双-pass 构建时吸收此种子作为强制源，从 {@code wx,wz} 继续追踪，
     * 并携带 {@code accum}（上游汇流面积）/ {@code level}（分支层级）/ {@code surfaceY}
     * （上游尾节点水面），使河流跨缝连续、宽度不重置（无颈缩）。</p>
     */
    public static final class OutletSeed {
        public final int dRX, dRZ;        // 出口指向的邻 region 增量（如 +1,0 = +X 邻）
        public final double wx, wz;       // 出口（上游尾节点）世界坐标（wu）
        public final double accum;        // 上游尾节点汇流面积（wu²），下游续流起点
        public final double surfaceY;     // 上游尾节点水面世界 Y（下游续流首节点水面，保证连续）
        public final int level;           // 上游河分支层级（下游续流继承）
        public OutletSeed(int dRX, int dRZ, double wx, double wz,
                          double accum, double surfaceY, int level) {
            this.dRX = dRX; this.dRZ = dRZ;
            this.wx = wx; this.wz = wz;
            this.accum = accum; this.surfaceY = surfaceY; this.level = level;
        }
    }

    public final int rx, rz;
    public final List<RiverPolyline> rivers;
    public final List<LakeNode> lakes;     // 本 region 内流湖（可能为空）
    public final List<OutletSeed> outlets; // 出口种子（双-pass 交接用；pass-1 产物）
    public final boolean outletOcean;     // 本 region 是否有河到达海洋
    public final double dischargeArea;    // 主河出口汇流面积（诊断用）
    // 诊断计数（PL-RGA 对齐探针用）
    public final int sourceCount;         // 候选源数（e>min 且非边界）
    public final int rolledBack;          // 整条回滚数
    public final int joined;              // 就近汇入（树状）终止数

    public RiverLineRegion(int rx, int rz, List<RiverPolyline> rivers,
                           List<LakeNode> lakes, List<OutletSeed> outlets,
                           boolean outletOcean, double dischargeArea,
                           int sourceCount, int rolledBack, int joined) {
        this.rx = rx;
        this.rz = rz;
        this.rivers = List.copyOf(rivers);
        this.lakes = List.copyOf(lakes);
        this.outlets = List.copyOf(outlets);
        this.outletOcean = outletOcean;
        this.dischargeArea = dischargeArea;
        this.sourceCount = sourceCount;
        this.rolledBack = rolledBack;
        this.joined = joined;
    }

    /** 是否有任何水文特征（河或湖），采样时用于跳过空 region。 */
    public boolean hasWater() { return !rivers.isEmpty() || !lakes.isEmpty(); }
    /** 兼容别名（仅河）。 */
    public boolean hasRiver() { return !rivers.isEmpty(); }
}
