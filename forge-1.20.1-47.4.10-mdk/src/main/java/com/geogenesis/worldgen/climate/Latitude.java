package com.geogenesis.worldgen.climate;

/**
 * 纬度工具（保留旧 API 契约）。
 */
public final class Latitude {

    private Latitude() {}

    private static final double DEFAULT_SCALE = 5000.0;

    /** 将世界 Z 坐标映射到纬度 [0,1]（赤道→极地） */
    public static double latitude01(double worldZ, double scale) {
        double t = Math.abs(worldZ) / scale;
        return Math.min(t, 1.0);
    }

    /** 默认尺度的纬度 */
    public static double latitude01(double worldZ) {
        double t = Math.abs(worldZ) / DEFAULT_SCALE;
        return Math.min(t, 1.0);
    }
}
