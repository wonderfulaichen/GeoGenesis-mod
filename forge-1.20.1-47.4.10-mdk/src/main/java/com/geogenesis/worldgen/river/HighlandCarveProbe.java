package com.geogenesis.worldgen.river;

/**
 * 高地雕刻场景验证（2026-08-15 R17b + 2026-08-16 R18 高度门控）：
 * 手工构造 RiverSample，验证高地（200）上河心/岸坡雕刻结果：
 *  - 河心（d=0）：target ≈ 56±noise（深 4-8 块），不劈开 200 高地
 *  - 岸坡（d=w）：target ≈ 61（深 1 块）
 *  - 远处（d=w+riverHeight）：NONE（不雕刻）
 *  - ★ R18 主河高度门控（DW getRiverCarve）：h>90 → 深度×fade；h≥130 → NONE。
 *    200 高地主河段不再雕刻（"山顶大河"根因修复）。
 */
public final class HighlandCarveProbe {
    public static void main(String[] args) {
        // 构造样本：w=3, surf=62, bed=56, d 变化
        double w = 3.0;
        double surf = 62.0;
        double bed = 56.0;
        for (double h : new double[]{62, 65, 70, 90, 95, 110, 125, 130, 200}) {
            for (double d : new double[]{0, 1.5, 2.9, 3.0, 6.0, 12.0, 30.0}) {
                // record: (inChannel, waterSurfaceY, bedY, bankBlend, distance, width, baseWidth, flowDirX, flowDirZ, discharge, type)
                RiverSample rs = new RiverSample(true, surf, bed, 1.0, d, w, w, 1.0, 0.0, 1.0,
                    RiverSegmentType.REACH);
                RiverCarver.CarvedColumn cc = RiverCarver.carve(h, rs, 2048, 2048, 63.0);
                String r = cc.inChannel()
                    ? String.format("Y=%.1f water=%.1f wall=%s", cc.groundY(), cc.waterTopY(), cc.isWall())
                    : "NONE";
                System.out.printf("h=%3.0f d=%5.1f -> %s%n", h, d, r);
            }
            System.out.println();
        }
        // ★ R18 海洋浅槽验证：海床（h=30 < 63−3）上主河段贴海床浅槽、支流被拒
        RiverSample mainSea = new RiverSample(true, 62, 56, 1.0, 0, w, w, 1.0, 0.0, 1.0,
            RiverSegmentType.REACH);
        RiverSample tribSea = new RiverSample(true, 62, 56, 1.0, 0, w, w, 1.0, 0.0, 1.0,
            RiverSegmentType.TRIBUTARY);
        RiverCarver.CarvedColumn mc = RiverCarver.carve(30, mainSea, 2048, 2048, 63.0);
        RiverCarver.CarvedColumn tc = RiverCarver.carve(30, tribSea, 2048, 2048, 63.0);
        System.out.printf("ocean h=30 main d=0 -> %s (expect Y=28.0 water=63.0)%n",
            mc.inChannel() ? String.format("Y=%.1f water=%.1f", mc.groundY(), mc.waterTopY()) : "NONE");
        System.out.printf("ocean h=30 trib d=0 -> %s (expect NONE = tributary stays on land)%n",
            tc.inChannel() ? "CARVED" : "NONE");
    }
}
