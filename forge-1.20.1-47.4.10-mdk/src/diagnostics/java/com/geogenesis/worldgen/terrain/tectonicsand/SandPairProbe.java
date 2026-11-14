package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.Locale;

/**
 * 沙箱诊断：<b>单点的候选边界对逐条解剖</b>。
 *
 * <p>目的：{@link SandTerrain#boundary} 把多个候选对的四案例响应加权平均。
 * 若山带消失，只有两种可能：① 各对的 u 本身很小；② 平均把正负抵消。
 * 本探针把每一对的 {@code dEff / conv / own / oth / u / 权重} 全部打出来，二者一眼可分。</p>
 *
 * <p>用法：{@code SandPairProbe x z [点个数(沿 x 展开)]}</p>
 */
public final class SandPairProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        double x0 = args.length > 0 ? Double.parseDouble(args[0]) : 200;
        double z = args.length > 1 ? Double.parseDouble(args[1]) : 0;
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        for (int k = 0; k < n; k++) {
            double x = x0 + k * 25.0;
            SandPlateField.Sample s = SandPlateField.sample(SEED, x, z);
            double qx = s.warpedX(), qz = s.warpedZ();
            SandPlateField.Plate me = s.plate();
            double d1 = Math.hypot(qx - me.sx(), qz - me.sz());
            double crust = SandContinent.crustAt(SEED, qx, qz);
            double cq = SandTerrain.crust01(SEED, qx, qz);
            System.out.printf(Locale.ROOT,
                    "%n=== x=%.1f z=%.1f  me=(%d,%d) site=(%.1f,%.1f) d1=%.1f crust=%.3f dist=%.1f ==="
                            + "  sumUplift=%.4f e (%.0f m)%n",
                    x, z, me.cx(), me.cz(), me.sx(), me.sz(), d1, crust, s.dist(),
                    SandTerrain.boundary(s).uplift(), SandTerrain.boundary(s).uplift() * 11000);
            System.out.printf(Locale.ROOT, "%9s %8s %8s %8s %6s %6s %10s %8s%n",
                    "other", "dEff", "convRaw", "convN", "own", "oth", "u(e)", "w");
            double wSum = 0, upSum = 0;
            for (SandPlateField.Plate other : SandPlateField.platesNear(SEED, qx, qz, SandTerrain.PAIR_RADIUS)) {
                if (other.cx() == me.cx() && other.cz() == me.cz()) {
                    continue;
                }
                double sx0 = other.sx() - me.sx(), sz0 = other.sz() - me.sz();
                double len = Math.hypot(sx0, sz0);
                if (len < 1e-9) {
                    continue;
                }
                double nx = sx0 / len, nz = sz0 / len;
                double di = Math.hypot(qx - other.sx(), qz - other.sz());
                double dEff = Math.max(0.0, 0.5 * (di - d1));
                if (dEff > SandTerrain.RESPONSE_CUT) {
                    continue;
                }
                double vax = me.vxAt(qx, qz), vaz = me.vzAt(qx, qz);
                double vbx = other.vxAt(qx, qz), vbz = other.vzAt(qx, qz);
                double rx = vax - vbx, rz = vaz - vbz;
                double convRaw = rx * nx + rz * nz;
                double shear = Math.abs(rx * nz - rz * nx) / SandTerrain.CONV_SCALE;
                double convN = convRaw / SandTerrain.CONV_SCALE;
                double bx = qx + nx * dEff, bz = qz + nz * dEff;
                double own = SandTerrain.crust01(SEED, bx - nx * SandTerrain.EDGE_EPS,
                        bz - nz * SandTerrain.EDGE_EPS);
                double oth = SandTerrain.crust01(SEED, bx + nx * SandTerrain.EDGE_EPS,
                        bz + nz * SandTerrain.EDGE_EPS);
                boolean meSub = ((long) me.cx() * 1000003L + me.cz())
                        > ((long) other.cx() * 1000003L + other.cz());
                double u = SandTerrain.caseUplift(dEff, convN, shear, own, oth, meSub, s, cq);
                double w = Math.exp(-dEff * dEff / (2 * SandTerrain.BLEND_SIGMA * SandTerrain.BLEND_SIGMA));
                wSum += w;
                upSum += w * u;
                System.out.printf(Locale.ROOT,
                        "(%3d,%3d) %8.1f %8.3f %8.2f %6.0f %6.0f %10.4f %8.3f%n",
                        other.cx(), other.cz(), dEff, convRaw, convN, own, oth, u, w);
            }
            System.out.printf(Locale.ROOT, "  加权平均 u = %.4f e = %.0f m%n",
                    wSum > 1e-12 ? upSum / wSum : 0, (wSum > 1e-12 ? upSum / wSum : 0) * 11000);
        }
    }
}