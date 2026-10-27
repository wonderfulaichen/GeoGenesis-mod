package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/** 岸水高差候选：只产出河面下移量，不改地形、湖面、骨架或宽深剖面。 */
final class RiverBankHeight {
    static final double MAX_LOWERING = 1.0;
    static final double FLAT_SLOPE = 0.02;
    static final double FULL_SLOPE = 0.10;
    static final double SLOPE_RADIUS = 8.0;
    static final double WATER_TRANSITION = 16.0;

    private RiverBankHeight() { }

    /** 使用既有侵蚀网格；每个最终区域只计算一次。 */
    static double[] build(HydrologyPipeline.Result pipe) {
        boolean[] water = new boolean[pipe.nx() * pipe.nz()];
        mergeWater(water, pipe, 0, 0, pipe, 0, 0);
        return build(pipe, water);
    }

    static double[] build(HydrologyPipeline.Result pipe, boolean[] water) {
        int nx = pipe.nx(), nz = pipe.nz(), step = pipe.cellBlocks();
        double[] distance = waterDistance(water, new boolean[water.length], nx, nz, step);
        double[] result = new double[nx * nz];
        double[] bed = pipe.bed();
        int radius = Math.max(1, (int) Math.ceil(SLOPE_RADIUS / step));
        for (int x = 0; x < nx; x++) for (int z = 0; z < nz; z++) {
            int loX = Math.max(0, x - radius), hiX = Math.min(nx - 1, x + radius);
            int loZ = Math.max(0, z - radius), hiZ = Math.min(nz - 1, z + radius);
            double dx = (bed[hiX * nz + z] - bed[loX * nz + z])
                    / Math.max(step, (hiX - loX) * step);
            double dz = (bed[x * nz + hiZ] - bed[x * nz + loZ])
                    / Math.max(step, (hiZ - loZ) * step);
            result[x * nz + z] = lowering(Math.hypot(dx, dz), distance[x * nz + z]);
        }
        return result;
    }

    /** 合并与columnsFor借湖相同的世界格；只限制水面下切，不扩大湖域。 */
    static void mergeWater(boolean[] water, HydrologyPipeline.Result target, int ox, int oz,
                           HydrologyPipeline.Result source, int sx, int sz) {
        int nx = target.nx(), nz = target.nz(), step = target.cellBlocks();
        int radius = (int) Math.ceil(2.0 / step);
        for (int x = 0; x < nx; x++) for (int z = 0; z < nz; z++) {
            int i = (int) Math.round((ox + x * step - sx) / (double) source.cellBlocks());
            int j = (int) Math.round((oz + z * step - sz) / (double) source.cellBlocks());
            if (i < 0 || j < 0 || i >= source.nx() || j >= source.nz()) continue;
            int k = i * source.nz() + j;
            if (!source.lake()[k] && !source.sea()[k]) continue;
            // 将两格内同样保护，避免湖掩码的最近格/双线性边缘仍产生非零下切。
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                int xx = x + dx, zz = z + dz;
                if (xx >= 0 && zz >= 0 && xx < nx && zz < nz) water[xx*nz+zz] = true;
            }
        }
    }

    /** 坡度是地形梯度，不是流速；阈值是首版游戏尺度标定。 */
    static double lowering(double slope, double distanceToWater) {
        return MAX_LOWERING * smooth((slope - FLAT_SLOPE) / (FULL_SLOPE - FLAT_SLOPE))
                * smooth(distanceToWater / WATER_TRANSITION);
    }

    /** 不降低湖海权威平面；普通河面只下移，不被海平面反向抬高。 */
    static double surface(double original, double lowering, double seaLevel) {
        return Math.min(original, Math.max(seaLevel, original - lowering));
    }

    /** 8邻域两遍距离近似（block）；仅给临近湖海的下切量渐隐，不作河流拓扑判断。 */
    static double[] waterDistance(boolean[] lake, boolean[] sea, int nx, int nz, int step) {
        double[] d = new double[nx * nz];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        for (int k = 0; k < d.length; k++) if (lake[k] || sea[k]) d[k] = 0;
        double diagonal = Math.sqrt(2) * step;
        for (int pass = 0; pass < 2; pass++) {
            int direction = pass == 0 ? 1 : -1;
            for (int a = 0; a < nx; a++) for (int b = 0; b < nz; b++) {
                int x = pass == 0 ? a : nx - 1 - a;
                int z = pass == 0 ? b : nz - 1 - b;
                int k = x * nz + z, px = x - direction, pz = z - direction;
                if (pz >= 0 && pz < nz) d[k] = Math.min(d[k], d[x * nz + pz] + step);
                if (px < 0 || px >= nx) continue;
                d[k] = Math.min(d[k], d[px * nz + z] + step);
                if (z > 0) d[k] = Math.min(d[k], d[px * nz + z - 1] + diagonal);
                if (z + 1 < nz) d[k] = Math.min(d[k], d[px * nz + z + 1] + diagonal);
            }
        }
        return d;
    }

    private static double smooth(double v) {
        if (v <= 0) return 0;
        if (v >= 1) return 1;
        return v * v * (3 - 2 * v);
    }
}
