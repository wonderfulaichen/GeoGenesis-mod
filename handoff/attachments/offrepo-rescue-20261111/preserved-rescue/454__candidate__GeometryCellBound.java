package com.geogenesis.worldgen.terrain.candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>N2 严格包围（纯函数、零 MC 依赖）</b>：一格内连续 warp 的 Jacobian 行列式下界。
 *
 * <h3>为什么是"证明"而不是"采样"</h3>
 * 一格内（u,v 同属一个整数格）四角梯度固定，{@link GeometryTransform#sampleValueAndGradient}
 * 的两个偏导都是 (t,s) 的多项式（次数 ≤4）。多项式的 <b>Bernstein 系数</b>满足凸包性质：
 * 多项式在整块上的取值必落在其系数的凸包内。于是：
 * <ol>
 *   <li>把整格 de Casteljau 二分 {@code levels} 次，得 4^levels 个子矩形；每个子矩形的
 *       Bernstein 包围盒都是<b>严格上界</b>；</li>
 *   <li>全部子矩形包围盒的角点取凸包 ⇒ 该格 {@code (∂n/∂u, ∂n/∂v)} 的<b>严格凸多边形</b>；</li>
 *   <li>{@code det J = (1+k·a)(1+k·d) − k²·b·c} 对 {@code (a,b,c,d)} 多仿射
 *       ⇒ 在两个凸多边形之积上的最小值必在<b>顶点对</b>取得。</li>
 * </ol>
 * 故 {@link #detBounds} 给的是<b>严格下界</b>。它只覆盖被扫描的格，<b>不构成</b>全局单射证明。
 *
 * <p>本类只认识"一格"，不知道 world 坐标与 seed 语义；越界与参数校验由调用方负责。</p>
 */
final class GeometryCellBound {

    private static final int DEG = 4;
    private static final double[][] GRAD = {
            {-1, -1}, {1, -1}, {-1, 1}, {1, 1}, {0, -1}, {-1, 0}, {0, 1}, {1, 0}
    };
    private static final double[][] BINOM = binom();

    private GeometryCellBound() { }

    // ============================================================ 对外

    /**
     * 一格内 {@code (∂n/∂u, ∂n/∂v)} 的严格凸包顶点（逆时针；可能退化为 1~2 点）。
     *
     * @param levels de Casteljau 二分次数；0 = 整格一盒（松），2 已足够紧
     */
    static double[][] derivativeHull(int seed, int cellX, int cellZ, int levels) {
        Poly du = duPoly(cellGradients(seed, cellX, cellZ));
        Poly dv = dvPoly(cellGradients(seed, cellX, cellZ));
        double[][] bu = du.bernstein(), bv = dv.bernstein();
        List<double[]> corners = new ArrayList<>();
        for (double[] r : subRects(levels)) {
            double[] u = boxOf(bu, r), v = boxOf(bv, r);
            corners.add(new double[]{u[0], v[0]});
            corners.add(new double[]{u[0], v[1]});
            corners.add(new double[]{u[1], v[0]});
            corners.add(new double[]{u[1], v[1]});
        }
        return hull(corners);
    }

    /**
     * 该格 {@code det J} 的严格 {@code {下界, 上界, x顶点数, z顶点数}}（顶点对配对极值）。
     *
     * @param k 归一化偏导 → world 偏导的比例（{@link GeometryTransform#derivativeScale()}）
     */
    static double[] detBounds(int seedX, int seedZ, int cellX, int cellZ, int levels, double k) {
        double[][] px = derivativeHull(seedX, cellX, cellZ, levels);
        double[][] pz = derivativeHull(seedZ, cellX, cellZ, levels);
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] a : px) {
            for (double[] b : pz) {
                double det = det(a[0], a[1], b[0], b[1], k);
                lo = Math.min(lo, det);
                hi = Math.max(hi, det);
            }
        }
        return new double[]{lo, hi, px.length, pz.length};
    }

    /** {@code det J}；参数是归一化偏导。 */
    static double det(double a, double b, double c, double d, double k) {
        return (1 + a * k) * (1 + d * k) - b * k * c * k;
    }

    /** 该格四角梯度（顺序：00, 10, 01, 11）。 */
    static double[][] cellGradients(int seed, int cellX, int cellZ) {
        return new double[][]{grad(seed, cellX, cellZ), grad(seed, cellX + 1, cellZ),
                grad(seed, cellX, cellZ + 1), grad(seed, cellX + 1, cellZ + 1)};
    }

    /** 与 {@code RefNoise.Perlin.grad} 逐位同式的整数哈希 → GRAD_2D。 */
    static double[] grad(int seed, int x, int y) {
        int hash = seed;
        hash ^= 1619 * x;
        hash ^= 31337 * y;
        hash = hash * hash * hash * 60493;
        hash ^= hash >> 13;
        return GRAD[hash & 7];
    }

    // ============================================================ 导数多项式

    /** {@code d/dt} 的单项式多项式（供 check 逐点对拍解析式）。 */
    static Poly duPolynomial(double[][] g) {
        return duPoly(g);
    }

    /** {@code d/ds} 的单项式多项式。 */
    static Poly dvPolynomial(double[][] g) {
        return dvPoly(g);
    }

    /** {@code d/dt} 的单项式多项式。 */
    private static Poly duPoly(double[][] g) {
        Poly d00 = dotPoly(g[0], 0, 0), d10 = dotPoly(g[1], -1, 0);
        Poly d01 = dotPoly(g[2], 0, -1), d11 = dotPoly(g[3], -1, -1);
        Poly df0 = d10.minus(d00), df2 = d11.minus(d01);
        Poly a = Poly.constant(g[0][0]).plus(dxsPoly().times(df0))
                .plus(xsPoly().times(Poly.constant(g[1][0] - g[0][0])));
        Poly b = Poly.constant(g[2][0]).plus(dxsPoly().times(df2))
                .plus(xsPoly().times(Poly.constant(g[3][0] - g[2][0])));
        return yLerp(a, b);
    }

    /** {@code d/ds} 的单项式多项式。 */
    private static Poly dvPoly(double[][] g) {
        Poly d00 = dotPoly(g[0], 0, 0), d10 = dotPoly(g[1], -1, 0);
        Poly d01 = dotPoly(g[2], 0, -1), d11 = dotPoly(g[3], -1, -1);
        Poly df0 = d10.minus(d00), df2 = d11.minus(d01);
        Poly s0 = Poly.constant(g[0][1]).plus(xsPoly().times(Poly.constant(g[1][1] - g[0][1])));
        Poly s2 = Poly.constant(g[2][1]).plus(xsPoly().times(Poly.constant(g[3][1] - g[2][1])));
        Poly f0 = d00.plus(xsPoly().times(df0));
        Poly f2 = d01.plus(xsPoly().times(df2));
        // dv = (1−ys)·s0 + ys·s2 + dys·(f2 − f0)
        return yLerp(s0, s2).plus(dysPoly().times(f2.minus(f0)));
    }

    /**
     * 四角梯度点积 {@code gx·(t+dt) + gy·(s+ds)}。
     *
     * <p><b>偏置不能省</b>：右/上角的局部坐标是 {@code t−1}/{@code s−1}，漏掉这两个常数项
     * 会让整格导数偏移一个常量（本文件曾因此对拍失败、det 下界出现负值）。</p>
     */
    private static Poly dotPoly(double[] g, int dt, int ds) {
        Poly p = new Poly();
        p.add(1, 0, g[0]);
        p.add(0, 1, g[1]);
        if (dt != 0) {
            p.add(0, 0, dt * g[0]);
        }
        if (ds != 0) {
            p.add(0, 0, ds * g[1]);
        }
        return p;
    }

    /**
     * {@code 3t²−2t³}（Hermite 平滑）。
     *
     * <p>两个系数<b>不同</b>（3 与 −2），所以不能写成"同一个 scale 的两项"——
     * 本文件曾用 {@code tPow(2,3,1)} 得到错值的 {@code t²−t³}，使多项式式与解析式对拍失败。
     * 下面四个助手都显式写出各自的系数。</p>
     */
    private static Poly xsPoly() {
        Poly p = new Poly();
        p.add(2, 0, 3);
        p.add(3, 0, -2);
        return p;
    }

    /** {@code 6t−6t²}（即 {@code dxs}）。 */
    private static Poly dxsPoly() {
        Poly p = new Poly();
        p.add(1, 0, 6);
        p.add(2, 0, -6);
        return p;
    }

    /** {@code 3s²−2s³}。 */
    private static Poly ysPoly() {
        Poly p = new Poly();
        p.add(0, 2, 3);
        p.add(0, 3, -2);
        return p;
    }

    /** {@code 6s−6s²}。 */
    private static Poly dysPoly() {
        Poly p = new Poly();
        p.add(0, 1, 6);
        p.add(0, 2, -6);
        return p;
    }

    /** {@code (1−ys)·A + ys·B}。 */
    private static Poly yLerp(Poly a, Poly b) {
        return a.plus(ysPoly().times(b.minus(a)));
    }

    // ============================================================ 细分与凸包

    /** 子矩形：{@code {t0, t1, s0, s1}}，共 4^levels 个。 */
    static List<double[]> subRects(int levels) {
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{0, 1, 0, 1});
        for (int i = 0; i < levels; i++) {
            List<double[]> next = new ArrayList<>(out.size() * 4);
            for (double[] r : out) {
                double tm = (r[0] + r[1]) / 2, sm = (r[2] + r[3]) / 2;
                next.add(new double[]{r[0], tm, r[2], sm});
                next.add(new double[]{r[0], tm, sm, r[3]});
                next.add(new double[]{tm, r[1], r[2], sm});
                next.add(new double[]{tm, r[1], sm, r[3]});
            }
            out = next;
        }
        return out;
    }

    private static double[] boxOf(double[][] grid, double[] rect) {
        double[][] r = restrict2(grid, rect[0], rect[1], rect[2], rect[3]);
        return new double[]{minOf(r), maxOf(r)};
    }

    static double[][] restrict2(double[][] grid, double t0, double t1, double s0, double s1) {
        int n = grid.length;
        double[][] rows = new double[n][n];
        for (int p = 0; p < n; p++) {
            rows[p] = restrict1(grid[p], s0, s1);
        }
        double[][] out = new double[n][n];
        for (int q = 0; q < n; q++) {
            double[] col = new double[n];
            for (int p = 0; p < n; p++) {
                col[p] = rows[p][q];
            }
            double[] r = restrict1(col, t0, t1);
            for (int p = 0; p < n; p++) {
                out[p][q] = r[p];
            }
        }
        return out;
    }

    /** de Casteljau：把一条 Bernstein 控制多边形限制到 {@code [lo,hi]}。 */
    static double[] restrict1(double[] b, double lo, double hi) {
        if (lo <= 0 && hi >= 1) {
            return b.clone();
        }
        double[] seg = hi < 1 ? split1(b, hi)[0] : b.clone();
        return lo <= 0 ? seg : split1(seg, lo / hi)[1];
    }

    /** 在参数 {@code u} 处把控制多边形分成左右两半。 */
    static double[][] split1(double[] b, double u) {
        int n = b.length;
        double[] left = new double[n], right = new double[n];
        double[] row = b.clone();
        left[0] = row[0];
        right[n - 1] = row[n - 1];
        for (int k = 1; k < n; k++) {
            for (int i = 0; i < n - k; i++) {
                row[i] += u * (row[i + 1] - row[i]);
            }
            left[k] = row[0];
            right[n - 1 - k] = row[n - k - 1];
        }
        return new double[][]{left, right};
    }

    static double minOf(double[][] m) {
        double v = Double.POSITIVE_INFINITY;
        for (double[] row : m) {
            for (double x : row) {
                v = Math.min(v, x);
            }
        }
        return v;
    }

    static double maxOf(double[][] m) {
        double v = Double.NEGATIVE_INFINITY;
        for (double[] row : m) {
            for (double x : row) {
                v = Math.max(v, x);
            }
        }
        return v;
    }

    /** Andrew 单调链凸包（逆时针，去掉共线点）。 */
    static double[][] hull(List<double[]> points) {
        List<double[]> pts = new ArrayList<>(points);
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        List<double[]> uniq = new ArrayList<>();
        for (double[] p : pts) {
            if (uniq.isEmpty() || !same(uniq.get(uniq.size() - 1), p)) {
                uniq.add(p);
            }
        }
        if (uniq.size() <= 2) {
            return uniq.toArray(new double[0][]);
        }
        List<double[]> lo = new ArrayList<>(), up = new ArrayList<>();
        for (double[] p : uniq) {
            while (lo.size() >= 2 && cross(lo.get(lo.size() - 2), lo.get(lo.size() - 1), p) <= 0) {
                lo.remove(lo.size() - 1);
            }
            lo.add(p);
        }
        for (int i = uniq.size() - 1; i >= 0; i--) {
            double[] p = uniq.get(i);
            while (up.size() >= 2 && cross(up.get(up.size() - 2), up.get(up.size() - 1), p) <= 0) {
                up.remove(up.size() - 1);
            }
            up.add(p);
        }
        lo.remove(lo.size() - 1);
        up.remove(up.size() - 1);
        lo.addAll(up);
        return lo.toArray(new double[0][]);
    }

    private static boolean same(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static double[][] binom() {
        double[][] c = new double[DEG + 1][DEG + 1];
        for (int n = 0; n <= DEG; n++) {
            c[n][0] = 1;
            for (int k = 1; k <= n; k++) {
                c[n][k] = c[n - 1][k - 1] + (k <= n - 1 ? c[n - 1][k] : 0);
            }
        }
        return c;
    }

    // ============================================================ 多项式

    /** 二元单项式多项式：{@code c[i][j]·t^i·s^j}，次数 ≤ {@link #DEG}。 */
    static final class Poly {
        private final double[][] c = new double[DEG + 1][DEG + 1];

        static Poly constant(double v) {
            Poly p = new Poly();
            p.c[0][0] = v;
            return p;
        }

        /** {@code (i,j)} 处累加系数（只有本文件的算子构造多项式时使用）。 */
        void add(int i, int j, double v) {
            c[i][j] += v;
        }

        /** 在 {@code (t,s)} 上求值（check 用来把多项式式与解析式逐点对拍）。 */
        double at(double t, double s) {
            double total = 0;
            for (int i = 0; i <= DEG; i++) {
                for (int j = 0; j <= DEG; j++) {
                    if (c[i][j] != 0) {
                        total += c[i][j] * Math.pow(t, i) * Math.pow(s, j);
                    }
                }
            }
            return total;
        }

        /** 调试：非零系数清单 {@code t^i s^j: c}。 */
        String describe() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= DEG; i++) {
                for (int j = 0; j <= DEG; j++) {
                    if (c[i][j] != 0) {
                        sb.append("t^").append(i).append("s^").append(j).append(": ").append(c[i][j]).append('\n');
                    }
                }
            }
            return sb.toString();
        }

        Poly plus(Poly o) {
            Poly p = new Poly();
            for (int i = 0; i <= DEG; i++) {
                for (int j = 0; j <= DEG; j++) {
                    p.c[i][j] = c[i][j] + o.c[i][j];
                }
            }
            return p;
        }

        Poly minus(Poly o) {
            Poly p = new Poly();
            for (int i = 0; i <= DEG; i++) {
                for (int j = 0; j <= DEG; j++) {
                    p.c[i][j] = c[i][j] - o.c[i][j];
                }
            }
            return p;
        }

        Poly times(Poly o) {
            Poly p = new Poly();
            for (int i = 0; i <= DEG; i++) {
                for (int j = 0; j <= DEG; j++) {
                    if (c[i][j] == 0) {
                        continue;
                    }
                    for (int k = 0; i + k <= DEG; k++) {
                        for (int l = 0; j + l <= DEG; l++) {
                            if (o.c[k][l] != 0) {
                                p.c[i + k][j + l] += c[i][j] * o.c[k][l];
                            }
                        }
                    }
                }
            }
            return p;
        }

        /** 单项式系数 → Bernstein 系数网格（{@code (DEG+1)²}）。 */
        double[][] bernstein() {
            double[][] g = new double[DEG + 1][DEG + 1];
            for (int p = 0; p <= DEG; p++) {
                for (int q = 0; q <= DEG; q++) {
                    double total = 0;
                    for (int i = 0; i <= p; i++) {
                        for (int j = 0; j <= q; j++) {
                            if (c[i][j] != 0) {
                                total += BINOM[p][i] / BINOM[DEG][i] * BINOM[q][j] / BINOM[DEG][j] * c[i][j];
                            }
                        }
                    }
                    g[p][q] = total;
                }
            }
            return g;
        }
    }
}
