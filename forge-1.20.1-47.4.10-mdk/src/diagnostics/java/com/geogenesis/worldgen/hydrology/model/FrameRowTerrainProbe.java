package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 边框行的地形真相：窗口最后一行在【窗外】是否更低。
 *
 * <p>若窗外更低，则链被窗口边界"挡住"、只能在行内横向找出口 ⇒
 * 图上那条水平长直是<b>窗口裁剪伪影</b>，不是地形本身的流向。</p>
 */
public final class FrameRowTerrainProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;

        var tp = TerrainParams.defaults();
        double hs = Math.max(0.01, tp.horizontalScale());
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int[] frame = provider.windowFrameForProbe(bx, bz);
        int ox = frame[0], oz = frame[1], step = frame[2], n = frame[3];
        var p = provider.pipeForProbe(bx, bz);
        double[] bed = p.bed();
        System.out.printf("window origin=(%d,%d) step=%d n=%d  lastZ=%d  lastX=%d%n",
                ox, oz, step, n, oz + (n - 1) * step, ox + (n - 1) * step);

        int lowerOutside = 0, higherOutside = 0, none = 0;
        StringBuilder rows = new StringBuilder();
        for (int i = 20; i < n - 20; i += 7) {
            int here = i * n + (n - 1);            // z 最后一行
            double hIn = bed[here];
            int wx = ox + i * step, wz = oz + (n - 1) * step;
            double hOut = gen.erodedHeight((wx) / hs, (wz + step) / hs);
            String verdict;
            if (hOut < hIn - 1e-6) { lowerOutside++; verdict = "OUT_LOWER"; }
            else if (hOut > hIn + 1e-6) { higherOutside++; verdict = "OUT_HIGHER"; }
            else { none++; verdict = "EQUAL"; }
            // 同行横向最低（窗口内）
            double minRow = here;
            for (int a = Math.max(0, i - 3); a <= Math.min(n - 1, i + 3); a++) {
                minRow = Math.min(minRow, bed[a * n + (n - 1)]);
            }
            if (rows.length() < 4000) {
                rows.append(String.format(
                        "   i=%-3d x=%-6d bed_in=%.3f  bed_out(z+%d)=%.3f  %s%n",
                        i, wx, hIn, step, hOut, verdict));
            }
        }
        System.out.print(rows);
        System.out.printf("%n窗外更低=%d  窗外更高=%d  相等=%d%n", lowerOutside, higherOutside, none);
        System.out.println(lowerOutside > higherOutside
                ? "VERDICT: 窗外普遍更低 ⇒ 边框横向长直是【窗口裁剪伪影】"
                : "VERDICT: 窗外不更低 ⇒ 需另找原因");
    }
}
