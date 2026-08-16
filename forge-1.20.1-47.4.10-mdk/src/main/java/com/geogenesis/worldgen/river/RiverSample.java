package com.geogenesis.worldgen.river;

/**
 * 河流雕刻采样结果（确定性几何河网 · Phase 2/4）。
 *
 * <p>{@code sampleRiver(wx,wz)} 的返回值：纯函数（只读河网），同一世界坐标
 * 任意线程/chunk 同结果 → 结构性无缝。Y 字段为块语义（world Y）。</p>
 *
 * @param inChannel   是否在河道（宽度 + 岸坡范围内）
 * @param waterSurfaceY 水面 Y（主河 = 全局海平面；支流 = 节点抬升插值）
 * @param bedY        河床 Y（雕刻目标底；瀑布落点已含跌水潭加深）
 * @param bankBlend   岸坡混合 [0,1]：0 = 河心（河床），1 = 岸顶（原地形）
 * @param distance    到河心线距离（wu）
 * @param width       河道半宽（wu）
 * @param baseWidth   段级基准半宽（wu；岸坡比例基准，DW baseWidth 语义）
 * @param flowDirX    流向 X（单位向量，沿下游方向）
 * @param flowDirZ    流向 Z
 * @param discharge   流量（该段 1 + 汇入支流数；RIVER_NETWORK 图层用）
 * @param type        段类型（REACH/MOUTH = 主河，TRIBUTARY = 支流；null = 无段）
 */
public record RiverSample(
        boolean inChannel,
        double waterSurfaceY,
        double bedY,
        double bankBlend,
        double distance,
        double width,
        double baseWidth,
        double flowDirX,
        double flowDirZ,
        double discharge,
        RiverSegmentType type) {

    /** 不在河道（统一哨兵，避免装箱） */
    public static final RiverSample NONE = new RiverSample(false, 0, 0, 1, 0, 0, 0, 1, 0, 0, null);
}
