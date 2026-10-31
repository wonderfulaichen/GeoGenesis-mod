package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>真实配方三交汇「局部场景」搜索器（只读；本类是本轮唯一新增文件）</b>。
 *
 * <p>给 {@code RecipeJunctionGallery}（渲染方）交付<b>真实</b>配方交汇位置 + 边几何，
 * 使渲染器不必从零搜图。本类<b>不接全幅</b>、<b>不改任何既有类</b>、
 * <b>不研究也不修改几何</b>，只输出 CSV 与每场景的 {@code segments/regions/scene.json}。</p>
 *
 * <h3>几何来源（借现成代码，零重写）</h3>
 * <ol>
 *   <li>{@link LocalBoundaryGeometry#galleryOwner()} + {@link LocalBoundaryGeometry#of}：
 *       与 {@code atGallery} 同一条 {@code buildCombineRegional(SEED, 3600f)} 路径
 *       （{@code galleryOwner()} 的 javadoc 写明它就是"供探针再喂给 of"的对象）。
 *       复用同一 owner ⇒ 每个窗口不必重建 CombineCandidate。</li>
 *   <li><b>三交汇定位 = 反射调用 {@code LocalBoundaryGeometryProbe.findTriples}</b>
 *       （{@code private static}，"成对边共点 + 三点等势"校验的现成算法），
 *       <b>不在此重写</b>。见 {@link #locateTriples}。</li>
 *   <li>格 → 槽位 → 逻辑配方：{@link LocalBoundaryGeometry#slotOfCell} /
 *       {@link LocalBoundaryGeometry#categoryOfCell} + 沙箱库存 {@code slots.csv}
 *       （{@code SandboxInventoryProbe} 产出，本类只读）。库存缺失<b>直接失败</b>，绝不编造配方名。</li>
 * </ol>
 *
 * <h3>★「第四参与者」口径：不裁</h3>
 * <p>{@code findTriples} 按交点去重，一个真 4 度顶点只会被报成其中一组三格 ⇒ 本类在两个位置
 * <b>独立</b>测量"到底几格参与"：</p>
 * <ul>
 *   <li><b>交汇点处</b>：{@link LocalBoundaryGeometry#query} 的 {@code weight > 0} 计数
 *       ⇒ {@code status=EXACT3}，或 {@code FOUR_PLUS_CANNOT_TRIM}。</li>
 *   <li><b>三条边各自沿法向 ±120 块扫描</b>：出现第三格正权重 ⇒
 *       {@code edgeBandVerdict=EXTRA_CANNOT_TRIM} ⇒ 场景 status 升级为
 *       {@code BAND_EXTRA_CANNOT_TRIM}。</li>
 * </ul>
 * <p>第四格<b>始终保留</b>在 {@code slots.csv}（{@link #SLOTS_HEADER}）与该场景的
 * {@code regions.csv} 里；{@code scenes.csv} 另有 {@code slot4*} 列位供渲染器直接使用。</p>
 *
 * <h3>⚠ 继承的几何局限（本类不解决，只标注）</h3>
 * <p>{@link LocalBoundaryGeometry} 自己声明：u 平面 Voronoi 边精确，但映回 world 用反 warp
 * 阻尼牛顿，<b>只保证残差小，不证明 warp 单射</b>；折叠区附近 world 折线可能落到错误的原像分支
 * 而残差依旧很小。故本类所有 world 坐标只声明"实测残差小"。反解失败 / 超容限时几何层抛异常，
 * 本类<b>不吞</b>：发现阶段跳过该窗口（打印 {@code SKIP}），场景导出记 {@code REJECTED_GEOMETRY}。</p>
 *
 * <h3>用法</h3>
 * <pre>
 * java ... RecipeJunctionSceneSearch [--out=DIR] [--inventory=DIR] [--stride=1200] [--no-scan] [--max=16]
 * </pre>
 * <p>本类不做门禁断言；异常退出 3。</p>
 */
public final class RecipeJunctionSceneSearch {

    // ============================================ 输出契约（★ 冻结：改列名即破坏渲染方对接）

    /**
     * {@code scenes.csv} 表头。<b>已冻结</b>，渲染方按此列名读取。
     *
     * <p>每场景一行；{@code slot1..slot4} 为场景参与格按"交汇点处权重降序"的稳定排序，
     * 不足 4 个时数字列写 {@code 0}、文本列写空串。</p>
     *
     * <p>列名与 {@code RecipeJunctionGallery.readScenes} 的对接口径：</p>
     * <ul>
     *   <li>坐标 —— 它按 {@code x|cx|centerx} 与 {@code z|cz|centerz} 取（大小写不敏感），
     *       本表的 {@code centerX/centerZ} 命中第三档；</li>
     *   <li>{@code name} —— 它用 {@code sanitize(name)} 做场景目录名；本表给稳定短名，
     *       避免退化成 {@code real-1/real-2}；</li>
     *   <li>{@code note} —— 作为场景副标题。</li>
     * </ul>
     */
    static final String SCENES_HEADER =
            "sceneId,name,targetClass,junctionClass,centerX,centerZ,queryRadiusBlocks,"
                    + "status,junctionCount,edgeBandVerdict,"
                    + "slot1CellX,slot1CellZ,slot1Slot,slot1Entry,slot1Label,slot1Category,"
                    + "slot2CellX,slot2CellZ,slot2Slot,slot2Entry,slot2Label,slot2Category,"
                    + "slot3CellX,slot3CellZ,slot3Slot,slot3Entry,slot3Label,slot3Category,"
                    + "slot4CellX,slot4CellZ,slot4Slot,slot4Entry,slot4Label,slot4Category,note";

    /** {@code slots.csv}：场景内全部参与格（含第四参与者，<b>不裁</b>）。 */
    static final String SLOTS_HEADER = "sceneId,sceneSlotIndex,cellX,cellZ,slotIndex,entryIndex,"
            + "label,category,votes,worldEdgeCount,maxWeightInScene";

    /** {@code junctions.csv}：场景内每个已确认三交汇点。 */
    static final String JUNCTIONS_HEADER = "sceneId,junctionId,kindA,kindB,kindC,"
            + "cellA,cellB,cellC,slots,entries,labels,junctionX,junctionZ,"
            + "distToSceneCenter,participationAtJunction";

    // ============================================ 常量（只读口径）

    /** {@code LandCompositionGallery.SEED}；仅用于日志对拍，不参与构建。 */
    static final long CANONICAL_SEED = 9139912035078620160L;
    /** 与 {@code atGallery} 同一条 {@code buildCombineRegional} 的模式场尺度。 */
    static final float REGIONAL_MODE_SCALE = 3600f;
    /** 场景查询半径（块）。任务口径 radius 450。 */
    static final double SCENE_RADIUS = 450.0;
    /** 站点窗口半宽下限（格）；实际由闭式界自动放大。 */
    static final int REQ_HALF = 4;
    /** world 折线弦偏差 / 最大段长（块）：探针交付口径。 */
    static final double DELIVER_CHORD = 0.5;
    static final double DELIVER_MAXSEG = 2.0;
    /** 粗扫窗口半径（块）：> 场景半径，stride 不超其 2 倍即无缝。 */
    static final double DISCOVERY_RADIUS = 650.0;
    /** 两个三交汇并入同一场景的最大 world 距离（块）。 */
    static final double CLUSTER_RADIUS = 400.0;
    /** 权重过渡带半宽（块）：与几何层一致。 */
    static final double BAND_HALF = LocalBoundaryGeometry.BAND_HALF_BLOCKS;
    /** 边法向扫描步长（块）。 */
    static final double BAND_STEP = 10.0;
    /** 参考三交汇（用户给定；来源 {@code real-boundary-local/w3/verify.txt}）。 */
    static final double REF_JX = -362.3561;
    static final double REF_JZ = -977.0970;
    /** 参考窗口（块）：{@code (-3640..3360, -3363..3637)}。 */
    static final double WIN_X0 = -3640, WIN_X1 = 3360, WIN_Z0 = -3363, WIN_Z1 = 3637;
    /** 默认输出目录。 */
    static final String DEFAULT_OUT = "build/terrain-upgrade/recipe-junctions";
    /** 默认库存目录（{@code SandboxInventoryProbe} 产出）。 */
    static final String DEFAULT_INVENTORY = "build/terrain-upgrade/real-boundary-local/inventory";

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    /** 场景目标；顺序即优先级。 */
    static final String[] TARGETS = {
            "T1_ORIGINAL_DALES",
            "T2_COMPOSITE_STEPPE_HILLS2",
            "T3_PLATEAU",
            "T4_BADLANDS",
            "T5_PLATEAU_AND_BADLANDS",
    };

    // ============================================ 状态

    private final Path out;
    private final Path inventoryDir;
    private final int stride;
    private final boolean noScan;
    private final int maxScenes;

    private Object owner;
    private int partSeed;
    private String[] slotLabels = new String[0];
    private String[] slotCats = new String[0];
    private String[] slotVotes = new String[0];
    private int[] slotEntries = new int[0];

    private RecipeJunctionSceneSearch(Path out, Path inventoryDir, int stride,
                                      boolean noScan, int maxScenes) {
        this.out = out;
        this.inventoryDir = inventoryDir;
        this.stride = stride;
        this.noScan = noScan;
        this.maxScenes = maxScenes;
    }

    /** 入口；异常 ⇒ {@code exit(3)}。 */
    public static void main(String[] argv) {
        Locale.setDefault(Locale.ROOT);
        int code;
        try {
            Args a = parseArgs(argv);
            code = new RecipeJunctionSceneSearch(a.out(), a.inventory(), a.stride(),
                    a.noScan(), a.maxScenes()).run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("[FAIL] RecipeJunctionSceneSearch aborted: " + t);
            code = 3;
        }
        System.out.flush();
        System.exit(code);
    }

    /** 命令行参数包。 */
    private record Args(Path out, Path inventory, int stride, boolean noScan, int maxScenes) { }

    private static Args parseArgs(String[] argv) {
        Path o = Path.of(DEFAULT_OUT);
        Path inv = Path.of(DEFAULT_INVENTORY);
        int st = 1200;
        boolean no = false;
        int max = 16;
        for (String a : argv) {
            if (a.startsWith("--out=")) o = Path.of(a.substring(6));
            else if (a.startsWith("--inventory=")) inv = Path.of(a.substring(12));
            else if (a.startsWith("--stride=")) st = Integer.parseInt(a.substring(9));
            else if (a.equals("--no-scan")) no = true;
            else if (a.startsWith("--max=")) max = Integer.parseInt(a.substring(6));
            else throw new IllegalArgumentException("unknown arg: " + a);
        }
        if (st <= 0) no = true;
        return new Args(o, inv, Math.max(1, st), no, Math.max(1, max));
    }

    // ============================================ 主流程

    private int run() throws Exception {
        Files.createDirectories(out.resolve("scenes"));
        long t0 = System.nanoTime();
        loadInventory();
        this.owner = LocalBoundaryGeometry.galleryOwner();
        this.partSeed = readPartitionSeed(owner);
        System.out.printf(Locale.ROOT,
                "[rjss] owner ready (gallerySeed=%d, partitionSeed=%d, slots=%d)%n",
                readGallerySeed(), partSeed, slotLabels.length);

        List<Junction> junctions = discovery();
        System.out.println("[rjss] discovered junctions = " + junctions.size());
        List<Cluster> clusters = cluster(junctions);
        List<SceneRow> scenes = selectScenes(clusters);

        Files.writeString(out.resolve("clusters.csv"), clustersCsv(clusters), UTF8);
        Files.writeString(out.resolve("scenes.csv"), scenesCsv(scenes), UTF8);
        Files.writeString(out.resolve("slots.csv"), slotsCsv(scenes), UTF8);
        Files.writeString(out.resolve("junctions.csv"), junctionsCsv(scenes), UTF8);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        Files.writeString(out.resolve("run.log"), runLog(scenes, ms), UTF8);
        System.out.printf(Locale.ROOT, "[rjss] scenes=%d out=%s elapsed=%.1fs%n",
                scenes.size(), out.toAbsolutePath(), ms / 1000.0);
        return 0;
    }

    private static long readGallerySeed() {
        try {
            Field f = LandCompositionGallery.class.getDeclaredField("SEED");
            f.setAccessible(true);
            return f.getLong(null);
        } catch (ReflectiveOperationException e) {
            return -1L;
        }
    }

    // ============================================ 库存（只读）

    /** 读 {@code slots.csv}：槽位 → entry / label / category / votes。缺文件即失败。 */
    private void loadInventory() throws Exception {
        Path p = inventoryDir.resolve("slots.csv");
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("missing sandbox inventory " + p.toAbsolutePath()
                    + " — run SandboxInventoryProbe first; this tool never invents recipe labels");
        }
        List<String> lines = Files.readAllLines(p, UTF8);
        int n = Math.max(0, lines.size() - 1);
        if (n == 0) throw new IllegalStateException("empty slots.csv: " + p);
        slotLabels = new String[n];
        slotCats = new String[n];
        slotVotes = new String[n];
        slotEntries = new int[n];
        for (int i = 0; i < n; i++) {
            String[] f = lines.get(i + 1).trim().split(",", -1);
            if (f.length < 6) throw new IllegalStateException("bad slots.csv row: " + lines.get(i + 1));
            slotEntries[i] = Integer.parseInt(f[1].trim());
            slotLabels[i] = f[2].trim();
            slotCats[i] = f[3].trim();
            slotVotes[i] = f[5].trim();
        }
        System.out.printf(Locale.ROOT, "[rjss] inventory %s -> %d slots%n", p, n);
    }

    // ============================================ 发现

    /** 参考点 + 粗扫窗口；每窗独立建几何并反射取三交汇，按 world 坐标去重。 */
    private List<Junction> discovery() {
        List<double[]> seeds = new ArrayList<>();
        seeds.add(new double[]{REF_JX, REF_JZ});
        if (!noScan) {
            for (double z = WIN_Z0; z <= WIN_Z1; z += stride) {
                for (double x = WIN_X0; x <= WIN_X1; x += stride) {
                    seeds.add(new double[]{x, z});
                }
            }
        }
        List<Junction> all = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        int done = 0, skipped = 0;
        for (double[] c : seeds) {
            done++;
            LocalBoundaryGeometry g;
            try {
                g = LocalBoundaryGeometry.of(owner, c[0], c[1], DISCOVERY_RADIUS, REQ_HALF,
                        DELIVER_CHORD);
            } catch (RuntimeException ex) {
                skipped++;
                System.out.printf(Locale.ROOT, "[rjss] SKIP window (%.0f,%.0f): %s%n",
                        c[0], c[1], ex.getMessage());
                continue;
            }
            List<Object[]> triples = locateTriples(g, c[0], c[1], DISCOVERY_RADIUS);
            int added = 0;
            for (Object[] t : triples) {
                Junction j = Junction.of(t);
                if (dedup.add(String.format(Locale.ROOT, "%.2f|%.2f", j.wx, j.wz))) {
                    all.add(j);
                    added++;
                }
            }
            System.out.printf(Locale.ROOT, "[rjss] window %d/%d (%.0f,%.0f) triples=%d new=%d%n",
                    done, seeds.size(), c[0], c[1], triples.size(), added);
        }
        System.out.printf(Locale.ROOT, "[rjss] windows=%d skipped=%d%n", done, skipped);
        return all;
    }

    /**
     * 反射调用 {@code LocalBoundaryGeometryProbe.findTriples}（现成算法，不重写）。
     *
     * <p>签名 {@code private static List<Triple> findTriples(LocalBoundaryGeometry,double,double,double)}；
     * 元素是包内 {@code record Triple}，通过其访问器取值。</p>
     *
     * @return 每项摊平为 {@code {wx, wz, px, pz, a, b, c}}（{@code a..c} 是 {@code Cell}）
     */
    private static List<Object[]> locateTriples(LocalBoundaryGeometry g, double cx, double cz,
                                                double maxR) {
        try {
            Method m = LocalBoundaryGeometryProbe.class.getDeclaredMethod("findTriples",
                    LocalBoundaryGeometry.class, double.class, double.class, double.class);
            m.setAccessible(true);
            Object res = m.invoke(null, g, cx, cz, maxR);
            List<Object[]> out = new ArrayList<>();
            if (!(res instanceof List<?> list)) return out;
            for (Object t : list) {
                out.add(new Object[]{acc(t, "wx"), acc(t, "wz"), acc(t, "px"), acc(t, "pz"),
                        acc(t, "a"), acc(t, "b"), acc(t, "c")});
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reflect LocalBoundaryGeometryProbe.findTriples", e);
        }
    }

    private static Object acc(Object t, String name) throws ReflectiveOperationException {
        Method m = t.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(t);
    }

    // ============================================ 聚类

    /** 单个真实三交汇点。 */
    private static final class Junction {
        final double wx, wz;
        final LocalBoundaryGeometry.Cell a, b, c;

        Junction(double wx, double wz, LocalBoundaryGeometry.Cell a,
                 LocalBoundaryGeometry.Cell b, LocalBoundaryGeometry.Cell c) {
            this.wx = wx;
            this.wz = wz;
            this.a = a;
            this.b = b;
            this.c = c;
        }

        static Junction of(Object[] t) {
            return new Junction((Double) t[0], (Double) t[1],
                    (LocalBoundaryGeometry.Cell) t[4],
                    (LocalBoundaryGeometry.Cell) t[5],
                    (LocalBoundaryGeometry.Cell) t[6]);
        }

        List<LocalBoundaryGeometry.Cell> cells() {
            return List.of(a, b, c);
        }
    }

    /** 一个场景候选：彼此靠近的三交汇点集合。 */
    private static final class Cluster {
        final List<Junction> members = new ArrayList<>();
        double cx, cz;

        void seal() {
            double sx = 0, sz = 0;
            for (Junction j : members) {
                sx += j.wx;
                sz += j.wz;
            }
            cx = sx / members.size();
            cz = sz / members.size();
        }
    }

    /** 贪心聚类：与簇内任一成员距离 ≤ {@link #CLUSTER_RADIUS} 即并入。 */
    private List<Cluster> cluster(List<Junction> junctions) {
        List<Cluster> clusters = new ArrayList<>();
        for (Junction j : junctions) {
            Cluster hit = null;
            for (Cluster cl : clusters) {
                if (near(cl, j)) {
                    hit = cl;
                    break;
                }
            }
            if (hit == null) {
                hit = new Cluster();
                clusters.add(hit);
            }
            hit.members.add(j);
        }
        for (Cluster cl : clusters) cl.seal();
        clusters.sort((x, y) -> Integer.compare(y.members.size(), x.members.size()));
        return clusters;
    }

    private static boolean near(Cluster cl, Junction j) {
        for (Junction m : cl.members) {
            if (Math.hypot(m.wx - j.wx, m.wz - j.wz) <= CLUSTER_RADIUS) return true;
        }
        return false;
    }

    // ============================================ 选场景

    /**
     * 选场景：每个目标取"最近原点"的合格簇，但<b>含用户参考三交汇的簇优先</b>
     * （{@link #REF_JX}/{@link #REF_JZ} 是用户实机认可的真实 DALES 交汇，
     * T1 必须锁定它，不能因为"离原点更近"被别的簇换掉）。
     */
    private List<SceneRow> selectScenes(List<Cluster> clusters) throws Exception {
        Map<Cluster, List<String>> chosen = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String target : TARGETS) {
            Cluster best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (Cluster cl : clusters) {
                if (!satisfies(cl, target)) continue;
                double d = holdsReference(cl) ? -1.0 : Math.hypot(cl.cx, cl.cz);
                if (d < bestD) {
                    bestD = d;
                    best = cl;
                }
            }
            if (best == null) {
                missing.add(target);
                System.out.println("[rjss] target MISSING: " + target);
            } else {
                chosen.computeIfAbsent(best, k -> new ArrayList<>()).add(target);
                System.out.printf(Locale.ROOT,
                        "[rjss] target %-28s -> cluster (%.1f,%.1f) members=%d ref=%s%n",
                        target, best.cx, best.cz, best.members.size(), holdsReference(best));
            }
        }
        List<SceneRow> rows = new ArrayList<>();
        int id = 0;
        for (Map.Entry<Cluster, List<String>> e : chosen.entrySet()) {
            if (id >= maxScenes) break;
            SceneRow r = exportScene(++id, e.getValue(), e.getKey());
            if (r.note.isEmpty()) {
                r.note = r.targetText() + " | " + r.junctionClass + " | " + r.status
                        + " | edges=" + r.edgeBandVerdict;
            }
            rows.add(r);
        }
        for (String m : missing) {
            SceneRow r = new SceneRow(++id, List.of(m + "_NOT_FOUND"));
            r.status = "TARGET_NOT_FOUND";
            r.note = "no real junction of this class inside the scanned window; renderer may "
                    + "reuse a found real triple as a controlled arm instead";
            rows.add(r);
        }
        return rows;
    }

    /** 该簇是否包含用户给定的参考三交汇（{@link #CLUSTER_RADIUS} 内）。 */
    private static boolean holdsReference(Cluster cl) {
        for (Junction j : cl.members) {
            if (Math.hypot(j.wx - REF_JX, j.wz - REF_JZ) <= CLUSTER_RADIUS) return true;
        }
        return false;
    }

    /** 目标判据：全部基于"该簇内是否存在满足条件的三交汇"。 */
    private boolean satisfies(Cluster cl, String target) {
        for (Junction j : cl.members) {
            if (targetMatch(kindsOf(j), target)) return true;
        }
        return false;
    }

    private List<String> kindsOf(Junction j) {
        List<String> out = new ArrayList<>();
        for (LocalBoundaryGeometry.Cell c : j.cells()) {
            int slot = slotOf(c);
            out.add(kindOf(inventoryLabel(slot), inventoryCategory(slot)));
        }
        return out;
    }

    /** 一组三格种类是否兑现该目标（簇判据与单点判据共用，避免两处口径漂移）。 */
    private static boolean targetMatch(List<String> kinds, String target) {
        return switch (target) {
            case "T1_ORIGINAL_DALES" -> kinds.contains("ORIGINAL_DALES");
            case "T2_COMPOSITE_STEPPE_HILLS2" ->
                    kinds.contains("COMPOSITE_STEPPE_HILLS2");
            case "T3_PLATEAU" -> kinds.contains("UNMIXABLE_PLATEAU");
            case "T4_BADLANDS" -> kinds.contains("UNMIXABLE_BADLANDS");
            case "T5_PLATEAU_AND_BADLANDS" ->
                    kinds.contains("UNMIXABLE_PLATEAU") && kinds.contains("UNMIXABLE_BADLANDS");
            default -> false;
        };
    }

    /**
     * 配方种类（渲染器据此分"原版子配方 / 复合项 / 不可混条目"）。
     *
     * <p>复合项的 kind 带<b>归一化后的两个成员名</b>（如 {@code COMPOSITE_STEPPE_HILLS2}），
     * 因此 {@code junctionClass} 自解释、且 {@link #targetMatch} 无需回看原始 label。</p>
     */
    static String kindOf(String label, String category) {
        // ⚠ 复合项必须先判：`dales-geo[DALES_GEO] + hills2[HILLS_2]` 也以 dales-geo 开头
        if (label.contains(" + ")) return "COMPOSITE_" + membersOf(label);
        if (label.startsWith("dales-geo")) return "ORIGINAL_DALES";
        if ("PLATEAU".equals(category)) return "UNMIXABLE_PLATEAU";
        if ("BADLANDS".equals(category)) return "UNMIXABLE_BADLANDS";
        if ("MOUNTAINS".equals(category)) return "UNMIXABLE_MOUNTAINS";
        return "ORIGINAL_" + shadeOf(label);
    }

    /** {@code "steppe[STEPPE] + hills2[HILLS_2]"} → {@code "STEPPE_HILLS2"}（顺序保持 label 原序）。 */
    static String membersOf(String label) {
        List<String> parts = new ArrayList<>();
        for (String p : label.split(" \\+ ")) parts.add(shadeOf(p));
        return String.join("_", parts);
    }

    /** {@code "hills1-geo[HILLS_1_GEO]"} → {@code "HILLS1"}；取方括号内 shade 的首段并去下划线尾。 */
    static String shadeOf(String label) {
        int lb = label.indexOf('[');
        int rb = label.lastIndexOf(']');
        String core = lb >= 0 && rb > lb ? label.substring(lb + 1, rb) : label;
        core = core.replace("_GEO", "").replace("_", "");
        if (core.isEmpty()) core = "OTHER";
        return core;
    }

    // ============================================ 场景导出

    /** 场景一行（同时是 CSV 行的数据源）。 */
    private static final class SceneRow {
        final int id;
        final List<String> targets;
        final List<Member> members = new ArrayList<>();
        final List<JunctionOut> junctions = new ArrayList<>();
        double cx, cz;
        String status = "EXACT3";
        String edgeBandVerdict = "NO_EDGE_SAMPLED";
        String junctionClass = "";
        String note = "";
        int thirdCellInBand;

        SceneRow(int id, List<String> targets) {
            this.id = id;
            this.targets = targets;
        }

        String idText() { return String.format(Locale.ROOT, "s%02d", id); }

        /** 渲染方直接用作场景目录名的稳定短名（只允许 {@code [a-z0-9-]}）。 */
        String nameText() {
            String t = targets.isEmpty() ? "target" : targets.get(0);
            StringBuilder sb = new StringBuilder();
            for (char ch : t.toLowerCase(Locale.ROOT).toCharArray()) {
                if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) sb.append(ch);
                else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
            }
            String s = sb.toString().replaceAll("-+$", "");
            if (s.isEmpty()) s = "scene";
            return idText() + "-" + s;
        }

        String targetText() { return String.join("|", targets); }
    }

    /** 场景内的一个参与格。 */
    private static final class Member {
        final int cellX, cellZ, slot, entry, edgeCount;
        final String label, category, votes;
        double maxWeight;

        Member(int cellX, int cellZ, int slot, int entry, String label, String category,
               String votes, int edgeCount) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.slot = slot;
            this.entry = entry;
            this.label = label;
            this.category = category;
            this.votes = votes;
            this.edgeCount = edgeCount;
        }

        long key() { return LocalBoundaryGeometry.pack(cellX, cellZ); }

        /** 与几何层同样式的列名安全 id（{@code m12_n34}），供 {@code Sample.regions} 对拍。 */
        String id() { return "m" + cellX + "_n" + cellZ; }
    }

    /** 场景内一个已确认三交汇。 */
    private static final class JunctionOut {
        final double wx, wz, dist;
        final List<Member> trio = new ArrayList<>();
        int participation = -1;

        JunctionOut(double wx, double wz, double dist) {
            this.wx = wx;
            this.wz = wz;
            this.dist = dist;
        }
    }

    /** 导出单场景：自建几何、复核三交汇、测参与格数、扫三条边、落盘。 */
    private SceneRow exportScene(int id, List<String> targets, Cluster cl) throws Exception {
        Junction anchor = pickAnchor(cl, targets);
        SceneRow row = new SceneRow(id, targets);
        row.cx = anchor.wx;
        row.cz = anchor.wz;

        LocalBoundaryGeometry geo;
        try {
            geo = LocalBoundaryGeometry.of(owner, row.cx, row.cz, SCENE_RADIUS, REQ_HALF,
                    DELIVER_CHORD);
            geo.setMaxSeg(DELIVER_MAXSEG);
        } catch (RuntimeException ex) {
            row.status = "REJECTED_GEOMETRY";
            row.note = "geometry build failed: " + ex.getMessage();
            return row;
        }

        Path dir = out.resolve("scenes").resolve(row.idText());
        Files.createDirectories(dir);
        geo.writeSegmentsCsv(dir.resolve("segments.csv"));
        geo.writeRegionsCsv(dir.resolve("regions.csv"));
        Files.writeString(dir.resolve("scene.json"), geo.sceneJson(), UTF8);

        List<Object[]> triples = locateTriples(geo, row.cx, row.cz, SCENE_RADIUS);
        if (triples.isEmpty()) {
            row.status = "REJECTED_NO_JUNCTION_IN_SCENE";
            row.note = "anchor junction did not reproduce at radius " + SCENE_RADIUS;
            return row;
        }
        for (Object[] t : triples) collectTriple(row, geo, t);
        measureAtJunctions(geo, row);
        orderJunctionsByTarget(row);
        classifyScene(row);
        row.junctionClass = junctionClassOf(row);
        scanEdges(geo, row);
        sortMembers(row);
        Files.writeString(dir.resolve("scene-note.txt"), sceneNote(row), UTF8);
        return row;
    }

    /**
     * 把"本场景目标所依赖的那个三交汇"排到首位 —— {@code junctionClass} / {@code junctions.csv}
     * 的第 0 行因此<b>始终</b>描述兑现目标的那组三格，而不是随便一个簇内交汇。
     */
    private void orderJunctionsByTarget(SceneRow row) {
        for (String target : row.targets) {
            for (int i = 0; i < row.junctions.size(); i++) {
                if (!junctionSatisfies(row.junctions.get(i), target)) continue;
                if (i > 0) row.junctions.add(0, row.junctions.remove(i));
                return;
            }
        }
    }

    /** 单个三交汇是否兑现该目标（{@link #satisfies} 的单点版）。 */
    private boolean junctionSatisfies(JunctionOut jo, String target) {
        List<String> kinds = new ArrayList<>();
        for (Member m : jo.trio) kinds.add(kindOf(m.label, m.category));
        return targetMatch(kinds, target);
    }

    /** 锚点：优先取能满足本场景某个目标的成员，否则取簇内第一个。 */
    private Junction pickAnchor(Cluster cl, List<String> targets) {
        for (String target : targets) {
            for (Junction j : cl.members) {
                Cluster one = new Cluster();
                one.members.add(j);
                one.seal();
                if (satisfies(one, target)) return j;
            }
        }
        return cl.members.get(0);
    }

    /** 把一个确认三交汇写进行（成员先入 {@code row.members}，第四参与者也会被 join 进来）。 */
    private void collectTriple(SceneRow row, LocalBoundaryGeometry geo, Object[] t) {
        Junction j = Junction.of(t);
        JunctionOut jo = new JunctionOut(j.wx, j.wz, Math.hypot(j.wx - row.cx, j.wz - row.cz));
        for (LocalBoundaryGeometry.Cell c : j.cells()) jo.trio.add(memberFor(row, geo, c));
        row.junctions.add(jo);
    }

    private Member memberFor(SceneRow row, LocalBoundaryGeometry geo,
                             LocalBoundaryGeometry.Cell c) {
        Member prev = memberByCell(row, c.cellX(), c.cellZ());
        if (prev != null) return prev;
        int slot = geo.slotOfCell(c.cellX(), c.cellZ());
        Member m = new Member(c.cellX(), c.cellZ(), slot, entryOf(slot),
                inventoryLabel(slot), geo.categoryOfCell(c.cellX(), c.cellZ()),
                votesOf(slot), geo.edgeCountOf(c));
        row.members.add(m);
        return m;
    }

    /**
     * 交汇点处 {@code weight > 0} 的真实参与格数；同时把各格权重记到 {@link Member}。
     * 点落在查询域外 ⇒ -1（不伪造）。
     */
    private void measureAtJunctions(LocalBoundaryGeometry geo, SceneRow row) {
        for (JunctionOut jo : row.junctions) {
            if (Math.hypot(jo.wx - row.cx, jo.wz - row.cz) > geo.queryRadius()) continue;
            LocalBoundaryGeometry.Sample s = geo.query(jo.wx, jo.wz);
            int k = 0;
            for (int i = 0; i < s.weight.length; i++) {
                if (s.weight[i] <= 0) continue;
                k++;
                LocalBoundaryGeometry.Site site = siteById(geo, s.regions[i]);
                if (site == null) continue;
                Member m = memberFor(row, geo, site);
                m.maxWeight = Math.max(m.maxWeight, s.weight[i]);
            }
            jo.participation = k;
        }
    }

    /** 由参与格数定 status：>3 ⇒ 四点及以上（保留，不裁）。 */
    private void classifyScene(SceneRow row) {
        int maxK = 0;
        for (JunctionOut jo : row.junctions) maxK = Math.max(maxK, jo.participation);
        if (maxK > 3) row.status = "FOUR_PLUS_CANNOT_TRIM";
        else if (maxK == 3) row.status = "EXACT3";
        else row.status = "PARTICIPATION_UNMEASURED";
    }

    private String junctionClassOf(SceneRow row) {
        JunctionOut best = row.junctions.get(0);
        List<String> kinds = new ArrayList<>();
        for (Member m : best.trio) kinds.add(kindOf(m.label, m.category));
        kinds.sort(String::compareTo);
        return String.join("+", kinds);
    }

    // ============================================ 边扫描（第四参与者检测）

    /**
     * 对场景内每个三交汇的三条边做 ±120 块法向扫描，检测<b>该交汇三格之外</b>的第四格夺权重。
     *
     * <p>⚠ 口径：交汇的第二、第三格<em>就是</em>该边的天然邻居，靠近顶点时必然在带宽内出现。
     * 因此只把"三格之外的第五格"算作 {@code EXTRA}；顶点一侧的第三格另计为
     * {@code NEAR_VERTEX_THIRD}（这是 Voronoi 顶点的固有几何，不是缺陷，也不裁）。</p>
     */
    private void scanEdges(LocalBoundaryGeometry geo, SceneRow row) {
        int sampled = 0, extra = 0, third = 0;
        for (JunctionOut jo : row.junctions) {
            for (int i = 0; i < 3; i++) {
                Member a = jo.trio.get(i);
                Member b = jo.trio.get((i + 1) % 3);
                Member c = jo.trio.get((i + 2) % 3);
                for (LocalBoundaryGeometry.Edge e : edgesBetween(geo, a, b)) {
                    int[] r = scanOneEdge(geo, row, e, c);
                    if (r == null) continue;
                    sampled++;
                    third += r[0];
                    extra += r[1];
                }
            }
        }
        row.thirdCellInBand = third;
        if (sampled == 0) {
            row.edgeBandVerdict = "NO_EDGE_SAMPLED";
        } else if (extra > 0) {
            // ⚠ 不改 status：status 只报"交汇点处几格参与"（顶点本身的性质）；
            // 带宽里出现的第三/第四格是"边中线 ±120 处几格有权重"，两件事分开报，否则读数会混。
            row.edgeBandVerdict = "EXTRA_CANNOT_TRIM";
        } else if (third > 0) {
            row.edgeBandVerdict = "NEAR_VERTEX_THIRD_ONLY";
        } else {
            row.edgeBandVerdict = "CLEAN_PAIR_ONLY";
        }
    }

    /**
     * 单条边沿法向 ±{@link #BAND_HALF} 扫描。
     *
     * @return {@code null} 该边不可用（退化 / 无有效采样）；否则为
     *         {@code [出现"本交汇第三格"的采样点数, 出现"三格之外"的采样点数]}
     */
    private int[] scanOneEdge(LocalBoundaryGeometry geo, SceneRow row,
                              LocalBoundaryGeometry.Edge e, Member thirdCell) {
        int mid = e.wx.length / 2;
        if (mid <= 0 || mid + 1 >= e.wx.length) return null;
        double mx = e.wx[mid], mz = e.wz[mid];
        double nx = -(e.wz[mid + 1] - e.wz[mid - 1]);
        double nz = e.wx[mid + 1] - e.wx[mid - 1];
        double len = Math.hypot(nx, nz);
        if (!(len > 0)) return null;
        nx /= len;
        nz /= len;
        String idA = e.a.id(), idB = e.b.id(), idC = thirdCell == null ? null : thirdCell.id();
        int steps = (int) Math.round(BAND_HALF / BAND_STEP);
        int used = 0, third = 0, extra = 0;
        for (int k = -steps; k <= steps; k++) {
            double x = mx + nx * (k * BAND_STEP);
            double z = mz + nz * (k * BAND_STEP);
            if (Math.hypot(x - row.cx, z - row.cz) > geo.queryRadius()) continue;
            used++;
            int[] hit = classifyForeign(geo, row, geo.query(x, z), idA, idB, idC);
            if (hit[0] > 0) third++;
            if (hit[1] > 0) extra++;
        }
        return used == 0 ? null : new int[]{third, extra};
    }

    /**
     * 把该点的"外来格"分成两类并全部并入 {@link SceneRow#members}（<b>绝不裁</b>）。
     *
     * @return {@code [本交汇第三格的命中数, 三格之外命中的数目]}
     */
    private int[] classifyForeign(LocalBoundaryGeometry geo, SceneRow row,
                                  LocalBoundaryGeometry.Sample s, String idA, String idB,
                                  String idC) {
        int third = 0, other = 0;
        for (int i = 0; i < s.weight.length; i++) {
            if (s.weight[i] <= 0) continue;
            String id = s.regions[i];
            if (id.equals(idA) || id.equals(idB)) continue;
            LocalBoundaryGeometry.Site site = siteById(geo, id);
            if (site != null) {
                Member m = memberFor(row, geo, site);
                m.maxWeight = Math.max(m.maxWeight, s.weight[i]);
            }
            if (idC != null && id.equals(idC)) third++;
            else other++;
        }
        return new int[]{third, other};
    }

    private List<LocalBoundaryGeometry.Edge> edgesBetween(LocalBoundaryGeometry geo,
                                                          Member a, Member b) {
        List<LocalBoundaryGeometry.Edge> out = new ArrayList<>();
        for (LocalBoundaryGeometry.Edge e : geo.edges()) {
            boolean ab = e.a.cellX() == a.cellX && e.a.cellZ() == a.cellZ
                    && e.b.cellX() == b.cellX && e.b.cellZ() == b.cellZ;
            boolean ba = e.a.cellX() == b.cellX && e.a.cellZ() == b.cellZ
                    && e.b.cellX() == a.cellX && e.b.cellZ() == a.cellZ;
            if (ab || ba) out.add(e);
        }
        return out;
    }

    // ============================================ 成员排序与查询辅助

    /** 成员排序：交汇点权重降序 → cellX → cellZ（稳定，渲染方按此取 slot1..slot4）。 */
    private void sortMembers(SceneRow row) {
        row.members.sort((a, b) -> {
            int c = Double.compare(b.maxWeight, a.maxWeight);
            if (c != 0) return c;
            c = Integer.compare(a.cellX, b.cellX);
            return c != 0 ? c : Integer.compare(a.cellZ, b.cellZ);
        });
        for (JunctionOut jo : row.junctions) {
            jo.trio.sort((a, b) -> Double.compare(b.maxWeight, a.maxWeight));
        }
    }

    private static Member memberByCell(SceneRow row, int cx, int cz) {
        for (Member m : row.members) {
            if (m.cellX == cx && m.cellZ == cz) return m;
        }
        return null;
    }

    private static LocalBoundaryGeometry.Site siteById(LocalBoundaryGeometry geo, String id) {
        for (LocalBoundaryGeometry.Site s : geo.sites()) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    // ============================================ 格 → 槽位 → 配方

    private int slotOf(LocalBoundaryGeometry.Cell c) {
        return RegionPartition.selectIndex(
                RefNoise.mapClamped(valCoord2D(partSeed, c.cellX(), c.cellZ()), -1f, 1f),
                slotLabels.length);
    }

    /** 分区种子：从 owner 的 {@code RegionPartition.seed} 反射读一次（与几何层同源）。 */
    private int readPartitionSeed(Object o) {
        try {
            Object part = readField(o, "partition");
            return (Integer) readField(part, "seed");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read RegionPartition.seed from owner", e);
        }
    }

    private static Object readField(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类找
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 生产 {@code RegionPartition.valCoord2D} 的逐位复刻（WHITE 格 id）。 */
    private static float valCoord2D(int seed, int x, int z) {
        int n = seed;
        n ^= 1619 * x;
        n ^= 31337 * z;
        return n * n * n * 60493 / 2.14748365E9F;
    }

    private String inventoryLabel(int slot) {
        return slot >= 0 && slot < slotLabels.length ? slotLabels[slot] : "?";
    }

    private String inventoryCategory(int slot) {
        return slot >= 0 && slot < slotCats.length ? slotCats[slot] : "?";
    }

    private int entryOf(int slot) {
        return slot >= 0 && slot < slotEntries.length ? slotEntries[slot] : -1;
    }

    private String votesOf(int slot) {
        return slot >= 0 && slot < slotVotes.length ? slotVotes[slot] : "?";
    }

    // ============================================ CSV 输出

    private String scenesCsv(List<SceneRow> rows) {
        StringBuilder sb = new StringBuilder(SCENES_HEADER).append('\n');
        for (SceneRow r : rows) {
            sb.append(r.idText()).append(',').append(r.nameText()).append(',')
                    .append(csv(r.targetText())).append(',')
                    .append(csv(r.junctionClass)).append(',')
                    .append(f4(r.cx)).append(',').append(f4(r.cz)).append(',')
                    .append(f1(SCENE_RADIUS)).append(',')
                    .append(r.status).append(',').append(r.junctions.size()).append(',')
                    .append(r.edgeBandVerdict);
            for (int i = 0; i < 4; i++) {
                appendSlot(sb, i < r.members.size() ? r.members.get(i) : null);
            }
            sb.append(',').append(csv(r.note)).append('\n');
        }
        return sb.toString();
    }

    private static void appendSlot(StringBuilder sb, Member m) {
        sb.append(',').append(m == null ? "0" : String.valueOf(m.cellX))
                .append(',').append(m == null ? "0" : String.valueOf(m.cellZ))
                .append(',').append(m == null ? "0" : String.valueOf(m.slot))
                .append(',').append(m == null ? "0" : String.valueOf(m.entry))
                .append(',').append(m == null ? "" : csv(m.label))
                .append(',').append(m == null ? "" : m.category);
    }

    private String slotsCsv(List<SceneRow> rows) {
        StringBuilder sb = new StringBuilder(SLOTS_HEADER).append('\n');
        for (SceneRow r : rows) {
            for (int i = 0; i < r.members.size(); i++) {
                Member m = r.members.get(i);
                sb.append(r.idText()).append(',').append(i).append(',')
                        .append(m.cellX).append(',').append(m.cellZ).append(',')
                        .append(m.slot).append(',').append(m.entry).append(',')
                        .append(csv(m.label)).append(',').append(m.category).append(',')
                        .append(m.votes).append(',').append(m.edgeCount).append(',')
                        .append(String.format(Locale.ROOT, "%.6f", m.maxWeight)).append('\n');
            }
        }
        return sb.toString();
    }

    private String junctionsCsv(List<SceneRow> rows) {
        StringBuilder sb = new StringBuilder(JUNCTIONS_HEADER).append('\n');
        for (SceneRow r : rows) {
            for (int j = 0; j < r.junctions.size(); j++) {
                sb.append(junctionLine(r, r.junctions.get(j), j));
            }
        }
        return sb.toString();
    }

    private static String junctionLine(SceneRow r, JunctionOut jo, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.idText()).append(',').append(idx);
        for (Member m : jo.trio) sb.append(',').append(kindOf(m.label, m.category));
        for (Member m : jo.trio) sb.append(',').append(m.cellX).append(';').append(m.cellZ);
        for (Member m : jo.trio) sb.append(',').append(m.slot);
        for (Member m : jo.trio) sb.append(',').append(m.entry);
        for (Member m : jo.trio) sb.append(',').append(csv(m.label));
        sb.append(',').append(f4(jo.wx)).append(',').append(f4(jo.wz)).append(',')
                .append(f3(jo.dist)).append(',').append(jo.participation).append('\n');
        return sb.toString();
    }

    private static String clustersCsv(List<Cluster> clusters) {
        StringBuilder sb = new StringBuilder("clusterIndex,centerX,centerZ,memberCount,");
        sb.append("junction0X,junction0Z,junction1X,junction1Z,junction2X,junction2Z\n");
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            sb.append(i).append(',').append(f4(c.cx)).append(',').append(f4(c.cz)).append(',')
                    .append(c.members.size());
            for (int k = 0; k < 3; k++) {
                boolean has = k < c.members.size();
                sb.append(',').append(has ? f4(c.members.get(k).wx) : "")
                        .append(',').append(has ? f4(c.members.get(k).wz) : "");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String sceneNote(SceneRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append("sceneId=").append(row.idText()).append('\n')
                .append("targets=").append(row.targetText()).append('\n')
                .append("center=").append(f4(row.cx)).append(',').append(f4(row.cz)).append('\n')
                .append("status=").append(row.status).append('\n')
                .append("edgeBandVerdict=").append(row.edgeBandVerdict).append('\n')
                .append("thirdCellInBandSamples=").append(row.thirdCellInBand).append('\n')
                .append("junctionCount=").append(row.junctions.size()).append('\n')
                .append("members=").append(row.members.size()).append('\n')
                .append('\n')
                .append("edge-band verdict semantics:\n")
                .append("  CLEAN_PAIR_ONLY           every edge sees only its own two cells\n")
                .append("  NEAR_VERTEX_THIRD_ONLY    the junction's own 3rd cell appears inside the\n")
                .append("                            +-120 band (inherent to a Voronoi vertex)\n")
                .append("  EXTRA_CANNOT_TRIM         a cell OUTSIDE the triple gains weight => the scene\n")
                .append("                            is 4-way or more and MUST be kept, not trimmed\n")
                .append("  NO_EDGE_SAMPLED           no usable edge in this scene\n")
                .append('\n')
                .append("geometry caveat (inherited from LocalBoundaryGeometry, not fixed here):\n")
                .append("  u-plane Voronoi edges are exact; world edges come from inverse-warp\n")
                .append("  Newton roots with small residual only. Injectivity of the warp is NOT\n")
                .append("  proven, so 'small residual' does not prove 'correct preimage'.\n");
        for (JunctionOut jo : row.junctions) {
            sb.append('\n').append("junction ").append(f4(jo.wx)).append(',')
                    .append(f4(jo.wz)).append("  participation(weight>0)=")
                    .append(jo.participation).append('\n');
            for (Member m : jo.trio) {
                sb.append("  cell ").append(m.cellX).append(',').append(m.cellZ)
                        .append(" slot=").append(m.slot).append(" entry=").append(m.entry)
                        .append(" ").append(m.label).append('\n');
            }
        }
        return sb.toString();
    }

    private String runLog(List<SceneRow> rows, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append("RecipeJunctionSceneSearch run log\n")
                .append("gallerySeed=").append(CANONICAL_SEED).append('\n')
                .append("regionalModeScale=").append(REGIONAL_MODE_SCALE).append('\n')
                .append("sceneRadiusBlocks=").append(SCENE_RADIUS).append('\n')
                .append("reqHalf=").append(REQ_HALF).append('\n')
                .append("chord=").append(DELIVER_CHORD).append(" maxSeg=").append(DELIVER_MAXSEG)
                .append('\n')
                .append("discoveryRadiusBlocks=").append(DISCOVERY_RADIUS).append('\n')
                .append("scanWindow=X[").append(WIN_X0).append(',').append(WIN_X1)
                .append("] Z[").append(WIN_Z0).append(',').append(WIN_Z1)
                .append("] stride=").append(stride).append(" noScan=").append(noScan)
                .append('\n')
                .append("elapsedMs=").append(ms).append('\n')
                .append("\nheaders (frozen):\n")
                .append("  scenes.csv    : ").append(SCENES_HEADER).append('\n')
                .append("  slots.csv     : ").append(SLOTS_HEADER).append('\n')
                .append("  junctions.csv : ").append(JUNCTIONS_HEADER).append('\n')
                .append("\nscenes:\n");
        for (SceneRow r : rows) {
            sb.append("  ").append(r.idText()).append("  status=").append(r.status)
                    .append("  center=").append(f4(r.cx)).append(',').append(f4(r.cz))
                    .append("  targets=").append(r.targetText())
                    .append("  members=").append(r.members.size())
                    .append("  edges=").append(r.edgeBandVerdict).append('\n');
        }
        return sb.toString();
    }

    // ============================================ 小工具

    private static String csv(String s) {
        if (s == null) return "";
        return s.indexOf(',') >= 0 ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    private static String f4(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    private static String f3(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
}
