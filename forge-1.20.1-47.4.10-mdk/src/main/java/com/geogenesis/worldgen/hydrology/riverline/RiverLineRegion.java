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

        public RiverPolyline(Node[] nodes, double[] surfaceY, double[] width, double[] depth) {
            this.nodes = nodes;
            this.surfaceY = surfaceY;
            this.width = width;
            this.depth = depth;
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

    public final int rx, rz;
    public final List<RiverPolyline> rivers;
    public final List<LakeNode> lakes;     // 本 region 内流湖（可能为空）
    public final boolean outletOcean;     // 本 region 是否有河到达海洋
    public final double dischargeArea;    // 主河出口汇流面积（诊断用）
    // 诊断计数（PL-RGA 对齐探针用）
    public final int sourceCount;         // 候选源数（e>min 且非边界）
    public final int rolledBack;          // 整条回滚数
    public final int joined;              // 就近汇入（树状）终止数

    public RiverLineRegion(int rx, int rz, List<RiverPolyline> rivers,
                           List<LakeNode> lakes,
                           boolean outletOcean, double dischargeArea,
                           int sourceCount, int rolledBack, int joined) {
        this.rx = rx;
        this.rz = rz;
        this.rivers = List.copyOf(rivers);
        this.lakes = List.copyOf(lakes);
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
