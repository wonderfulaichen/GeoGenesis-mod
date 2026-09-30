package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续几何层：把 D8 格链转成有惯性的中心线，并给出宽深。
 *
 * <h2>硬约束：几何<b>不能</b>改变水量拓扑</h2>
 * <p>本类只读 {@link HydroTileResult} 的拓扑与水量，输出点列；<b>绝不</b>回写
 * {@code down/fill/basinId/discharge}。平滑后的点被<b>夹在其所属的两格内</b>
 * （见 {@link #clampToCells}），因此构造上不可能把水"绕到另一条山脊"。</p>
 *
 * <h2>惯性（SimpleHydrology 的方向动量）</h2>
 * <p>每步方向 = {@code normalize((1−m)·目标方向 + m·上一方向)}。转角越大、惯性保留越多
 * ⇒ 消除 D8 的 45° 锯齿观感，但目标方向始终是合法下游边。</p>
 *
 * <h2>宽深（Leopold-Maddock 幂律）</h2>
 * <p>{@code W = W0·(Q/Qref)^0.42}、{@code D = D0·(Q/Qref)^0.40}，并受 {@code D ≤ 0.9W}
 * 护栏；沿程只随 Q 单调变化，与几何平滑无关。</p>
 */
public final class HydroFlowGeometry {

    private HydroFlowGeometry() { }

    /** 方向动量权重（0 = 纯 D8 折线；1 = 完全不转向）。 */
    public static volatile double MOMENTUM = 0.55;

    /**
     * 参考流量（= 宽度标定参考，与成河阈值<b>解耦</b>；见 {@link HydroConfig#widthAreaRef()}）。
     *
     * <p>★ 2026-09-30 加宽（用户判据"河完全不像河"）：实测出图河道只有 1~2 块宽
     * （hairline）。原因：W0=1.5 太小且多数格流量刚过阈值 ⇒ 宽度贴下限。
     * 现取 W0=3.0（半宽）⇒ 阈值处 6 块宽、10× 流量处 ~20 块宽，与 MC 里"河"的观感同量级。</p>
     */
    private static final double W0 = 2.5;
    private static final double D0 = 1.0;
    private static final double B_W = 0.42;
    private static final double B_D = 0.40;
    private static final double W_MIN = 1.5, W_MAX = 48.0;
    private static final double D_MIN = 0.8, D_MAX = 12.0;

    /** 一条中心线（世界方块坐标）。 */
    public record HydroPolyline(double[] x, double[] z, double[] surfaceY,
                                double[] width, double[] depth, byte[] state, int[] cellIdx) {
        public int size() {
            return x.length;
        }
    }

    public static double widthFor(double q, HydroConfig cfg) {
        double ref = Math.max(1e-9, cfg.widthAreaRef());
        double w = W0 * Math.pow(Math.max(q, 0) / ref, B_W);
        return Math.max(W_MIN, Math.min(W_MAX, w));
    }

    public static double depthFor(double q, double width, HydroConfig cfg) {
        double ref = Math.max(1e-9, cfg.widthAreaRef());
        double d = D0 * Math.pow(Math.max(q, 0) / ref, B_D);
        d = Math.max(D_MIN, Math.min(D_MAX, d));
        return Math.min(d, 0.9 * width);
    }

    /**
     * 抽取该 tile core 内的中心线。
     *
     * <p>起点 = core 内没有上游的流动格（SOURCE / 盆地出流后的 DOWNSTREAM）；
     * 顺 {@code down} 走，直到出 core、入海或进入盆地。</p>
     */
    public static List<HydroPolyline> centerlines(HydroTileResult res) {
        HydroTileTopology topo = res.topo;
        HydroTileBalance bal = res.balance;
        HydroTileField f = topo.field;
        int w = f.w;
        List<HydroPolyline> out = new ArrayList<>();
        boolean[] used = new boolean[w * w];
        for (int start = 0; start < w * w; start++) {
            if (!topo.inCore(start)) continue;
            FlowState st = FlowState.values()[bal.state[start]];
            if (!st.isFlowing()) continue;
            if (hasUpstreamChannel(topo, bal, start)) continue;
            List<Integer> chain = new ArrayList<>();
            int cur = start;
            for (int guard = 0; guard < w * w; guard++) {
                if (cur < 0 || used[cur]) break;
                if (!topo.inCore(cur)) break;
                FlowState s2 = FlowState.values()[bal.state[cur]];
                if (!s2.isFlowing()) { chain.add(cur); break; }
                chain.add(cur);
                used[cur] = true;
                int nxt = topo.down[cur];
                if (nxt < 0) break;
                if (topo.basinId[nxt] >= 0) break;                 // 入湖即止（湖面由湖层表达）
                cur = nxt;
            }
            if (chain.size() < 2) continue;
            out.add(buildPolyline(topo, bal, chain));
        }
        return out;
    }

    private static boolean hasUpstreamChannel(HydroTileTopology topo, HydroTileBalance bal, int idx) {
        int w = topo.field.w;
        int lx = idx / w, lz = idx % w;
        for (int d = 0; d < 8; d++) {
            int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
            if (!topo.field.inside(nx, nz)) continue;
            int nb = nx * w + nz;
            if (topo.down[nb] != idx) continue;
            if (FlowState.values()[bal.state[nb]].isFlowing()) return true;
        }
        return false;
    }

    /**
     * D8 格链 → 连续中心线（<b>亚格细化 + 惯性</b>）。
     *
     * <p>★ 2026-09-30【修"河像方块拼起来"的最后一块】：D8 只有 8 个方向 ⇒ 只用格心与
     * 惯性混合，中心线必然落在 45° 折线上。本实现改为沿真实地形梯度走<b>连续流线</b>：</p>
     * <ol>
     *   <li>每步方向 = 归一化的 {@code −∇h}（中心差分，与 geotransport 的速度场同源），
     *       与链的 D8 目标方向做动量混合（SimpleHydrology 的方向惯性）；</li>
     *   <li>用【闭式格心穿越步长】推进（geotransport {@code __stepsize}：取两轴到格边界
     *       最小距离、上限 √2）⇒ 天然落在格边界上，与像素/格对齐、可复现；</li>
     *   <li>若下一步落在链上"下一格及其邻域"之外 ⇒ 视为偏离流路，回退到该格心继续 ——
     *       <b>连续方向 + 拓扑护栏</b>，既有蜿蜒，又不会绕到别的山脊。</li>
     * </ol>
     * <p>结果：中心线在同一条 D8 流路上连续弯曲（无 45° 阶梯），湖/溢口/端点仍锚在真实格。</p>
     */
    private static HydroPolyline buildPolyline(HydroTileTopology topo, HydroTileBalance bal,
                                              List<Integer> chain) {
        HydroTileField f = topo.field;
        int n = chain.size();
        double cell = f.cfg.cellBlocks();
        List<double[]> pts = new ArrayList<>(n * 4);
        double prevDx = Double.NaN, prevDz = Double.NaN;
        List<Integer> ptCells = new ArrayList<>(n * 4);
        double px = HydroContract.cellCenterX(f.globalX(chain.get(0)));
        double pz = HydroContract.cellCenterZ(f.globalZ(chain.get(0)));
        pts.add(new double[]{px, pz});
        ptCells.add(chain.get(0));
        for (int i = 0; i < n - 1; i++) {
            int c = chain.get(i), nc = chain.get(i + 1);
            double cx = HydroContract.cellCenterX(f.globalX(nc));
            double cz = HydroContract.cellCenterZ(f.globalZ(nc));
            // 目标方向 = 指向下一格心；连续方向 = 地形最陡下降（中心差分）
            double tx = cx - px, tz = cz - pz;
            double tl = Math.hypot(tx, tz);
            if (tl > 1e-9) { tx /= tl; tz /= tl; }
            double gx = gradX(f, px, pz), gz = gradZ(f, px, pz);
            double gl = Math.hypot(gx, gz);
            double sx = gl > 1e-9 ? -gx / gl : tx;
            double sz = gl > 1e-9 ? -gz / gl : tz;
            // 动量：地形梯度方向（连续）与上一方向混合；目标方向作为拓扑拉力
            double bx = 0.5 * sx + (Double.isNaN(prevDx) ? 0.5 * tx : 0.5 * prevDx);
            double bz = 0.5 * sz + (Double.isNaN(prevDz) ? 0.5 * tz : 0.5 * prevDz);
            double bl = Math.hypot(bx, bz);
            if (bl > 1e-9) { bx /= bl; bz /= bl; } else { bx = tx; bz = tz; }
            prevDx = bx; prevDz = bz;
            double step = stepsize(px, pz, bx, bz, cell);
            double qx = px + bx * step, qz = pz + bz * step;
            // 拓扑护栏：新点必须仍在"本格·下格 及其 8 邻"内，否则回退到下一格心
            if (!nearCells(f, topo, qx, qz, c, nc)) {
                qx = cx; qz = cz;
                prevDx = tx; prevDz = tz;
            }
            pts.add(new double[]{qx, qz});
            ptCells.add(nearestCell(f, qx, qz, nc));
            px = qx; pz = qz;
        }
        if (pts.size() < 2) {                    // 退化：至少给两端格心
            pts.add(new double[]{HydroContract.cellCenterX(f.globalX(chain.get(n - 1))),
                    HydroContract.cellCenterZ(f.globalZ(chain.get(n - 1)))});
            ptCells.add(chain.get(n - 1));
        }
        double[] x = new double[pts.size()];
        double[] z = new double[pts.size()];
        double[] sy = new double[pts.size()];
        double[] wd = new double[pts.size()];
        double[] dp = new double[pts.size()];
        byte[] st = new byte[pts.size()];
        int[] ci = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            int c = ptCells.get(i);
            x[i] = pts.get(i)[0];
            z[i] = pts.get(i)[1];
            double q = bal.discharge[c];
            double width = widthFor(q, f.cfg);
            sy[i] = Double.isNaN(bal.waterLevel[c]) ? f.height[c] : bal.waterLevel[c];
            wd[i] = width;
            dp[i] = depthFor(q, width, f.cfg);
            st[i] = bal.state[c];
            ci[i] = c;
        }
        return new HydroPolyline(x, z, sy, wd, dp, st, ci);
    }

    // ===== 连续流线的三个基础算子（geotransport 同源）=====

    /** 中心差分地形梯度 X（回退单侧；越界用可见范围）。 */
    private static double gradX(HydroTileField f, double wx, double wz) {
        double d = f.cfg.cellBlocks();
        double a = heightAt(f, wx - d, wz), b = heightAt(f, wx + d, wz);
        return (b - a) / (2 * d);
    }

    private static double gradZ(HydroTileField f, double wx, double wz) {
        double d = f.cfg.cellBlocks();
        double a = heightAt(f, wx, wz - d), b = heightAt(f, wx, wz + d);
        return (b - a) / (2 * d);
    }

    private static double heightAt(HydroTileField f, double wx, double wz) {
        int gx = HydroContract.cellX(wx), gz = HydroContract.cellZ(wz);
        int lx = gx - f.winMinX, lz = gz - f.winMinZ;
        if (!f.inside(lx, lz)) return 0;
        return f.height[lx * f.w + lz];
    }

    /**
     * 闭式格心穿越步长（geotransport {@code __stepsize}）：取两轴到格边界的最小距离，
     * 上限 √2 格 ⇒ 每步都落在格边界上 ⇒ 与格对齐、可复现、不穿透。
     */
    private static double stepsize(double x, double z, double dx, double dz, double cell) {
        double tx = Math.abs(dx) < 1e-12 ? Double.MAX_VALUE : cell / Math.abs(dx);
        double tz = Math.abs(dz) < 1e-12 ? Double.MAX_VALUE : cell / Math.abs(dz);
        return Math.min(Math.min(tx, tz) * 0.5, Math.sqrt(2.0) * cell);
    }

    /** 拓扑护栏：点是否落在"本格/下格及其 8 邻"内（允许在流路走廊内蜿蜒）。 */
    private static boolean nearCells(HydroTileField f, HydroTileTopology topo,
                                     double wx, double wz, int c, int nc) {
        int gx = HydroContract.cellX(wx), gz = HydroContract.cellZ(wz);
        for (int anchor : new int[]{c, nc}) {
            int ax = f.globalX(anchor), az = f.globalZ(anchor);
            if (Math.abs(gx - ax) <= 1 && Math.abs(gz - az) <= 1) return true;
        }
        return false;
    }

    /** 点所属的水文格（用于取该点的流量/水面/状态）；越界回退到给定格。 */
    private static int nearestCell(HydroTileField f, double wx, double wz, int fallback) {
        int gx = HydroContract.cellX(wx), gz = HydroContract.cellZ(wz);
        int lx = gx - f.winMinX, lz = gz - f.winMinZ;
        if (!f.inside(lx, lz)) return fallback;
        return lx * f.w + lz;
    }

    /** 把点夹在【链上前一格、本格、下一格】的外接矩形内 —— 蜿蜒但不离开真实流路。 */
    private static double[] clampToCells(HydroTileField f, double[] p, List<Integer> chain, int i) {
        double cell = f.cfg.cellBlocks();
        int gx0 = f.globalX(chain.get(i)), gz0 = f.globalZ(chain.get(i));
        int minGx = gx0, maxGx = gx0, minGz = gz0, maxGz = gz0;
        for (int k = Math.max(0, i - 1); k <= Math.min(chain.size() - 1, i + 1); k++) {
            int gx = f.globalX(chain.get(k)), gz = f.globalZ(chain.get(k));
            minGx = Math.min(minGx, gx); maxGx = Math.max(maxGx, gx);
            minGz = Math.min(minGz, gz); maxGz = Math.max(maxGz, gz);
        }
        return new double[]{
                Math.max(minGx * cell, Math.min((maxGx + 1) * cell, p[0])),
                Math.max(minGz * cell, Math.min((maxGz + 1) * cell, p[1]))};
    }
}
