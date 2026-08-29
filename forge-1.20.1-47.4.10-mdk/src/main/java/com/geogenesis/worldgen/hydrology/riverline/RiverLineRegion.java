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
        /**
         * 分支层级（Strahler 式）：1 = 直接入海/入湖的河（干流）；
         * n+1 = 汇入 n 级河的支流。用于诊断"分支的分支"是否生成
         * （层级 1 占绝对多数 = 只有干流没有支系；出现 3、4 级 = 树状水系成型）。
         */
        public final int level;

        public RiverPolyline(Node[] nodes, double[] surfaceY, double[] width,
                             double[] depth, int level) {
            this.nodes = nodes;
            this.surfaceY = surfaceY;
            this.width = width;
            this.depth = depth;
            this.level = level;
        }
    }

    /** 湖泊节点（河流终止于内流洼地）：展平为湖面 + 半径/淡出（PL-RGA outlet_local_minimum）。 */
    public static final class LakeNode {
        public final double x, z;     // 世界坐标（wu）
        public final double height;   // 湖面世界 Y（= 该节点 slope-drop 后河高）
        public LakeNode(double x, double z, double height) {
            this.x = x; this.z = z; this.height = height;
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
