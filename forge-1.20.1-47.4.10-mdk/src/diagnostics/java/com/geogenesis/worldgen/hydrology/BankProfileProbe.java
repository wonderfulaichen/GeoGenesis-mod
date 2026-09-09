package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 岸坡横断面探针（2026-09-09）。
 *
 * <p>用户反馈："岸坡过渡区边缘不过渡 → 断面；应该平滑过渡到实际地形"。
 * 本探针沿【垂直于河流方向】逐格走，导出最终地形（cell.height = 雕刻 + 侵蚀合成后，
 * 即玩家实际看到的地面）的横断面，量化：</p>
 * <ol>
 *   <li>每 1 格水平步长的高度突变量（= 坡度）；≥2 / ≥3 格即视觉"垂直面"。</li>
 *   <li><b>最大突变发生在离河心多远处</b>——区分三种假设：
 *       <ul>
 *         <li>近河心（d≈width）→ 河槽岸壁问题；</li>
 *         <li>中段（width..valley）→ 谷壁坡度过陡；</li>
 *         <li>远段（d≈valley 及更外）→ <b>雕刻带与真实地形的接缝</b>（用户所指）。</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>A/B 开关</b>：把 {@code RiverLineParams.bankRunMax} 设为 0 可精确复现
 * 自适应谷宽之前的旧行为（adaptiveBankRun 退化为 baseRun），无需改代码结构。</p>
 */
public final class BankProfileProbe {

    private BankProfileProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int regionR = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int maxNodes = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int reach = args.length > 3 ? Integer.parseInt(args[3]) : 40;   // 向岸外走多少 block

        TerrainParams params = TerrainParams.defaults();
        double hs = params.horizontalScale();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);

        List<RiverLineRegion.RiverPolyline> rivers = new ArrayList<>();
        for (int rz = -regionR; rz <= regionR; rz++) {
            for (int rx = -regionR; rx <= regionR; rx++) {
                rivers.addAll(engine.network().region(rx, rz).rivers);
            }
        }

        int steps2 = 0, steps3 = 0, total = 0, bigCount = 0;
        int atBoundary = 0, insideBand = 0, outsideBand = 0;
        double maxStep = 0.0, sumAbs = 0.0, sumBigDist = 0.0;
        List<String> examples = new ArrayList<>();
        int nodesDone = 0;

        for (RiverLineRegion.RiverPolyline pl : rivers) {
            if (nodesDone >= maxNodes) break;
            for (int i = 0; i + 1 < pl.nodes.length && nodesDone < maxNodes; i++) {
                double ax = pl.nodes[i].x(), az = pl.nodes[i].z();
                double bx = pl.nodes[i + 1].x(), bz = pl.nodes[i + 1].z();
                double tx = bx - ax, tz = bz - az;
                double len = Math.hypot(tx, tz);
                if (len < 1e-6) continue;
                tx /= len; tz /= len;
                double px = -tz, pz = tx;              // 单位垂直方向（wu）
                nodesDone++;

                double prev = Double.NaN;
                boolean prevIn = true;
                double localMax = 0.0, localMaxD = 0.0;
                StringBuilder sb = new StringBuilder();
                for (int d = 0; d <= reach; d++) {
                    double wx = ax + px * (d / hs);
                    double wz = az + pz * (d / hs);
                    int cbx = (int) Math.floor(wx * hs);
                    int cbz = (int) Math.floor(wz * hs);
                    Cell c = terrain.sampleCell(cbx, cbz);
                    double h = c.height;
                    // ★ 本列是否仍在雕刻 footprint 内（命中任一河段）
                    boolean inFp = !engine.sampleBlockAll(cbx, cbz, hs).isEmpty();
                    if (d > 0) {
                        double st = Math.abs(h - prev);
                        sumAbs += st;
                        total++;
                        if (st >= 2.0) steps2++;
                        if (st >= 3.0) steps3++;
                        if (st > maxStep) maxStep = st;
                        if (st > localMax) { localMax = st; localMaxD = d; }
                        // ★ 归因：台阶发生在【边界上】/【雕刻带内】/【带外自然地形】
                        if (st >= 2.0) {
                            if (inFp != prevIn) atBoundary++;
                            else if (inFp) insideBand++;
                            else outsideBand++;
                        }
                    }
                    prev = h;
                    prevIn = inFp;
                    if (d % 4 == 0) {
                        sb.append(d).append(':').append(String.format("%.1f", h))
                          .append(inFp ? "" : "*").append(' ');
                    }
                }
                if (localMax >= 2.0) { sumBigDist += localMaxD; bigCount++; }
                if (examples.size() < 6) {
                    examples.add("node" + nodesDone + " maxStep=" + String.format("%.2f", localMax)
                            + " @d=" + (int) localMaxD + " | " + sb);
                }
            }
        }

        System.out.println("=== BankProfileProbe ===");
        System.out.println("seed=" + seed + " nodes=" + nodesDone + " reach=" + reach);
        System.out.println("samples=" + total
                + " meanAbsStep=" + (total > 0 ? String.format("%.3f", sumAbs / total) : "0"));
        System.out.println("steps>=2=" + steps2 + " steps>=3=" + steps3
                + " maxStep=" + String.format("%.2f", maxStep));
        System.out.println("step>=2 attribution: atFootprintBoundary=" + atBoundary
                + " insideBand=" + insideBand + " outsideNatural=" + outsideBand);
        System.out.println("bigStepAtDistMean="
                + (bigCount > 0 ? String.format("%.1f", sumBigDist / bigCount) : "n/a")
                + " (count=" + bigCount + ")");
        for (String e : examples) System.out.println("  " + e);
    }
}
