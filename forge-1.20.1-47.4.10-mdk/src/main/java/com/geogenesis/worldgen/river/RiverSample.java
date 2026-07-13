package com.geogenesis.worldgen.river;

/**
 * 单点河流采样结果（不修改 Cell）。
 *
 * <p>由 {@link RiverField#sample(double, double)} 产出，供预览「水文」图层、
 * 生物群系判定与 {@link RiverField#apply} 的河谷刻蚀消费。
 */
public final class RiverSample {

    /** 是否在河网内（距最近河段 ≤ 河宽） */
    public boolean riverMask;
    /** 距河心归一化距离：0=河心，1=谷缘 */
    public double riverDistance = 1.0;
    /** 河流湿度（连续蓝边，0=无河，1=河心） */
    public double riverWetness;
    /** 上游汇入节点数（流量） */
    public int flowCount;
    /** 是否瀑布段（相邻河段端点落差 > waterfallDrop） */
    public boolean isWaterfall;
    /** 源头类型：0=无，1=溪源，2=山泉，3=源头湖 */
    public int sourceType;

    /** 空样本（无河） */
    public static RiverSample empty() {
        RiverSample r = new RiverSample();
        r.riverMask = false;
        r.riverDistance = 1.0;
        r.riverWetness = 0.0;
        r.flowCount = 0;
        r.isWaterfall = false;
        r.sourceType = 0;
        return r;
    }
}
