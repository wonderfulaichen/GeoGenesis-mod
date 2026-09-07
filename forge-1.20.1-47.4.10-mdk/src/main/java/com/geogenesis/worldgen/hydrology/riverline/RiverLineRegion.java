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
        public final double x, z;     // 世界坐标（wu）
        public final double height;   // 湖面世界 Y（= 洼地溢出高程 spill）
        public final double radius;   // 湖面半径（wu，按洼地面积换算）
        public final double depth;    // 最大水深（block，诊断用）

        public LakeNode(double x, double z, double height, double radius, double depth) {
            this.x = x; this.z = z; this.height = height;
            this.radius = radius; this.depth = depth;
        }
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
