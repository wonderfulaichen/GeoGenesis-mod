package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 源头谷地归属探针（2026-09-01）：用户的两条判据。
 *
 * <p>① 源头应在【山谷】里：沿垂直于流向的方向看，两侧地形都应比源点高（汇流槽）。
 * 现有成河判据只看 D8 汇流面积，而汇流面积在【开阔凸坡】上同样会随下坡累积达标
 * ——坡面无谷但依然"汇流"，这正是截图里源头切在坡面上的原因。</p>
 *
 * <p>② 源头不应落在【另一条河的过渡区】里：到最近另一条河中心线的距离（block）
 * 若小于该河的谷壁影响半径 valley=3.5×半宽，则新河是在别人的谷壁上再开一条槽。</p>
 */
public final class SourceValleyProbe {

    private SourceValleyProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        double off = args.length > 2 ? Double.parseDouble(args[2]) : 24.0;   // 横距(wu)
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineParams P = RiverLineParams.defaults();
        double regionSize = P.regionSize();
        double seamTol = 48.0;

        // 收集全部河（跨 3×3 region），用于"是否落在别的河谷里"的判定
        List<RiverLineRegion.RiverPolyline> all = new ArrayList<>();
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                all.addAll(engine.network().region(rx, rz).rivers);
            }
        }

        int n = 0, seam = 0;
        int inValley = 0, onShoulder = 0, onRidge = 0;
        int insideOther = 0;
        double sumMargin = 0;
        List<String> badValley = new ArrayList<>();
        List<String> badOther = new ArrayList<>();

        for (RiverLineRegion.RiverPolyline r : all) {
            if (r.nodes.length < 2) continue;
            double hx = r.nodes[0].x(), hz = r.nodes[0].z();
            // 缝头（跨 region 续流端）不考核
            // ★ 用 floorMod：Java 的 % 对负坐标返回负值，会把所有河误判成"贴边界"
            // Math.floorMod 无 double 重载，手写正余数
            double fx = hx - regionSize * Math.floor(hx / regionSize);
            double fz = hz - regionSize * Math.floor(hz / regionSize);
            double toBorder = Math.min(Math.min(fx, regionSize - fx), Math.min(fz, regionSize - fz));
            if (toBorder < seamTol) { seam++; continue; }

            // ① 汇流槽判据：沿垂直流向两侧采样
            double dx = r.nodes[1].x() - hx, dz = r.nodes[1].z() - hz;
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) continue;
            double px = -dz / len, pz = dx / len;          // 垂直流向（横向）
            // ★ 必须与生产同源：advanceToValleyHead 的汇流槽判据走 groundYAt →
            //   terrainY.yAt → CellGenerator.sampleWu（含侵蚀 tile delta）。原先用
            //   sample()（不含侵蚀）复核，等于拿另一份地形去考核判据——本项目已两次
            //   栽在同一类问题上（WaterfallCornerProbe 的 platform、WaterfallProbe 的
            //   angleFails 都是探针与生产数据不一致造成的假指标）。
            double h0 = terrain.sampleWu(hx, hz).height;
            double hL = terrain.sampleWu(hx - px * off, hz - pz * off).height;
            double hR = terrain.sampleWu(hx + px * off, hz + pz * off).height;
            double margin = Math.min(hL, hR) - h0;         // >0 = 两侧都更高 = 槽内
            n++;
            sumMargin += margin;
            if (margin >= 1.0) inValley++;
            else if (margin >= -1.0) onShoulder++;
            else {
                onRidge++;
                if (badValley.size() < 10) {
                    badValley.add(String.format("    源点 wu(%.0f,%.0f) 块(%d,%d) 高=%.1f "
                                    + "两侧=%.1f/%.1f 槽深裕度=%.1f",
                            hx, hz, (int) Math.floor(hx * hs), (int) Math.floor(hz * hs),
                            h0, hL, hR, margin));
                }
            }

            // ② 是否落在另一条河的过渡区内
            // ★ 半径取【最近点处的局部半宽】，与生产 insideExistingValley 同式
            //   （RiverLineNetwork:1012 逐节点 max(width[i],1)×3.5）。原先整条河只用
            //   o.width[1]——那是源头淡出后的最窄处，量中下游河段时半径普遍偏小，
            //   等于拿一把更短的尺子去量，只会漏报。本项目已四次栽在"探针与生产取
            //   的数据不同源"，这次是【公式不同】。
            double bestExcess = Double.POSITIVE_INFINITY;   // <0 = 在别人谷里
            for (RiverLineRegion.RiverPolyline o : all) {
                if (o == r) continue;
                double bestD = Double.POSITIVE_INFINITY;
                int bestI = 0;
                for (int i = 0; i + 1 < o.nodes.length; i++) {
                    double ax = o.nodes[i].x(), az = o.nodes[i].z();
                    double bx = o.nodes[i + 1].x(), bz = o.nodes[i + 1].z();
                    double abx = bx - ax, abz = bz - az;
                    double l2 = abx * abx + abz * abz;
                    double t = l2 < 1e-9 ? 0.0
                            : Math.max(0.0, Math.min(1.0, ((hx - ax) * abx + (hz - az) * abz) / l2));
                    double d = Math.hypot(hx - (ax + abx * t), hz - (az + abz * t));
                    if (d < bestD) { bestD = d; bestI = i; }
                }
                // ★ 合法汇流豁免：邻河的【出口】就在本河河头处 → 这是支流汇入，不是
                //   侵入谷壁。扇形散流的细流正是接在主河河头格上（emitFeederRills 的
                //   cells.add(head)），不豁免会把每一条被补给的主河误判成缺陷——实测
                //   insideOther 1→2 / 1→4 与细流存活数 +1 / +3 恰好一一对应。
                var mouth = o.nodes[o.nodes.length - 1];
                if (Math.hypot(hx - mouth.x(), hz - mouth.z()) <= 1.5 * P.gridCell()) continue;
                double wLocal = Math.max(o.width[Math.min(bestI, o.width.length - 1)], 1.0);
                double oValleyBlocks = wLocal * 3.5 * hs;    // valley=3.5×半宽，wu→block
                bestExcess = Math.min(bestExcess, bestD * hs - oValleyBlocks);
            }
            if (bestExcess < 0) {
                insideOther++;
                if (badOther.size() < 10) {
                    badOther.add(String.format("    源点 wu(%.0f,%.0f) 块(%d,%d) 侵入邻河谷壁 %.1f 格",
                            hx, hz, (int) Math.floor(hx * hs), (int) Math.floor(hz * hs), -bestExcess));
                }
            }
        }

        System.out.println("=== SourceValleyProbe ===");
        System.out.printf("seed=%d 横向采样距=%.0fwu  河总数=%d  其中缝头=%d（不考核）%n",
                seed, off, n + seam, seam);
        if (n == 0) { System.out.println("无可考核源头"); return; }
        System.out.printf("① 谷地归属（两侧更高者相对源点的裕度，平均 %.1f 格）：%n", sumMargin / n);
        System.out.printf("   槽内/山谷 (>=+1格) = %d (%.0f%%)  ← 应有形态%n", inValley, inValley * 100.0 / n);
        System.out.printf("   坡肩/近直 (-1~+1)  = %d (%.0f%%)%n", onShoulder, onShoulder * 100.0 / n);
        System.out.printf("   凸坡/脊 (<-1格)    = %d (%.0f%%)  ← 用户抱怨：源头切在坡面上%n",
                onRidge, onRidge * 100.0 / n);
        System.out.printf("② 落在另一条河过渡区内 = %d (%.0f%%)  ← 用户抱怨：不该在别河谷壁里%n",
                insideOther, insideOther * 100.0 / n);
        if (!badValley.isEmpty()) {
            System.out.println("   非谷地源头样例：");
            for (String s : badValley) System.out.println(s);
        }
        if (!badOther.isEmpty()) {
            System.out.println("   侵入邻河谷地样例：");
            for (String s : badOther) System.out.println(s);
        }
        System.out.println("status=" + ((onRidge * 4 <= n && insideOther * 4 <= n) ? "PASS" : "FAIL"));
    }
}
