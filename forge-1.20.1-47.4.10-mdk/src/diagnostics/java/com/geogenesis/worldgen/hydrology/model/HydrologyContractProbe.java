package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * P0 contracts for the river/lake lifecycle model.
 *
 * <p>Each case calls the current model API with a small adversarial field. A
 * failing case is reported and the process exits non-zero; a printed PASS is
 * therefore evidence from the real implementation, not a copied oracle.</p>
 */
public final class HydrologyContractProbe {
    private static final double EPS = 1e-9;

    public static void main(String[] args) {
        int failures = 0;
        failures += run("equal-level DAG accumulation", HydrologyContractProbe::equalLevelDag);
        failures += run("cell-size area scaling", HydrologyContractProbe::cellSizeScaling);
        failures += run("physical tiny-basin area", HydrologyContractProbe::physicalTinyBasin);
        failures += run("overflow representation is not capped", HydrologyContractProbe::overflowRepresentation);
        failures += run("basin identity chooses lowest rim", HydrologyContractProbe::basinIdentity);
        failures += run("grid step does not change the channel mask", HydrologyContractProbe::stepInvariance);
        failures += run("world coordinate block-to-wu mapping", HydrologyContractProbe::worldCoordinateMapping);
        System.out.printf("contracts=%d failures=%d%n", 7, failures);
        if (failures != 0) System.exit(1);
    }

    private static int run(String name, Runnable test) {
        try {
            test.run();
            System.out.println("[PASS] " + name);
            return 0;
        } catch (Throwable ex) {
            System.out.println("[FAIL] " + name + ": " + ex.getMessage());
            return 1;
        }
    }

    /**
     * Equal water levels are legal in a DAG; index order is not a topological order.
     *
     * <p><b>两个方向都必须测</b>（实测教训）：只测 "下游索引更大" 时，
     * 索引序遍历恰好等于拓扑序 ⇒ 有缺陷的实现也会通过（假绿）。
     * 必须补 "下游索引更小" 的方向才具判别力。</p>
     */
    private static void equalLevelDag() {
        // 方向 A：0→1→2（下游索引更大）
        double[] levelA = {0, 0, 0};
        int[] parentA = {1, 2, -1};
        boolean[] resolvedA = {true, true, true};
        RiverNetwork.Result a = RiverNetwork.build(3, 1, levelA, parentA, 1.0,
                Map.of(), resolvedA, new RiverNetwork.Params(0.1, 1.0, 1.0, 0.5, 100.0));
        requireClose(a.discharge()[0], 1.0, "A source discharge");
        requireClose(a.discharge()[1], 2.0, "A middle discharge");
        requireClose(a.discharge()[2], 3.0, "A sink discharge");

        // 方向 B：2→1→0（下游索引更小）—— 索引序遍历在此必然算错
        double[] levelB = {0, 0, 0};
        int[] parentB = {-1, 0, 1};
        boolean[] resolvedB = {true, true, true};
        RiverNetwork.Result b = RiverNetwork.build(3, 1, levelB, parentB, 1.0,
                Map.of(), resolvedB, new RiverNetwork.Params(0.1, 1.0, 1.0, 0.5, 100.0));
        requireClose(b.discharge()[2], 1.0, "B source discharge");
        requireClose(b.discharge()[1], 2.0, "B middle discharge");
        requireClose(b.discharge()[0], 3.0, "B sink discharge");
    }

    /** Changing the physical cell area scales flux, but not the solved water level. */
    private static void cellSizeScaling() {
        SyntheticLake lake = syntheticLake(4, 4, 0.0, 6.0);
        LakeWaterBalance.Climate climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        LakeWaterBalance.Result unit = new LakeWaterBalance(5, 5, 1.0, lake.bed, lake.surface)
                .dropTinyBasins(false).solve(climate, lake.sea, lake.border);
        LakeWaterBalance.Result doubleCell = new LakeWaterBalance(5, 5, 2.0, lake.bed, lake.surface)
                .dropTinyBasins(false).solve(climate, lake.sea, lake.border);
        require(unit.basins().size() == doubleCell.basins().size(), "basin count changed with cell size");
        require(!unit.basins().isEmpty(), "synthetic basin was not found");
        requireClose(unit.basins().get(0).level(), doubleCell.basins().get(0).level(),
                "water level changed with cell size");
        double ratio = doubleCell.basins().get(0).inflow() / unit.basins().get(0).inflow();
        requireClose(ratio, 4.0, "physical inflow area scaling");
    }

