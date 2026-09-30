package com.geogenesis.worldgen.hydrology.sim;

/**
 * 新水文核心的【数据契约】（零 MC 依赖）。
 *
 * <h2>为什么单独固化契约</h2>
 * <p>本重构的失败模式全部来自"同一概念被两套口径表达"：旧链同时存在 e 空间与块空间、
 * 生产湖与模拟湖、region 局部格点与全球格点。故先把单位/索引/序/守恒判据写成常量与
 * 注释，后续每一层只允许引用这里，禁止就地另行解释。</p>
 *
 * <h2>单位（强制）</h2>
 * <ul>
 *   <li>长度：<b>方块（block, double）</b>；不使用 wu。需要 wu 时由适配器在边界换算。</li>
 *   <li>面积：block²；体积：block³；流量：block³/s（模型时间，见下）。</li>
 *   <li>模型时间：世界生成不需要真实时间轴，统一取"单位时间"= 1，
 *       故 {@code source} 量纲为 block³/单位时间，{@code decay} 量纲为 1/block
 *       （沿程衰减系数，geotransport 的 {@code exp(-∫decay ds)} 形式）。</li>
 *   <li>高度：绝对方块 Y（含负值）；海平面由 {@link #seaLevelY} 注入。</li>
 * </ul>
 *
 * <h2>索引方向（强制）</h2>
 * <ul>
 *   <li>水文格 {@code (gx, gz)} 覆盖方块 {@code [gx*cellBlocks, (gx+1)*cellBlocks)}。</li>
 *   <li>格点<b>全球对齐</b>：{@code gx = floorDiv(blockX, cellBlocks)}。
 *       同一世界点在任意 tile 中都是同一格 —— 这是跨 tile 一致的唯一前提。</li>
 *   <li>一维下标统一 <b>x 主序</b>：{@code idx = gxLocal * width + gzLocal}
 *       （与项目既有 {@code cells[lx*16 + lz]} 约定一致）。</li>
 *   <li>负坐标一律 {@link Math#floorDiv(int, int)} / {@link Math#floorMod(int, int)}，
 *       禁止 Java 截断除法（{@code -1/4 == 0} 会把负世界整体错位一格）。</li>
 * </ul>
 *
 * <h2>确定性（强制）</h2>
 * <ul>
 *   <li>所有 tie-break 使用固定字典序 {@code (value, globalX, globalZ, directionOrdinal)}；
 *       见 {@link #compareCells(double, int, int, double, int, int)}。</li>
 *   <li>禁止 {@code HashSet/HashMap} 的迭代顺序参与结果；遍历一律用固定方向序或排好序的数组。</li>
 *   <li>禁止并行 {@code atomicAdd} 式汇总顺序依赖；浮点求和按固定 global 顺序执行。</li>
 * </ul>
 *
 * <h2>守恒判据（验收口径）</h2>
 * <pre>
 *   source + inflow - evaporation - infiltration
 *     = deltaStorage + outflow + boundaryLoss
 * </pre>
 * <p>稳态（世界生成）取 {@code deltaStorage = 0}：湖蓄满后全部通过最低溢口下泄。</p>
 */
public final class HydroContract {

    private HydroContract() { }

    /** 契约版本：任何改变单位/索引/守恒口径的改动都必须递增，并让缓存失效。 */
    public static final int CONTRACT_VERSION = 1;

    /** 求解器版本：算法改动但契约不变时递增（缓存键的一部分）。 */
    public static final int SOLVER_VERSION = 1;

    /**
     * 水文格边长（方块）。1 = 逐块（最精确、最贵）。
     *
     * <p>★ 2026-09-30 由 <b>4 → 2</b>（用户判据"河像一个个方块拼起来"）：
     * 4 块格 ⇒ 中心线折点 / 河宽 / 水面最多每 4 块一变 ⇒ 河道呈 4 块阶梯，
     * 即使几何层做了段内插值也消不掉"每 4 块一个拐点"的观感。
     * 降到 2 块：台阶间距减半、宽度量化减半，代价是 field 采样 ×4
     * （有 {@link HydroCellSampler} 跨 tile memoization 兜底）。
     * 若日后仍嫌方块感，下一步是【几何层亚格细化】（沿真实地形梯度在原两格间插入
     * 1 块精度点），比继续降本值便宜得多。</p>
     */
    public static final int CELL_BLOCKS = 2;

    /** 每个 tile 的核（core）格数：tile 覆盖 {@code coreCells × coreCells} 格。 */
    public static final int TILE_CORE_CELLS = 64;

    /** halo 格数：窗口 = core + 2·halo。halo 是同一全球格点阵的只读输入，不是邻居结果副本。 */
    public static final int TILE_HALO_CELLS = 8;

