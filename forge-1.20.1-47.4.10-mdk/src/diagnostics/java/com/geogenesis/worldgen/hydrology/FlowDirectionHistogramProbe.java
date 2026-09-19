package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;

/**
 * 「连续流向场（动量）」探针（2026-09-19，P1 验收）。
 *
 * <h2>要验的三件事</h2>
 * <ol>
 *   <li><b>零回归</b>：{@code MOMENTUM_WEIGHT = 0} 时，{@code flowTo} 与 {@code accum}
 *       必须与纯 D8 <b>逐位一致</b>（本场只新增 {@code dirX/dirZ}，不应改动汇流）。</li>
 *   <li><b>45° 聚集被打散</b>：纯 D8 的流向 100% 落在 45° 的整数倍上
 *       （这就是"河网太直太规则"的直接证据）；加动量后该占比应显著下降。</li>
 *   <li><b>粒子追踪更自然</b>（P2 预览）：沿 {@code dirAtWu}（双线性插值）推进的轨迹，
 *       其 <b>弯曲度 sinuosity = 路径长 / 首尾直线距离</b> 应进入自然曲流河区间（约 1.3~2.0），
 *       而沿 D8 逐格跳变的轨迹显著偏小（更直）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runFlowDirectionHistogramProbe [-PprobeArgs="seed wuX wuZ R cell W1"]}</pre>
 */
public final class FlowDirectionHistogramProbe {

