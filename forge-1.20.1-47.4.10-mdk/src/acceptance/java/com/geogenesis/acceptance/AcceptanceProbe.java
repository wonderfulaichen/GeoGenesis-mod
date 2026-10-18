package com.geogenesis.acceptance;

import com.geogenesis.worldgen.terrain.Cell;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

/**
 * 验收支线的判定与报告/出图（无事件、无生命周期）。
 *
 * <p>计划口径对齐 {@code GeoGenesisGenerator.fillTerrainColumn}：
 * {@code surfaceY=floor(cell.height)}；{@code water=riverWater||ocean}（ocean = height&lt;seaLevel）；
 * 水柱填充区间为 {@code [surfaceY+1, fillTop]}，{@code fillTop=max(floor(waterTop),floor(lipY))}。</p>
 *
 * <h3>水体身份（水 ≠ 含水流体）</h3>
 * <p>只有 {@code Blocks.WATER}（含水/流动水，均为 {@link LiquidBlock}）与 ICE / FROSTED_ICE
 * 算"水体方块"。</p>
 * <p><b>刻意排除</b>：① 岩浆（{@code FluidTags.LAVA}）；② <b>植被与含水固体</b>
 * （水草/海带/甘蔗/树叶/含水的台阶与栅栏等）—— 它们是"格子里有水流体"但不是水体方块，
 * 把它们当水会让"水面上长草"的列被误读成湿列。这类格单独记入
 * {@link Column#aboveWater()} 的 {@code waterlogged} 字段，供人判读。</p>
 *
 * <h3>两个"顶"必须分清（实机证据驱动）</h3>
 * <p>实测 westLip 四点：水面 Y=64 与计划一致，但 {@code topSolid} 达 65/74/69/65 ——
 * 那是水面之上的<b>植被/装饰</b>，不是地形。因此本类分成两个字段：</p>
 * <ul>
 *   <li>{@code topNonAirY}：最高<b>非空气</b>格，<b>含水体</b>（水面即其上界时 == waterTop）；</li>
 *   <li>{@code topNonWaterY}：最高<b>非空气且非水体</b>格，<b>含植被</b>。它
 *       <b>不是地形面</b>，不能当地表判据用。</li>
 * </ul>
 *
 * <h3>连通性按"计划水柱窗口"匹配，不认地下水</h3>
 * <p>先前 {@code wet()} 遇首个非空气即返回 false，会被水面之上的植被挡住而误报干列
 * （实测四条 link 全 false，而逐列水柱其实吻合）。现在改成：</p>
 * <ol>
 *   <li>整列扫描找水体（不提前 break、不因植被中断）；</li>
 *   <li>连通性用 {@code matched} 掩码 = 计划该列出水 <b>且</b> 实际在该列
 *       <b>计划水柱区间 [surfaceY+1, fillTop]</b> 内确实有水体 ——
 *       于是地下洞穴水不会被当成"地表水连通"。</li>
 * </ol>
 */
final class AcceptanceProbe {

    /** "无此值"哨兵（无水 / 无方块共用，报告显示 none）。 */
    static final int NO_WATER = Integer.MIN_VALUE;

    private static final int PANEL = 48;
    private static final int SCALE = 4;
    private static final int GAP = 8;
    /** 8 连通组件最小规模：只判"孤立单格"，故阈值 = 2（不设轴向段长阈值，避免误红窄对角河）。 */
    private static final int MIN_COMPONENT = 2;
    /** 连通性 BFS 搜索半径（块）。 */
    private static final int LINK_RADIUS = 12;
    private static final int WATER_RGB = 0x2F6FE0;
    private static final int MISSING_RGB = 0x202020;
    private static final int ABOVE_WATER_MAX = 24;

    enum Kind { SEAM, LIP_WEST, LIP_EAST, EXT_CHUNK }

    record Target(String label, int x, int z, Kind kind) { }

    /** 用户给定坐标；扩展 chunk 取该 chunk 中心块。 */
    static final List<Target> TARGETS = List.of(
            new Target("seam(-1020,508)", -1020, 508, Kind.SEAM),
            new Target("westLip(-636,696)", -636, 696, Kind.LIP_WEST),
            new Target("westLip(-636,700)", -636, 700, Kind.LIP_WEST),
            new Target("eastLip(-604,596)", -604, 596, Kind.LIP_EAST),
            new Target("eastLip(-608,600)", -608, 600, Kind.LIP_EAST),
            new Target("extChunk(34,-149)", (34 << 4) + 8, (-149 << 4) + 8, Kind.EXT_CHUNK));

    /** 单块是否到达 FULL（判据见 {@link #isReady}）。 */
    record ChunkJob(int cx, int cz) { }

    /** 任意列的湿判定（计划掩码 / 计划窗口匹配掩码）。 */
    interface WetFn { boolean wet(int x, int z); }

    /** 逐列计划查询（用于"按计划水柱窗口匹配实际水"）。 */
    interface PlanRef { Plan at(int x, int z); }

    /**
     * "某列在给定 Y 区间内是否有水体"的抽象。
     *
     * <p>抽成接口的唯一目的：离线自检可以在没有真实 {@code LevelChunk} 的情况下注入替身，
     * 从而把 link / coverage 的判定逻辑纳入负控。生产路径恒为
     * {@link #chunkWindowWet}（真实 BlockState 读回）。</p>
     */
    interface WindowWet { boolean wetIn(int x, int z, int fromY, int toY); }

    private AcceptanceProbe() { }

    // ===== 规划 =====