    /** 世界生成不设真实时间轴；稳态求解下 lake 蓄放视为瞬时，故此值仅用于文档与量纲。 */
    public static final double UNIT_TIME = 1.0;

    /** 海平面 Y（方块）。由生产接线注入真实值；核心内部只作比较。 */
    public static final double seaLevelY = 62.0;

    /** 判定"同一水面"的高度容差（方块）。小于此差视为同一平面，用于湖面平坦化与溢口判定。 */
    public static final double LEVEL_EPS = 1e-6;

    /** 填洼 ε 微坡（方块/步）：无此微坡则填洼平面 D8 找不到下坡，河流在洼地死尾。 */
    public static final double FILL_EPS = 1e-5;

    /** 守恒判据容差（相对）：{@code |源-汇| <= tol · max(1, |source|)}。 */
    public static final double CONSERVATION_TOL = 1e-9;

    /**
     * 递归/闭包求解的默认上游环数上限；达到上限 ⇒ 该方向上游水量保守省略（规范截断）。
     *
     * <p>★ 2026-09-29 由 6 → <b>2</b>（创建世界卡死实测）：闭包 field 采样是每个
     * 上游 tile 一次 {@code erodedHeightForRouting}（含侵蚀 tile 同步生成），半径 6
     * 时单次 resolve 可触及数十上游 tile ⇒ 实测 view probe 25 tile 窗口解析 141~245s。
     * 半径 2（≤5×5 tile）把上游水量语境截在 ~512 块内 —— 对成河阈值（几十格汇流）
     * 与宽深标定足够；截断是<b>结构属性</b>（由查询根自决）⇒ 仍规范、顺序无关。</p>
     */
    public static final int DEFAULT_MAX_RINGS = 2;

    /**
     * 固定 8 邻方向序。ordinal 参与 tie-break ⇒ <b>顺序本身是契约的一部分，不得重排</b>。
     * 顺序：E, NE, N, NW, W, SW, S, SE（先东后顺时针）。
     */
    public static final int[] DIR_DX = { 1, 1, 0, -1, -1, -1, 0, 1 };
    public static final int[] DIR_DZ = { 0, -1, -1, -1, 0, 1, 1, 1 };

    /** 方向名的固定顺序，用于诊断输出（与 ordinal 一一对应）。 */
    public static final String[] DIR_NAME = { "E", "NE", "N", "NW", "W", "SW", "S", "SE" };

    /** 8 邻距离权重（方块）：轴向 1，对角 √2；用于以真实距离计算衰减与坡度。 */
    public static final double AXIAL_DIST = 1.0;
    public static final double DIAG_DIST = Math.sqrt(2.0);

    public static double dirDist(int ordinal) {
        return (ordinal % 2 == 0) ? AXIAL_DIST : DIAG_DIST;
    }

    /**
     * 契约规定的全局格点全序比较：先比数值，再比 globalX，再比 globalZ。
     *
     * <p>所有"并列取谁"的场合（填洼出队、下游选择、盆地归属、端口归并）必须调用本方法，
     * 禁止依赖容器迭代顺序。</p>
     *
     * @return 负数表示 a 应排在 b 之前
     */
    public static int compareCells(double va, int ax, int az, double vb, int bx, int bz) {
        int c = Double.compare(va, vb);
        if (c != 0) return c;
        c = Integer.compare(ax, bx);
        if (c != 0) return c;
        return Integer.compare(az, bz);
    }

    /** 世界方块 X → 全球水文格 gx（floorDiv 语义，负坐标正确）。 */
    public static int cellX(double blockX) {
        return Math.floorDiv((int) Math.floor(blockX), CELL_BLOCKS);
    }

    /** 世界方块 Z → 全球水文格 gz（floorDiv 语义，负坐标正确）。 */
    public static int cellZ(double blockZ) {
        return Math.floorDiv((int) Math.floor(blockZ), CELL_BLOCKS);
    }

    /** 全球格 gx 的格心方块 X。 */
    public static double cellCenterX(int gx) {
        return (gx + 0.5) * (double) CELL_BLOCKS;
    }

    /** 全球格 gz 的格心方块 Z。 */
    public static double cellCenterZ(int gz) {
        return (gz + 0.5) * (double) CELL_BLOCKS;
    }

    /** 缓存键哈希：契约/求解器/配置任一变化都必须使旧结果失效。 */
    public static long cacheSalt(long seed, int configHash) {
        long h = seed;
        h = h * 1000003L + CONTRACT_VERSION;
        h = h * 1000003L + SOLVER_VERSION;
        h = h * 1000003L + configHash;
        h = h * 1000003L + CELL_BLOCKS;
        h = h * 1000003L + TILE_CORE_CELLS;
        return h;
    }
}
