package com.geogenesis.worldgen.hydrology;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.OceanFeatures;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * R1-a：原始高度快照（double[256]）专用诊断 —— 只读，不改生产、不写用户 TOML。
 *
 * <p><b>被测的生产改动</b>（{@code GeoGenesisTerrain.generateChunkBody}）：
 * 在 {@code generator.sample(...)} 之后、{@code extractFromTile(...)} 之前保存
 * {@code double[256] originalHeights}；{@code riversEnabled && hs > 0.01} 时走
 * {@code HydrologyChunkEngine.calculateColumns → ModelHydrologyProvider.columnsForHeights}，
 * hs 特殊值（{@code <= 0.01} / NaN）仍走 {@code calculate(int,int)} 的原重采样。</p>
 *
 * <p><b>本探针要证明的三件事</b>：</p>
 * <ol>
 *   <li><b>采样等价</b>（默认轻模式）：对 gate 内的 hs，256 列<b>坐标原始位</b>与
 *       {@link HydrologyChunkSampling#sample} 内的块→wu 表达式逐位相同（显式整 chunk 检查，
 *       不靠"采样结果碰巧相同"绕过），且忽略坐标后 <b>height 原始位</b>与
 *       <b>全部 {@link Cell} 字段</b>相同、且不复用同一 Cell 实例
 *       （复用会让 {@code extractFromTile} 的就地改写污染快照语义）。</li>
 *   <li><b>特殊 hs 不得误用快照</b>（轻模式）：gate 镜像在这些 hs 上必须为 false，
 *       且两套映射确实分叉；并用真实 helper 反证回落坐标 = {@code block/max(0.01,hs)}
 *       —— 只证条件/分叉/回落映射，<b>不</b>要求构造非法 TerrainParams。</li>
 *   <li><b>快路径与公开路径等价 + 独立性/异常契约</b>（{@code --heavy}）：
 *       {@code calculateColumns(heights)} 与 {@code calculate(...).carvedColumns()}
 *       全部列字段逐位相同，且 {@code waterColumns > 0}（无水窗口一律判红，杜绝"空列也相等"的假绿）；
 *       {@code HydrologyChunkResult.originalCells()} 是完整独立 {@code Cell[256]}
 *       （跨调用不共享数组、调用方改写不外溢）；公开入口保留
 *       {@code IllegalArgumentException} 契约。heavy 用反射读 <b>terrain 自己持有的</b>
 *       {@code hydrologyExperiment}，不新建 engine（避免第二个 provider / 线程池与同源比较缺口）。</li>
 * </ol>
 *
 * <p><b>★ 三处必须手工同步的镜像</b>（生产改动后本探针可能假绿，看到即须改这里）：</p>
 * <ol>
 *   <li>{@link #snapshotCoord} ↔ 私有 {@code GeoGenesisTerrain.toWu}；</li>
 *   <li>{@link #snapshotGate} ↔ {@code generateChunkBody} 的 {@code hs > 0.01}（外加
 *       {@code riversEnabled}，heavy 模式单独查）；</li>
 *   <li>{@link #samplingCoord} ↔ {@code HydrologyChunkEngine} 构造的 {@code max(0.01, hscale)}
 *       与 {@link HydrologyChunkSampling#sample} 的 {@code block/hscale} 除法
 *       （<b>clamp 只在引擎</b>，helper 本身不 clamp）。</li>
 * </ol>
 *
 * <p><b>未覆盖</b>：Forge 实机装配 / 装饰 / 落块 / 真实耗时与 GC；{@code riversEnabled=false}
 * 分支（heavy 只把它当硬前提）；hs 特殊值的实跑路径（生产已用 gate 回避，本探针只镜像条件）；
 * 1-ulp 差异（{@code extractFromTile} 用 {@code bx*(1/hs)}、快照用 {@code bx/hs}）仅作 info 打印，
 * <b>不</b>作判据；{@code getChunkCells} 的真实接线（快照 → {@code calculateColumns} →
 * {@code applyHydrologyValley}）由主代理 A/B 全图对比覆盖，本探针只测 provider 层与公开入口。</p>
 *
 * <p><b>CLI</b>（{} 内为默认）：</p>
 * <pre>
 *   --self-test              纯本地自检（不建 CellGenerator、不读 TOML、不跑水文）
 *   --toml=&lt;path&gt;           {run/config/geogenesis-common.toml} 只读且<b>必需</b>；
 *                            缺失/非文件 ⇒ PRECONDITION + exit 2（绝不回落默认值）
 *   --seed=&lt;long&gt;           {9139912035078620160}
 *   --chunks="x,z;x,z;..."   {0,0;-1,-1;32,32;31,-33;100000,-100000}
 *   --heavy                  追加生产模式（默认 hs、单 chunk；会真跑一次水文 region）
 *   --chunk="x,z"            {14,3} = floorDiv(230,16), floorDiv(55,16)
 *                            —— perf 探针的 (230,55) 是【方块】窗中心，不是 chunk
 *   --help
 * 退出码 0=全过 1=判据失败 2=前提不成立 3=FATAL
 * </pre>
 */
public final class HydrologyHeightSnapshotProbe {

    /** 与 {@code GeoGenesisGenerator.WORLD_MIN_Y / WORLD_MAX_Y} 同值（生产构造参数）。 */
    static final int WORLD_MIN_Y = -64, WORLD_MAX_Y = 320;
    static final Path DEFAULT_TOML = Path.of("run", "config", "geogenesis-common.toml");
    /** 每次运行一个唯一子目录（Files.createTempDirectory），避免覆盖既有副本。 */
    static final Path WORK_ROOT = Path.of("build", "hydrology-hs-snapshot");
    /** heavy 默认窗口：perf 探针给的是【方块】窗中心 (230,55)，不是 chunk ⇒ 必须 floorDiv 16。 */
    static final int HEAVY_BLOCK_CX = 230, HEAVY_BLOCK_CZ = 55;
    static final int HEAVY_CX = Math.floorDiv(HEAVY_BLOCK_CX, 16), HEAVY_CZ = Math.floorDiv(HEAVY_BLOCK_CZ, 16);
    /** 水文探针族默认种子（ViewSpec.SEED），保证 heavy 默认窗口落在既有河窗口内。 */
    static final long DEFAULT_SEED = 9139912035078620160L;
    /** 覆盖 gate 内 5 个 hs；1/2/1.3/0.5/8 依次压 1.0 的 `hs != 1.0` 特例、整数倍、非二进制分数、小数、大整数。 */
    static final double[] HS_SWEEP = {1.0, 2.0, 1.3, 0.5, 8.0};
    /** 生产 gate 阈值：{@code generateChunkBody} 写的是 {@code hs > 0.01}。 */
    static final double SNAPSHOT_MIN_HS = 0.01;
    /** gate 必须为 false 的 hs：等于阈值、低于阈值、0、负数、NaN。 */
    static final double[] SPECIAL_HS = {SNAPSHOT_MIN_HS, 0.005, 0.0, -1.0, Double.NaN};
    /** 原点 / 负坐标符号边界 / region 边界(32 chunk=512 block) / 负奇数邻缝 / 远坐标(1.6M block)。 */
    static final int[][] DEFAULT_CHUNKS = {{0, 0}, {-1, -1}, {32, 32}, {31, -33}, {100000, -100000}};

    static final String USAGE = """
            HydrologyHeightSnapshotProbe —— R1-a 原始高度快照契约诊断（只读）

            --self-test              纯本地自检（改 height 一位必须抓红；不建地形、不读 TOML）
            --toml=<path>            {run/config/geogenesis-common.toml} 只读且必需；
                                     缺失 => PRECONDITION + exit 2（不回落默认值）。
                                     副本写到 build/hydrology-hs-snapshot/run-<随机>/ 下：
                                     config-input.toml（原副本）+ config-effective.toml（工作副本）
            --seed=<long>            {9139912035078620160}
            --chunks="x,z;x,z;..."   {0,0;-1,-1;32,32;31,-33;100000,-100000}
            --heavy                  追加生产模式：默认 hs 下实测
                                     calculateColumns(heights) vs calculate().carvedColumns()
                                     全字段 + waterColumns>0、originalCells 独立性、异常契约
                                     （会跑一次水文 region；engine 取自 terrain 自身）
            --chunk="x,z"            {14,3} = floorDiv(230,16), floorDiv(55,16)
                                     （(230,55) 是 perf 探针的【方块】窗中心，不是 chunk）
            --help

            轻模式只做原采样等价（每 hs×chunk 512 次 sample，不建 HydrologyChunkEngine）。
            判据失败即 System.exit(1)；不覆盖 Forge 实机/装饰/落块/耗时。
            """;

    private static int checks;

    private HydrologyHeightSnapshotProbe() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int code;
        try {
            Spec spec = Spec.parse(args);
            if (spec.help()) {
                System.out.print(USAGE);
                code = 0;
            } else if (spec.selfTest()) {
                code = selfTest();
            } else {
                code = run(spec);
            }
        } catch (Precondition e) {
            System.out.println("PRECONDITION " + e.getMessage());
            code = 2;
        } catch (AssertionError e) {
            System.out.println("FAIL " + e.getMessage());
            code = 1;
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            code = 3;
        }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    // ==================================================================
    // CLI
    // ==================================================================

    record Spec(Path toml, long seed, int[][] chunks, boolean heavy, int heavyCx, int heavyCz,
                boolean selfTest, boolean help) {

        static Spec parse(String[] args) {
            Path toml = DEFAULT_TOML;
            long seed = DEFAULT_SEED;
            int[][] chunks = DEFAULT_CHUNKS;
            boolean heavy = false, selfTest = false, help = false;
            int hx = HEAVY_CX, hz = HEAVY_CZ;
            for (String a : args) {
                if (!a.startsWith("--")) {
                    throw new IllegalArgumentException("未知位置参数: " + a + "（见 --help）");
                }
                int eq = a.indexOf('=');
                String key = eq < 0 ? a.substring(2) : a.substring(2, eq);
                String val = eq < 0 ? "" : a.substring(eq + 1);
                switch (key) {
                    case "toml" -> toml = Path.of(val);
                    case "seed" -> seed = Long.parseLong(val);
                    case "chunks" -> chunks = parseChunks(val);
                    case "heavy" -> heavy = true;
                    case "chunk" -> {
                        int[][] one = parseChunks(val);
                        if (one.length != 1) throw new IllegalArgumentException("--chunk 只接受一个 x,z");
                        hx = one[0][0];
                        hz = one[0][1];
                    }
                    case "self-test" -> selfTest = true;
                    case "help" -> help = true;
                    default -> throw new IllegalArgumentException("未知选项 --" + key + "（见 --help）");
                }
            }
            return new Spec(toml, seed, chunks, heavy, hx, hz, selfTest, help);
        }
    }

    static int[][] parseChunks(String text) {
        String[] parts = text.split(";");
        int[][] out = new int[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] xy = parts[i].trim().split(",");
            if (xy.length != 2) throw new IllegalArgumentException("chunk 写法应为 x,z: " + parts[i]);
            out[i] = new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())};
        }
        return out;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    static int run(Spec spec) throws IOException {
        checks = 0;
        System.out.println("=== HydrologyHeightSnapshotProbe ===");
        System.out.println("mode=" + (spec.heavy() ? "light+heavy" : "light"));
        Config cfg = loadConfig(spec.toml());
        printProvenance(cfg, spec);
        CellGenerator gen = new CellGenerator(cfg.params(), WORLD_MIN_Y, WORLD_MAX_Y);
        gen.seed(spec.seed());
        lightSweep(gen, spec);
        System.out.println("info: snapshotDivVsExtractMulUlpMismatch=" + ulpMismatch(spec)
                + "（非判据：extractFromTile 读 bx*(1/hs)，快照存 bx/hs）");
        if (spec.heavy()) heavy(gen, cfg.params(), spec);
        System.out.println("checks=" + checks);
        System.out.println("status=PASS");
        return 0;
    }

    /** 轻模式：gate 内 hs 的采样等价 + 特殊 hs 的条件/分叉（不建引擎、不跑水文）。 */
    static void lightSweep(CellGenerator gen, Spec spec) {
        for (double hs : HS_SWEEP) {
            check(snapshotGate(hs), "HS_SWEEP 内 hs=" + hs + " 必须落在 gate 内");
        }
        for (int[] chunk : spec.chunks()) {
            for (double hs : HS_SWEEP) checkCase(gen, hs, chunk[0], chunk[1]);
        }
        for (double hs : SPECIAL_HS) {
            check(!snapshotGate(hs), "特殊 hs=" + hs + " 不得走快照路径");
            check(!sameCoord(160.0, hs), "特殊 hs=" + hs + " 两套映射必须分叉（生产靠 gate 回避）");
        }
        // 特殊 hs 的回落路径确实按 max(0.01, hs) 原重采样（用真实 helper 反证 samplingCoord 镜像）
        checkClampMirror(gen, 0.005, 0, 0);
    }

    /**
     * 反证 {@link #samplingCoord} 镜像：clamp 在 {@code HydrologyChunkEngine} 构造
     * （{@code max(0.01, params.hs)}），helper 只做除法 ⇒ 这里按引擎同款 clamp 传参。
     */
    static void checkClampMirror(CellGenerator gen, double hs, int cx, int cz) {
        Cell[] sampled = HydrologyChunkSampling.sample(gen, Math.max(SNAPSHOT_MIN_HS, hs), cx, cz);
        int diff = -1;
        for (int lz = 0; lz < 16 && diff < 0; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell ref = gen.sample(samplingCoord(cx * 16 + lx, hs), samplingCoord(cz * 16 + lz, hs));
                if (!cellDump(ref).equals(cellDump(sampled[lx * 16 + lz]))) {
                    diff = lx * 16 + lz;
                    break;
                }
            }
        }
        check(diff < 0, "hs=" + hs + " 的 clamp 镜像与 HydrologyChunkSampling 不符 @cell " + diff);
        System.out.println("case hs=" + hs + " clampMirror=OK（回落路径坐标 = block/max(0.01,hs)）");
    }

    /**
     * 单 (hs, chunk) 的采样等价：快照坐标 + height 原始位 + 全部 Cell 字段 + 实例不复用。
     *
     * <p>快照侧完全照抄生产循环（{@code sample} 之后立刻记 height），重采样侧直接调
     * {@link HydrologyChunkSampling#sample}（同包 helper，生产 calculate 用的就是它）。</p>
     */
    static void checkCase(CellGenerator gen, double hs, int cx, int cz) {
        Snapshot snap = capture(gen, hs, cx, cz);
        double sampleHs = Math.max(SNAPSHOT_MIN_HS, hs);
        Cell[] sampled = HydrologyChunkSampling.sample(gen, sampleHs, cx, cz);
        String label = caseLabel(hs, cx, cz);
        check(sampled.length == 256, label + " sample 长度=" + sampled.length);
        int cDiff = firstCoordMismatch(hs, sampleHs, cx, cz);
        check(cDiff < 0, label + " 坐标原始位不等 @cell " + cDiff);
        int hDiff = firstHeightDiff(snap.heights(), sampled);
        check(hDiff < 0, label + " height 原始位不等 @cell " + hDiff);
        int fDiff = firstCellDiff(snap.cells(), sampled);
        String detail = fDiff < 0 ? ""
                : " " + firstTokenDiff(cellDump(snap.cells()[fDiff]), cellDump(sampled[fDiff]));
        check(fDiff < 0, label + " Cell 字段不等 @cell " + fDiff + detail);
        int shared = 0;
        for (int i = 0; i < 256; i++) if (snap.cells()[i] == sampled[i]) shared++;
        check(shared == 0, label + " 快照与重采样复用同一 Cell 实例 ×" + shared);
        System.out.println(label + " heightBits=OK fields=OK distinct=OK");
    }

    /**
     * 整 chunk（256 格）坐标原始位比较：快照镜像 {@link #snapshotCoord} vs
     * {@link HydrologyChunkSampling#sample} 内的块→wu 表达式。
     *
     * <p>helper 侧<b>独立重写</b>为 {@code (chunk*16+local)/scale}，不复用 {@link #samplingCoord}
     * ⇒ 两套坐标若漂移，即使采样结果碰巧同 height/同字段也会在此被抓到。</p>
     *
     * @return 首个坐标原始位不符的 cell 下标（{@code lx*16+lz}），全等返回 -1
     */
    static int firstCoordMismatch(double hs, double sampleHs, int cx, int cz) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int bx = cx * 16 + lx, bz = cz * 16 + lz;
                if (Double.doubleToRawLongBits(snapshotCoord(bx, hs))
                                != Double.doubleToRawLongBits(bx / sampleHs)
                        || Double.doubleToRawLongBits(snapshotCoord(bz, hs))
                                != Double.doubleToRawLongBits(bz / sampleHs)) {
                    return lx * 16 + lz;
                }
            }
        }
        return -1;
    }

    /** 生产快照语义：{@code sample} 后就地取 height（extractFromTile 之前）。 */
    static Snapshot capture(CellGenerator gen, double hs, int cx, int cz) {
        Cell[] cells = new Cell[256];
        double[] heights = new double[256];
        int baseX = cx * 16, baseZ = cz * 16;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell c = gen.sample(snapshotCoord(baseX + lx, hs), snapshotCoord(baseZ + lz, hs));
                cells[lx * 16 + lz] = c;
                heights[lx * 16 + lz] = c.height;
            }
        }
        return new Snapshot(cells, heights);
    }

    // ==================================================================
    // heavy：生产路径（默认 hs，单 chunk）
    // ==================================================================

    static void heavy(CellGenerator gen, TerrainParams params, Spec spec) {
        double hs = params.horizontalScale();
        if (!snapshotGate(hs)) {
            throw new Precondition("生产快照路径要求 hs>0.01，实测 horizontalScale=" + hs);
        }
        int cx = spec.heavyCx(), cz = spec.heavyCz();
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(spec.seed());
        if (!terrain.riversEnabled()) {
            throw new Precondition("riversEnabled=false：水文整体关闭，快照路径不参与 ⇒ heavy 无意义");
        }
        Snapshot snap = capture(gen, hs, cx, cz);
        HydrologyChunkResult first = terrain.calculateHydrologyChunk(cx, cz);
        System.out.println("heavy chunk=(" + cx + "," + cz + ") columns=" + first.carvedColumns().size()
                + " waterColumns=" + first.waterColumns() + " hs=" + hs);
        heavyPublished(first, snap);
        // ★ waterColumns=0 ⇒ 该窗口根本没有水，列字段"全等"毫无意义 ⇒ 必须判红。
        check(first.waterColumns() > 0, "heavy 窗口无水（waterColumns=0）⇒ 列全等会假绿："
                + "改用 --chunk=\"17,-33\"（已证河窗）或其它含河 chunk");
        // ★ 用 terrain 自己持有的 engine（反射）：不新建 provider / 线程池，保证与
        //   terrain.calculateHydrologyChunk 同源（同一 provider、同一 region 缓存）。
        HydrologyChunkEngine engine = engineOf(terrain);
        heavyFastPath(engine, first, snap.heights(), cx, cz);
        heavyIndependence(engine, gen, hs, cx, cz);
        heavyExceptions(engine, snap.heights(), cx, cz);
    }

    /**
     * 反射取 {@code GeoGenesisTerrain} 自己持有的 {@code hydrologyExperiment}。
     *
     * <p>不新建 engine：第二份 provider 会多起一个 4 线程池，且"快路径 vs 公开路径"要比较的是
     * <b>同一份</b> region 缓存/策略指纹，否则差异可能来自 provider 身份而非被测接线。</p>
     */
    static HydrologyChunkEngine engineOf(GeoGenesisTerrain terrain) {
        try {
            Field field = GeoGenesisTerrain.class.getDeclaredField("hydrologyExperiment");
            field.setAccessible(true);
            Object engine = field.get(terrain);
            if (!(engine instanceof HydrologyChunkEngine typed)) {
                throw new IllegalStateException("hydrologyExperiment 不是 HydrologyChunkEngine: " + engine);
            }
            return typed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 terrain 持有的 HydrologyChunkEngine", e);
        }
    }

    /** 公开 calculate 的 originalCells：完整、逐位等于生产重采样、不别名。 */
    static void heavyPublished(HydrologyChunkResult first, Snapshot snap) {
        Cell[] cells = first.originalCells();
        check(cells != null && cells.length == 256,
                "originalCells 必须是完整 256 列，实测 " + (cells == null ? "null" : cells.length));
        int nulls = 0;
        for (Cell c : cells) if (c == null) nulls++;
        check(nulls == 0, "originalCells 含 null ×" + nulls);
        int diff = firstCellDiff(cells, snap.cells());
        check(diff < 0, "originalCells 与生产快照采样不一致 @cell " + diff);
        int shared = 0;
        for (int i = 0; i < 256; i++) if (cells[i] == snap.cells()[i]) shared++;
        check(shared == 0, "originalCells 复用了快照 Cell 实例 ×" + shared);
        System.out.println("heavy originalCells 完整=256 逐位等于重采样=true 独立实例=true");
    }

    /** 快路径 columnsForHeights vs 公开 calculate 的 carvedColumns：全部列字段。 */
    static void heavyFastPath(HydrologyChunkEngine engine, HydrologyChunkResult first, double[] heights,
                              int cx, int cz) {
        List<HydrologyBlockCarvedColumn> viaHeights = engine.calculateColumns(cx, cz, heights);
        List<HydrologyBlockCarvedColumn> viaCells = first.carvedColumns();
        check(viaHeights.size() == viaCells.size(),
                "列数 " + viaHeights.size() + " != " + viaCells.size());
        String diff = firstColumnDiff(viaHeights, viaCells);
        check(diff == null, "calculateColumns != calculate.carvedColumns: " + diff);
        int lakeNode = 0;
        for (HydrologyBlockCarvedColumn c : viaHeights) if (c.lakeNode() != null) lakeNode++;
        check(lakeNode == 0, "lakeNode 应恒 null（legacy 已退役），实测非 null ×" + lakeNode);
        System.out.println("heavy columns 全字段逐位相等=true n=" + viaHeights.size());
    }

    /** 独立性：跨调用不共享数组；调用方改写已发布 Cell 不得污染内部状态。 */
    static void heavyIndependence(HydrologyChunkEngine engine, CellGenerator gen, double hs, int cx, int cz) {
        HydrologyChunkResult a = engine.calculate(cx, cz);
        HydrologyChunkResult b = engine.calculate(cx, cz);
        check(a.originalCells() != null && a.originalCells().length == 256
                        && b.originalCells() != null && b.originalCells().length == 256,
                "独立性前置：originalCells 必须完整 256 列");
        check(a.originalCells() != b.originalCells(), "originalCells 两次调用共享同一数组（别名）");
        long before = Double.doubleToRawLongBits(a.originalCells()[37].height);
        a.originalCells()[37].height = Double.NaN;
        a.originalCells()[0] = null;
        check(Double.doubleToRawLongBits(b.originalCells()[37].height) == before
                        && b.originalCells()[0] != null, "公开结果之间共享可变 Cell 实例");
        HydrologyChunkResult c = engine.calculate(cx, cz);
        check(Double.doubleToRawLongBits(c.originalCells()[37].height) == before,
                "调用方改写 originalCells 后再次 calculate 被污染（内部持有同一份 Cell[]）");
        Cell[] fresh = HydrologyChunkSampling.sample(gen, Math.max(SNAPSHOT_MIN_HS, hs), cx, cz);
        check(firstCellDiff(c.originalCells(), fresh) < 0, "污染后重算内容偏离生产重采样");
        System.out.println("heavy originalCells 独立性：数组不共享、改写不外溢=true");
    }

    /** 新旧入口的长度校验；旧 Cell[256] 含 null 的 NPE 只在已就绪 region 上检查。 */
    static void heavyExceptions(HydrologyChunkEngine engine, double[] heights, int cx, int cz) {
        checkIae(() -> engine.calculateColumns(cx, cz, null), "null heights 应抛 IllegalArgumentException");
        checkIae(() -> engine.calculateColumns(cx, cz, new double[255]),
                "255 heights 应抛 IllegalArgumentException");
        check(engine.calculateColumns(cx, cz, heights.clone()).size() == 256,
                "256 heights 应正常返回 256 列");
        try {
            Field field = HydrologyChunkEngine.class.getDeclaredField("modelProvider");
            field.setAccessible(true);
            var provider = (com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider) field.get(engine);
            checkOriginalLength(() -> provider.columnsFor(cx, cz, null));
            checkOriginalLength(() -> provider.columnsFor(cx, cz, new Cell[255]));
            boolean npe = false;
            try { provider.columnsFor(cx, cz, new Cell[256]); }
            catch (NullPointerException expected) { npe = true; }
            check(npe, "旧 Cell[256] 含 null 入口必须保持 NPE，不改成长度 IAE");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取原始 Cell 入口", e);
        }
        System.out.println("heavy 异常契约：新旧 null/255 → IAE，旧256含null → NPE，合法256列通过");
    }

    static void checkOriginalLength(Runnable action) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) {
            if ("originalCells must be 256".equals(expected.getMessage())) return;
            throw new AssertionError("旧 Cell 入口长度异常文案改变", expected);
        }
        throw new AssertionError("旧 Cell 入口长度异常未抛出");
    }

    // ==================================================================
    // 比较器（Cell / 列 / 高度）
    // ==================================================================

    static String cellDump(Cell c) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("height=").append(dhex(c.height)).append(';');
        sb.append("continent=").append(dhex(c.continent)).append(';');
        sb.append("e=").append(dhex(c.e)).append(';');
        sb.append("eOcean=").append(dhex(c.eOcean)).append(';');
        sb.append("blendCont=").append(dhex(c.blendCont)).append(';');
        sb.append("eLand=").append(dhex(c.eLand)).append(';');
        sb.append("eClimate=").append(dhex(c.eClimate)).append(';');
        sb.append("snowLineE=").append(dhex(c.snowLineE)).append(';');
        sb.append("coastCoord=").append(dhex(c.coastCoord)).append(';');
        if (c.typeWeights == null) {
            sb.append("typeWeights=null;");
        } else {
            sb.append("typeWeights=").append(c.typeWeights.length);
            for (double v : c.typeWeights) sb.append(',').append(dhex(v));
            sb.append(';');
        }
        sb.append("terrainType=").append(c.terrainType == null ? -1 : c.terrainType.ordinal()).append(';');
        sb.append("variantTerrain=").append(c.variantTerrain == null ? -1 : c.variantTerrain.ordinal()).append(';');
        sb.append("climate.present=").append(c.climate != null).append(';');
        if (c.climate != null) {
            sb.append("climate=").append(dhex(c.climate.temperature())).append(',')
                    .append(dhex(c.climate.humidity())).append(',')
                    .append(dhex(c.climate.continentality())).append(',')
                    .append(dhex(c.climate.precipitation())).append(';');
        }
        sb.append("biomeType=").append(c.biomeType == null ? -1 : c.biomeType.ordinal()).append(';');
        sb.append("temperature=").append(dhex(c.temperature)).append(';');
        sb.append("humidity=").append(dhex(c.humidity)).append(';');
        sb.append("precipitation=").append(dhex(c.precipitation)).append(';');
        sb.append("continentNoise=").append(dhex(c.continentNoise)).append(';');
        sb.append("isLake=").append(c.isLake).append(';');
        sb.append("lakeMask=").append(c.lakeMask).append(';');
        sb.append("erosionMask=").append(c.erosionMask).append(';');
        sb.append("shape=").append(dhex(c.shape)).append(';');
        sb.append("riverNetDischarge=").append(dhex(c.riverNetDischarge)).append(';');
        sb.append("riverDistance=").append(dhex(c.riverDistance)).append(';');
        sb.append("oasisNoise=").append(dhex(c.oasisNoise)).append(';');
        sb.append("gradient=").append(Integer.toHexString(Float.floatToRawIntBits(c.gradient))).append(';');
        sb.append("rockTypeId=").append(c.rockTypeId).append(';');
        sb.append("rockLayer=").append(c.rockLayer).append(';');
        sb.append("rockSeqPacked=").append(c.rockSeqPacked).append(';');
        sb.append("rockTilt=").append(dhex(c.rockTilt)).append(';');
        sb.append("riverType=").append(c.riverType).append(';');
        sb.append("riverSurfaceY=").append(dhex(c.riverSurfaceY)).append(';');
        sb.append("riverLipY=").append(dhex(c.riverLipY)).append(';');
        sb.append("isSnow=").append(c.isSnow).append(';');
        appendOcean(sb, c);
        appendLand(sb, c);
        return sb.toString();
    }

    static void appendOcean(StringBuilder sb, Cell c) {
        sb.append("oceanFeat.present=").append(c.oceanFeat != null).append(';');
        if (c.oceanFeat == null) return;
        sb.append("oceanFeat=").append(dhex(c.oceanFeat.total)).append(',')
                .append(dhex(c.oceanFeat.ridge)).append(',')
                .append(dhex(c.oceanFeat.seamount)).append(',')
                .append(dhex(c.oceanFeat.arc)).append(',')
                .append(dhex(c.oceanFeat.baseE)).append(';');
    }

    static void appendLand(StringBuilder sb, Cell c) {
        sb.append("landFeat.present=").append(c.landFeat != null).append(';');
        if (c.landFeat == null) return;
        sb.append("landFeat=").append(dhex(c.landFeat.total)).append(',')
                .append(dhex(c.landFeat.single)).append(',')
                .append(dhex(c.landFeat.field)).append(',')
                .append(dhex(c.landFeat.singleEdifice)).append(',')
                .append(dhex(c.landFeat.fieldEdifice)).append(';');
    }

    /** 首个字段差异（cellDump 是唯一字段清单，比较与报告同源，不会漂移）。 */
    static String firstTokenDiff(String a, String b) {
        String[] x = a.split(";"), y = b.split(";");
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) if (!x[i].equals(y[i])) return x[i] + " != " + y[i];
        return "len " + x.length + " != " + y.length;
    }

    static int firstCellDiff(Cell[] a, Cell[] b) {
        if (a == null || b == null) return 0;
        if (a.length != b.length) return 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null || b[i] == null) {
                if (a[i] != b[i]) return i;
                continue;
            }
            if (!cellDump(a[i]).equals(cellDump(b[i]))) return i;
        }
        return -1;
    }

    static int firstHeightDiff(double[] heights, Cell[] cells) {
        int n = Math.min(heights.length, cells.length);
        for (int i = 0; i < n; i++) {
            if (Double.doubleToRawLongBits(heights[i]) != Double.doubleToRawLongBits(cells[i].height)) return i;
        }
        return heights.length == cells.length ? -1 : n;
    }

    static String firstColumnDiff(List<HydrologyBlockCarvedColumn> a, List<HydrologyBlockCarvedColumn> b) {
        if (a.size() != b.size()) return "size " + a.size() + " != " + b.size();
        for (int i = 0; i < a.size(); i++) {
            HydrologyBlockCarvedColumn x = a.get(i), y = b.get(i);
            if (x.blockX() != y.blockX() || x.blockZ() != y.blockZ()) return "coord@" + i;
            if (cbits(x.originalGroundY(), y.originalGroundY())) return "originalGroundY@" + i;
            if (cbits(x.carvedGroundY(), y.carvedGroundY())) return "carvedGroundY@" + i;
            if (cbits(x.waterSurfaceY(), y.waterSurfaceY())) return "waterSurfaceY@" + i;
            if (cbits(x.lipSurfaceY(), y.lipSurfaceY())) return "lipSurfaceY@" + i;
            if (cbits(x.erosion(), y.erosion())) return "erosion@" + i;
            if (cbits(x.erosionMask(), y.erosionMask())) return "erosionMask@" + i;
            if (x.fillWater() != y.fillWater()) return "fillWater@" + i;
            if (x.lakePlan() != y.lakePlan()) return "lakePlan@" + i;
            if (x.lakeNode() != y.lakeNode()) return "lakeNode@" + i;
            if (cbits(x.lakeLevelY(), y.lakeLevelY())) return "lakeLevelY@" + i;
        }
        return null;
    }

    // ==================================================================
    // 生产映射镜像（★ 改动生产必须同步这三处）
    // ==================================================================

    /** 镜像私有 {@code GeoGenesisTerrain.toWu(block)}。 */
    static double snapshotCoord(double block, double hs) {
        return (hs > SNAPSHOT_MIN_HS && hs != 1.0) ? block / hs : block;
    }

    /** 镜像引擎 clamp + helper 除法：{@code block / max(0.01, hs)}。 */
    static double samplingCoord(double block, double hs) {
        return block / Math.max(SNAPSHOT_MIN_HS, hs);
    }

    /** 镜像 {@code generateChunkBody} 的快照门（{@code riversEnabled} 另查）。 */
    static boolean snapshotGate(double hs) {
        return hs > SNAPSHOT_MIN_HS;
    }

    static boolean sameCoord(double block, double hs) {
        return Double.doubleToRawLongBits(snapshotCoord(block, hs))
                == Double.doubleToRawLongBits(samplingCoord(block, hs));
    }

    /** 非判据信息：快照用 bx/hs、extractFromTile 用 bx*(1/hs)，非二进制友好 hs 会出现 1-ulp 分叉。 */
    static int ulpMismatch(Spec spec) {
        int n = 0;
        for (int[] chunk : spec.chunks()) {
            for (double hs : HS_SWEEP) {
                int baseX = chunk[0] * 16, baseZ = chunk[1] * 16;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (Double.doubleToRawLongBits((baseX + lx) / hs)
                                != Double.doubleToRawLongBits((baseX + lx) * (1.0 / hs))) n++;
                        if (Double.doubleToRawLongBits((baseZ + lz) / hs)
                                != Double.doubleToRawLongBits((baseZ + lz) * (1.0 / hs))) n++;
                    }
                }
            }
        }
        return n;
    }

    // ==================================================================
    // 配置（只读原件；工作副本 + Forge spec 校正，与 HydrologyPerfProbe 同款）
    // ==================================================================

    record Config(TerrainParams params, String sha256, String path, boolean corrected) { }

    /**
     * 加载真实 TOML 的【工作副本】（原件只读、绝不写回）。
     *
     * <p>缺失 / 非文件 ⇒ {@link Precondition}（exit 2）：被测对象必须是用户真实配置，
     * 回落到 spec 默认值会让"改了配置没生效"这类缺陷假绿。</p>
     */
    static Config loadConfig(Path toml) throws IOException {
        if (!Files.isRegularFile(toml)) {
            throw new Precondition("缺少真实生产配置（本探针不回落默认值）: " + toml.toAbsolutePath()
                    + " —— 先跑一次 runClient/runPreview 生成，或用 --toml=<path>");
        }
        String sha = sha256(toml);
        Files.createDirectories(WORK_ROOT);
        // 唯一子目录：不覆盖上一次运行的副本（原副本 + 工作副本各一份）。
        Path dir = Files.createTempDirectory(WORK_ROOT, "run-");
        Path inputCopy = dir.resolve("config-input.toml");
        Path effective = dir.resolve("config-effective.toml");
        Files.copy(toml, inputCopy, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(toml, effective, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("inputCopy=" + inputCopy.toAbsolutePath());
        System.out.println("effectiveCopy=" + effective.toAbsolutePath());
        // ★ 不 close：后续 sample / 水文会经 GeoGenesisConfig.INSTANCE 实时读值。
        CommentedFileConfig cfg = CommentedFileConfig.of(effective, TomlFormat.instance());
        cfg.load();
        boolean correct = GeoGenesisConfig.SPEC.isCorrect(cfg);
        GeoGenesisConfig.SPEC.setConfig(cfg);
        if (!GeoGenesisConfig.SPEC.isLoaded()) throw new IllegalStateException("TOML 未加载: " + effective);
        TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
        cfg.save();   // Forge 校正只写 effective 副本
        check(sha.equals(sha256(toml)), "原 TOML 在加载/保存后 SHA 变化（探针改到了原件）");
        return new Config(params, sha, toml.toAbsolutePath().toString(), !correct);
    }

    static void printProvenance(Config cfg, Spec spec) {
        System.out.println("toml=" + cfg.path() + " sha256=" + cfg.sha256()
                + " correctedByForge=" + cfg.corrected()
                + " fingerprint=" + GeoGenesisConfig.configFingerprint());
        System.out.println("params horizontalScale=" + cfg.params().horizontalScale()
                + " seaLevel=" + cfg.params().seaLevel() + " minY=" + cfg.params().minY()
                + " maxY=" + cfg.params().maxY() + " verticalScale=" + cfg.params().verticalScale());
        System.out.println("java=" + System.getProperty("java.version") + " seed=" + spec.seed()
                + " hsSweep=" + java.util.Arrays.toString(HS_SWEEP) + " chunks=" + chunkList(spec.chunks()));
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ==================================================================
    // 自检（纯本地；不建 CellGenerator、不读 TOML、不跑水文）
    // ==================================================================

    static int selfTest() {
        checks = 0;
        System.out.println("=== HydrologyHeightSnapshotProbe --self-test ===");
        Cell a = selfCell(), b = selfCell();
        check(cellDump(a).equals(cellDump(b)), "相同字段应判等");
        b.height = Math.nextUp(b.height);
        check(!cellDump(a).equals(cellDump(b)), "height 改一位必须被抓到");
        check(firstTokenDiff(cellDump(a), cellDump(b)).startsWith("height="), "首差应报 height");
        b = selfCell();
        b.typeWeights[2] = Math.nextUp(b.typeWeights[2]);
        check(!cellDump(a).equals(cellDump(b)), "typeWeights 数组改一位必须被抓到");
        b = selfCell();
        b.climate = null;
        check(!cellDump(a).equals(cellDump(b)), "climate 缺失必须被抓到");
        b = selfCell();
        b.oceanFeat = null;
        check(!cellDump(a).equals(cellDump(b)), "oceanFeat 缺失必须被抓到");
        Cell[] cells = {selfCell(), selfCell(), selfCell()};
        double[] heights = {cells[0].height, cells[1].height, cells[2].height};
        check(firstHeightDiff(heights, cells) < 0, "高度一致应通过");
        heights[1] = Math.nextUp(heights[1]);
        check(firstHeightDiff(heights, cells) == 1, "高度改一位必须定位到 cell 1");
        check(firstCellDiff(cells, cells.clone()) < 0, "同一批 Cell 应判等");
        check(firstCellDiff(cells, new Cell[]{cells[0], cells[1]}) == 0, "长度不等必须报红");
        check(firstCoordMismatch(1.0, 1.0, 0, 0) < 0, "坐标一致应通过");
        check(firstCoordMismatch(1.0, 2.0, 0, 0) == 16, "坐标漂移必须定位到 cell 16");
        check(sameCoord(160, 1.0) && sameCoord(160, 2.0) && sameCoord(160, 1.3)
                && sameCoord(160, 0.5) && sameCoord(160, 8.0), "gate 内 hs 两套映射同值");
        check(!sameCoord(160, SNAPSHOT_MIN_HS) && !sameCoord(160, 0.005) && !sameCoord(160, Double.NaN),
                "特殊 hs 两套映射必须分叉");
        check(snapshotGate(1.0) && !snapshotGate(SNAPSHOT_MIN_HS) && !snapshotGate(Double.NaN)
                && !snapshotGate(0.0), "gate 镜像：1/0.01/NaN/0 判定");
        HydrologyBlockCarvedColumn ca =
                new HydrologyBlockCarvedColumn(-2, 3, 80, 79, 81, 82, 1, 0.5, true, false);
        HydrologyBlockCarvedColumn cb =
                new HydrologyBlockCarvedColumn(-2, 3, 80, 79, 81, Math.nextUp(82.0), 1, 0.5, true, false);
        check(firstColumnDiff(List.of(ca), List.of(ca)) == null, "列相同应判等");
        check("lipSurfaceY@0".equals(firstColumnDiff(List.of(ca), List.of(cb))), "列改一位必须被抓到");
        check("size 1 != 2".equals(firstColumnDiff(List.of(ca), List.of(ca, ca))), "列数不等必须报红");
        System.out.println("checks=" + checks);
        System.out.println("status=PASS");
        return 0;
    }

    /** 全字段非默认的样本 Cell（selfCell 两两独立，供改位测试）。 */
    static Cell selfCell() {
        Cell c = new Cell();
        c.height = 71.25;
        c.continent = 0.5;
        c.e = 0.1;
        c.eOcean = -0.2;
        c.blendCont = 0.3;
        c.eLand = 0.4;
        c.eClimate = 0.15;
        c.snowLineE = 0.8;
        c.coastCoord = 0.25;
        c.typeWeights = new double[]{0.25, 0.25, 0.25, 0.125, 0.125};
        c.variantTerrain = c.terrainType;
        c.temperature = 0.2;
        c.humidity = -0.1;
        c.precipitation = 0.6;
        c.continentNoise = 0.5;
        c.isLake = true;
        c.lakeMask = true;
        c.erosionMask = false;
        c.shape = -0.2;
        c.riverNetDischarge = 3.5;
        c.riverDistance = 12.5;
        c.oasisNoise = 0.05;
        c.gradient = 0.125f;
        c.rockTypeId = 2;
        c.rockLayer = 1;
        c.rockSeqPacked = 0x1A2B;
        c.rockTilt = 3.25;
        c.riverType = 1;
        c.riverSurfaceY = 62.0;
        c.riverLipY = 63.5;
        c.isSnow = false;
        c.oceanFeat = new OceanFeatures.FeatureResult(0.1, 0.02, 0.03, -0.01, 0.4);
        return c;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    record Snapshot(Cell[] cells, double[] heights) { }

    /** 前提不成立（不是判据失败）。 */
    static final class Precondition extends RuntimeException {
        Precondition(String message) {
            super(message);
        }
    }

    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    static void checkIae(Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    static String dhex(double v) {
        return Long.toHexString(Double.doubleToRawLongBits(v));
    }

    static boolean cbits(double a, double b) {
        return Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b);
    }

    static String caseLabel(double hs, int cx, int cz) {
        return "case hs=" + hs + " chunk=(" + cx + "," + cz + ")";
    }

    static String chunkList(int[][] chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(chunks[i][0]).append(',').append(chunks[i][1]);
        }
        return sb.toString();
    }
}
