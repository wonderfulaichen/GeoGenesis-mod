package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 游戏入口模拟探针（2026-08-15 R16b）：
 * 模拟 GeoGenesisGenerator.fillTerrainColumn 的调用链——
 *   rs.inChannel() → carve → cc.inChannel() 检查才应用雕刻结果。
 * 验证：无 NONE 塌陷（groundY 保持原地形）、无 -Infinity 水柱、海上河不筑墙。
 */
public final class EntryDiagProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);
        net.setWorldSeed(seed);

        int hits = 0, carved = 0, noneTrap = 0, infTrap = 0, seaWall = 0, total = 0;
        int skipLow = 0, skipHigh = 0, tribSea = 0, oceanTrough = 0;
        double seaLevel = net.seaLevelY();
        // 模拟 fillTerrainColumn：逐点采样 + carve + NONE 检查
        for (double z = -1024; z < 3072; z += 16) {
            for (double x = -1024; x < 3072; x += 16) {
                double h = gen.terrainEQuick(x, z) >= 0
                    ? gen.heightCurve().heightFromE(gen.terrainEQuick(x, z)) : 0;
                RiverSample rs = net.sampleRiver(x, z);
                total++;
                if (!rs.inChannel()) continue;
                hits++;
                boolean main = rs.type() == RiverSegmentType.REACH
                            || rs.type() == RiverSegmentType.MOUTH;
                RiverCarver.CarvedColumn cc = RiverCarver.carve(h, rs, x, z, seaLevel);
                // ★ R18：海洋列（h < seaLevel−3）主河走浅槽雕刻、支流必须被拒
                if (h < seaLevel - 3.0) {
                    if (cc.inChannel() && main) oceanTrough++;
                    else if (!cc.inChannel() && !main) tribSea++;
                }
                if (!cc.inChannel()) {
                    // skip 区（NONE）：游戏入口 R16b 后保留原地形 → 无塌陷
                    noneTrap++;
                    if (h < rs.waterSurfaceY() - 3) skipLow++;
                    else skipHigh++;
                } else {
                    carved++;
                    if (Double.isInfinite(cc.waterTopY())) infTrap++;
                    // 海上河墙检测：原地形 < 水面−4 但被抬到水面（skip 未覆盖）
                    if (h < rs.waterSurfaceY() - 4 && cc.groundY() > h + 1) seaWall++;
                }
            }
        }
        System.out.println("total=" + total + " hits=" + hits
            + " carved=" + carved + " skipped=" + noneTrap
            + " (skipLow=" + skipLow + " skipHigh=" + skipHigh + ")");
        System.out.println("infWater=" + infTrap + " seaWall=" + seaWall
            + " (expect 0 / 0)");
        System.out.println("R18 ocean: main trough=" + oceanTrough
            + " tribSeaRejected=" + tribSea
            + " (expect trough>0 = underwater valley, trib=0)");
    }
}
