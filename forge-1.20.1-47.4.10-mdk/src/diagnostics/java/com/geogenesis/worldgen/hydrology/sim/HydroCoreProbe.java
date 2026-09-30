package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 新水文核心的对抗性验证探针（方案 §8 的 9 项矩阵）。
 *
 * <p>不依赖 MC，也不依赖任何"参照图"：判据全部是纯函数性质 ——
 * 生命周期闭环、湖唯一性、水量守恒、溢口最低性、跨 tile 一致、无限坐标、
 * 湿润/干旱、几何分离、以及反向验证（把错的东西做出来必须被测出来）。</p>
 *
 * <p>运行：{@code gradlew runHydroCoreProbe [-PprobeArgs="seed"]}</p>
 */
public final class HydroCoreProbe {

    private static int failures = 0;
    private static long seed = 12345L;

    public static void main(String[] args) {
        if (args.length > 0) seed = Long.parseLong(args[0]);
        System.out.println("=== HydroCoreProbe seed=" + seed + " ===");
        testContract();
        testLifecycleOnSynthetic();
        testSpillMinimalityAndLakeUniqueness();
        testConservation();
        testCrossTileOrderIndependence();
        testInfiniteCoordinates();
        testWetVsArid();
        testGeometrySeparation();
        testReverseValidation();
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASS");
            System.exit(0);
        } else {
            System.out.println("FAILURES = " + failures);
            System.exit(1);
        }
    }

    // ================= 合成地形 =================

    /** 圆锥（严格单调，无洼地）：所有水都应入海。 */
    private static HydroSampler cone(double cx, double cz, double r, double base, double peak) {
        return new HydroSampler() {
            @Override public double height(double x, double z) {
                double d = Math.hypot(x - cx, z - cz);
                double t = Math.max(0, 1 - d / r);
                return base + (peak - base) * t * t;
            }
        };
    }

    /**
     * 碗（单个闭合洼地 + 唯一最低溢口）。
     *
     * <p>⚠ <b>外侧必须下降</b>：若外侧继续上升，rim 就不是鞍点，水只能一路溢出到窗口边
     * ⇒ 那是"无界洼地"，判为未决才是对的（本项目曾因把这种地形当成"应该解析成功的碗"
     * 而误判引擎过保守）。</p>
     */
    private static HydroSampler bowl(double cx, double cz, double r, double rim, double bottom) {
        return new HydroSampler() {
            @Override public double height(double x, double z) {
                double d = Math.hypot(x - cx, z - cz);
                if (d >= r) return rim - (d - r) * 0.25;      // 外侧下降 ⇒ rim 是真鞍点
                double t = d / r;
                return rim - (rim - bottom) * (1 - t * t);
            }
        };
    }

    /** 阶梯台地：D8 在严格等高台上无下坡 ⇒ 检验 ε 微坡是否真的救活。 */
    private static HydroSampler stairs() {
        return new HydroSampler() {
            @Override public double height(double x, double z) {
                int s = (int) Math.floor(x / 40.0);
                return 90 + s * 6 - Math.floor(z / 200.0) * 12;
            }
        };
    }

    /** 鞍口 + 两个嵌套盆地（外盆包围内盆）。所有高度都在海平面之上。 */
    private static HydroSampler saddleNested() {
        return new HydroSampler() {
            @Override public double height(double x, double z) {
                double d1 = Math.hypot(x - 100, z - 100);
                double outer = 100 - 20 * Math.max(0, 1 - d1 / 120);
                double d2 = Math.hypot(x - 120, z - 90);
                double inner = 86 - 6 * Math.max(0, 1 - d2 / 40);
                double saddle = 98 + 2 * Math.sin(x * 0.01) * Math.cos(z * 0.01);
                return Math.max(70, Math.min(outer, Math.min(inner, saddle)));
            }
        };
    }

    /** 沿海平原 + 海：用于检验入海与干旱衰减。 */
    private static HydroSampler coast() {
        return new HydroSampler() {
            @Override public double height(double x, double z) {
                double base = 70 - (x + 400) * 0.03;         // 向东降到海里
                double ridge = 25 * Math.exp(-Math.pow((x + 120) / 90.0, 2));
                double noise = 6 * Math.sin(x * 0.021) * Math.cos(z * 0.017);
                return base + ridge + noise;
            }
        };
    }

    // ================= [0] 契约自检 =================

    private static void testContract() {
        section("[0] 契约自检（索引/负坐标/全序）");
        // 索引往返回
        boolean ok = true;
        int[] probes = {0, 1, -1, 3, -4, 4, 7, -8, 123456, -123456};
        for (int g : probes) {
            double c = HydroContract.cellCenterX(g);
            int back = HydroContract.cellX(c);
            if (back != g) { ok = false; System.out.println("      cellCenterX 往返失败 g=" + g + " back=" + back); }
            double c2 = HydroContract.cellCenterZ(g);
            if (HydroContract.cellZ(c2) != g) { ok = false; System.out.println("      cellCenterZ 往返失败 g=" + g); }
        }
        // 负方块坐标必须 floorDiv（负世界不错位）。
        // ⚠ 断言必须与【格距】无关：此前写死 -0.5→-1、-4.5→-2（那是 CELL_BLOCKS=4 的期望），
        //   格距改 2 后 -4.5 的格号本应是 -3 ⇒ 假失败。改为按 CELL_BLOCKS 计算期望。
        int cb = HydroContract.CELL_BLOCKS;
        if (HydroContract.cellX(-0.5) != -1
                || HydroContract.cellX(-(cb + 0.5)) != -2) {
            ok = false;
            System.out.println("      负坐标 floorDiv 语义错误（CELL_BLOCKS=" + cb + "）");
        }
        // 全序必须自洽（反对称）
        if (HydroContract.compareCells(5, 1, 2, 5, 1, 3) >= 0
                || HydroContract.compareCells(5, 1, 3, 5, 1, 2) <= 0) {
            ok = false;
            System.out.println("      compareCells 全序非自洽");
        }
        report("[0]", ok, "索引往返 / 负坐标 floorDiv / 全序自洽");
    }

    // ================= [1] 生命周期闭环 =================

    private static void testLifecycleOnSynthetic() {
        section("[1] 生命周期闭环（LAND_SINK 必须 = 0）");
        boolean ok = true;
        long sinkTotal = 0;
        HydroConfig cfg = HydroConfig.defaults();
        String[] names = {"cone", "bowl", "stairs", "saddleNested", "coast"};
        HydroSampler[] samplers = {cone(0, 0, 900, 40, 180), bowl(0, 0, 90, 130, 80),
                stairs(), saddleNested(), coast()};
        for (int i = 0; i < samplers.length; i++) {
            HydroWorldSolver solver = new HydroWorldSolver(seed, cfg, samplers[i]);
            solver.resolve(new HydroTileKey(seed, 0, 0, 0));
            // 扫 3×3 tile，统计归宿
            int[] total = new int[OutcomeKind.values().length];
            for (int tx = -1; tx <= 1; tx++) {
                for (int tz = -1; tz <= 1; tz++) {
                    HydroTileResult r = solver.resolve(new HydroTileKey(seed, tx, tz, 0));
                    int[] c = r.outcomeCounts();
                    for (int k = 0; k < c.length; k++) total[k] += c[k];
                }
            }
            long sink = total[OutcomeKind.LAND_SINK.ordinal()];
            sinkTotal += sink;
            boolean rowOk = sink == 0;
            if (!rowOk) ok = false;
            System.out.printf("      %-13s SEA=%d SPILL_TO_BASIN=%d CLOSED=%d PENDING=%d LAND_SINK=%d%n",
                    names[i], total[OutcomeKind.SEA.ordinal()],
                    total[OutcomeKind.SPILL_TO_BASIN.ordinal()],
                    total[OutcomeKind.CLOSED_BASIN.ordinal()],
                    total[OutcomeKind.BOUNDARY_PENDING.ordinal()], sink);
        }
        report("[1]", ok, "5 种合成地形 3×3 tile 内 LAND_SINK 合计 = " + sinkTotal);
    }

    // ================= [2] 溢口最低性 + 湖唯一性 =================

    private static void testSpillMinimalityAndLakeUniqueness() {
        section("[2] 湖泊唯一性 + 最低溢口（minimax）");
        boolean ok = true;
        // ★ 碗心放在 core 中部，r=90 —— 这是"溢口可精确求解"的正例。
        //   ⚠ 半径口径必须与格距解耦：此前按【块】给 90，而 core = 64 格 × CELL_BLOCKS
        //     块 ⇒ 格距 2 时 core 只有 128 块、碗半径 90 块 + rim 必溢出 core ⇒ 假失败。
        //     改为按【格】给：r = coreCells/2 再留 20% 余量。
        // ★ 碗心必须落在 core 【内部】，半径按格给（格距无关）。
        //   ⚠ 此前写死圆心块(130,130)：那是 CELL_BLOCKS=4 时 core(0..255 块)的中部；
        //     格距改 2 后 core 只有 0..127 块 ⇒ 圆心跑出 core ⇒ 假失败。
        //   core 中心（块）= (TILE_CORE_CELLS/2) × CELL_BLOCKS。
        double cb = HydroContract.CELL_BLOCKS;
        double coreCenter = HydroContract.TILE_CORE_CELLS * 0.5 * cb;
        double bowlR = HydroContract.TILE_CORE_CELLS * 0.3 * cb;   // 留足 rim 余量
        HydroWorldSolver solver = new HydroWorldSolver(seed, HydroConfig.defaults(),
                bowl(coreCenter, coreCenter, bowlR, 130, 80));
        HydroTileResult r = solver.resolve(new HydroTileKey(seed, 0, 0, 0));
        HydroTileTopology topo = r.topo;
        if (topo.basinCount == 0) {
            ok = false;
            System.out.println("      碗形地形竟然没有盆地 —— 洼地识别失败");
        }
        boolean anyResolved = false;
        for (int b = 0; b < topo.basinCount; b++) {
            if (topo.basinResolved(b)) anyResolved = true;
        }
        if (!anyResolved) {
            ok = false;
            System.out.println("      rim 在窗内的盆地竟被判为未决 —— 未决判定过保守（回归）");
        }
        for (int b = 0; b < topo.basinCount; b++) {
            // 唯一水位：盆内所有格共用同一个 spillLevel（无第二套定义）
            double lvl = topo.basinSpillLevel[b];
            int[] members = topo.basinMemberArray(b);
            if (members.length == 0) { ok = false; System.out.println("      盆地 " + b + " 无成员"); }
            // minimax 校验：spillLevel 必须等于"盆内格与盆外邻的 max(h) 的最小值"
            double recomputed = minimaxSpill(topo, members);
            if (Math.abs(recomputed - lvl) > 1e-9) {
                ok = false;
                System.out.printf("      盆地 %d minimax 不一致: 记录=%.6f 重算=%.6f%n", b, lvl, recomputed);
            }
            if (!topo.basinResolved(b)) continue;
            // 溢口格必须存在且方向合法
            int sc = topo.basinSpillCell[b];
            int sd = topo.basinSpillDir[b];
            if (sc < 0 || sd < 0) { ok = false; System.out.println("      盆地 " + b + " 已解析却无溢口"); }
        }
        System.out.println("      盆地数 = " + topo.basinCount + "（碗形地形应为 1）");
        if (topo.basinCount != 1) {
            ok = false;
            System.out.println("      碗形地形盆地数 ≠ 1 ⇒ 洼地被拆成多个物种");
        }
        // [2b] 被窗口切断的盆地必须【正确】判为未决（不能假装解析成功）
        HydroWorldSolver cut = new HydroWorldSolver(seed, HydroConfig.defaults(),
                bowl(0, 0, HydroContract.TILE_CORE_CELLS * cb * 1.6, 130, 80));   // 远超 core ⇒ rim 必在窗外
        HydroTileTopology cutTopo = cut.resolve(new HydroTileKey(seed, 0, 0, 0)).topo;
        boolean cutPending = false;
        for (int b = 0; b < cutTopo.basinCount; b++) if (!cutTopo.basinResolved(b)) cutPending = true;
        if (cutTopo.basinCount > 0 && !cutPending) {
            ok = false;
            System.out.println("      rim 在窗外的盆地竟被判为已解析 —— 会输出错误水位");
        } else {
            System.out.println("      被切断的盆地正确判为未决（rim 在窗外）");
        }
        report("[2]", ok, "盆地唯一水位 + minimax 重算一致 + 溢口合法 + 未决判定双向正确");
    }

    private static double minimaxSpill(HydroTileTopology topo, int[] members) {
        HydroTileField f = topo.field;
        int w = f.w;
        double best = Double.MAX_VALUE;
        for (int c : members) {
            int lx = c / w, lz = c % w;
            for (int d = 0; d < 8; d++) {
                int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                if (!f.inside(nx, nz)) continue;
                int nb = nx * w + nz;
                if (topo.basinId[nb] == topo.basinId[c]) continue;
                best = Math.min(best, Math.max(f.height[c], f.height[nb]));
            }
        }
        return best == Double.MAX_VALUE ? Double.NaN : best;
    }

    // ================= [3] 水量守恒 =================

    private static void testConservation() {
        section("[3] 水量守恒（source + inflow = evap + spill + boundaryOut）");
        boolean ok = true;
        HydroSampler[] samplers = {bowl(0, 0, 90, 130, 80), coast(), saddleNested()};
        for (HydroSampler s : samplers) {
            HydroWorldSolver solver = new HydroWorldSolver(seed, HydroConfig.defaults(), s);
            for (int tx = -1; tx <= 1; tx++) {
                for (int tz = -1; tz <= 1; tz++) {
                    HydroTileResult r = solver.resolve(new HydroTileKey(seed, tx, tz, 0));
                    HydroTileBalance b = r.balance;
                    double res = b.conservationResidual();
                    double lhs = b.totalSource + b.boundaryIn;
                    double tol = HydroContract.CONSERVATION_TOL * Math.max(1, Math.abs(lhs));
                    if (Math.abs(res) > tol) {
                        ok = false;
                        System.out.printf("      tile(%d,%d) 守恒破坏: 残差=%.6e (lhs=%.3f)%n",
                                tx, tz, res, lhs);
                    }
                }
            }
        }
        report("[3]", ok, "3 种地形 × 3×3 tile 守恒式成立（含 window 出口与未决滞留记账）");
    }

    // ================= [4] 跨 tile 顺序无关 =================

    private static void testCrossTileOrderIndependence() {
        section("[4] 跨 tile 顺序无关 + 逐位确定");
        boolean ok = true;
        HydroSampler s = coast();
        HydroConfig cfg = HydroConfig.defaults();
        // 顺序 A：从 (-1,-1) 起，行主序
        HydroWorldSolver a = new HydroWorldSolver(seed, cfg, s);
        List<int[]> orderA = new ArrayList<>();
        for (int tx = -1; tx <= 1; tx++) for (int tz = -1; tz <= 1; tz++) orderA.add(new int[]{tx, tz});
        // 顺序 B：从 (1,1) 起，反向
        HydroWorldSolver b = new HydroWorldSolver(seed, cfg, s);
        List<int[]> orderB = new ArrayList<>();
        for (int tx = 1; tx >= -1; tx--) for (int tz = 1; tz >= -1; tz--) orderB.add(new int[]{tx, tz});
        for (int[] t : orderA) a.resolve(new HydroTileKey(seed, t[0], t[1], 0));
        for (int[] t : orderB) b.resolve(new HydroTileKey(seed, t[0], t[1], 0));
        for (int[] t : orderA) {
            HydroTileResult ra = a.resolve(new HydroTileKey(seed, t[0], t[1], 0));
            HydroTileResult rb = b.resolve(new HydroTileKey(seed, t[0], t[1], 0));
            if (!sameBits(ra.balance.boundaryIn, rb.balance.boundaryIn)
                    || !sameBits(ra.balance.boundaryOut, rb.balance.boundaryOut)
                    || !sameBits(ra.balance.totalLoss, rb.balance.totalLoss)
                    || !sameStateArray(ra.balance.state, rb.balance.state)
                    || !sameLevelArray(ra.balance.waterLevel, rb.balance.waterLevel)) {
                ok = false;
                System.out.println("      tile(" + t[0] + "," + t[1] + ") 两种查询顺序结果不同");
            }
        }
        report("[4]", ok, "海岸地形 3×3 tile：正序 vs 倒序逐位一致");
    }

    // ================= [5] 无限坐标 =================

    private static void testInfiniteCoordinates() {
        section("[5] 无限坐标（负 / ±2^20 块）");
        boolean ok = true;
        HydroWorldSolver solver = new HydroWorldSolver(seed, HydroConfig.defaults(), coast());
        int[][] tiles = {{0, 0}, {-1, -1}, {-262144 / (HydroContract.TILE_CORE_CELLS * HydroContract.CELL_BLOCKS),
                -262144 / (HydroContract.TILE_CORE_CELLS * HydroContract.CELL_BLOCKS)},
                {262144 / (HydroContract.TILE_CORE_CELLS * HydroContract.CELL_BLOCKS),
                        262144 / (HydroContract.TILE_CORE_CELLS * HydroContract.CELL_BLOCKS)}};
        for (int[] t : tiles) {
            try {
                HydroTileResult r = solver.resolve(new HydroTileKey(seed, t[0], t[1], 0));
                int[] c = r.outcomeCounts();
                long sink = c[OutcomeKind.LAND_SINK.ordinal()];
                if (sink != 0) { ok = false; System.out.println("      tile(" + t[0] + "," + t[1] + ") LAND_SINK=" + sink); }
                System.out.printf("      tile(%d,%d) OK 状态[%s]%n", t[0], t[1], r.balance.stateSummary());
            } catch (RuntimeException e) {
                ok = false;
                System.out.println("      tile(" + t[0] + "," + t[1] + ") 抛异常: " + e);
            }
        }
        report("[5]", ok, "含 ±2^20 块处在内的 4 个 tile 无溢出/无异常");
    }

    // ================= [6] 湿润 vs 干旱 =================

    private static void testWetVsArid() {
        section("[6] 湿润 / 干旱 水量差异");
        boolean ok = true;
        HydroConfig cfg = HydroConfig.defaults();
        final double aridity = 0.02;
        HydroSampler wet = new HydroSampler() {
            @Override public double height(double x, double z) { return coast().height(x, z); }
            @Override public double source(double x, double z) { return 1.0; }
            @Override public double decay(double x, double z) { return 0.0; }
        };
        HydroSampler arid = new HydroSampler() {
            @Override public double height(double x, double z) { return coast().height(x, z); }
            @Override public double source(double x, double z) { return 0.25; }
            @Override public double decay(double x, double z) { return aridity; }
        };
        HydroWorldSolver sw = new HydroWorldSolver(seed, cfg, wet);
        HydroWorldSolver sa = new HydroWorldSolver(seed, cfg, arid);
        double wetOut = 0, aridOut = 0, wetEvap = 0, aridEvap = 0;
        for (int tx = -1; tx <= 1; tx++) {
            for (int tz = -1; tz <= 1; tz++) {
                HydroTileResult rw = sw.resolve(new HydroTileKey(seed, tx, tz, 0));
                HydroTileResult ra = sa.resolve(new HydroTileKey(seed, tx, tz, 0));
                wetOut += rw.balance.windowOut;
                aridOut += ra.balance.windowOut;
                wetEvap += rw.balance.totalLoss;
                aridEvap += ra.balance.totalLoss;
            }
        }
        System.out.printf("      湿润: windowOut=%.1f evap=%.1f%n", wetOut, wetEvap);
        System.out.printf("      干旱: windowOut=%.1f evap=%.1f%n", aridOut, aridEvap);
        if (!(wetOut > aridOut)) {
            ok = false;
            System.out.println("      干旱区出流未低于湿润区 ⇒ decay 未生效");
        }
        if (!(aridEvap > 0)) {
            ok = false;
            System.out.println("      干旱区蒸发为 0 ⇒ 蒸发平衡未生效");
        }
        if (Math.abs(wetEvap) > 1e-9) {
            ok = false;
            System.out.println("      湿润区（decay=0）出现蒸发 ⇒ 违反 decay 语义");
        }
        report("[6]", ok, "湿润出流 > 干旱出流；干旱蒸发 > 0；湿润蒸发 = 0");
    }

    private static long lakeCells(HydroTileResult r) {
        long n = 0;
        for (byte s : r.balance.state) {
            if (FlowState.values()[s] == FlowState.LAKE_STORAGE) n++;
        }
        return n;
    }

    // ================= [7] 几何分离 =================

    private static void testGeometrySeparation() {
        section("[7] 几何分离（曲流/宽深不得改变拓扑）");
        boolean ok = true;
        // ⚠ 阈值必须与格距解耦：写死数值会在改格距时假失败（面积 ÷4 ⇒ 同样地形不达标）。
        //   本项测"几何层只读"，与阈值无关 ⇒ 取"2 格汇流"的小阈值保证确有河道。
        HydroConfig probeCfg = HydroConfig.defaults()
                .withChannelThreshold(2.0 * HydroConfig.defaults().cellArea());
        HydroWorldSolver solver = new HydroWorldSolver(seed, probeCfg, coast());
        HydroTileResult r = solver.resolve(new HydroTileKey(seed, 0, 0, 0));
        byte[] before = r.balance.state.clone();
        int[] downBefore = Arrays.copyOf(r.topo.down, r.topo.down.length);
        List<HydroFlowGeometry.HydroPolyline> p1 = HydroFlowGeometry.centerlines(r);
        double m = HydroFlowGeometry.MOMENTUM;
        HydroFlowGeometry.MOMENTUM = 0.0;
        List<HydroFlowGeometry.HydroPolyline> p2 = HydroFlowGeometry.centerlines(r);
        HydroFlowGeometry.MOMENTUM = m;
        if (p1.isEmpty()) {
            // 空转即失效：禁止把"没东西可测"报告成 PASS
            ok = false;
            System.out.println("      中心线 0 条 ⇒ 判据空转（无法证明几何层只读）");
        }
        if (!Arrays.equals(before, r.balance.state)) {
            ok = false;
            System.out.println("      几何层修改了 FlowState（必须只读）");
        }
        if (!Arrays.equals(downBefore, r.topo.down)) {
            ok = false;
            System.out.println("      几何层修改了 D8 下游（必须只读）");
        }
        if (p1.size() != p2.size()) {
            ok = false;
            System.out.println("      惯性开关改变了中心线条数: " + p1.size() + " vs " + p2.size());
        } else {
            // 端点格索引必须一致（形状可不同，拓扑不可不同）
            for (int i = 0; i < p1.size(); i++) {
                int[] a = p1.get(i).cellIdx();
                int[] b = p2.get(i).cellIdx();
                if (!Arrays.equals(a, b)) {
                    ok = false;
                    System.out.println("      惯性开关改变了第 " + i + " 条中心线的格链");
                    break;
                }
            }
        }
        System.out.println("      中心线 " + p1.size() + " 条 · 段数一致 · 拓扑未变");
        report("[7]", ok, "开关惯性只改几何形状，不改 state/down/格链");
    }

    // ================= [8] 反向验证 =================

    private static void testReverseValidation() {
        section("[8] 反向验证（把错的东西做出来必须被测出）");
        boolean ok = true;
        // (a) 人为制造陆地断头：把某个内部格的下游抹掉并绕开边界/海
        HydroWorldSolver solver = new HydroWorldSolver(seed, HydroConfig.defaults(), coast());
        HydroTileResult r = solver.resolve(new HydroTileKey(seed, 0, 0, 0));
        HydroTileTopology topo = r.topo;
        HydroTileField f = topo.field;
        int victim = -1;
        for (int i = 0; i < f.w * f.w; i++) {
            // 只要"非海、非边框、有下游"即可（不再要求已成河道 —— 否则高阈值下会找不到样本而跳过判据）
            if (topo.inCore(i) && !topo.sea[i] && !f.isBorder(i) && topo.down[i] >= 0) {
                victim = i;
                break;
            }
        }
        if (victim < 0) {
            ok = false;
            System.out.println("      未找到内部有下游的格 ⇒ 判据空转（无法证明断头可被测出）");
        } else {
            // 直接在副本上模拟"断头"：把该格的 down 改成 -1，并检查判据能识别
            int saved = topo.down[victim];
            topo.down[victim] = -1;
            int[] c = r.outcomeCounts();
            long sink = c[OutcomeKind.LAND_SINK.ordinal()];
            topo.down[victim] = saved;
            if (sink == 0) {
                ok = false;
                System.out.println("      人为制造的陆地断头【未被检出】 ⇒ 判据无效");
            } else {
                System.out.println("      人为断头被检出: LAND_SINK=" + sink);
            }
        }
        // (b) 端口契约：egress/ingress 两侧必须算出同一规范 id
        HydroTileTopology t = r.topo;
        boolean idOk = true;
        for (HydroTileTopology.CoreEdge e : t.coreEgress()) {
            HydroTileKey.PortId id = e.egressPortId();
            // 下游格 = 上游格 + dir
            int ux = id.gx(), uz = id.gz();
            int vx = ux + HydroContract.DIR_DX[id.dir()];
            int vz = uz + HydroContract.DIR_DZ[id.dir()];
            // 下游 tile 的 ingress 侧规范 id 应为同一个
            int tx = HydroTileKey.tileOfCell(vx), tz = HydroTileKey.tileOfCell(vz);
            HydroTileKey down = new HydroTileKey(seed, tx, tz, 0);
            HydroTileField df = HydroTileField.sample(down, r.cfg, coast());
            HydroTileTopology dt = HydroTileTopology.build(df);
            int dlx = vx - df.winMinX, dlz = vz - df.winMinZ;
            boolean found = false;
            if (df.inside(dlx, dlz)) {
                int didx = dlx * df.w + dlz;
                for (HydroTileTopology.CoreEdge ie : dt.coreIngress()) {
                    if (ie.coreIdx() == didx) {
                        HydroTileKey.PortId iid = ie.ingressPortId();
                        if (iid.gx() == id.gx() && iid.gz() == id.gz() && iid.dir() == id.dir()) {
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (!found) {
                idOk = false;
                System.out.println("      端口 id 两侧不一致: " + id);
                break;
            }
        }
        if (!idOk) ok = false;
        else System.out.println("      端口契约: egress 与 ingress 两侧规范 id 全部匹配");
        report("[8]", ok, "断头可被测出 + 跨 tile 端口 id 两侧一致");
    }

    // ================= 工具 =================

    private static boolean sameBits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static boolean sameStateArray(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    private static boolean sameLevelArray(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!sameBits(a[i], b[i])) return false;
        }
        return true;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void report(String tag, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println("      " + (ok ? "PASS" : "FAIL") + " " + detail);
    }
}
