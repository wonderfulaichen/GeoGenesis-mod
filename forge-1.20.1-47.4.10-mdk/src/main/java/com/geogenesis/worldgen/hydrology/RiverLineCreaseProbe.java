package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 折痕量化探针：在陆地河附近采样雕刻高度场，输出离散拉普拉斯峰值。
 *
 * <p>放射折痕（弯角平分线 / region 边界的属主硬切）会在 carved 场留下
 * 高曲率脊线 → 离散拉普拉斯出现明显尖峰。本探针在修复前后对比该峰值，
 * 数值显著下降即说明属主切换折痕被 smooth-min 消除。</p>
 */
public final class RiverLineCreaseProbe {
    private RiverLineCreaseProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 找一处陆地河点
        int rx = 0, rz = 0, found = 0;
        double seaLevel = terrain.heightCurve().seaLevelY();
        outer:
        for (int z = -512; z <= 512; z += 4) {
            for (int x = -512; x <= 512; x += 4) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, 1.0);
                if (s != null && s.distToCenter() <= s.width() * 0.8
                        && terrain.sampleWu(x, z).height >= seaLevel + 5.0) {
                    rx = x; rz = z; found = 1; break outer;
                }
            }
        }
        if (found == 0) { System.out.println("no land river found in scan"); return; }
        System.out.println("land river near (" + rx + "," + rz + ")");

        int R = 40;
        Map<Long, List<HydrologyBlockCarvedColumn>> cache = new HashMap<>();
        double[][] cv = new double[2 * R + 1][2 * R + 1];
        for (int j = 0; j <= 2 * R; j++) {
            for (int i = 0; i <= 2 * R; i++) {
                cv[i][j] = getCarved(cache, engine, terrain, rx - R + i, rz - R + j);
            }
        }

        // 原始地形（用于区分深河道与岸坡区）
        double[][] orig = new double[2 * R + 1][2 * R + 1];
        for (int j = 0; j <= 2 * R; j++) {
            for (int i = 0; i <= 2 * R; i++) {
                orig[i][j] = terrain.sampleWu(rx - R + i, rz - R + j).height;
            }
        }

        // 离散拉普拉斯：折痕处 |lap| 出现尖峰
        double maxLap = 0.0, maxAtX = 0, maxAtZ = 0;
        double maxLapBank = 0.0, maxBankX = 0, maxBankZ = 0;
        for (int j = 1; j < 2 * R; j++) {
            for (int i = 1; i < 2 * R; i++) {
                double lap = cv[i + 1][j] + cv[i - 1][j] + cv[i][j + 1] + cv[i][j - 1] - 4 * cv[i][j];
                if (Math.abs(lap) > maxLap) {
                    maxLap = Math.abs(lap); maxAtX = rx - R + i; maxAtZ = rz - R + j;
                }
                // 岸坡/谷壁区（非深河道，雕刻量 |Δ|<3）：隔离河道本身的陡曲率，专捕放射折痕
                if (Math.abs(orig[i][j] - cv[i][j]) < 3.0) {
                    if (Math.abs(lap) > maxLapBank) {
                        maxLapBank = Math.abs(lap); maxBankX = rx - R + i; maxBankZ = rz - R + j;
                    }
                }
            }
        }
        System.out.println("max |Laplacian(carved)| (whole)                = "
                + String.format("%.3f", maxLap) + " at (" + (int) maxAtX + "," + (int) maxAtZ + ")");
        System.out.println("max |Laplacian(carved)| (bank/valley, |Δ|<3)   = "
                + String.format("%.3f", maxLapBank) + " at (" + (int) maxBankX + "," + (int) maxBankZ + ")");
        System.out.println("（bank/valley 指标隔离了河道本身的陡曲率；放射折痕会在此出现尖峰，smooth-min 修复后应显著下降）");

        // 尖锐度指标：相邻格梯度方向夹角（度）。折痕=梯度方向突跳（大夹角）；
        // 平滑鼓包=梯度缓缓转动（小夹角）。这才直接反映"放射折痕"是否被消除。
        double maxAngle = 0.0;
        for (int j = 1; j < 2 * R - 1; j++) {
            for (int i = 1; i < 2 * R - 1; i++) {
                if (Math.abs(orig[i][j] - cv[i][j]) >= 3.0) continue;   // 仅岸坡/谷壁区
                double gx = (cv[i + 1][j] - cv[i - 1][j]) * 0.5;
                double gy = (cv[i][j + 1] - cv[i][j - 1]) * 0.5;
                int[][] nb = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
                for (int[] p : nb) {
                    int ni = p[0], nj = p[1];
                    if (ni < 1 || ni >= 2 * R - 1 || nj < 1 || nj >= 2 * R - 1) continue;
                    if (Math.abs(orig[ni][nj] - cv[ni][nj]) >= 3.0) continue;
                    double ngx = (cv[ni + 1][nj] - cv[ni - 1][nj]) * 0.5;
                    double ngy = (cv[ni][nj + 1] - cv[ni][nj - 1]) * 0.5;
                    double dot = gx * ngx + gy * ngy;
                    double cross = gx * ngy - gy * ngx;
                    double ang = Math.abs(Math.atan2(cross, dot)) * 180.0 / Math.PI;
                    if (ang > maxAngle) maxAngle = ang;
                }
            }
        }
        System.out.println("max gradient-angle jump (bank/valley, deg)      = "
                + String.format("%.2f", maxAngle));
        System.out.println("（该值直接衡量折痕尖锐度：旧代码在弯角处会显著更大；距离场平滑后应明显下降）");

        // 打印 carved 矩阵（步长 2，省篇幅）；河弯附近若见放射条纹即折痕残留
        System.out.println("-- carved matrix (step 2) --");
        for (int j = 0; j <= 2 * R; j += 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= 2 * R; i += 2) {
                sb.append(String.format("%6.1f", cv[i][j]));
            }
            System.out.println(sb);
        }
    }

    private static double getCarved(Map<Long, List<HydrologyBlockCarvedColumn>> cache,
                                    HydrologyExperimentEngine engine, CellGenerator terrain,
                                    int bx, int bz) {
        int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        List<HydrologyBlockCarvedColumn> cols = cache.get(key);
        if (cols == null) {
            double[] g = new double[256];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    g[lz * 16 + lx] = terrain.sampleWu(cx * 16 + lx, cz * 16 + lz).height;
                }
            }
            cols = HydrologyBlockCarver.carveChunk(engine, cx, cz, 1.0, g);
            cache.put(key, cols);
        }
        for (HydrologyBlockCarvedColumn c : cols) {
            if (c.blockX() == bx && c.blockZ() == bz) return c.carvedGroundY();
        }
        return terrain.sampleWu(bx, bz).height;
    }
}