    private FlowDirectionHistogramProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        double wuX = args.length > 1 ? Double.parseDouble(args[1]) : -377 * 2.0;
        double wuZ = args.length > 2 ? Double.parseDouble(args[2]) : -335 * 2.0;
        double R = args.length > 3 ? Double.parseDouble(args[3]) : 256.0;
        double cell = args.length > 4 ? Double.parseDouble(args[4]) : 24.0;
        double w1 = args.length > 5 ? Double.parseDouble(args[5]) : 0.45;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);

        System.out.printf("=== FlowDirectionHistogramProbe seed=%d 中心wu(%.0f,%.0f)±%.0f cell=%.0f W1=%.2f ===%n",
                seed, wuX, wuZ, R, cell, w1);

        double minX = wuX - R, minZ = wuZ - R, maxX = wuX + R, maxZ = wuZ + R;

        // ---- 基线：纯 D8 ----
        FlowField.MOMENTUM_WEIGHT = 0.0;
        FlowField base = new FlowField(minX, minZ, maxX, maxZ, cell, gen::terrainEQuick);

        // ---- 实验：带动量 ----
        FlowField.MOMENTUM_WEIGHT = w1;
        FlowField mom = new FlowField(minX, minZ, maxX, maxZ, cell, gen::terrainEQuick);
        FlowField.MOMENTUM_WEIGHT = 0.0;      // 复位

        int n = base.cols() * base.rows();

        // ================= [1] 零回归：flowTo / accum 必须逐位一致 =================
        int diffTo = 0, diffAcc = 0;
        for (int i = 0; i < n; i++) {
            if (base.flowTo(i) != mom.flowTo(i)) diffTo++;
            if (Double.doubleToLongBits(base.accumAt(i)) != Double.doubleToLongBits(mom.accumAt(i))) diffAcc++;
        }
        System.out.printf("%n[1] 零回归（汇流图不受动量影响）%n");
        System.out.printf("    flowTo 不一致 = %d%n", diffTo);
        System.out.printf("    accum  不一致 = %d  %s%n", diffAcc,
                (diffTo == 0 && diffAcc == 0) ? "✅ 逐位一致" : "❌ 有差异（不应发生）");

        // ================= [2] 45° 聚集度 =================
        // 只看"成河"的格（累积量前 10%）—— 河网形态由这些格决定
        double[] accs = new double[n];
        for (int i = 0; i < n; i++) accs[i] = base.accumAt(i);
        double[] sorted = accs.clone();
        Arrays.sort(sorted);
        double thr = sorted[(int) (n * 0.90)];
        System.out.printf("%n[2] 45°聚集度（成河格 = accum ≥ %.0f，共 %d 格）%n", thr, countAbove(accs, thr));
        System.out.printf("    纯 D8  : 落在 45° 整数倍(±1°) 的占比 = %.1f%%%n", axisShare(base, accs, thr, 2.0));
        System.out.printf("    带动量 : 落在 45° 整数倍(±1°) 的占比 = %.1f%%%n", axisShare(mom, accs, thr, 2.0));

        // ================= [3] 粒子追踪弯曲度（P2 预览）=================
        // 起点选取：取【D8 路径最长】的成河格（避免选到网格角点/洼地 ⇒ 路径长 0 的无效样本）
        int start = -1;
        double bestLen = -1.0;
        for (int i = 0; i < n; i++) {
            if (accs[i] < thr) continue;
            if (base.flowTo(i) < 0) continue;
            double l = traceD8(base, i, 400)[1];
            if (l > bestLen) { bestLen = l; start = i; }
        }
        if (start < 0) {
            System.out.println("\n[3] 跳过：本窗口无有效成河格（accum 阈值过高或全是海洋）");
            return;
        }
        double[] pD8 = traceD8(base, start, 400);
        double[] pMom = traceParticle(mom, start, cell * 0.5, 4000, R * 2.0);
        System.out.printf("%n[3] 粒子追踪弯曲度（起点 = D8 路径最长的成河格 idx=%d，accum=%.0f）%n",
                start, accs[start]);
        System.out.printf("    D8 逐格跳变   : 路径长=%.1f 直线=%.1f sinuosity=%.3f%n",
                pD8[1], pD8[2], pD8[1] / Math.max(1e-9, pD8[2]));
        System.out.printf("    沿连续流向场  : 路径长=%.1f 直线=%.1f sinuosity=%.3f%n",
                pMom[1], pMom[2], pMom[1] / Math.max(1e-9, pMom[2]));
        System.out.println("    判读：自然曲流河 sinuosity ≈ 1.3~2.0；D8 接近 1.0（直）");
        System.out.println("          ⇒ 沿连续流向场的值应明显大于 D8。");
    }

    private static int countAbove(double[] a, double thr) {
        int c = 0;
        for (double v : a) if (v >= thr) c++;
        return c;
    }

    /** 成河格中，方向落在 45° 整数倍 ±tol 度内的占比（%）。 */
    private static double axisShare(FlowField f, double[] accs, double thr, double tolDeg) {
        int hit = 0, tot = 0;
        for (int i = 0; i < accs.length; i++) {
            if (accs[i] < thr) continue;
            double dx = f.dirXAt(i), dz = f.dirZAt(i);
            if (dx == 0.0 && dz == 0.0) continue;
            double deg = Math.toDegrees(Math.atan2(dz, dx));
            double m = ((deg % 45.0) + 45.0) % 45.0;
            double d = Math.min(m, 45.0 - m);
            tot++;
            if (d <= tolDeg) hit++;
        }
        return tot == 0 ? 0.0 : 100.0 * hit / tot;
    }

    private static int argMax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    /** 沿 D8 逐格跳变的路径：{距离, 路径长, 首尾直线} / 索引 0 未用。 */
    private static double[] traceD8(FlowField f, int start, int maxSteps) {
        double px = f.cellCenterX(start), pz = f.cellCenterZ(start);
        double sx = px, sz = pz, len = 0.0;
        int cur = start;
        for (int k = 0; k < maxSteps; k++) {
            int down = f.flowTo(cur);
            if (down < 0) break;
            double qx = f.cellCenterX(down), qz = f.cellCenterZ(down);
            len += Math.hypot(qx - px, qz - pz);
            px = qx; pz = qz; cur = down;
        }
        return new double[]{0, len, Math.hypot(px - sx, pz - sz)};
    }

    /** 沿连续流向场推进的粒子轨迹：{距离, 路径长, 首尾直线}。 */
    private static double[] traceParticle(FlowField f, int start, double step, int maxSteps, double maxLen) {
        double px = f.cellCenterX(start), pz = f.cellCenterZ(start);
        double sx = px, sz = pz, len = 0.0;
        for (int k = 0; k < maxSteps; k++) {
            double[] d = f.dirAtWu(px, pz);
            if (d[0] == 0.0 && d[1] == 0.0) break;
            px += d[0] * step;
            pz += d[1] * step;
            len += step;
            if (len > maxLen) break;
        }
        return new double[]{0, len, Math.hypot(px - sx, pz - sz)};
    }
}
