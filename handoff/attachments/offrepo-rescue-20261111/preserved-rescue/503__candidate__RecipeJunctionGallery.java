package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>局部三向配方交汇画廊</b>：真实场景（{@code scenes.csv} 全部行）+ 受控三格组合，每张 601² 原生局部图。
 *
 * <p>只做局部图与剖面，<b>不接整幅、不接海陆组装、不接 Blender、不接水文</b>，不改任何既有文件。</p>
 *
 * <h3>两条臂（口径必须分开看）</h3>
 * <ul>
 *   <li><b>REAL</b>：{@code buildCombineRegional(SEED, 3600f)} 的真实 {@link RegionPartition} 几何，
 *       权重按 {@code LocalBoundaryGeometry.Sample.regionSlots} → {@code catalog.slotEntry(slot)}
 *       <b>按真实票槽归并到条目</b>。绝不按 {@code Category} 降级合并 —— 同一大类下
 *       PLAIN 与 STEPPE 是两个配方，归并会把它们的交界抹掉。
 *       <b>参与条目数不设上限</b>：实测 3 格与 4 格都有，超过 4 个也照原样保留、绝不截断
 *       （引擎与 {@link RecipeMorphCatalog} 本身支持全部 18 个条目）。</li>
 *   <li><b>CONTROLLED</b>：与真实臂<b>同一份</b>三格几何（原 DALES 点 (-362.3561,-977.0970) 的
 *       {@code (-1,-2),(0,-1),(-1,-1)}），把受控组合的成员按该顺序映射上去。
 *       这<b>不是</b>真实布局，图上必须标明。</li>
 * </ul>
 *
 * <h3>受控臂覆盖（10 个低地复合 + 3 个子配方 + 3 个大类，全 18 条目上镜）</h3>
 * <pre>
 *   五大类三三组合 10 组 : {0,3,15} … {15,16,17}
 *   低地复合 4 组       : {5,6,7} {8,9,10} {11,12,13} {14,15,16}  ⇒ 复合 5..14 全覆盖
 *   子配方 1 组         : {1,2,4}（STEPPE / DALES_GEO / HILLS2）
 * </pre>
 * <p>{@code --check} 会用 {@code coverage} 断言这 18 个条目<b>都</b>至少作为一条通道出现过。</p>
 *
 * <h3>每场景产出</h3>
 * <pre>
 *   morph.f64be          引擎 RecipeJunctionMorph.sample(x,z,double[18]) 的世界 Y
 *   finished-lerp.f64be  诊断对照 <b>Σᵢ wᵢ·y(referenceᵢ)</b>：先把每个参与条目的原配方换算成世界 Y，
 *                        再加权求和（<b>不是</b> y(Σᵢ wᵢ·referenceᵢ)）。两者不等价，因为
 *                        {@code HeightCurve.heightFromE} 非线性；本文件的定义以本条为准，
 *                        {@code gallery.json.finishedLerpFormula} 与 {@code scene.json.finishedLerp}
 *                        都把同一算式写进数据里，符号定义见 {@link #FINISHED_LERP_FORMULA}
 *   hard.f64be           无过渡：主导权重条目的 y(reference)
 *   hard-old.f64be       仅含台地的场景：台地旧 0..1.05 口径的同一张图（如实记录，非端点）
 *   weights.f64be        row-major，每像素 P 个 double（P = 该场景实际参与条目数，可为 3/4/…）
 *   participants.f64be   每像素权重 &gt; 0 的条目数（无上限）
 *   profiles.csv         0°/60°/120° 三条穿交点的 1D 剖面，±300 块、步长 0.5
 *   segments.csv / regions.csv / scene.json
 * </pre>
 *
 * <h3>高度映射（冻结）与配置门禁</h3>
 * <p>{@code y = HeightCurve.heightFromE(max(CombineCandidate.GROUND_E + h01·E_SPAN, 0))}，
 * 曲线来自冻结 TOML。{@code GROUND_E/E_SPAN} 若被动过，构造期直接抛错。</p>
 *
 * <p><b>配置门禁</b>：先在<b>一次性副本</b>上跑 {@code GeoGenesisConfig.SPEC.isCorrect}，
 * 不通过就<b>直接失败</b>，绝不把 {@code setConfig} 交给 Forge 的自动校正去"修好"再继续 ——
 * 那会让冻结 TOML 被静默改值（历史教训：{@code erosionLocalChargeWeight} 被 corrected 成默认值），
 * 出图口径随之无声变化。</p>
 *
 * <h3>口径声明（必须随图交付）</h3>
 * <p>真实臂只含<b>陆地层信号</b>：无海陆组装、<b>无 Blender</b>、无水文的任何图层。
 * 图里唯一保留的"旧层"是 {@code hard-old}（台地旧 0..1.05 值域），它<b>只为量纲对照</b>存在，
 * 不是端点、不是过渡结果；含台地场景的新旧差异<b>不能</b>整体归因于过渡算法。</p>
 */
public final class RecipeJunctionGallery {

    static final long SEED = 9139912035078620160L;

    // ---------------------------------------------------------------- 局部图口径
    static final int SIZE = 601;
    static final double STEP = 1.0;
    static final double RADIUS = 450.0;
    static final int REQ_HALF = 4;
    static final double CHORD = 0.02;
    static final double MAX_SEG = 2.0;
    /** 受控臂使用的真实三格几何中心（原 DALES 交汇点）。 */
    static final double CONTROLLED_CX = -362.3561;
    static final double CONTROLLED_CZ = -977.0970;
    /** 受控映射顺序：原 (-1,-2)、(0,-1)、(-1,-1)。 */
    static final int[][] CONTROLLED_CELLS = {{-1, -2}, {0, -1}, {-1, -1}};

    static final double PROFILE_HALF = 300.0;
    static final double PROFILE_STEP = 0.5;
    static final int[] PROFILE_ANGLES = {0, 60, 120};

    static final int ENTRY_COUNT = 18;

    /**
     * {@code finished-lerp.f64be} 的<b>实际</b>算式（写进 {@code gallery.json}）。
     *
     * <pre>
     *   finished-lerp = Σᵢ wᵢ · y(referenceᵢ)        // 先逐条换 Y，再加权（本类实现的就是它）
     *   NOT           = y( Σᵢ wᵢ · referenceᵢ )      // 先加权 h01，再换一次 Y
     *   y(h01)        = HeightCurve.heightFromE(max(GROUND_E + h01·E_SPAN, 0))
     * </pre>
     *
     * <p>{@code y} 是非线性样条，两种写法<b>不相等</b>；本画廊此前曾用过后者，
     * 现已统一为前者，并把算式同时写进 {@code gallery.json} 与每个 {@code scene.json}
     * （{@code finishedLerp}），避免"两条都叫 lerp"的口径混淆。</p>
     */
    static final String FINISHED_LERP_FORMULA =
            "\"sum_i w_i * y(reference_i)  [not y(sum_i w_i * reference_i)]\"";

    /** 5 大类：平原 P(0) / 丘陵1 H1(3) / 台地修正(15) / 劣地(16) / 山地1(17) 的全 10 种三向组合。 */
    static final int[][] CATEGORY_COMBOS = {
        {0, 3, 15}, {0, 3, 16}, {0, 3, 17}, {0, 15, 16}, {0, 15, 17},
        {0, 16, 17}, {3, 15, 16}, {3, 15, 17}, {3, 16, 17}, {15, 16, 17}
    };

    /**
     * 低地复合 4 组：把 10 个复合条目（5..14）<b>全部</b>带上场。
     * 复合条目两两相邻成组，第 4 组带平台/劣地做对照。
     */
    static final int[][] COMPOSITE_COMBOS = {
        {5, 6, 7}, {8, 9, 10}, {11, 12, 13}, {14, 15, 16}
    };

    /** 子配方 1 组：三个原始子配方同场（STEPPE / DALES_GEO / HILLS2）。 */
    static final int[] SUBRECIPE_COMBO = {1, 2, 4};

    static final int IDX_PLATEAU = 15;

    private final Path out;
    private final Path scenesCsv;
    private final Set<String> only;
    private final boolean skipReal;
    private final boolean skipControlled;
    private final RecipeMorphCatalog catalog = new RecipeMorphCatalog(SEED);
    private final RecipeJunctionMorph morph;
    private final HeightCurve curve;
    private final boolean configCorrect;
    private final List<String> finished = new ArrayList<>();
    private final List<Scene> planned = new ArrayList<>();
    /** 与 {@code scenes.csv} 的 {@code slotNEntry} 列交叉核对的结果（ENTRY 不一致 ⇒ 失败）。 */
    private final List<String> csvMismatches = new ArrayList<>();

    /**
     * @param configPath 冻结的 {@code run/config/geogenesis-common.toml}
     * @param out        输出根目录（每场景一个子目录）
     * @param scenesCsv  另一臂产出的 {@code scenes.csv}；{@code null} 则跳过真实臂
     */
    public RecipeJunctionGallery(Path configPath, Path out, Path scenesCsv,
                                 Set<String> only, boolean skipReal, boolean skipControlled)
            throws IOException {
        if (CombineCandidate.GROUND_E != 0.02 || CombineCandidate.E_SPAN != 1.50) {
            throw new IllegalStateException("frozen height mapping changed: GROUND_E="
                    + CombineCandidate.GROUND_E + " E_SPAN=" + CombineCandidate.E_SPAN);
        }
        this.out = out;
        this.scenesCsv = scenesCsv;
        this.only = only;
        this.skipReal = skipReal || scenesCsv == null;
        this.skipControlled = skipControlled;
        Files.createDirectories(out);
        Files.copy(configPath, out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        // ★ 门禁：先在一次性副本上跑 isCorrect —— 它会<b>就地</b>校正配置对象，
        //   所以绝不能拿即将喂给 setConfig 的那份去验，否则 Forge 的自动校正就是静默生效的。
        this.configCorrect = specCorrect(out.resolve("config-input.toml"));
        if (!configCorrect) {
            throw new IllegalStateException("recipe-junction-gallery: frozen TOML is not correct per"
                    + " GeoGenesisConfig.SPEC; refusing to continue because ForgeConfigSpec would"
                    + " silently auto-correct it and change the figure's height mapping."
                    + " Fix the TOML (see log line 'Incorrect key …') instead of relaxing this gate.");
        }
        try (CommentedFileConfig cfg = CommentedFileConfig.of(
                out.resolve("config-input.toml"), TomlFormat.instance())) {
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            this.curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);
        }
        this.morph = new RecipeJunctionMorph(catalog, SEED);
    }

    /**
     * 在<b>一次性副本</b>上验 spec 正确性：{@code isCorrect} 会就地校正配置对象，
     * 所以绝不能拿它去验即将喂给 {@code setConfig} 的那个实例，否则自动校正就是静默生效的。
     *
     * <p>不通过时把文件级差异一并打印（本次实测：本机 TOML 确实返回 false），
     * 便于主代理直接定位要修的键，而不是靠放宽门禁绕过。</p>
     */
    private static boolean specCorrect(Path file) throws IOException {
        try (CommentedFileConfig probe = CommentedFileConfig.of(file, TomlFormat.instance())) {
            probe.load();
            boolean ok = GeoGenesisConfig.SPEC.isCorrect(probe);
            if (!ok) {
                System.out.println("SPEC-GATE: " + file + " is not correct;"
                        + " the ForgeConfigSpec log line above names the offending key.");
            }
            return ok;
        }
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        try {
            if (args.length < 2 || "--help".equals(args[0])) {
                System.out.print(usage());
                if (args.length < 2) System.exit(2);
                return;
            }
            Set<String> only = new LinkedHashSet<>();
            Path scenes = null;
            boolean skipReal = false, skipControlled = false, check = false, report = false;
            for (int i = 2; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--only=")) only.addAll(Arrays.asList(a.substring(7).split(",")));
                else if (a.startsWith("--scenes=")) scenes = Path.of(a.substring(9));
                else if ("--skip-real".equals(a)) skipReal = true;
                else if ("--skip-controlled".equals(a)) skipControlled = true;
                else if ("--check".equals(a)) check = true;
                else if ("--spec-report".equals(a)) report = true;
                else throw new IllegalArgumentException("unknown argument " + a);
            }
            if (report) {
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                Files.copy(Path.of(args[0]), out.resolve("config-input.toml"),
                        StandardCopyOption.REPLACE_EXISTING);
                System.out.println("specCorrect=" + specCorrect(out.resolve("config-input.toml"))
                        + "  (read-only report; no gate, nothing written back to " + args[0] + ")");
                System.exit(0);
            }
            RecipeJunctionGallery g = new RecipeJunctionGallery(Path.of(args[0]), Path.of(args[1]),
                    scenes, only, skipReal, skipControlled);
            if (check) {
                g.selfCheck();
            } else {
                g.run();
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("RECIPE-JUNCTION-GALLERY FAILED");
            System.exit(1);
        }
    }

    private static String usage() {
        return "RecipeJunctionGallery <config.toml> <outDir> [--scenes=scenes.csv]"
                + " [--only=s01,c03] [--skip-real] [--skip-controlled] [--check] [--spec-report]\n"
                + "  --check       : 只建几何 + 抽样自检（不写 601² 大图）\n"
                + "  --spec-report : 只报冻结 TOML 的 spec 结论（副本上判，不回写）\n";
    }

    // ================================================================ 主流程

    void run() throws IOException {
        Files.createDirectories(out.resolve("scenes"));
        planScenes();
        System.out.println("scenes=" + planned.size() + " seed=" + SEED);
        for (Scene s : planned) {
            render(s);
            finished.add(s.id());
        }
        writeIndex();
        System.out.println("DONE scenes=" + finished.size());
    }

    /** 规划 + 覆盖断言（run 与 --check 共用同一套口径）。 */
    private void planScenes() throws IOException {
        planned.clear();
        for (Scene s : allScenes()) {
            if (!only.isEmpty() && !only.contains(s.id())) continue;
            planned.add(s);
        }
        if (planned.isEmpty()) throw new IllegalStateException("no scene selected");
    }

    private List<Scene> allScenes() throws IOException {
        List<Scene> list = new ArrayList<>();
        if (!skipControlled) {
            for (int i = 0; i < CATEGORY_COMBOS.length; i++) {
                list.add(controlled(String.format(Locale.ROOT, "c%02d", i + 1), "CATEGORY",
                        CATEGORY_COMBOS[i], "5-category three-way #" + (i + 1)));
            }
            for (int i = 0; i < COMPOSITE_COMBOS.length; i++) {
                list.add(controlled(String.format(Locale.ROOT, "x%02d", i + 1), "COMPOSITE",
                        COMPOSITE_COMBOS[i], "lowland composite three-way #" + (i + 1)));
            }
            list.add(controlled("x05", "SUBRECIPE", SUBRECIPE_COMBO, "sub-recipe three-way"));
        }
        if (!skipReal) list.addAll(realScenes());
        return list;
    }

    private static Scene controlled(String id, String group, int[] entries, String name) {
        return new Scene(id, group, name, CONTROLLED_CX, CONTROLLED_CZ, entries.clone(), "CONTROLLED",
                "entries mapped onto the real three cells (-1,-2),(0,-1),(-1,-1) of the DALES junction;"
                        + " this is NOT the real slot layout");
    }

    /**
     * 读 {@code scenes.csv} 的<b>全部</b>行（表头按名取列，支持引号字段），不做 id 白名单。
     *
     * <p>同时交叉核对 {@code slotNCellX/CellZ/Slot/Entry}：{@code catalog.slotEntry(slot)} 必须与
     * CSV 的 entry 一致（不一致说明槽位表口径不同，所有"真实配方"标签都会错 ⇒ 硬失败）；
     * 只有"该格不在本场景几何里"这种几何尺度差异才降级为告警。</p>
     */
    private List<Scene> realScenes() throws IOException {
        List<String> lines = Files.readAllLines(scenesCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalStateException("empty scenes.csv: " + scenesCsv);
        String[] head = splitCsv(lines.get(0));
        int cId = col(head, "sceneId"), cName = col(head, "name"), cX = col(head, "centerX");
        int cZ = col(head, "centerZ"), cStatus = col(head, "status"), cNote = col(head, "note");
        List<Scene> list = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = splitCsv(line);
            if (f.length < head.length) continue;
            String id = sanitize(f[cId]);
            if (!ids.add(id)) throw new IllegalStateException("duplicate sceneId " + id + " in " + scenesCsv);
            Scene s = new Scene(id, "REAL", f[cName], Double.parseDouble(f[cX]),
                    Double.parseDouble(f[cZ]), new int[0], f[cStatus], f[cNote]);
            list.add(s);
            crossCheckSlots(head, f, s);
        }
        if (list.isEmpty()) throw new IllegalStateException("no real scene parsed from " + scenesCsv);
        reportCsvCrossCheck();
        return list;
    }

    /** {@code slotN*} 列与 catalog 交叉核对（第四个参与者同样核，不裁）。 */
    private void crossCheckSlots(String[] head, String[] f, Scene s) throws IOException {
        LocalBoundaryGeometry geo = geometry(s.cx(), s.cz());
        for (int k = 1; k <= 4; k++) {
            String px = "slot" + k;
            int iCellX = optionalCol(head, px + "CellX"), iCellZ = optionalCol(head, px + "CellZ");
            int iSlot = optionalCol(head, px + "Slot"), iEntry = optionalCol(head, px + "Entry");
            int iLabel = optionalCol(head, px + "Label");
            if (iCellX < 0 || iCellZ < 0 || iSlot < 0 || iEntry < 0) continue;
            String cx = f[iCellX].trim(), cz = f[iCellZ].trim();
            String slot = f[iSlot].trim(), entry = f[iEntry].trim();
            if (cx.isEmpty() || cz.isEmpty() || slot.isEmpty() || entry.isEmpty()) continue;
            String label = iLabel >= 0 ? f[iLabel].trim() : "";
            // 不存在的参与者：CSV 写 0,0,0,0 + 空 label ⇒ 不是真槽位，跳过。
            if ("0".equals(cx) && "0".equals(cz) && "0".equals(entry) && label.isEmpty()) continue;
            int cellX = Integer.parseInt(cx), cellZ = Integer.parseInt(cz);
            int slotIdx = Integer.parseInt(slot), csvEntry = Integer.parseInt(entry);
            int catalogEntry = catalog.slotEntry(slotIdx);
            String at = "scene " + s.id() + " slot" + k + " cell " + cellX + ":" + cellZ
                    + " slot " + slotIdx;
            if (catalogEntry != csvEntry) {
                csvMismatches.add("ENTRY " + at + ": catalog " + catalogEntry + " vs csv " + csvEntry);
            }
            boolean site = geo.sites().stream().anyMatch(t -> t.cellX() == cellX && t.cellZ() == cellZ);
            if (!site) csvMismatches.add("CELL " + at + " is not a site of this scene geometry");
        }
    }

    /** ENTRY 不一致 ⇒ 硬失败；只有 CELL 备注 ⇒ 打印告警并落盘。 */
    private void reportCsvCrossCheck() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String m : csvMismatches) {
            sb.append(m).append('\n');
            if (m.startsWith("ENTRY")) {
                throw new IllegalStateException("scenes.csv slot mapping disagrees with the catalog: " + m);
            }
        }
        Files.writeString(out.resolve("scenes-csv-crosscheck.txt"),
                csvMismatches.isEmpty() ? "no mismatch against scenes.csv\n" : sb.toString(),
                StandardCharsets.UTF_8);
        if (!csvMismatches.isEmpty()) {
            System.out.println("WARN scenes.csv geometry note x" + csvMismatches.size()
                    + " (see scenes-csv-crosscheck.txt)");
        }
    }

    private static int col(String[] head, String name) {
        int i = optionalCol(head, name);
        if (i < 0) throw new IllegalArgumentException("scenes.csv missing column " + name);
        return i;
    }

    private static int optionalCol(String[] head, String name) {
        for (int i = 0; i < head.length; i++) if (head[i].equals(name)) return i;
        return -1;
    }

    /** 最小 CSV 切分：去 BOM、双引号包裹（含逗号与 "" 转义）。 */
    private static String[] splitCsv(String line) {
        String text = line.replace("\uFEFF", "");
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    private static String sanitize(String s) {
        return s.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ================================================================ 自检（--check）

    /**
     * <b>不写 601² 大图</b>的接线自检：
     * <ol>
     *   <li>配置门禁已通过（构造期）；</li>
     *   <li>每场景建几何 → 发现参与条目 → 抽样 5×5 + 三剖面端点，断言权重和为 1、条目号合法、
     *       所有输出量有限、参与者数 <b>不被裁剪</b>（记录实测最大值，不设上限）；</li>
     *   <li>受控臂断言 18 个条目<b>全部</b>至少作为一条通道出现（复合 5..14 全覆盖）。</li>
     * </ol>
     */
    void selfCheck() throws IOException {
        planScenes();
        int[] covered = new int[ENTRY_COUNT];
        int maxParticipants = 0;
        int points = 0;
        StringBuilder log = new StringBuilder("check seed=" + SEED + "\n");
        for (Scene s : planned) {
            LocalBoundaryGeometry geo = geometry(s.cx(), s.cz());
            int[] entryOfSite = mapSites(geo, s);
            int[] channels = channels(s, geo, entryOfSite);
            if (!s.isReal()) for (int c : channels) covered[c]++;
            Scratch sc = new Scratch(channels.length);
            for (int j = -2; j <= 2; j++) {
                for (int i = -2; i <= 2; i++) {
                    maxParticipants = Math.max(maxParticipants,
                            sample(s, geo, entryOfSite, channels, sc,
                                    s.cx() + i * 150, s.cz() + j * 150));
                    points++;
                }
            }
            for (int angle : PROFILE_ANGLES) {
                double r = Math.toRadians(angle);
                maxParticipants = Math.max(maxParticipants,
                        sample(s, geo, entryOfSite, channels, sc,
                                s.cx() + PROFILE_HALF * Math.cos(r),
                                s.cz() + PROFILE_HALF * Math.sin(r)));
                points++;
            }
            String line = String.format(Locale.ROOT, "%-6s %-10s channels=%d [%s] center=%s",
                    s.id(), s.group(), channels.length, ids(channels),
                    centerWeights(s, geo, entryOfSite, channels, sc));
            log.append(line).append('\n');
            System.out.println(line);
        }
        if (!skipControlled) {
            StringBuilder gap = new StringBuilder();
            for (int e = 0; e < ENTRY_COUNT; e++) {
                if (covered[e] == 0) gap.append(gap.length() == 0 ? "" : ", ").append(e);
            }
            if (gap.length() > 0) {
                throw new IllegalStateException("controlled arm does not cover entries [" + gap
                        + "]; every one of the 18 must appear at least once");
            }
            log.append("coverage: all 18 entries appear in the controlled arm\n");
        }
        log.append("maxParticipantsObserved=").append(maxParticipants)
                .append(" (no cap: only 4-participant and 3-participant layouts were observed;"
                        + " nothing is trimmed if more ever appear)\n");
        log.append("points=").append(points).append('\n');
        Files.writeString(out.resolve("selfcheck.txt"), log.toString(), StandardCharsets.UTF_8);
        System.out.println("PASS selfcheck scenes=" + planned.size() + " points=" + points
                + " maxParticipants=" + maxParticipants);
    }

    /** 抽一点，返回该点的参与条目数（并做全部有限性/合法性断言）。 */
    private int sample(Scene s, LocalBoundaryGeometry geo, int[] entryOfSite, int[] channels,
                       Scratch sc, double x, double z) {
        evaluate(x, z, geo, entryOfSite, channels, sc);
        double sum = 0;
        int active = 0;
        for (int k = 0; k < channels.length; k++) {
            sum += sc.field[k];
            if (sc.field[k] > 0) active++;
        }
        if (Math.abs(sum - 1) > 1e-9) {
            throw new IllegalStateException("weights sum " + sum + " at " + x + "," + z + " in " + s.id());
        }
        if (active < 1) throw new IllegalStateException("no participant at " + x + "," + z);
        if (active > channels.length) throw new IllegalStateException("participants > channels");
        return active;
    }

    private String centerWeights(Scene s, LocalBoundaryGeometry geo, int[] entryOfSite,
                                 int[] channels, Scratch sc) {
        evaluate(s.cx(), s.cz(), geo, entryOfSite, channels, sc);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < channels.length; k++) {
            if (sc.field[k] <= 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(catalog.entry(channels[k]).id())
                    .append('=').append(String.format(Locale.ROOT, "%.3f", sc.field[k]));
        }
        return sb.toString();
    }

    private String ids(int[] channels) {
        StringBuilder sb = new StringBuilder();
        for (int c : channels) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c).append(':').append(catalog.entry(c).id());
        }
        return sb.toString();
    }

    // ================================================================ 单场景

    private LocalBoundaryGeometry geometry(double cx, double cz) {
        LocalBoundaryGeometry geo = LocalBoundaryGeometry.of(
                catalog.original(), cx, cz, RADIUS, REQ_HALF, CHORD);
        geo.setMaxSeg(MAX_SEG);
        return geo;
    }

    private void render(Scene scene) throws IOException {
        Path dir = out.resolve("scenes").resolve(scene.id());
        Files.createDirectories(dir);
        long t0 = System.nanoTime();
        LocalBoundaryGeometry geo = geometry(scene.cx(), scene.cz());
        int[] entryOfSite = mapSites(geo, scene);
        int[] channels = channels(scene, geo, entryOfSite);
        boolean plateau = Arrays.stream(channels).anyMatch(c -> c == IDX_PLATEAU);
        Metrics m = new Metrics();
        Scratch sc = new Scratch(channels.length);
        try (Writers w = new Writers(dir, channels.length, plateau)) {
            fields(scene, geo, entryOfSite, channels, sc, m, w);
            profiles(dir, scene, geo, entryOfSite, channels, sc, m);
        }
        writeGeometry(geo, dir);
        Files.writeString(dir.resolve("scene.json"),
                sceneJson(scene, channels, geo, plateau, m, (System.nanoTime() - t0) / 1_000_000L),
                StandardCharsets.UTF_8);
        System.out.println(scene.id() + " ok sites=" + geo.sites().size() + " channels="
                + channels.length + " ms=" + (System.nanoTime() - t0) / 1_000_000L);
    }

    private static void writeGeometry(LocalBoundaryGeometry geo, Path dir) throws IOException {
        try {
            geo.writeSegmentsCsv(dir.resolve("segments.csv"));
            geo.writeRegionsCsv(dir.resolve("regions.csv"));
        } catch (Exception e) {
            throw new IOException("cannot write geometry csv for " + dir, e);
        }
    }

    /**
     * 站点 → 条目：真实臂按 {@code slotEntry(槽)}，受控臂按原三格顺序。
     *
     * <p>受控臂的几何里除那三格外的站点只登记为 -1（它们不出现在任何有效过渡里）；
     * <b>真正被查到时</b>若仍是 -1，{@link #mergeWeights} 立刻抛错 —— 严格性不丢。</p>
     */
    private int[] mapSites(LocalBoundaryGeometry geo, Scene scene) {
        List<LocalBoundaryGeometry.Site> sites = geo.sites();
        int[] entry = new int[sites.size()];
        for (int i = 0; i < entry.length; i++) {
            LocalBoundaryGeometry.Site s = sites.get(i);
            entry[i] = scene.entries().length == 0
                    ? catalog.slotEntry(geo.slotOfCell(s.cellX(), s.cellZ()))
                    : controlledEntry(s, scene);
        }
        return entry;
    }

    /** 原三格 → 受控条目；其它格返回 -1（被查到才报错）。 */
    private static int controlledEntry(LocalBoundaryGeometry.Site s, Scene scene) {
        for (int k = 0; k < CONTROLLED_CELLS.length; k++) {
            if (s.cellX() == CONTROLLED_CELLS[k][0] && s.cellZ() == CONTROLLED_CELLS[k][1]) {
                return scene.entries()[k];
            }
        }
        return -1;
    }

    /**
     * 权重通道 = 参与条目，<b>按条目</b>而不是按大类，<b>数量不设上限</b>
     * （{@link RecipeMorphCatalog} 支持全部 18 个条目，出现多少就保留多少）。
     *
     * <p>受控臂用声明的 3 个；真实臂用实测出现的集合：中心粗扫 121×121 + 全部边中点 + 边端点。</p>
     */
    private int[] channels(Scene scene, LocalBoundaryGeometry geo, int[] entryOfSite) {
        if (scene.entries().length > 0) {
            int[] c = scene.entries().clone();
            Arrays.sort(c);
            return c;
        }
        boolean[] seen = new boolean[ENTRY_COUNT];
        double[] merged = new double[ENTRY_COUNT];
        int n = 121;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                discover(scene.cx() + (i - n / 2) * (2 * PROFILE_HALF / (n - 1)),
                        scene.cz() + (j - n / 2) * (2 * PROFILE_HALF / (n - 1)),
                        geo, entryOfSite, merged, seen);
            }
        }
        for (LocalBoundaryGeometry.Edge e : geo.edges()) {
            for (int k = 0; k < e.wx.length; k++) {
                discover(e.wx[k], e.wz[k], geo, entryOfSite, merged, seen);
            }
        }
        discover(scene.cx(), scene.cz(), geo, entryOfSite, merged, seen);
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < ENTRY_COUNT; i++) if (seen[i]) list.add(i);
        if (list.isEmpty()) throw new IllegalStateException("no participating entry at " + scene.id());
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private void discover(double x, double z, LocalBoundaryGeometry geo, int[] entryOfSite,
                          double[] merged, boolean[] seen) {
        if (Math.hypot(x - geo.centerX(), z - geo.centerZ()) >= geo.queryRadius()) return;
        mergeWeights(x, z, geo, entryOfSite, merged);
        for (int e = 0; e < ENTRY_COUNT; e++) if (merged[e] > 0) seen[e] = true;
    }

    /** 按条目归并权重并归一化；参与格数可变，<b>一个都不裁</b>。 */
    private void mergeWeights(double x, double z, LocalBoundaryGeometry geo,
                              int[] entryOfSite, double[] merged) {
        LocalBoundaryGeometry.Sample s = geo.query(x, z);
        Arrays.fill(merged, 0.0);
        double sum = 0;
        for (int i = 0; i < s.weight.length; i++) {
            if (s.weight[i] <= 0) continue;
            int e = entryOfSite[i];
            if (e < 0 || e >= ENTRY_COUNT) throw new IllegalStateException("entry " + e + " for site " + i);
            merged[e] += s.weight[i];
            sum += s.weight[i];
        }
        if (!(sum > 1e-9)) throw new IllegalStateException("empty weight set at " + x + "," + z);
        for (int i = 0; i < ENTRY_COUNT; i++) merged[i] /= sum;
    }

    // ================================================================ 场

    private void fields(Scene scene, LocalBoundaryGeometry geo, int[] entryOfSite, int[] channels,
                        Scratch sc, Metrics m, Writers w) throws IOException {
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double x = scene.cx() + (i - SIZE / 2) * STEP;
                double z = scene.cz() + (j - SIZE / 2) * STEP;
                evaluate(x, z, geo, entryOfSite, channels, sc);
                w.morph.write(sc.field[sc.p]);
                w.lerp.write(sc.field[sc.p + 1]);
                w.hard.write(sc.field[sc.p + 2]);
                if (w.hardOld != null) w.hardOld.write(sc.field[sc.p + 3]);
                w.participants.write(sc.field[sc.p + 4]);
                w.weights.writeRow(sc.field, 0, sc.p);
                m.add(sc);
            }
            if (j % 100 == 0) System.out.println("  " + scene.id() + " row=" + j);
        }
    }

    /**
     * 单点求值：{@code field[0..P-1]} 为各参与条目权重，
     * {@code field[P..P+4]} 依次为 morph / lerp / hard / hardOld / participants（世界 Y）。
     *
     * <p>{@code hardOld} 用 {@link RecipeMorphCatalog.Entry#oldReference}：非台地条目它与
     * {@code reference} 相同，故只有台地主导处才真正体现旧 0..1.05 口径。</p>
     */
    private void evaluate(double x, double z, LocalBoundaryGeometry geo, int[] entryOfSite,
                          int[] channels, Scratch sc) {
        mergeWeights(x, z, geo, entryOfSite, sc.merged);
        int p = channels.length;
        int active = 0, best = 0;
        double bestW = -1;
        for (int k = 0; k < p; k++) {
            double wk = sc.merged[channels[k]];
            sc.field[k] = wk;
            if (wk > 0) active++;
            if (wk > bestW) { bestW = wk; best = k; }
            sc.ref[k] = wk > 0 ? catalog.entry(channels[k]).reference(x, z) : Double.NaN;
        }
        double lerp = 0, representedWeight = 0;
        for (int k = 0; k < p; k++) {
            representedWeight += sc.field[k];
            if (sc.field[k] > 0) lerp += sc.field[k] * y(sc.ref[k]);
        }
        if (Math.abs(representedWeight - 1) > 1e-12) {
            throw new IllegalStateException("channel discovery missed a participant at " + x + "," + z);
        }
        // ★ 口径：finished-lerp = Σ wᵢ·y(referenceᵢ)（先换 Y 再加权），见 FINISHED_LERP_FORMULA。
        //   不是 y(Σ wᵢ·referenceᵢ) —— HeightCurve 非线性，两者不等价。
        sc.field[p] = y(morph.sample(x, z, sc.merged));
        sc.field[p + 1] = lerp;
        sc.field[p + 2] = y(sc.ref[best]);
        sc.field[p + 3] = y(catalog.entry(channels[best]).oldReference(x, z));
        sc.field[p + 4] = active;
        for (int k = 0; k < p + 5; k++) {
            if (!Double.isFinite(sc.field[k])) {
                throw new IllegalStateException("non-finite field[" + k + "] at " + x + "," + z);
            }
        }
    }

    private double y(double h01) {
        return curve.heightFromE(Math.max(CombineCandidate.GROUND_E + h01 * CombineCandidate.E_SPAN, 0));
    }

    // ================================================================ 剖面

    private void profiles(Path dir, Scene scene, LocalBoundaryGeometry geo, int[] entryOfSite,
                          int[] channels, Scratch sc, Metrics m) throws IOException {
        int p = channels.length;
        StringBuilder csv = new StringBuilder("scene,angle,t,x,z");
        for (int k = 0; k < p; k++) csv.append(",w").append(channels[k]);
        csv.append(",morph_y,lerp_y,hard_y,hard_old_y,participants\n");
        for (int angle : PROFILE_ANGLES) {
            double r = Math.toRadians(angle);
            int steps = (int) Math.round(PROFILE_HALF / PROFILE_STEP);
            for (int i = -steps; i <= steps; i++) {
                double t = i * PROFILE_STEP;
                double x = scene.cx() + t * Math.cos(r), z = scene.cz() + t * Math.sin(r);
                if (Math.hypot(x - scene.cx(), z - scene.cz()) > RADIUS) continue;
                evaluate(x, z, geo, entryOfSite, channels, sc);
                csv.append(scene.id()).append(',').append(angle)
                        .append(String.format(Locale.ROOT, ",%.3f,%.6f,%.6f", t, x, z));
                for (int k = 0; k < p; k++) csv.append(String.format(Locale.ROOT, ",%.9f", sc.field[k]));
                for (int k = 0; k < 5; k++) {
                    csv.append(String.format(Locale.ROOT, ",%.6f", sc.field[p + k]));
                }
                csv.append('\n');
                m.addProfile(sc);
            }
        }
        Files.writeString(dir.resolve("profiles.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    // ================================================================ 元数据

    private String sceneJson(Scene scene, int[] channels, LocalBoundaryGeometry geo,
                             boolean plateau, Metrics m, long ms) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"id\": \"").append(scene.id()).append("\",\n");
        sb.append("  \"group\": \"").append(scene.group()).append("\",\n");
        sb.append("  \"name\": \"").append(esc(scene.name())).append("\",\n");
        sb.append("  \"status\": \"").append(esc(scene.status())).append("\",\n");
        sb.append("  \"note\": \"").append(esc(scene.note())).append("\",\n");
        sb.append("  \"realLayout\": ").append(scene.entries().length == 0).append(",\n");
        sb.append(String.format(Locale.ROOT, "  \"center\": [%.6f, %.6f],%n", scene.cx(), scene.cz()));
        sb.append("  \"size\": ").append(SIZE).append(",\n");
        sb.append("  \"step\": ").append(STEP).append(",\n");
        sb.append(String.format(Locale.ROOT, "  \"queryRadius\": %.1f,%n", RADIUS));
        sb.append("  \"reqHalf\": ").append(REQ_HALF).append(",\n");
        sb.append("  \"chord\": ").append(CHORD).append(",\n");
        sb.append("  \"maxSeg\": ").append(MAX_SEG).append(",\n");
        sb.append("  \"plateauLegacyExported\": ").append(plateau).append(",\n");
        sb.append("  \"finishedLerp\": ").append(FINISHED_LERP_FORMULA).append(",\n");
        sb.append("  \"channelCount\": ").append(channels.length).append(",\n");
        sb.append("  \"participantCap\": null,\n");
        sb.append("  \"weightLayout\": \"row-major, per pixel, channelCount doubles\",\n");
        sb.append("  \"channels\": [");
        for (int k = 0; k < channels.length; k++) {
            if (k > 0) sb.append(", ");
            sb.append("{\"entryIndex\": ").append(channels[k])
                    .append(", \"id\": \"").append(esc(catalog.entry(channels[k]).id())).append("\"}");
        }
        sb.append("],\n");
        sb.append("  \"geometry\": {\"sites\": ").append(geo.sites().size())
                .append(", \"edges\": ").append(geo.edges().size())
                .append(String.format(Locale.ROOT, ", \"maxWarpResidual\": %.3e", geo.maxWarpResidualBlocks()))
                .append("},\n");
        sb.append("  \"layers\": \"land composition only: no sea/land assembly, no Blender, no hydrology;"
                + " hard-old is the plateau legacy 0..1.05 band kept for scale reference only\",\n");
        sb.append("  \"metrics\": ").append(m.json()).append(",\n");
        sb.append("  \"buildMs\": ").append(ms).append(",\n");
        sb.append("  \"seed\": \"").append(SEED).append("\"\n}\n");
        return sb.toString();
    }

    /** JSON 字符串转义（只用于 scene.json 的 name/status/note/id）。 */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** CSV 字段转义：含 `,` `"` CR/LF 时整体加双引号，内部 `"` 写成 `""`（RFC 4180）。 */
    private static String quoteCsv(String value) {
        String v = value == null ? "" : value;
        boolean need = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') { need = true; break; }
        }
        return need ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    /** {@code index.csv} 的 channels 列内容：{@code 5:PLAIN_GEO+STEPPE 9:STEPPE+DALES_GEO …}（空格分隔）。 */
    private String channelsText(String sceneId) throws IOException {
        Path json = out.resolve("scenes").resolve(sceneId).resolve("scene.json");
        if (!Files.exists(json)) return "";
        String text = Files.readString(json, StandardCharsets.UTF_8);
        int at = text.indexOf("\"channels\": [");
        if (at < 0) return "";
        int end = text.indexOf(']', at);
        String body = text.substring(at + "\"channels\": [".length(), end);
        StringBuilder sb = new StringBuilder();
        for (String part : body.split("\\{")) {
            int i = part.indexOf("\"entryIndex\": ");
            int q = part.indexOf("\"id\": \"");
            if (i < 0 || q < 0) continue;
            String idx = part.substring(i + "\"entryIndex\": ".length()).split(",")[0].trim();
            String id = part.substring(q + "\"id\": \"".length());
            int close = id.indexOf('"');
            if (close >= 0) id = id.substring(0, close);
            if (sb.length() > 0) sb.append(' ');
            sb.append(idx).append(':').append(id);
        }
        return sb.toString();
    }

    private void writeIndex() throws IOException {
        StringBuilder csv = new StringBuilder(
                "sceneId,group,name,centerX,centerZ,realLayout,status,channels,directory\n");
        for (Scene s : planned) {
            if (!finished.contains(s.id())) continue;
            String ch = channelsText(s.id());
            // 每个字段都过 quoteCsv：channels 值里必然含 "," 与 ":"，name/status/note 也可能含逗号。
            csv.append(quoteCsv(s.id())).append(',')
                    .append(quoteCsv(s.group())).append(',')
                    .append(quoteCsv(s.name())).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", s.cx())).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", s.cz())).append(',')
                    .append(s.isReal()).append(',')
                    .append(quoteCsv(s.status())).append(',')
                    .append(quoteCsv(ch)).append(',')
                    .append(quoteCsv("scenes/" + s.id())).append('\n');
        }
        Files.writeString(out.resolve("index.csv"), csv.toString(), StandardCharsets.UTF_8);
        StringBuilder json = new StringBuilder("{\n  \"seed\": \"" + SEED + "\",\n  \"size\": " + SIZE
                + ",\n  \"step\": " + STEP + ",\n  \"profileHalf\": " + PROFILE_HALF
                + ",\n  \"profileStep\": " + PROFILE_STEP
                + ",\n  \"finishedLerpFormula\": " + FINISHED_LERP_FORMULA
                + ",\n  \"configSpecCorrect\": " + configCorrect
                + ",\n  \"scenes\": [");
        for (int i = 0; i < finished.size(); i++) {
            if (i > 0) json.append(", ");
            json.append('"').append(finished.get(i)).append('"');
        }
        json.append("]\n}\n");
        Files.writeString(out.resolve("gallery.json"), json.toString(), StandardCharsets.UTF_8);
    }

    // ================================================================ 载体

    private record Scene(String id, String group, String name, double cx, double cz,
                         int[] entries, String status, String note) {
        boolean isReal() { return entries.length == 0; }
    }

    /** 复用缓冲：避免每像素分配。 */
    private static final class Scratch {
        final double[] merged = new double[ENTRY_COUNT];
        final double[] ref;
        final double[] field;
        final int p;

        Scratch(int p) {
            this.p = p;
            this.ref = new double[p];
            this.field = new double[p + 5];
        }
    }

    /** 各 f64be 输出流（大端 double，与既有探针一致）。 */
    private static final class Writers implements AutoCloseable {
        final Sink morph, lerp, hard, participants, weights;
        final Sink hardOld;

        Writers(Path dir, int channels, boolean plateau) throws IOException {
            morph = new Sink(dir.resolve("morph.f64be"));
            lerp = new Sink(dir.resolve("finished-lerp.f64be"));
            hard = new Sink(dir.resolve("hard.f64be"));
            participants = new Sink(dir.resolve("participants.f64be"));
            weights = new Sink(dir.resolve("weights.f64be"));
            hardOld = plateau ? new Sink(dir.resolve("hard-old.f64be")) : null;
            if (channels <= 0) throw new IllegalArgumentException("channel count " + channels);
        }

        @Override public void close() throws IOException {
            for (Sink s : new Sink[]{morph, lerp, hard, hardOld, participants, weights}) {
                if (s != null) s.close();
            }
        }
    }

    private static final class Sink implements AutoCloseable {
        private final DataOutputStream out;

        Sink(Path file) throws IOException {
            this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
        }

        void write(double v) throws IOException {
            if (!Double.isFinite(v)) throw new IllegalStateException("non-finite value written to " + out);
            out.writeDouble(v);
        }

        void writeRow(double[] values, int from, int count) throws IOException {
            for (int i = 0; i < count; i++) write(values[from + i]);
        }

        @Override public void close() throws IOException { out.close(); }
    }

    /** 逐场景统计（写进 scene.json，供渲染脚本标注）。 */
    private static final class Metrics {
        private double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        private double maxLerpDiff = 0, maxLerpDiffProfile = 0, maxHardOldDiff = 0;
        private int maxParticipants = 0;
        private long pixels = 0;

        void add(Scratch sc) {
            double y = sc.field[sc.p];
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            maxLerpDiff = Math.max(maxLerpDiff, Math.abs(y - sc.field[sc.p + 1]));
            maxHardOldDiff = Math.max(maxHardOldDiff, Math.abs(sc.field[sc.p + 2] - sc.field[sc.p + 3]));
            maxParticipants = Math.max(maxParticipants, (int) sc.field[sc.p + 4]);
            pixels++;
        }

        void addProfile(Scratch sc) {
            maxLerpDiffProfile = Math.max(maxLerpDiffProfile,
                    Math.abs(sc.field[sc.p] - sc.field[sc.p + 1]));
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"yMin\": %.6f, \"yMax\": %.6f, \"maxAbsMorphMinusLerp\": %.6f,"
                            + " \"maxAbsMorphMinusLerpOnProfiles\": %.6f,"
                            + " \"maxAbsHardMinusHardOld\": %.6f,"
                            + " \"maxParticipants\": %d, \"pixels\": %d}",
                    minY, maxY, maxLerpDiff, maxLerpDiffProfile, maxHardOldDiff, maxParticipants, pixels);
        }
    }
}
