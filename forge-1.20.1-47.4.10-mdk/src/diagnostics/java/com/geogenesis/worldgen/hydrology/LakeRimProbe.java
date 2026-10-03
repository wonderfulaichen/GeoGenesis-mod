package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 湖「盆沿」测量（2026-10）—— 从湖心向 8 方向走，量地形在哪里升过湖水位。
 *
 * <h2>要回答</h2>
 * <p>若盆沿距离【远大于】湖的轮廓 bbox ⇒ 湖水位算高了（水本应更早被地形挡住）。</p>
 * <pre>  盆沿距离 ≈ 轮廓半径  ⇒ 水位合理
 *      盆沿距离 ≫ 轮廓半径  ⇒ 水位过高（把整条缓坡变成湖）</pre>
 *
 * <pre>{@code gradlew runLakeRimProbe [-PprobeArgs="seed regionX regionZ [idx] [maxDist]"]}</pre>
 */
public final class LakeRimProbe {

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int idx = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        int maxDist = args.length > 4 ? Integer.parseInt(args[4]) : 2000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine.network();
        double hs = tp.horizontalScale();

        RiverLineRegion r = net.region(rx, rz);
        if (idx >= r.lakes.size()) { System.out.println("idx 越界"); return; }
        RiverLineRegion.LakeNode ln = r.lakes.get(idx);
        double level = ln.height;
        int cells = ln.cellX == null ? 0 : ln.cellX.length;

        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int i = 0; i < cells; i++) {
            minX = Math.min(minX, ln.cellX[i] * hs); maxX = Math.max(maxX, ln.cellX[i] * hs);
            minZ = Math.min(minZ, ln.cellZ[i] * hs); maxZ = Math.max(maxZ, ln.cellZ[i] * hs);
        }
        double halfDiag = 0.5 * Math.hypot(maxX - minX, maxZ - minZ);

        System.out.printf("=== LakeRimProbe seed=%d r(%d,%d) 湖[%d] ===%n", seed, rx, rz, idx);
        System.out.printf("  湖心=(%.0f,%.0f)块  spill(水位)=%.2f  轮廓格=%d%n",
                ln.x * hs, ln.z * hs, level, cells);
        System.out.printf("  轮廓 bbox=%.0fx%.0f块  半对角=%.0f块%n", maxX - minX, maxZ - minZ, halfDiag);
        System.out.println();
        System.out.printf("  方向      盆沿距离(块)   盆沿地形   说明%n");

        double minRim = Double.MAX_VALUE;
        for (int[] d : DIRS) {
            double dx = d[0] / Math.hypot(d[0], d[1]), dz = d[1] / Math.hypot(d[0], d[1]);
            double rim = Double.NaN, rimH = Double.NaN;
            for (int t = 1; t <= maxDist; t += 4) {
                double x = (ln.x * hs + dx * t) / hs, z = (ln.z * hs + dz * t) / hs;
                double h = gen.sample(x, z).height;
                if (h >= level) { rim = t; rimH = h; break; }
            }
            String note;
            if (Double.isNaN(rim)) { note = "★ 到 " + maxDist + " 块仍未升过水位"; }
            else if (rim > halfDiag * 2) { note = "★ 盆沿远大于轮廓（水位可能过高）"; }
            else note = "正常";
            if (!Double.isNaN(rim)) minRim = Math.min(minRim, rim);
            System.out.printf("  (%2d,%2d)   %8s      %7s   %s%n",
                    d[0], d[1],
                    Double.isNaN(rim) ? ">" + maxDist : String.format("%.0f", rim),
                    Double.isNaN(rimH) ? "-" : String.format("%.1f", rimH), note);
        }
        System.out.printf("%n最近盆沿 = %s 块（轮廓半对角 %.0f 块）%n",
                Double.isNaN(minRim) ? "未找到" : String.format("%.0f", minRim), halfDiag);
        System.out.printf("  比值 = %s%n",
                Double.isNaN(minRim) ? "∞" : String.format("%.2f", minRim / Math.max(1, halfDiag)));
        System.out.println("判读：比值 ≫1（数倍以上）⇒ 湖水位把大片缓坡变成了湖 ⇒ 水位算高了");
    }

    private LakeRimProbe() { }
}