    /** 目标点所在 chunk 的 3x3 邻域（去重、保插入序）；全部必达 FULL。 */
    static void planChunks(List<Target> targets, ArrayDeque<ChunkJob> out) {
        Set<Long> seen = new LinkedHashSet<>();
        for (Target t : targets) {
            int cx0 = t.x() >> 4;
            int cz0 = t.z() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long key = (((long) (cx0 + dx)) << 32) | ((cz0 + dz) & 0xFFFFFFFFL);
                    if (seen.add(key)) out.add(new ChunkJob(cx0 + dx, cz0 + dz));
                }
            }
        }
    }

    static Plan plan(Cell cell, boolean hydrologyOn, double seaLevel) {
        double groundY = cell.height;
        boolean riverWater = hydrologyOn && cell.riverType != 0 && cell.riverSurfaceY > groundY;
        double waterTop = riverWater ? cell.riverSurfaceY : seaLevel;
        boolean ocean = hydrologyOn && groundY < seaLevel;
        if (ocean) waterTop = Math.max(waterTop, seaLevel);
        boolean water = hydrologyOn ? (riverWater || ocean) : (cell.isWater() || riverWater);
        int surfaceY = (int) Math.floor(groundY);
        int waterTopBlock = (int) Math.floor(waterTop);
        int lipBlock = (riverWater && cell.riverLipY > waterTop)
                ? (int) Math.floor(cell.riverLipY) : waterTopBlock;
        int fillTop = Math.max(waterTopBlock, lipBlock);
        int waterHeight = water ? Math.max(0, fillTop - surfaceY) : 0;
        return new Plan(water && waterHeight > 0, groundY, surfaceY, waterHeight > 0 ? fillTop : NO_WATER,
                waterTopBlock, lipBlock - waterTopBlock, waterHeight, riverWater, ocean,
                cell.riverType, cell.riverSurfaceY, cell.riverLipY, cell.isLake);
    }

    // ===== 读回（全列扫描，绝不提前 break）=====

    /**
     * 整列 BlockState 读回：全列扫描，水体段以 {@link Run} 列表给出（可检出中空）。
     *
     * <p>两个"顶"分别记录（见类注释）：{@code topNonAirY} 含水，{@code topNonWaterY} 含植被。
     * 另外记录水面之上的逐块明细，用于判断"是不是地层压在水上"。</p>
     */
    static Column read(LevelChunk chunk, int x, int z) {
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        int topNonAir = NO_WATER;
        int topNonWater = NO_WATER;
        List<Run> runs = new ArrayList<>();
        int runBottom = NO_WATER;
        int runTop = NO_WATER;
        int waterCount = 0;
        int waterloggedCount = 0;
        for (int y = maxY - 1; y >= minY; y--) {
            BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
            boolean waterBody = isWaterBody(state);
            boolean air = state.isAir();
            if (!air && topNonAir == NO_WATER) topNonAir = y;
            if (!air && !waterBody && topNonWater == NO_WATER) topNonWater = y;
            if (isWaterlogged(state)) waterloggedCount++;
            if (waterBody) {
                waterCount++;
                if (runTop == NO_WATER) runTop = y;
                runBottom = y;
            } else if (runTop != NO_WATER) {
                runs.add(new Run(runBottom, runTop));
                runTop = NO_WATER;
                runBottom = NO_WATER;
            }
        }
        if (runTop != NO_WATER) runs.add(new Run(runBottom, runTop));
        int waterTop = runs.isEmpty() ? NO_WATER : maxTop(runs);
        List<AboveBlock> above = aboveWaterDetail(chunk, x, z, waterTop, topNonAir);
        return new Column(chunk, x, z, minY, maxY, topNonAir, topNonWater, waterCount,
                waterloggedCount, runs, above);
    }

    private static int maxTop(List<Run> runs) {
        int best = NO_WATER;
        for (Run r : runs) best = Math.max(best, r.top());
        return best;
    }

    /** 水面之上（waterTop+1 .. topNonAir）逐块明细；只是记录，不参与判定。 */
    private static List<AboveBlock> aboveWaterDetail(LevelChunk chunk, int x, int z,
                                                    int waterTop, int topNonAir) {
        List<AboveBlock> out = new ArrayList<>();
        if (waterTop == NO_WATER || topNonAir == NO_WATER || topNonAir <= waterTop) return out;
        int from = Math.max(waterTop + 1, topNonAir - ABOVE_WATER_MAX + 1);
        for (int y = from; y <= topNonAir; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = chunk.getBlockState(pos);
            out.add(new AboveBlock(y, blockId(s), s.isAir(), isWaterlogged(s), s.blocksMotion()));
        }
        return out;
    }

    // ===== 水体身份 =====

    /**
     * 水体方块：{@code Blocks.WATER}（含水/流动水，均为 {@link LiquidBlock}）或冰。
     *
     * <p>不含岩浆；也不含"含水的固体/植被"（水草、海带、含水台阶等）—— 后者由
     * {@link #isWaterlogged} 单独报告。</p>
     */
    static boolean isWaterBody(BlockState state) {
        if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) return true;
        if (!state.getFluidState().is(FluidTags.WATER)) return false;
        return state.getBlock() instanceof LiquidBlock;
    }

    /** 该格是否"固体/植被 + 内部含水"（含水方块），不是水体方块。 */
    static boolean isWaterlogged(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) && !isWaterBody(state);
    }

    /** 兼容旧名：语义等同 {@link #isWaterBody}。 */
    static boolean isWater(BlockState state) {
        return isWaterBody(state);
    }

    /**
     * chunk 是否真的完成 FULL。
     *
     * <p>{@code LevelChunk.getStatus()} 恒返回 FULL，不可作判据。要求
     * {@code getChunk(...,FULL,true)} 返回 {@link LevelChunk} 且 {@code getChunkNow}
     * 指同一实例。</p>
     */
    static boolean isReady(Object access, LevelChunk inCacheNow) {
        return access instanceof LevelChunk chunk && chunk == inCacheNow;
    }

    // ===== 湿判定 =====

    /** 单列<b>任意深度</b>是否有水体（全列扫描，不被植被中断）。chunk 缺失 ⇒ false。 */
    static boolean wetAny(BiFunction<Integer, Integer, LevelChunk> lookup, int x, int z) {
        LevelChunk chunk = lookup.apply(x >> 4, z >> 4);
        if (chunk == null) return false;
        for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
            if (isWaterBody(chunk.getBlockState(new BlockPos(x, y, z)))) return true;
        }
        return false;
    }

    /** 单列在 {@code [fromY, toY]} 内是否有水体（真实 chunk 版本）。 */
    static boolean chunkWindowWet(BiFunction<Integer, Integer, LevelChunk> lookup,
                                  int x, int z, int fromY, int toY) {
        if (fromY == NO_WATER || toY == NO_WATER) return false;
        LevelChunk chunk = lookup.apply(x >> 4, z >> 4);
        if (chunk == null) return false;
        int lo = Math.max(fromY, chunk.getMinBuildHeight());
        int hi = Math.min(toY, chunk.getMaxBuildHeight() - 1);
        for (int y = lo; y <= hi; y++) {
            if (isWaterBody(chunk.getBlockState(new BlockPos(x, y, z)))) return true;
        }
        return false;
    }

    /** 生产用窗口查询器（真实 BlockState）。 */
    static WindowWet windowOf(BiFunction<Integer, Integer, LevelChunk> lookup) {
        return (x, z, fromY, toY) -> chunkWindowWet(lookup, x, z, fromY, toY);
    }

    /** 任意深度有水（诊断用掩码）。 */
    static WetFn anyDepthMask(BiFunction<Integer, Integer, LevelChunk> lookup) {
        return (x, z) -> wetAny(lookup, x, z);
    }

    /**
     * <b>计划窗口匹配</b>掩码：计划该列出水 <b>且</b> 实际在该列计划水柱区间
     * {@code [surfaceY+1, fillTop]} 内确有水体。
     *
     * <p>连通性与 link 检查一律用它 —— 地下洞穴水不在计划窗口内，因此不会被算成
     * "地表水连通"（那是假连接）。</p>
     */
    static WetFn matchedMask(PlanRef planRef, WindowWet window) {
        return (x, z) -> {
            Plan p = planRef.at(x, z);
            if (p == null || !p.water()) return false;
            return window.wetIn(x, z, p.surfaceY() + 1, p.waterTopY());
        };
    }

    /** 计划掩码（仅计划侧出水）。 */
    static WetFn planMaskOf(PlanRef planRef) {
        return (x, z) -> {
            Plan p = planRef.at(x, z);
            return p != null && p.water();
        };
    }

    // ===== 8 邻连通 =====

    /** 8 连通组件统计（窗口半径内的湿格；size 只计湿格）。 */
    record Component(int size, int minX, int maxX, int minZ, int maxZ) { }

    /** 以 (x,z) 为种子的 8 连通<b>湿</b>组件；种子本身不湿则 size=0。size 只计湿格。 */
    static Component component(WetFn mask, int x, int z, int radius) {
        if (!mask.wet(x, z)) return new Component(0, x, x, z, z);
        Set<Long> seen = new LinkedHashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen.add(key(x, z));
        q.add(new int[]{x, z});
        int size = 1;
        int minX = x, maxX = x, minZ = z, maxZ = z;
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cur[0] + dx;
                    int nz = cur[1] + dz;
                    if (Math.abs(nx - x) > radius || Math.abs(nz - z) > radius) continue;
                    if (!seen.add(key(nx, nz))) continue;
                    if (!mask.wet(nx, nz)) continue;      // 非湿格只标记访问，不计入 size
                    size++;
                    q.add(new int[]{nx, nz});
                    minX = Math.min(minX, nx);
                    maxX = Math.max(maxX, nx);
                    minZ = Math.min(minZ, nz);
                    maxZ = Math.max(maxZ, nz);
                }
            }
        }
        return new Component(size, minX, maxX, minZ, maxZ);
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** 两格是否在同一 8 连通湿组件内（精确 BFS，不用包围盒近似）。 */
    static boolean connected(WetFn mask, int x1, int z1, int x2, int z2, int radius) {
        if (!mask.wet(x1, z1) || !mask.wet(x2, z2)) return false;
        if (x1 == x2 && z1 == z2) return true;
        long target = key(x2, z2);
        Set<Long> seen = new LinkedHashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen.add(key(x1, z1));
        q.add(new int[]{x1, z1});
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cur[0] + dx;
                    int nz = cur[1] + dz;
                    if (Math.abs(nx - x1) > radius || Math.abs(nz - z1) > radius) continue;
                    long k = key(nx, nz);
                    if (k == target) return true;
                    if (!seen.add(k)) continue;
                    if (!mask.wet(nx, nz)) continue;
                    q.add(new int[]{nx, nz});
                }
            }
        }
        return false;
    }

    /** 整数列枚举（含两端，Bresenham）。西 lip 为轴向 5 列，东 lip 为对角 5 列。 */
    static List<int[]> integerColumns(int x1, int z1, int x2, int z2) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = x1 <= x2 ? 1 : -1;
        int sz = z1 <= z2 ? 1 : -1;
        int x = x1;
        int z = z1;
        int err = dx - dz;
        while (true) {
            out.add(new int[]{x, z});
            if (x == x2 && z == z2) break;
            int e2 = err * 2;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }
        return out;
    }

    // ===== 跨缝后继（从计划掩码查，不猜）=====

    /**
     * 从计划掩码里找跨缝后继：在种子 8 连通组件内，取另一侧（{@code z >= seamZ+1}）
     * 离缝最近、同 z 上 |x−x0| 最小的一列。找不到返回 null（计划本身没跨过去）。
     */
    static int[] planSuccessorAcross(WetFn planMask, int x0, int z0, int seamZ, int radius) {
        if (!planMask.wet(x0, z0)) return null;
        int bestX = Integer.MIN_VALUE;
        int bestZ = Integer.MIN_VALUE;
        int bestScore = Integer.MAX_VALUE;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = x0 + dx;
                int z = z0 + dz;
                if (z <= seamZ) continue;
                if (!planMask.wet(x, z)) continue;
                if (!connected(planMask, x0, z0, x, z, radius)) continue;
                int score = (z - seamZ) * 1000 + Math.abs(dx);
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        return bestX == Integer.MIN_VALUE ? null : new int[]{bestX, bestZ};
    }

    // ===== 判定 =====

    /**
     * 计划 vs 实际（逐点）。
     *
     * <p>计划出水 ⇒ {@code [surfaceY+1, fillTop]} <b>每一格</b>必须是水体/冰、水位顶 = {@code fillTop}、
     * 全列仅 1 段水（无中空）；计划不出水 ⇒ 全列 0 水体。
     * 地表 {@code surfaceY} 必须是实心地形（非空气、非水体）。</p>
     *
     * <p><b>连通性缺失不放行</b>：{@code continuity == null} 表示没探测，判 FAIL
     * （{@code notProbed}），不能当成"无连通问题"。</p>
     */
    static Point evaluate(Target target, Plan plan, ColumnView actual, Verdict continuity) {
        if (actual == null) return missing(target, "no column");
        boolean surfaceOk = !actual.airAt(plan.surfaceY()) && !actual.waterAt(plan.surfaceY());
        int badCells = 0;
        if (plan.water()) {
            for (int y = plan.surfaceY() + 1; y <= plan.waterTopY(); y++) {
                if (!actual.waterAt(y)) badCells++;
            }
        }
        boolean waterOk;
        String waterNote;
        if (plan.water()) {
            boolean topOk = actual.waterTop() == plan.waterTopY();
            boolean singleRun = actual.runs().size() == 1;
            boolean countOk = actual.waterCount() == plan.waterHeight();
            waterOk = badCells == 0 && topOk && singleRun && countOk;
            waterNote = "top=" + actual.waterTop() + " plan=" + plan.waterTopY()
                    + " height=" + actual.waterCount() + "/" + plan.waterHeight()
                    + " runs=" + actual.runs().size() + " badCells=" + badCells;
        } else {
            waterOk = actual.waterCount() == 0;
            waterNote = "top=" + describe(actual.waterTop()) + " count=" + actual.waterCount();
        }
        boolean continuityOk = continuity != null && !continuity.fatal();
        String continuityNote = continuity == null ? "notProbed" : continuity.text();
        int aboveDelta = actual.topNonWater() == NO_WATER
                ? Integer.MAX_VALUE : actual.topNonWater() - plan.surfaceY();
        boolean pass = surfaceOk && waterOk && continuityOk;
        String checks = "surface=" + (surfaceOk ? "ok" : "BAD(" + actual.blockIdAt(plan.surfaceY()) + ")")
                + " water=" + (waterOk ? "ok" : "BAD")
                + " continuity=" + (continuityOk ? "ok" : "BAD")
                + " aboveDelta=" + (aboveDelta == Integer.MAX_VALUE ? "n/a" : String.valueOf(aboveDelta));
        return new Point(target, plan, actual, continuityNote, pass ? "PASS" : "FAIL", pass,
                checks, waterNote);
    }

    /**
     * 8 连通性：只有<b>孤立单格</b>才判 FAIL（真河至少有一个 8 邻湿格）。
     *
     * <p>刻意不设轴向段长阈值 —— 窄对角河每一步轴向只前进 1 格，任何 MIN_RUN&gt;1
     * 都会误红。是否"接到计划连接目标"由显式 {@link Link} 检查负责。</p>
     */
    static Verdict continuity(Component comp) {
        if (comp.size() == 0) return new Verdict("dry", false);
        if (comp.size() < MIN_COMPONENT) {
            return new Verdict("isolatedWater(size=" + comp.size() + ")", true);
        }
        return new Verdict("ok(size=" + comp.size() + ")", false);
    }

    static Point missing(Target target, String why) {
        return new Point(target, null, null, "n/a", "MISSING", false,
                "chunk=notReady reason=" + why, "n/a");
    }

    static Point error(Target target, String message) {
        return new Point(target, null, null, "n/a", "ERROR", false, "read=" + message, "n/a");
    }

    private static String describe(int y) {
        return y == NO_WATER ? "none" : String.valueOf(y);
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    // ===== 显式连通要求（西 lip 段 / 东 lip 对角 / 跨缝后继）=====

    /** 一条必须成立的连通要求：两端点（及其间全部整数列）在同一 8 连通湿组件内。 */
    record Link(String label, int x1, int z1, int x2, int z2, List<int[]> columns) {
        static Link of(String label, int x1, int z1, int x2, int z2) {
            return new Link(label, x1, z1, x2, z2, integerColumns(x1, z1, x2, z2));
        }
    }

    /**
     * 连通检查结果。
     *
     * <p>三态而非布尔：{@code UNVERIFIED} 表示<b>计划本身没要求这条连通</b>（计划两端不在同一
     * 8 连通湿组件内），因此本次<b>根本没验到</b>实际连通性。它<b>不是通过</b>——
     * 否则"计划不连通"会伪装成 link 全绿，把覆盖缺口洗成验收证据。</p>
     */
    enum LinkStatus { PASS, FAIL, UNVERIFIED }

    record LinkResult(String label, LinkStatus status, String why, List<String> columnLines) {
        /** 只有 PASS 才算通过；UNVERIFIED 与 FAIL 都不算。 */
        boolean pass() { return status == LinkStatus.PASS; }
    }

    /**
     * 检查一条 Link（实际侧用<b>计划窗口匹配</b>掩码，不认地下水）。
     *
     * <ul>
     *   <li>计划两端不连通（含任一端干） ⇒ {@code UNVERIFIED}：本种子下无从验证，
     *       <b>不计通过</b>；</li>
     *   <li>计划连通 ⇒ 实际必须同样连通，且<b>每个计划出水的中间列</b>都在其自身计划水柱区间内
     *       检到水体，否则 {@code FAIL}。</li>
     * </ul>
     */
    static LinkResult checkLink(Link link, PlanRef planRef, WindowWet window) {
        WetFn planMask = planMaskOf(planRef);
        WetFn matched = matchedMask(planRef, window);
        List<String> lines = new ArrayList<>();
        int matchedCols = 0;
        int planWetCols = 0;
        for (int[] c : link.columns()) {
            Plan p = planRef.at(c[0], c[1]);
            boolean planWet = p != null && p.water();
            boolean actualMatched = matched.wet(c[0], c[1]);
            if (planWet) planWetCols++;
            if (actualMatched) matchedCols++;
            lines.add("  col=" + c[0] + "," + c[1]
                    + " planWet=" + planWet
                    + " planWindow=" + (planWet
                    ? (p.surfaceY() + 1) + ".." + p.waterTopY() : "none")
                    + " actualMatched=" + actualMatched);
        }
        boolean planConnected = connected(planMask, link.x1(), link.z1(),
                link.x2(), link.z2(), LINK_RADIUS);
        boolean actualConnected = connected(matched, link.x1(), link.z1(),
                link.x2(), link.z2(), LINK_RADIUS);
        String why = "planConnected=" + planConnected + " actualConnected=" + actualConnected
                + " planWetCols=" + planWetCols + "/" + link.columns().size()
                + " matchedCols=" + matchedCols + "/" + link.columns().size();
        if (!planConnected) {
            return new LinkResult(link.label(), LinkStatus.UNVERIFIED,
                    why + " (planNotConnected=unverified)", lines);
        }
        boolean allPlanWetColumnsMatched = matchedCols >= planWetCols;
        if (actualConnected && allPlanWetColumnsMatched) {
            return new LinkResult(link.label(), LinkStatus.PASS, why, lines);
        }
        String failWhy = why + (allPlanWetColumnsMatched ? "" : " (columnsMissingPlannedWater)");
        return new LinkResult(link.label(), LinkStatus.FAIL, failWhy, lines);
    }

    /**
     * 连通要求总量校验：<b>每条 Link 都必须 PASS</b>。
     *
     * <p>任一 {@code FAIL} ⇒ FAIL；任一 {@code UNVERIFIED} ⇒ 同样不绿（本次没验到 ≠ 通过）。
     * 这样"计划不连通"与"跨缝找不到后继"都无法把整体洗成绿。</p>
     *
     * @return null = 全部通过；否则不绿原因
     */
    static String linkGate(List<LinkResult> links) {
        if (links.isEmpty()) return "noLinksChecked";
        long fail = links.stream().filter(r -> r.status() == LinkStatus.FAIL).count();
        long unverified = links.stream().filter(r -> r.status() == LinkStatus.UNVERIFIED).count();
        if (fail > 0) return "linkFail=" + fail + "/" + links.size();
        if (unverified > 0) return "linkUnverified=" + unverified + "/" + links.size();
        return null;
    }

    /**
     * 目标 lip/seam 若计划预期湿则必须实际湿（不允许"计划湿但读回全干"静默通过）。
     * 扩展 chunk 允许该点干，由覆盖扫描 {@link #coverage} 负责。
     *
     * @return null = 通过；否则 FAIL 原因
     */
    static String pointGate(Plan plan, ColumnView actual, Kind kind) {
        if (plan == null || actual == null) return "planOrActualMissing";
        if (kind == Kind.EXT_CHUNK) return null;
        if (plan.water() && actual.waterCount() == 0) return "plannedWetButActualDry";
        return null;
    }

    // ===== 湿覆盖扫描（扩展 chunk 与整个已加载 3x3）=====

    /**
     * 湿覆盖结果。
     *
     * <p>{@code planWetColumns} = 扫描范围内计划出水的列数；{@code matchedColumns} = 其中
     * 实际在计划水柱区间内检到水的列数。</p>
     *
     * <p>⚠ {@code DRY} 与 {@code MISSING} 的区别是本类的关键语义：</p>
     * <ul>
     *   <li>{@code VERIFIED}：计划有湿列且全部匹配 —— 真正验到水；</li>
     *   <li>{@code DRY}：<b>单 chunk</b> 扫描且计划全干（如扩展 chunk 34,-149）——
     *       这是<b>合法</b>结果，仅记 {@code dryCoverage}，<b>不当作水证据</b>，也不判 FAIL；</li>
     *   <li>{@code PARTIAL}：计划有湿列但部分不匹配 ⇒ FAIL；</li>
     *   <li>{@code MISSING}：<b>多 chunk 汇总</b>范围零计划湿列 ⇒ {@code wetCoverageMissing}，
     *       整体不得绿（"整片都没计划出水"说明这一轮没验到水，不是合法干）。</li>
     * </ul>
     */
    record Coverage(String label, int chunksScanned, int columnsScanned, int planWetColumns,
                    int matchedColumns, String status, String detail) {
        /** VERIFIED 才算水证据；DRY 只是"合法无水可验"。 */
        boolean verifiedWater() { return "VERIFIED".equals(status); }
        /** 合法干：单 chunk 且计划全干。不当水证据，但也不判 FAIL。 */
        boolean dryLegal() { return "DRY".equals(status); }
        /** 覆盖门禁通过：VERIFIED 或 DRY。 */
        boolean passes() { return verifiedWater() || dryLegal(); }
        /** 整片零计划湿列 ⇒ 水覆盖未建立，整体不得绿。 */
        boolean missing() { return "MISSING".equals(status); }
    }

    /**
     * 扫描给定 chunk 集合内全部 16×16 列：统计计划湿列，并逐列验证实际在其计划水柱区间内有水。
     *
     * <p>成本受控：每列只读计划区间那 1~2 格，不整列扫。</p>
     *
     * <p><b>DRY vs MISSING</b>：只有<b>单 chunk</b> 扫描且计划全干才判 {@code DRY}
     * （扩展 chunk 的合法干）；多 chunk 汇总零湿列判 {@code MISSING}（覆盖未建立）。</p>
     */
    static Coverage coverage(String label, Iterable<int[]> chunkCoords, PlanRef planRef,
                             WindowWet window) {
        int chunks = 0;
        int columns = 0;
        int planWet = 0;
        int matched = 0;
        List<String> bad = new ArrayList<>();
        for (int[] cc : chunkCoords) {
            chunks++;
            int baseX = cc[0] << 4;
            int baseZ = cc[1] << 4;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    columns++;
                    Plan p = planRef.at(x, z);
                    if (p == null || !p.water()) continue;
                    planWet++;
                    boolean complete=true;
                    for(int y=p.surfaceY()+1;y<=p.waterTopY();y++)
                        if(!window.wetIn(x,z,y,y)){complete=false;break;}
                    if (complete) {
                        matched++;
                    } else if (bad.size() < 8) {
                        bad.add("    dryAt=" + x + "," + z
                                + " planWindow=" + (p.surfaceY() + 1) + ".." + p.waterTopY());
                    }
                }
            }
        }
        String status;
        if (planWet == 0) status = chunks == 1 ? "DRY" : "MISSING";
        else if (matched == planWet) status = "VERIFIED";
        else status = "PARTIAL";
        String detail = String.join("\n", bad);
        return new Coverage(label, chunks, columns, planWet, matched, status, detail);
    }

    /** 覆盖门禁：DRY / VERIFIED 通过；PARTIAL / MISSING 不绿。 */
    static String coverageGate(Coverage c) {
        if (c.missing()) return c.label() + "=wetCoverageMissing";
        if (!c.passes()) {
            return c.label() + "=partial(" + c.matchedColumns() + "/" + c.planWetColumns() + ")";
        }
        return null;
    }

    /**
     * 从 {@link net.minecraft.world.level.ChunkPos} 列表取 {@code {x, z}} 整型对（按 x 再 z 升序）。
     *
     * <p>★ 只读 {@code pos.x / pos.z}，<b>绝不手工解 {@code ChunkPos.asLong}</b> ——
     * 该格式是 <b>x 低 32 位 / z 高 32 位</b>，手解必错。实测
     * {@code asLong(528,-2400)} 的 {@code >>32 == -2400}、{@code (int) == 528}，
     * 旧写法 {@code (key>>32, key)} 会把 (528,-2400) 解成 (-2400,528)，
     * 导致覆盖扫描整片错误区域且零命中。</p>
     *
     * <p>放在本类（而非 Forge 主类）的原因：本类无事件/无静态 Forge 初始化，
     * 离线自检可直接用<b>真实 {@link net.minecraft.world.level.ChunkPos}</b>
     * 调用同一份代码做负控，而不是另写假公式自证。</p>
     */
    static List<int[]> chunkCoordsOf(List<net.minecraft.world.level.ChunkPos> positions) {
        List<int[]> out = new ArrayList<>(positions.size());
        for (net.minecraft.world.level.ChunkPos pos : positions) out.add(new int[]{pos.x, pos.z});
        out.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0])
                : Integer.compare(a[1], b[1]));
        return out;
    }

    // ===== 报告 =====

    record ChunkRecord(int cx, int cz, boolean ok, String why, long ms) { }

    static void writeReport(Path file, List<String> header, List<ChunkRecord> chunkRecords,
                            List<Point> points, List<LinkResult> links,
                            List<Coverage> coverages) throws IOException {
        List<String> lines = new ArrayList<>(header);
        lines.add("");
        lines.add("[links]");
        for (LinkResult r : links) {
            lines.add("link=" + r.label() + " status=" + r.status() + " pass=" + r.pass() + " " + r.why());
            lines.addAll(r.columnLines());
        }
        lines.add("");
        lines.add("[coverage]");
        for (Coverage c : coverages) {
            lines.add("coverage=" + c.label() + " status=" + c.status()
                    + " pass=" + c.passes() + " chunks=" + c.chunksScanned()
                    + " columns=" + c.columnsScanned()
                    + " planWet=" + c.planWetColumns() + " matched=" + c.matchedColumns());
            if (!c.detail().isEmpty()) lines.add(c.detail());
        }
        lines.add("");
        lines.add("[chunks]");
        for (ChunkRecord c : chunkRecords) {
            lines.add("chunk=" + c.cx() + "," + c.cz() + " ok=" + c.ok() + " ms=" + c.ms()
                    + (c.ok() ? "" : " why=" + c.why()));
        }
        lines.add("");
        lines.add("[points]");
        for (Point p : points) lines.addAll(pointLines(p));
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static List<String> pointLines(Point p) {
        List<String> out = new ArrayList<>();
        Target t = p.target();
        out.add("");
        out.add("[point]");
        out.add("label=" + t.label());
        out.add("kind=" + t.kind());
        out.add("x=" + t.x());
        out.add("z=" + t.z());
        out.add("chunkX=" + (t.x() >> 4));
        out.add("chunkZ=" + (t.z() >> 4));
        out.add("localX=" + (t.x() & 15));
        out.add("localZ=" + (t.z() & 15));
        out.add("status=" + p.status());
        out.add("pass=" + p.pass());
        out.add("checks=" + p.checks());
        out.add("water=" + p.waterNote());
        out.add("continuity=" + p.continuityNote());
        Plan pl = p.plan();
        if (pl != null) {
            out.add("plan.groundY=" + round3(pl.groundY()));
            out.add("plan.surfaceY=" + pl.surfaceY());
            out.add("plan.water=" + pl.water());
            out.add("plan.waterTopY=" + describe(pl.waterTopY()));
            out.add("plan.waterHeight=" + pl.waterHeight());
            out.add("plan.lipAboveTop=" + pl.lipAboveTop());
            out.add("plan.riverWater=" + pl.riverWater());
            out.add("plan.ocean=" + pl.ocean());
            out.add("plan.riverType=" + pl.riverType());
            out.add("plan.riverSurfaceY=" + round3(pl.riverSurfaceY()));
            out.add("plan.riverLipY=" + round3(pl.riverLipY()));
            out.add("plan.isLake=" + pl.isLake());
        }
        ColumnView av = p.actual();
        if (av != null) {
            out.add("actual.topNonAirY=" + describe(av.topNonAir()));
            out.add("actual.topNonWaterY=" + describe(av.topNonWater()));
            out.add("actual.topNonWaterNote=topmost non-air non-water; INCLUDES vegetation, NOT terrain surface");
            out.add("actual.waterTopY=" + describe(av.waterTop()));
            out.add("actual.waterBottomY=" + describe(av.waterBottom()));
            out.add("actual.waterCount=" + av.waterCount());
            out.add("actual.waterRuns=" + av.runs().size());
            out.add("actual.waterRunList=" + av.runsText());
            out.add("actual.waterloggedCells=" + av.waterloggedCount());
            if (pl != null) out.add("actual.surfaceBlock=" + av.blockIdAt(pl.surfaceY()));
            out.addAll(aboveWaterLines(av));
        }
        return out;
    }

    /** 水面之上逐块明细：ID / air / waterlogged / blocksMotion（只记录，不改判定）。 */
    private static List<String> aboveWaterLines(ColumnView av) {
        List<String> out = new ArrayList<>();
        List<AboveBlock> above = av.aboveWater();
        out.add("actual.aboveWaterCount=" + above.size());
        for (AboveBlock b : above) {
            out.add("  aboveWater y=" + b.y() + " id=" + b.id()
                    + " air=" + b.air() + " waterlogged=" + b.waterlogged()
                    + " blocksMotion=" + b.blocksMotion());
        }
        return out;
    }

    private static String round3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ===== 俯视 PNG（BlockState 回读图，非游戏截图）=====

    record Panel(String label, int x, int z) { }

    static List<Panel> panels(List<Target> targets) {
        List<Panel> out = new ArrayList<>();
        for (Target t : targets) out.add(new Panel(t.label(), t.x(), t.z()));
        return out;
    }

    static void writeTopView(Path file, List<Panel> panels,
                             BiFunction<Integer, Integer, LevelChunk> lookup) throws IOException {
        int cells = PANEL * SCALE;
        int width = panels.size() * cells + (panels.size() + 1) * GAP;
        BufferedImage img = new BufferedImage(width, cells + 2 * GAP, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < panels.size(); i++) {
            drawPanel(img, GAP + i * (cells + GAP), GAP, panels.get(i), lookup);
        }
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        ImageIO.write(img, "png", file.toFile());
    }

    private static void drawPanel(BufferedImage img, int ox, int oy, Panel panel,
                                  BiFunction<Integer, Integer, LevelChunk> lookup) {
        int half = PANEL / 2;
        for (int dz = 0; dz < PANEL; dz++) {
            for (int dx = 0; dx < PANEL; dx++) {
                int x = panel.x() - half + dx;
                int z = panel.z() - half + dz;
                fillCell(img, ox + dx * SCALE, oy + dz * SCALE,
                        columnColor(lookup.apply(x >> 4, z >> 4), x, z));
            }
        }
        int cx = ox + half * SCALE;
        int cy = oy + half * SCALE;
        for (int k = -SCALE; k < SCALE * 2; k++) {
            plot(img, cx + k, cy - SCALE, 0xFFFFFF);
            plot(img, cx + k, cy + SCALE * 2, 0xFFFFFF);
            plot(img, cx - SCALE, cy + k, 0xFFFFFF);
            plot(img, cx + SCALE * 2, cy + k, 0xFFFFFF);
        }
    }

    /** 俯视取色：自上而下找第一个水体或非空气格（水优先，与顶部植物无关）。 */
    private static int columnColor(LevelChunk chunk, int x, int z) {
        if (chunk == null) return MISSING_RGB;
        for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (isWaterBody(state)) return WATER_RGB;
            if (!state.isAir()) {
                MapColor color = state.getMapColor(chunk, pos);
                return color == null || color == MapColor.NONE ? 0x8A8A8A : color.col;
            }
        }
        return 0x101010;
    }

    private static void fillCell(BufferedImage img, int px, int py, int rgb) {
        for (int dy = 0; dy < SCALE; dy++) {
            for (int dx = 0; dx < SCALE; dx++) plot(img, px + dx, py + dy, rgb);
        }
    }

    private static void plot(BufferedImage img, int x, int y, int rgb) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return;
        img.setRGB(x, y, rgb);
    }

    // ===== 数据载体 =====

    record Plan(boolean water, double groundY, int surfaceY, int waterTopY,
                int waterTopBlock, int lipAboveTop, int waterHeight, boolean riverWater,
                boolean ocean, int riverType, double riverSurfaceY, double riverLipY,
                boolean isLake) { }

    /** 一段连续水体：{@code bottom..top}（含两端）。 */
    record Run(int bottom, int top) {
        int height() { return top - bottom + 1; }
        @Override public String toString() { return bottom + ".." + top; }
    }

    /** 水面之上的一格：只记录事实，不参与判定。 */
    record AboveBlock(int y, String id, boolean air, boolean waterlogged, boolean blocksMotion) { }

    /** 判定只依赖这些查询，故真实列与合成列共用同一套 evaluate。 */
    interface ColumnView {
        boolean airAt(int y);
        boolean waterAt(int y);
        int topNonAir();
        int topNonWater();
        int waterCount();
        int waterloggedCount();
        List<Run> runs();
        List<AboveBlock> aboveWater();
        String blockIdAt(int y);

        default int waterTop() {
            int best = NO_WATER;
            for (Run r : runs()) best = Math.max(best, r.top());
            return best;
        }

        default int waterBottom() {
            int best = NO_WATER;
            for (Run r : runs()) best = (best == NO_WATER) ? r.bottom() : Math.min(best, r.bottom());
            return best;
        }

        default String runsText() {
            StringBuilder sb = new StringBuilder();
            for (Run r : runs()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(r);
            }
            return sb.length() == 0 ? "none" : sb.toString();
        }
    }

    /** 真实 chunk 的整列读回。 */
    static final class Column implements ColumnView {
        private final LevelChunk chunk;
        private final int x;
        private final int z;
        private final int minY;
        private final int maxY;
        private final int topNonAir;
        private final int topNonWater;
        private final int waterCount;
        private final int waterloggedCount;
        private final List<Run> runs;
        private final List<AboveBlock> above;

        Column(LevelChunk chunk, int x, int z, int minY, int maxY, int topNonAir,
               int topNonWater, int waterCount, int waterloggedCount, List<Run> runs,
               List<AboveBlock> above) {
            this.chunk = chunk;
            this.x = x;
            this.z = z;
            this.minY = minY;
            this.maxY = maxY;
            this.topNonAir = topNonAir;
            this.topNonWater = topNonWater;
            this.waterCount = waterCount;
            this.waterloggedCount = waterloggedCount;
            this.runs = List.copyOf(runs);
            this.above = List.copyOf(above);
        }

        int minY() { return minY; }
        int maxY() { return maxY; }

        @Override public int topNonAir() { return topNonAir; }
        @Override public int topNonWater() { return topNonWater; }
        @Override public int waterCount() { return waterCount; }
        @Override public int waterloggedCount() { return waterloggedCount; }
        @Override public List<Run> runs() { return runs; }
        @Override public List<AboveBlock> aboveWater() { return above; }
        @Override public boolean airAt(int y) { return stateAt(y).isAir(); }
        @Override public boolean waterAt(int y) { return isWaterBody(stateAt(y)); }

        @Override public String blockIdAt(int y) {
            return blockId(stateAt(y));
        }

        BlockState stateAt(int y) {
            return chunk.getBlockState(new BlockPos(x, Math.max(minY, Math.min(maxY - 1, y)), z));
        }

        @Override
        public String toString() {
            return "topNonAir=" + describe(topNonAir) + " topNonWater=" + describe(topNonWater)
                    + " water=" + runsText() + " n=" + waterCount
                    + " logged=" + waterloggedCount;
        }
    }

    /**
     * 合成列（仅离线自检用）：给定哪些 Y 是水体、哪些是含水固体（岩浆/植被等价物）。
     *
     * <p>用于构造负控（水柱中空 / 岩浆 / 水面之上有植被）而不需要真实 chunk ——
     * 也不需要 MC 注册表 bootstrap（那在探针进程里不可用）。</p>
     */
    static ColumnView syntheticColumn(int[] waterYs) {
        return syntheticColumn(waterYs, new int[0]);
    }

    /**
     * 合成列：{@code waterYs} 为水体，{@code waterloggedYs} 为"含水的非水体格"
     * （岩浆/含水台阶/植被 —— 都不算水）。
     */
    static ColumnView syntheticColumn(int[] waterYs, int[] waterloggedYs) {
        return new ColumnView() {
            @Override public boolean waterAt(int y) {
                for (int wy : waterYs) if (wy == y) return true;
                return false;
            }
            @Override public boolean airAt(int y) { return false; }          // 合成列无空气格
            @Override public int topNonAir() { return Math.max(maxOf(waterYs), maxOf(waterloggedYs)); }
            @Override public int topNonWater() { return maxOf(waterloggedYs); }
            @Override public int waterCount() { return waterYs.length; }
            @Override public int waterloggedCount() {
                int n = 0;
                for (int y : waterloggedYs) {
                    boolean alsoWater = false;
                    for (int wy : waterYs) if (wy == y) alsoWater = true;
                    if (!alsoWater) n++;
                }
                return n;
            }
            @Override public List<AboveBlock> aboveWater() {
                int top = waterTop();
                List<AboveBlock> out = new ArrayList<>();
                if (top == NO_WATER) return out;
                int hi = topNonAir();
                for (int y = top + 1; y <= hi; y++) {
                    out.add(new AboveBlock(y, blockIdAt(y), false, isLogged(y), true));
                }
                return out;
            }
            @Override public String blockIdAt(int y) {
                if (waterAt(y)) return "minecraft:water";
                if (isLogged(y)) return "selftest:waterlogged_solid";
                return "selftest:solid";
            }

            private boolean isLogged(int y) {
                for (int ly : waterloggedYs) if (ly == y) return true;
                return false;
            }

            @Override public List<Run> runs() {
                int[] ys = waterYs.clone();
                java.util.Arrays.sort(ys);
                List<Run> out = new ArrayList<>();
                int i = 0;
                while (i < ys.length) {
                    int j = i;
                    while (j + 1 < ys.length && ys[j + 1] == ys[j] + 1) j++;
                    out.add(new Run(ys[i], ys[j]));
                    i = j + 1;
                }
                return List.copyOf(out);
            }
        };
    }

    private static int maxOf(int[] ys) {
        int best = NO_WATER;
        for (int y : ys) best = Math.max(best, y);
        return best;
    }

    record Verdict(String text, boolean fatal) { }

    record Point(Target target, Plan plan, ColumnView actual, String continuityNote,
                 String status, boolean pass, String checks, String waterNote) { }
}