    /** A four-cell pit is tiny at cellSize=1 but physically visible at cellSize=2. */
    private static void physicalTinyBasin() {
        SyntheticLake lake = syntheticLake(4, 4, 0.0, 6.0);
        LakeWaterBalance.Climate climate = new LakeWaterBalance.Climate(1.0, 0.0, 1.0);
        LakeWaterBalance.Result oneBlock = new LakeWaterBalance(5, 5, 1.0, lake.bed, lake.surface)
                .solve(climate, lake.sea, lake.border);
        LakeWaterBalance.Result fourBlocks = new LakeWaterBalance(5, 5, 2.0, lake.bed, lake.surface)
                .solve(climate, lake.sea, lake.border);
        require(oneBlock.basins().isEmpty(), "one-block cells retained a sub-threshold physical basin");
        require(!fourBlocks.basins().isEmpty(), "larger physical cells lost a visible basin");
    }

    /** A large overflow must be represented as water, not silently reduced by a stream cap. */
    private static void overflowRepresentation() {
        int n = 20;
        int cells = n * n;
        double[] bed = new double[cells];
        double[] level = new double[cells];
        boolean[] resolved = new boolean[cells];
        boolean[] sea = new boolean[cells];
        int[] parent = new int[cells];
        Arrays.fill(resolved, true);
        Arrays.fill(parent, -1);
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int k = x * n + z;
                bed[k] = level[k] = n - x;
                if (x + 1 < n) parent[k] = (x + 1) * n + z;
                sea[k] = x == n - 1;
            }
        }
        ContinuousRiverNetwork.Params params = new ContinuousRiverNetwork.Params(
                0.03, 1.0, 0.1, 1.0, 1.5, 0.45, 100.0);
        ContinuousRiverNetwork.Result baseline = ContinuousRiverNetwork.build(
                bed, level, resolved, n, n, 1.0, params, 2_000, 0x51EEDL, 0,
                sea, parent, new boolean[cells], Map.of());
        Map<Integer, Double> overflow = new HashMap<>();
        overflow.put(1 * n + n / 2, 1_000.0);
        ContinuousRiverNetwork.Result fed = ContinuousRiverNetwork.build(
                bed, level, resolved, n, n, 1.0, params, 2_000, 0x51EEDL, 0,
                sea, parent, new boolean[cells], overflow);
        double increase = max(fed.flux()) - max(baseline.flux());
        require(increase > 800.0,
                "overflow increase=" + increase + " is below represented outflow 1000");
    }

    /** The nearest rim is intentionally higher; the lower, farther rim is the valid outlet. */
    private static void basinIdentity() {
        SyntheticLake lake = syntheticLake(4, 4, 0.0, 6.0);
        lake.bed[1 * 5 + 0] = 5.0; // nearest north rim: higher
        lake.bed[3 * 5 + 2] = 2.0; // farther east rim: lower
        double[] level = lake.surface.level().clone();
        level[1 * 5 + 0] = 5.0;
        level[3 * 5 + 2] = 2.0;
        EquilibriumWater.Surface surface = new EquilibriumWater.Surface(
                level, lake.surface.resolved(), lake.surface.parent(), 0, 0, 0);
        LakeWaterBalance.Result result = new LakeWaterBalance(5, 5, 1.0, lake.bed, surface)
                .dropTinyBasins(false)
                .solve(new LakeWaterBalance.Climate(1.0, 0.0, 1.0), lake.sea, lake.border);
        require(!result.basins().isEmpty(), "basin was not identified");
        require(!result.overflowAt().isEmpty(), "full basin has no overflow representation");
        int outlet = result.overflowAt().keySet().iterator().next();
        require(outlet == 3 * 5 + 2,
                "outlet=" + outlet + " selected nearest/high rim instead of lower rim");
    }

    /**
     * 诊断网格步长（cellSize）不得改变河道掩码。
     *
     * <p>x/z 是网格坐标、方向是单位向量 ⇒ 步长必须固定在网格空间。
     * 旧实现用 {@code 0.5 * cellSize}：step=4 时每步跨 2 格，
     * 跳过格心 ⇒ 同一世界点在 step=1/4 下得到不同河道。</p>
     */
    private static void stepInvariance() {
        int n = 24;
        double[] bed = new double[n * n];
        double[] level = new double[n * n];
        boolean[] resolved = new boolean[n * n];
        boolean[] sea = new boolean[n * n];
        int[] parent = new int[n * n];
        Arrays.fill(resolved, true);
        Arrays.fill(parent, -1);
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int k = x * n + z;
                // 一个倾斜平原 + 一条浅沟，保证流向确定、不需要湖泊/海。
                bed[k] = 20.0 - 0.30 * x - 0.10 * z - 1.5 * Math.exp(-Math.pow(z - 12, 2) / 8.0);
                level[k] = bed[k] + 0.05;
            }
        }
        ContinuousRiverNetwork.Params params = new ContinuousRiverNetwork.Params(
                0.03, 1.0, 0.05, 1.0, 1.5, 0.45, 100.0);
        ContinuousRiverNetwork.Result unit = ContinuousRiverNetwork.build(
                bed, level, resolved, n, n, 1.0, params, 20_000, 0x5EEDL, 0,
                sea, parent, new boolean[n * n], Map.of());
        ContinuousRiverNetwork.Result coarse = ContinuousRiverNetwork.build(
                bed, level, resolved, n, n, 4.0, params, 20_000, 0x5EEDL, 0,
                sea, parent, new boolean[n * n], Map.of());
        int diff = 0, onUnit = 0;
        for (int k = 0; k < unit.channel().length; k++) {
            if (unit.channel()[k]) onUnit++;
            if (unit.channel()[k] != coarse.channel()[k]) diff++;
        }
        require(onUnit > 0, "no channel formed in the step-invariance fixture");
        // ★ 直接断言【行进本身】而非只看最终掩码 —— 实测教训：
        //   本 fixture 上 step=1 与 step=4 的掩码碰巧相同（3×3 平滑把粗步长的空洞填平），
        //   只看掩码的判据【不具判别力】（旧代码下也 PASS）。
        //   而步长是"每步多少格"的直接后果 ⇒ 同一批流线的总步数必然随之改变：
        //     step=1 → 每步 0.5 格；step=4 → 每步 2.0 格（旧代码）⇒ 总步数差约 4×。
        require(unit.streamSteps() == coarse.streamSteps(),
                "marching depends on grid step: steps(step=1)=" + unit.streamSteps()
                        + " steps(step=4)=" + coarse.streamSteps());
        require(diff == 0,
                "channel mask changed with grid step: differing cells=" + diff + "/" + onUnit);
    }

    /**
     * 管线的地形回调必须做 block → wu 换算（{@code wu = block / horizontalScale}）。
     *
     * <p>{@code CellGenerator.sample/sampleWu} 的坐标语义是 <b>wu</b>，
     * 而 {@link HydrologyPipeline} 的 Request 契约是 <b>block</b>
     * （centerBlockX/radiusBlocks/step）。默认 {@code horizontalScale = 2.0}
     * ⇒ 直接把 block 传进去会采到**错误的世界位置**（2 倍错位），
     * 所有窗口产出的地形区域都不是契约声称的区域。</p>
     *
     * <p>判据：管线窗口中心格的 bed 必须等于 {@code sampleWu(cx/hs, cz/hs).height}。
     * 修复前（直接传 block）与该值必然不同（hs=2 ⇒ 采样点差一倍）。</p>
     */
    private static void worldCoordinateMapping() {
        com.geogenesis.worldgen.terrain.TerrainParams tp =
                com.geogenesis.worldgen.terrain.TerrainParams.defaults();
        double hs = tp.horizontalScale();
        require(hs != 1.0, "fixture requires hs != 1 (default is 2.0); otherwise contract is vacuous");
        com.geogenesis.worldgen.terrain.CellGenerator gen =
                new com.geogenesis.worldgen.terrain.CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(9139912035078620160L);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        int cx = -140, cz = 137, radius = 64, step = 4;
        HydrologyPipeline.Result r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, step, climate,
                        0, HydrologyPipeline.RiverModel.D8));
        int n = r.nx();
        int center = (n / 2) * n + n / 2;        // 中心格（n 为奇数）
        double expected = gen.sampleWu(cx / hs, cz / hs).height;
        requireClose(r.bed()[center], expected, "center bed (block→wu)");
    }

    private static SyntheticLake syntheticLake(int basinWidth, int basinHeight,
                                               double basinBed, double sill) {
        int n = 5;
        double[] bed = new double[n * n];
        Arrays.fill(bed, 10.0);
        for (int x = 1; x <= basinWidth / 2; x++) {
            for (int z = 1; z <= basinHeight / 2; z++) bed[x * n + z] = basinBed;
        }
        bed[0 * n + 2] = sill;
        bed[2 * n + 4] = sill;
        boolean[] sea = new boolean[n * n];
        boolean[] border = new boolean[n * n];
        int[] parent = new int[n * n];
        double[] level = new double[n * n];
        boolean[] resolved = new boolean[n * n];
        Arrays.fill(parent, -1);
        Arrays.fill(level, 10.0);
        Arrays.fill(resolved, true);
        for (int k = 0; k < bed.length; k++) level[k] = bed[k];
        for (int x = 1; x <= basinWidth / 2; x++) {
            for (int z = 1; z <= basinHeight / 2; z++) level[x * n + z] = sill;
        }
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            int k = x * n + z;
            border[k] = x == 0 || z == 0 || x == n - 1 || z == n - 1;
        }
        return new SyntheticLake(bed, new EquilibriumWater.Surface(level,
                resolved, parent, 0, 0, 0), sea, border);
    }

    private record SyntheticLake(double[] bed, EquilibriumWater.Surface surface,
                                 boolean[] sea, boolean[] border) { }

    private static double max(double[] values) {
        double result = 0;
        for (double value : values) result = Math.max(result, value);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireClose(double actual, double expected, String name) {
        if (Math.abs(actual - expected) > EPS) {
            throw new AssertionError(name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private HydrologyContractProbe() { }
}
