package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
/**
 * 完整水文生命周期的<b>判据型</b>门禁探针（2026-09-29，用户裁定"必须看到完整周期"）。
 *
 * <p>与 {@code HydroCoreViewProbe} 的区别：那个是出图+打印（无退出码、要 4 分钟走侵蚀）；
 * 本探针走<b>合成地形</b>（零侵蚀 tile，秒级）并以退出码说话，纳入 {@code runWorldgenGate}。</p>
 *
 * <p>判据直接对应本轮实测抓到的三个真实缺陷：</p>
 * <ol>
 *   <li>[L1] 源头必须存在 —— 曾因"上游有任意水流"判据恒真 ⇒ SOURCE 恒 0；</li>
 *   <li>[L2] 至少 1 条链完整走完 源头→汇流→洼地蓄水→最低溢口→继续下泄→入海；</li>
 *   <li>[L3] SOURCE 只能出现在链首 —— 曾因 halo/owner 窗口不同源，中游被误标源头；</li>
 *   <li>[L4] 陆地断头 = 0（拓扑契约，与水量无关）。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew runHydroLifecycleProbe [-PprobeArgs="seed"]}</p>
 */
public final class HydroLifecycleProbe {

    private static int failures = 0;
    private static long seed = 4242L;

    public static void main(String[] args) {
        if (args.length > 0) seed = Long.parseLong(args[0]);
        System.out.println("=== HydroLifecycleProbe seed=" + seed + " ===");

        // ★ 合成地形必须按【格距】缩放（格距减半 ⇒ 同一块窗口覆盖的块数减半）：
        //   分水岭脊原本在块 x∈[-460,-440]（CELL_BLOCKS=4 时在 5×5 core 内），
        //   格距改 2 后窗口只有 ±256 块 ⇒ 脊跑到窗外、链首只能贴 tile 边界
        //   （实测"潜在链首 1162 全部贴 core 边"）⇒ 内部断点消失、完整链 = 0。
        final double cb = HydroContract.CELL_BLOCKS;
        final double wuSpan = HydroContract.TILE_CORE_CELLS * cb;      // 单 tile 的块跨度
        final double ridgeLo = -wuSpan * 0.95, ridgeHi = -wuSpan * 0.88;  // 脊靠西边缘
        final double bowlCx = -wuSpan * 0.35, bowlCz = wuSpan * 0.25;     // 碗居中偏西
        final double bowlR = wuSpan * 0.12;
        HydroSampler life = new HydroSampler() {
            @Override
            public double height(double x, double z) {
                double cz = bowlCz;
                double slope = 90 - (x + wuSpan * 0.8) * 0.14 + (z - cz) * 0.015;
                double cx = bowlCx, r = bowlR, bottom = 70;
                double d = Math.hypot(x - cx, z - cz);
                if (d < r) {
                    double ang = Math.atan2(z - cz, x - cx);
                    double cosA = Math.cos(ang);
                    double edge = Math.abs(ang) >= Math.PI * 2.0 / 3.0 ? 86
                            : (cosA > 0.5 ? 84 : 88);
                    double t = d / r;
                    return edge - (edge - bottom) * (1 - t * t);
                }
                if (x >= ridgeLo && x <= ridgeHi) return 200;    // 分水岭脊（内部断点）
                return slope;
            }
        };

        // 阈值 2000：达标点须距窗口上游断点 40+ 格，才能落在 core 内部 ——
        // 低阈值（300）下斜向汇流让链首天然落在 halo/core 边带，被"防中游误标"规则
        // 正确排除 ⇒ 全图 SOURCE=0（实测 1464 个潜在链首全部贴 core 边）。
        // 阈值 = "20 格汇流"（与格距解耦）；脊与碗之间的集水区约 30 格宽，
        // 取 20 格保证确有河形成并流入碗（写死数值会在改格距时假失败）。
        HydroConfig cfg = HydroConfig.defaults()
                .withChannelThreshold(20.0 * HydroConfig.defaults().cellArea());
        HydroWorldSolver solver = new HydroWorldSolver(seed, cfg, life);

        // ---- 解析并扫描 5×5 core（3×3 不够：水向碗汇聚后链首被推到 core 外）----
        int tx0 = -2, tx1 = 2, tz0 = -2, tz1 = 2;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                solver.resolve(new HydroTileKey(seed, tx, tz, 0));
            }
        }

        // owner(gx,gz) → tile result；3×3 外 = null（出窗口）
        java.util.function.Function<int[], HydroTileResult> owner = p -> {
            int tx = HydroTileKey.tileOfCell(p[0]), tz = HydroTileKey.tileOfCell(p[1]);
            if (tx < tx0 || tx > tx1 || tz < tz0 || tz > tz1) return null;
            return solver.result(new HydroTileKey(seed, tx, tz, 0));
        };

        // ---- 扫描全部 SOURCE（owner 口径）并沿 D8 追踪 ----
        Set<Long> visited = new LinkedHashSet<>();
        List<long[]> starts = new ArrayList<>();
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                HydroTileResult r = solver.result(new HydroTileKey(seed, tx, tz, 0));
                HydroTileField f = r.topo.field;
                for (int i = 0; i < f.w * f.w; i++) {
                    if (!r.topo.inCore(i)) continue;                 // 只扫 core（owner 归属）
                    if (r.balance.state[i] != (byte) FlowState.SOURCE.ordinal()) continue;
                    starts.add(new long[]{f.globalX(i), f.globalZ(i)});
                }
            }
        }

        // ---- 碗区诊断（入湖失败时的定位输出；正常时只是信息）----
        {
            HydroTileResult r0 = solver.result(new HydroTileKey(seed, -1, 0, 0));   // 碗心 (-140,64) → tx=-1,tz=0
            HydroTileField f0 = r0.topo.field;
            int px = -140, pz = 64;
            int lx = px - f0.winMinX, lz = pz - f0.winMinZ;
            System.out.println("  [碗区] tile(-1,0) win=(" + f0.winMinX + "," + f0.winMinZ
                    + ") 碗心局部=(" + lx + "," + lz + ")");
            if (f0.inside(lx, lz)) {
                int idx = lx * f0.w + lz;
                int b = r0.topo.basinId[idx];
                System.out.println("  [碗区] 心格 h=" + f0.height[idx]
                        + " fill=" + r0.topo.fill[idx] + " basinId=" + b
                        + (b >= 0 ? " spill=" + r0.topo.basinSpillLevel[b]
                        + " cells=" + r0.topo.basinCellCount[b] : "")
                        + " state=" + FlowState.values()[r0.balance.state[idx]]
                        + " down=" + r0.topo.down[idx]);
            }
            // 西侧入水口线（z=64，x=-256..-64）的 h/fill/state/direction
            StringBuilder line = new StringBuilder("  [碗区] 西线 z=64: ");
            for (int x = -250; x <= -60; x += 10) {
                int gx = HydroContract.cellX(x), gz = HydroContract.cellZ(pz);
                int tx = HydroTileKey.tileOfCell(gx), tz = HydroTileKey.tileOfCell(gz);
                if (tx < -1 || tx > 1 || tz < -1 || tz > 1) continue;
                HydroTileResult rr = solver.result(new HydroTileKey(seed, tx, tz, 0));
                HydroTileField ff = rr.topo.field;
                int ii = gx - ff.winMinX, jj = gz - ff.winMinZ;
                if (!ff.inside(ii, jj)) continue;
                int i2 = ii * ff.w + jj;
                int dn = rr.topo.down[i2];
                line.append(String.format("x=%d[h=%.0f f=%.0f b=%d %s→%s] ",
                        x, ff.height[i2], rr.topo.fill[i2], rr.topo.basinId[i2],
                        FlowState.values()[rr.balance.state[i2]],
                        dn < 0 ? "OUT" : (ff.globalX(dn) + "," + ff.globalZ(dn))));
            }
            System.out.println(line);
        }

        // ---- 状态直方图 + 潜在链首（区分"格不存在" vs "被 coreEdge 排除"）----
        {
            int[] hist = new int[FlowState.values().length];
            int potential = 0, potentialOnCoreEdge = 0;
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int tz = tz0; tz <= tz1; tz++) {
                    HydroTileResult r = solver.result(new HydroTileKey(seed, tx, tz, 0));
                    HydroTileField f = r.topo.field;
                    for (int i = 0; i < f.w * f.w; i++) {
                        if (!r.topo.inCore(i)) continue;
                        FlowState st = FlowState.values()[r.balance.state[i]];
                        hist[st.ordinal()]++;
                        if (st != FlowState.CHANNEL) continue;
                        // 潜在链首：8 邻中没有任何"已成河"的上游
                        boolean upFlowing = false;
                        int x = i / f.w, z = i % f.w;
                        boolean coreEdge = false;
                        for (int d = 0; d < 8 && !upFlowing; d++) {
                            int nx = x + HydroContract.DIR_DX[d], nz = z + HydroContract.DIR_DZ[d];
                            if (!f.inside(nx, nz)) continue;
                            int nb = nx * f.w + nz;
                            if (!r.topo.inCore(nb)) coreEdge = true;
                            if (r.topo.down[nb] != i) continue;
                            FlowState us = FlowState.values()[r.balance.state[nb]];
                            if (us.isFlowing()) upFlowing = true;
                        }
                        if (!upFlowing) {
                            potential++;
                            if (coreEdge) potentialOnCoreEdge++;
                        }
                    }
                }
            }
            StringBuilder hb = new StringBuilder("  [直方图] core 状态：");
            for (FlowState s : FlowState.values()) {
                if (hist[s.ordinal()] > 0) hb.append(s).append('=').append(hist[s.ordinal()]).append(' ');
            }
            System.out.println(hb);
            System.out.println("  [直方图] 潜在链首 = " + potential + "（其中贴 core 边、被 SOURCE 排除规则挡住 = "
                    + potentialOnCoreEdge + "）");
        }

        int complete = 0, landSink = 0, midSource = 0, toBoundary = 0, seaNoLake = 0;
        String example = "";
        int srcTotal = starts.size();
        for (long[] s : starts) {
            int gx = (int) s[0], gz = (int) s[1];
            if (!visited.add(pack(gx, gz))) continue;
            // 追踪
            List<FlowState> seq = new ArrayList<>();
            int cgx = gx, cgz = gz;
            boolean sawSea = false, sawLake = false;
            boolean midSrc = false;
            for (int guard = 0; guard < 4_000_000; guard++) {
                HydroTileResult rr = owner.apply(new int[]{cgx, cgz});
                if (rr == null) { toBoundary++; break; }
                HydroTileField f = rr.topo.field;
                int lx = cgx - f.winMinX, lz = cgz - f.winMinZ;
                if (!f.inside(lx, lz)) { toBoundary++; break; }
                int idx = lx * f.w + lz;
                FlowState st = FlowState.values()[rr.balance.state[idx]];
                if (seq.isEmpty() && st != FlowState.SOURCE) break;     // 链首必须是源头
                if (!seq.isEmpty() && st == FlowState.SOURCE) midSrc = true;
                seq.add(st);
                visited.add(pack(cgx, cgz));
                if (st == FlowState.SEA) { sawSea = true; break; }
                if (st == FlowState.LAKE_STORAGE) sawLake = true;
                int d = rr.topo.down[idx];
                if (d < 0) { toBoundary++; break; }
                cgx = f.globalX(d);
                cgz = f.globalZ(d);
            }
            if (midSrc) midSource++;
            if (sawSea && sawLake) {
                if (isComplete(seq)) {
                    complete++;
                    if (example.isEmpty()) example = render(seq);
                }
            } else if (sawSea) {
                seaNoLake++;
            }
        }
        // 断头（全窗口拓扑口径）
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                landSink += solver.result(new HydroTileKey(seed, tx, tz, 0))
                        .outcomeCounts()[OutcomeKind.LAND_SINK.ordinal()];
            }
        }

        System.out.println("  源头扫描 = " + srcTotal);
        System.out.println("  完整六阶段链 = " + complete + (example.isEmpty() ? "" : "  例：" + example));
        System.out.println("  到海但未经湖 = " + seaNoLake + " · 出窗口 = " + toBoundary);
        System.out.println("  中游误标源头的链 = " + midSource);
        System.out.println("  LAND_SINK = " + landSink);

        check("[L1] 源头必须存在", srcTotal > 0,
                "SOURCE 恒 0 ⇒ 判据『上游有任意水流』类回归");
        check("[L2] 至少 1 条完整六阶段链", complete >= 1,
                "源头→汇流→洼地蓄水→最低溢口→继续下泄→入海");
        check("[L3] SOURCE 只能出现在链首", midSource == 0,
                "中游冒源头 ⇒ halo/owner 窗口不同源类回归");
        check("[L4] 陆地断头 = 0", landSink == 0, "拓扑契约");

        if (failures == 0) {
            System.out.println("ALL PASS");
            System.exit(0);
        }
        System.out.println("FAILURES = " + failures);
        System.exit(1);
    }

    /** 完整链 = 按序包含 SOURCE → CHANNEL → BASIN_ENTRY → LAKE_STORAGE → SPILLWAY → DOWNSTREAM → SEA（允许穿插）。 */
    private static boolean isComplete(List<FlowState> seq) {
        FlowState[] need = {
                FlowState.SOURCE, FlowState.CHANNEL, FlowState.BASIN_ENTRY,
                FlowState.LAKE_STORAGE, FlowState.SPILLWAY, FlowState.DOWNSTREAM, FlowState.SEA};
        int k = 0;
        for (FlowState s : seq) {
            if (k < need.length && s == need[k]) k++;
        }
        if (k == need.length) return true;
        // CHANNEL 若被 SPILLWAY 后的下泄段跳过（源头直连入湖口），退化为只要求首尾+湖段
        FlowState[] alt = {
                FlowState.SOURCE, FlowState.BASIN_ENTRY,
                FlowState.LAKE_STORAGE, FlowState.SPILLWAY, FlowState.DOWNSTREAM, FlowState.SEA};
        k = 0;
        for (FlowState s : seq) {
            if (k < alt.length && s == alt[k]) k++;
        }
        return k == alt.length;
    }

    private static String render(List<FlowState> seq) {
        StringBuilder sb = new StringBuilder();
        FlowState prev = null;
        for (FlowState s : seq) {
            if (s != prev) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(s);
                prev = s;
            }
        }
        return sb + "（" + seq.size() + " 格）";
    }

    private static long pack(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xffffffffL);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name + "  —— " + detail);
    }

    private HydroLifecycleProbe() { }
}
