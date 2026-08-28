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

    public final int rx, rz;
    public final List<RiverPolyline> rivers;
    public final boolean outletOcean;     // 本 region 是否有河到达海洋
    public final double dischargeArea;    // 主河出口汇流面积（诊断用）

    public RiverLineRegion(int rx, int rz, List<RiverPolyline> rivers,
                           boolean outletOcean, double dischargeArea) {
        this.rx = rx;
        this.rz = rz;
        this.rivers = List.copyOf(rivers);
        this.outletOcean = outletOcean;
        this.dischargeArea = dischargeArea;
    }

    public boolean hasRiver() { return !rivers.isEmpty(); }
}
