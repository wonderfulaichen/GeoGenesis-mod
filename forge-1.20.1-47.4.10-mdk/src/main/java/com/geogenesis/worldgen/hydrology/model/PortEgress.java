package com.geogenesis.worldgen.hydrology.model;

/**
 * ★ 显式出窗判定（ports 模块）：<b>完整真实候选 + 同一向量规则</b>。
 *
 * <h3>出口 vs 边缘（用户口径）</h3>
 * <p>"边缘不等于出口；自然沿边走也不能一律禁止"。所以本类<b>不</b>按"站在边框上"就出窗，
 * 而是：</p>
 * <ol>
 *   <li>把候选集<b>加宽</b>到窗外（窗外格用真实世界地形采样补全）—— 否则
 *       {@code vectorTraceNext} 的界内检查会让水流只能在【边框那一行】里找更低的格，
 *       实测就走成沿边框横走几百块的水平长直；</li>
 *   <li>在加宽后的 patch 上跑<b>同一个</b> {@code vectorTraceNext}（同权重、同惯性、同 span）；</li>
 *   <li>只有<b>获选的那一步确实出窗</b>才建立端口；获选仍在窗内 ⇒ 返回 null，
 *       链照常沿边走（自然贴边行为不被禁止）。</li>
 * </ol>
 *
 * <p>端口携带 anchor（本次末格世界坐标，收发两侧逐位相同）、真实下一步世界坐标、
 * 来向单位向量；身份（哪个 region）由 {@link BoundaryPort#stamp} 在 provider 侧盖。</p>
 */
final class PortEgress {

    private PortEgress() { }

    /**
     * 在边框格 {@code k} 上判断"完整候选下的下一步是否出窗"。
     *
     * @return 出窗 ⇒ 端口（身份未盖）；未出窗/不可采样/非边框 ⇒ {@code null}
     */
    static BoundaryPort egress(int k, double[] bed, int nx, int nz,
                               double inDirX, double inDirZ, int span,
                               DeterministicSkeleton.OutsideContinuation ctx) {
        if (bed == null || ctx == null) return null;
        int i = k / nz, j = k % nz;
        if (i != 0 && j != 0 && i != nx - 1 && j != nz - 1) return null;
        int s = Math.max(1, span), size = 2 * s + 1;
        double[] patch = widen(i, j, bed, nx, nz, s, size, ctx);
        if (patch == null) return null;
        var step = DeterministicSkeleton.vectorTraceNext(s * size + s, patch, size, size,
                inDirX, inDirZ, s);
        if (step.cell() < 0) return null;
        int ti = i + step.cell() / size - s, tj = j + step.cell() % size - s;
        if (ti >= 0 && tj >= 0 && ti < nx && tj < nz) return null;   // 仍在窗内 ⇒ 不是出口
        return port(i, j, ti, tj, k, ctx);
    }

    /** 加宽的候选 patch：窗内取 {@code bed}，窗外取真实世界地形；任一采样非有限 ⇒ 放弃。 */
    private static double[] widen(int i, int j, double[] bed, int nx, int nz, int s, int size,
                                  DeterministicSkeleton.OutsideContinuation ctx) {
        double[] patch = new double[size * size];
        for (int a = 0; a < size; a++) {
            for (int b = 0; b < size; b++) {
                int ii = i + a - s, jj = j + b - s;
                double h;
                if (ii >= 0 && jj >= 0 && ii < nx && jj < nz) {
                    h = bed[ii * nz + jj];
                } else {
                    h = ctx.terrain().height(ctx.originX() + ii * ctx.step(),
                            ctx.originZ() + jj * ctx.step());
                }
                if (!Double.isFinite(h)) return null;
                patch[a * size + b] = h;
            }
        }
        return patch;
    }

    /** 端口载荷（世界坐标 + 来向 + 兼容 dR）。身份留给 {@link BoundaryPort#stamp}。 */
    private static BoundaryPort port(int i, int j, int ti, int tj, int k,
                                     DeterministicSkeleton.OutsideContinuation ctx) {
        int step = ctx.step();
        int ax = ctx.originX() + i * step, az = ctx.originZ() + j * step;
        int bx = ctx.originX() + ti * step, bz = ctx.originZ() + tj * step;
        double dx = bx - ax, dz = bz - az;
        double len = Math.hypot(dx, dz);
        if (len < 1e-9) return null;
        return BoundaryPort.of(ax, az, bx, bz, dx / len, dz / len, k,
                Integer.signum(ti - i), Integer.signum(tj - j));
    }
}
