package com.geogenesis.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 配置键守卫：{@code GeoGenesisConfig} 里声明的每个键，必须在**生产代码**里被读取，
 * 否则门禁 FAIL。
 *
 * <p><b>为什么需要这个</b>：配置键的死活以前只靠人记。2026-11-06 一天之内清掉两批死开关
 * （骨架侵蚀 9 个键、预览面板两个开关），紧接着又发现 {@code erosionEnabled} 也是死的
 * —— 生产路径完全不读它，只有配置屏与预览面板在读写它。这类缺陷的共同特征是：
 * <b>UI 上有开关、TOML 里有键、点了没有任何效果</b>，用户会以为功能坏了。
 * 光靠 review 抓不住，必须机器守。</p>
 *
 * <h2>判据</h2>
 * <ul>
 *   <li><b>production</b>：{@code src/main/java/**}，但排除
 *       {@code config/GeoGenesisConfig.java} 自身（声明处与指纹都在这里，读它不代表生效）、
 *       {@code client/**}（UI 只是展示/编辑，不构成"影响产出"），
 *       以及 {@linkplain #CARRIER_FILES 数据载体文件}（见下）。</li>
 *   <li><b>LIVE</b>：在 production 里至少出现一次 → 通过。</li>
 *   <li><b>CLIENT_ONLY</b>：只在 {@code client/**} 出现 → 即"假开关"，FAIL。</li>
 *   <li><b>CARRIER_ONLY</b>：只在数据载体文件里出现（典型是
 *       {@code TerrainParams}/{@code SplineConfig} 的组件声明与 {@code buildParams()} 透传）
 *       → 说明该键只是被"搬运"，没有任何消费方 → FAIL。</li>
 *   <li><b>CONFIG_ONLY</b>：只在 {@code GeoGenesisConfig} 内部被读（典型是
 *       {@code buildSplineConfig()} 这类惰性构建器）→ <b>只警告不失败</b>。
 *       这类键是否真影响产出取决于那个构建器的调用方，机器判不了，留给人工。</li>
 *   <li><b>UNREAD</b>：整个 {@code src/main} 都没有 → FAIL。</li>
 *   <li><b>UNREACHABLE</b>（{@code --reachability} 或 <b>默认开启但只 WARN</b>）：
 *       只在「从生产入口做调用图 BFS 不可达」的文件里被读 → 目前<b>只报告不失败</b>，
 *       见下面的 TODO。</li>
 * </ul>
 *
 * <h3>★ 数据载体文件白名单（2026-11-07 新增）</h3>
 * <p>此前 LIVE 严重高估：{@code TerrainParams} / {@code SplineConfig} /
 * {@code OceanSplineConfig} / {@code MidSplineConfig} 是<b>数据载体 record</b>——
 * 它们只声明组件、并按顺序把值透传下去（{@code buildParams()} / {@code defaults()} /
 * {@code buildSplineConfig()}），<b>没有任何算法读取</b>。把它们算成"生产命中"
 * 会让一个只在载体里躺着的死键被误判为 LIVE。现在这些文件的命中单独归入
 * {@link #CARRIER_FILES 载体集合}，不计 LIVE。</p>
 *
 * <h3>★ 可达性 BFS（2026-11-07 新增，仍为 WARN）</h3>
 * <p>从生产入口（生成器 / 地形门面 / 群系源 / Mod 入口 / 服务端事件）出发，
 * 在 {@code src/main/java}（除 {@code client/}）上按"文件<b>去注释后</b>提到其它文件的简单类名"
 * 做 BFS。这是调用图的<b>过近似</b>（over-approximation）：它只会多算可达、不会少算，
 * 因此 {@code UNREACHABLE} 是<b>可信的</b>（出现即真的没有静态调用路径）。</p>
 *
 * <p><b>为什么现在仍不能判 FAIL</b>（2026-11-07 复核后的定案，不是遗留 TODO）：</p>
 * <ul>
 *   <li><b>① 误判源已清除，报告是真阳性。</b>本轮修掉两个真实误判源
 *       （javadoc 散文造边、{@code GeoGenesisConfig} 声明枢纽）。
 *       曾经仅剩的 2 个 UNREACHABLE 键（{@code elevHigh}/{@code reliefHigh}，
 *       只被带 {@code main} 的 {@code TerrainDiag} 读）<b>已于 2026-11-07 删除</b>，
 *       当前 {@code src/main} 内应为 0 个 UNREACHABLE。</li>
 *   <li><b>② 但"正确处置"未定案。</b>判 FAIL 的前提是存在一个正确的修法。
 *       这两个键的修法是「接线到生产」或「删除」，两者都改变行为/公共面，
 *       超出本轮「配置键清理」范围（用户明令不扩大范围）。此时判 FAIL
 *       只会逼人加白名单 ⇒ 变相放宽判据，与本探针的立身之本相反。</li>
 *   <li><b>③ 根清单是手写的，且没有自检。</b>BFS 只能证明"从这 5 个入口到不了"，
 *       不能证明"没有别的入口"（未来可能新增 datagen / 命令 / 事件订阅）。
 *       判 FAIL 会把"根清单漏项"（探针自身缺陷）变成构建红灯。</li>
 * </ul>
 * <p>⇒ <b>提升为 FAIL 的前置条件</b>（写在这里，避免再次变成"文档里的承诺"）：
 * ~~先处置掉 {@code elevHigh}/{@code reliefHigh}~~（已于 2026-11-07 删除，条件①已满足），
 * 再给 {@link #PRODUCTION_ROOTS} 加一条"根清单完整性"自检
 * （例如要求根类必须落在 {@code @Mod} 入口或 {@code ChunkGenerator} 子类集合内）。
 * 两条都满足后，把 {@code UNREACHABLE} 从 warnings 挪进 violations 即可，判据逻辑不用改。</p>
 *
 * <h3>★ 链路口径（2026-11-07 新增；只报告，不判 FAIL）</h3>
 * <p>把 LIVE 再按"读取点落在哪条地形链"细分：只被 morph 链读 / 只被 legacy 链读 /
 * 两条链都读 / 两条链都不读（嵌在生成器或门面层的开关）。</p>
 *
 * <p><b>为什么用户已裁定"无 legacy 存档"却仍不判 FAIL</b>：</p>
 * <ul>
 *   <li>legacy 链<b>并没有从代码里删除</b>，它仍是 {@code CellGenerator.morphBase=false}
 *       的活分支。被 legacy 读的键<b>不是死键</b>，只是"对新世界不影响"——
 *       这是<b>轴信息</b>，不是缺陷。</li>
 *   <li>判 FAIL 等于要求"凡不影响 morph 的键都必须删"，那会删掉整条 legacy 链的旋钮
 *       （{@code beltReliefAmp}/{@code basinBase}/{@code coastlineWarp*} 等 12 个键
 *        + 全部内层样条 Loc/Deriv），而用户本轮明确要求<b>保留并标注</b> legacy-only 的键。
 *       判据若与用户裁定冲突，错的是判据。</li>
 *   <li>该维度的价值是<b>让"对 morph 无效的旋钮数量"可见</b>，不必读代码就能看到。</li>
 * </ul>
 * <p>⚠ 两个已知盲区（据实写明，不粉饰）：<br>
 * ① {@code CellGenerator} <b>刻意不入任何一组的根</b> —— 它是全仓唯一的 {@code morphBase}
 *    分支点，从它出发同时到达两条链，维度会退化成"全部 SHARED"（实测 168/168）。
 *    代价：只被 {@code CellGenerator} 自己读的键会落进"两条链都不读"。<br>
 * ② 文件级 BFS 仍是过近似：{@code TerrainParams}/{@code SplineConfig} 等载体的
 *    方法体会让"仅透传"也呈现为可达 ⇒ 该维度<b>偏保守</b>（倾向于多报 SHARED）。</p>
 *
 * <h2>白名单</h2>
 * <p>{@code tools/config-key-guard-allowlist.txt}，每行 {@code key<TAB>理由}（{@code #} 开头为注释）。
 * 用于"键确实不驱动产出、但删除会牵连其它源集"的已知情况。<b>理由必须带证据行号</b>
 * （形如 {@code ErosionEngine.java:325}），探针会校验：文件存在、行号在范围内、
 * 且该行<b>去掉注释后仍有内容</b>（即证据指向真实代码而不是一段解释性注释）。
 * 历史事故正是"理由指向注释"：{@code erosionLocalChargeWeight} 的白名单理由引用了
 * {@code ErosionEngine.java:325}，而那一行是注释「引擎不再读取」，等于用"说明它死了"
 * 的注释证明它活着。</p>
 *
 * <p>白名单本身也被守卫：条目若已变成 LIVE，或键已从配置里消失，都会 FAIL ——
 * 防止白名单腐烂成永久豁免。</p>
 *
 * <p><b>零 MC 依赖</b>：本探针只用 JDK，不加载 Forge，也不读 TOML（探针进程没有 Forge 环境，
 * {@code GeoGenesisConfig.INSTANCE} 为 null）。全部判据来自源码文本。</p>
 *
 * <p>用法：{@code gradlew runConfigKeyGuardProbe}
 * （可选参数：仓库根，默认取工作目录；{@code --verbose} 打印全部明细；
 * {@code --no-reachability} 跳过 BFS）。任一违规 → {@code System.exit(1)}。</p>
 */
public final class ConfigKeyGuardProbe {

    private static final String CONFIG_REL = "src/main/java/com/geogenesis/config/GeoGenesisConfig.java";
    private static final String MAIN_REL = "src/main/java";
    /** {@code MAIN_REL} 之下的相对路径前缀 —— 注意 base 是 src/main/java 而不是仓库根。 */
    private static final String CLIENT_PREFIX = "com/geogenesis/client/";
    private static final String ALLOWLIST_REL = "tools/config-key-guard-allowlist.txt";

    /**
     * 数据载体文件：把配置值搬进 runtime record / 并<b>在 record 头部声明组件</b>。
     * 这些文件里的<b>头部声明</b>不算消费（见 {@link #stripRecordHeader}），
     * 但文件其余部分（{@code build*Inner()} / {@code oceanLocations()} /
     * {@code maxLandHi()} 等）<b>仍然算</b>生产消费 —— 它们真的把值喂给算法。
     */
    private static final Set<String> CARRIER_FILES = Set.of(
            "com/geogenesis/worldgen/terrain/TerrainParams.java",
            "com/geogenesis/worldgen/terrain/SplineConfig.java",
            "com/geogenesis/worldgen/terrain/OceanSplineConfig.java",
            "com/geogenesis/worldgen/terrain/MidSplineConfig.java");

    /**
     * 键名 → 载体组件名的别名表。
     *
     * <p>为什么需要：本探针<b>按名字</b>匹配消费点。配置字段 {@code splineOuterLoc0}
     * 被 {@code buildSplineConfig()} 传进 {@link com.geogenesis.worldgen.terrain.SplineConfig}
     * 的组件 {@code outerLoc0}（名字不同），再由 {@code outerLocations()} 消费。
     * 不做映射会把这类键误判成 CONFIG_ONLY。</p>
     *
     * <p>⚠ 新增别名必须能指出"组件被谁消费"，否则等于放宽判据。当前唯一一项：
     * {@code SplineConfig.outerLocations()} 被 {@code build()} 调用。</p>
     */
    private static final Map<String, String> KEY_ALIASES = Map.of(
            "splineOuterLoc0", "outerLoc0",
            "splineOuterLoc1", "outerLoc1",
            "splineOuterLoc2", "outerLoc2",
            "splineOuterLoc3", "outerLoc3",
            "splineOuterLoc4", "outerLoc4",
            "splineOuterLoc5", "outerLoc5",
            "splineOuterLoc6", "outerLoc6");

    /** 可达性 BFS 的根：真正的生产入口（MC 会调它们）。 */
    private static final List<String> PRODUCTION_ROOTS = List.of(
            "com/geogenesis/GeoGenesisMod.java",
            "com/geogenesis/GeoGenesisServerEvents.java",
            "com/geogenesis/worldgen/generator/GeoGenesisGenerator.java",
            "com/geogenesis/worldgen/generator/GeoGenesisBiomeSource.java",
            "com/geogenesis/worldgen/terrain/GeoGenesisTerrain.java");

    /**
     * 链路口径的根：<b>链专属</b>的入口（2026-11-07 新增，仅用于报告，不判 FAIL）。
     *
     * <p>{@code CellGenerator} <b>刻意不入两组</b>：它是全仓<b>唯一</b>按 {@code morphBase}
     * 分支的文件，从它出发同时到达两条链 ⇒ 任何键都会被判成"两条链都读"，
     * 链路维度会退化成没有信息量（实测：把它当根后 168/168 全 SHARED）。</p>
     */
    private static final List<String> MORPH_CHAIN_ROOTS = List.of(
            "com/geogenesis/worldgen/core/BaseWorldSampler.java",
            "com/geogenesis/worldgen/terrain/morph/MorphTerrainSampler.java",
            "com/geogenesis/worldgen/process/ProcessContext.java",
            "com/geogenesis/worldgen/process/ProcessRuntime.java");

    /** 声明：支持泛型（{@code EnumValue<CaveConfig.Preset> cavePreset} 此前被漏算 1 个）。 */
    private static final Pattern DECL = Pattern.compile(
            "public final ForgeConfigSpec\\.\\w+(?:\\s*<[^;{]*?>)?\\s+(\\w+)\\s*;");
    private static final Pattern ASSIGN = Pattern.compile("^\\s*(\\w+)\\s*=\\s*builder\\b");
    /** 必须覆盖 define / defineInRange / defineInList / <b>defineEnum</b>（后者此前被漏，导致 cavePreset 被误报）。 */
    private static final Pattern DEFINE = Pattern.compile("\\.define(?:InRange|InList|Enum)?\\(\\s*\"([^\"]+)\"");
    /** 白名单证据行号：{@code Foo.java:123} / {@code pkg/Foo.java:123}。 */
    private static final Pattern EVIDENCE = Pattern.compile("([\\w./\\\\-]+\\.java):(\\d+)");
    private static final Pattern JAVA_WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private ConfigKeyGuardProbe() {}

    public static void main(String[] args) throws Exception {
        boolean verbose = false;
        boolean reachability = true;
        String rootArg = null;
        for (String a : args) {
            if ("--verbose".equals(a) || "-v".equals(a)) {
                verbose = true;
            } else if ("--no-reachability".equals(a)) {
                reachability = false;
            } else if (!a.startsWith("-")) {
                rootArg = a;
            }
        }
        Path root = Paths.get(rootArg != null ? rootArg : ".").toAbsolutePath().normalize();
        Path configFile = root.resolve(CONFIG_REL);
        if (!Files.isRegularFile(configFile)) {
            fail("找不到配置源文件：" + configFile
                    + "（请在 forge-1.20.1-47.4.10-mdk 下运行，或把仓库根作为参数传入）");
            return;
        }

        String configSrc = stripComments(Files.readString(configFile, StandardCharsets.UTF_8));

        Set<String> declared = new LinkedHashSet<>();
        Matcher dm = DECL.matcher(configSrc);
        while (dm.find()) {
            declared.add(dm.group(1));
        }
        if (declared.isEmpty()) {
            fail("没有解析到任何 ForgeConfigSpec 键声明 —— 解析规则可能已与源码写法脱节");
            return;
        }

        List<String> problems = new ArrayList<>();
        Map<String, String> keyOf = parseKeyOf(configSrc);
        Set<String> keys = new TreeSet<>();
        for (String field : declared) {
            String key = keyOf.get(field);
            if (key == null) {
                problems.add("键已声明但从未 define：" + field + "（字段恒为 null，任何读取都会 NPE）");
                continue;
            }
            if (!key.equals(field)) {
                problems.add("字段名与键名不一致：" + field + " -> \"" + key + "\"（本探针按键名匹配消费点）");
            }
            keys.add(key);
        }

        Hits hits = scan(root, configFile, keys);
        Map<String, String> allow = readAllowlist(root.resolve(ALLOWLIST_REL), root, problems);
        Set<String> reachable = reachability ? reachableFiles(root) : null;
        Set<String> morphChain = reachability ? reachableFiles(root, MORPH_CHAIN_ROOTS) : null;

        String cfgResidual = redactFingerprint(configSrc);
        Map<String, Boolean> readInsideConfig = readInsideConfig(cfgResidual, keys);

        Tally t = classify(keys, hits, readInsideConfig, allow, reachable, problems, morphChain);

        for (String k : allow.keySet()) {
            if (!keys.contains(k)) {
                problems.add("白名单条目指向不存在的键：" + k + "（键已删除，条目必须同批删除）");
            }
        }

        printReport(root, configFile, keys, hits, t, allow, problems, reachable != null, verbose);

        if (!t.violations.isEmpty() || !problems.isEmpty()) {
            System.out.println("CONFIG KEY GUARD FAIL");
            System.exit(1);
            return;
        }
        System.out.println("CONFIG KEY GUARD PASS");
        System.exit(0);
    }

    // ------------------------------------------------------------------ 解析

    /** 字段名 → 键名（按 {@code 字段 = builder...define("key")} 的相邻关系推断）。 */
    private static Map<String, String> parseKeyOf(String configSrc) {
        Map<String, String> keyOf = new LinkedHashMap<>();
        String pending = null;
        for (String line : configSrc.split("\n", -1)) {
            Matcher am = ASSIGN.matcher(line);
            if (am.find()) {
                pending = am.group(1);
            }
            if (pending != null) {
                Matcher fm = DEFINE.matcher(line);
                if (fm.find()) {
                    keyOf.put(pending, fm.group(1));
                    pending = null;
                }
            }
        }
        return keyOf;
    }

    /**
     * 抹掉"声明 / 赋值 / .define 字符串 / 指纹方法体"这些非消费出现，剩下的才算一次配置类内部读。
     */
    private static Map<String, Boolean> readInsideConfig(String cfgResidual, Set<String> keys) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : keys) {
            String residual = cfgResidual
                    .replaceAll("public final ForgeConfigSpec\\.\\w+(?:\\s*<[^;{]*?>)?\\s+" + k + "\\s*;", " ")
                    .replaceAll("\\b" + k + "\\s*=\\s*builder", " ")
                    .replaceAll("\\.define(?:InRange|InList|Enum)?\\(\\s*\"" + k + "\"", " ");
            out.put(k, containsWord(residual, k));
        }
        return out;
    }

    /**
     * 命中记录：生产文件 / 客户端文件 / 载体文件 三组相对路径。
     *
     * <p>载体文件的处理是<b>行内</b>的：record 头部声明被剥掉后再扫，剩下的出现
     * （{@code oceanLocations()} / {@code build*Inner()} / {@code maxLandHi()} 之类
     * 真的把值喂给算法的方法体）<b>算生产消费</b>；只有声明处残留才归入载体组。</p>
     */
    private record Hits(Map<String, List<String>> production,
                        Map<String, List<String>> client,
                        Map<String, List<String>> carrier) {}

    private static Hits scan(Path root, Path self, Set<String> keys) throws IOException {
        Map<String, List<String>> production = new TreeMap<>();
        Map<String, List<String>> client = new TreeMap<>();
        Map<String, List<String>> carrier = new TreeMap<>();
        for (String k : keys) {
            production.put(k, new ArrayList<>());
            client.put(k, new ArrayList<>());
            carrier.put(k, new ArrayList<>());
        }
        Path mainRoot = root.resolve(MAIN_REL);
        try (Stream<Path> walk = Files.walk(mainRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toAbsolutePath().normalize().equals(self))
                    .forEach(p -> {
                        String text;
                        try {
                            text = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        String rel = mainRoot.relativize(p).toString().replace('\\', '/');
                        boolean inClient = rel.startsWith(CLIENT_PREFIX);
                        boolean inCarrier = CARRIER_FILES.contains(rel);
                        // 载体文件：先剥掉 record 头部（=纯声明），只留方法体。
                        String consumed = inCarrier ? stripRecordHeader(text) : text;
                        for (String k : keys) {
                            boolean hit = containsWord(consumed, k);
                            boolean declaredOnly = !hit && inCarrier && containsWord(text, k);
                            if (!hit && !declaredOnly) {
                                // 别名：配置字段名与载体组件名不同名时按别名再查一次。
                                String alias = KEY_ALIASES.get(k);
                                hit = alias != null && containsWord(consumed, alias);
                                declaredOnly = !hit && inCarrier && alias != null && containsWord(text, alias);
                                if (!hit && !declaredOnly) {
                                    continue;
                                }
                            }
                            if (inClient) {
                                client.get(k).add(rel);
                            } else if (declaredOnly) {
                                carrier.get(k).add(rel);
                            } else {
                                production.get(k).add(rel);
                            }
                        }
                    });
        }
        return new Hits(production, client, carrier);
    }

    /**
     * 剥掉 record 的<b>头部组件声明</b>（{@code public record X(...) {} } 里 {@code (...)} 那一段）。
     *
     * <p>为什么：载体 record 的头部就是"字段声明"的另一种写法 —— 它不消费任何值。
     * 真正消费的是方法体（{@code oceanLocations()} 返回数组、{@code buildOceanInner()}
     * 组装样条），那些出现必须保留，否则会把真 LIVE 的键误判成死键。</p>
     *
     * <p>找不到 record 头（不是 record 或写法特殊）时原样返回：保守（宁可多算 LIVE）。</p>
     */
    static String stripRecordHeader(String src) {
        int rec = src.indexOf("record ");
        if (rec < 0) {
            return src;
        }
        int open = src.indexOf('(', rec);
        if (open < 0) {
            return src;
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return src.substring(0, open) + "()" + src.substring(i + 1);
                }
            }
        }
        return src;
    }

    // ------------------------------------------------------------------ 可达性

    /**
     * 声明枢纽：{@code GeoGenesisConfig} 在自己的字段声明与 javadoc 里几乎点名了所有生产类。
     *
     * <p><b>为什么必须跳过它</b>（2026-11-07 实测踩到的坑，留证）：若允许 BFS <b>穿过</b>它，
     * 任何根都会"到达一切" —— 实测路径
     * {@code ProcessContext → GeoGenesisConfig --(CellGenerator)--> CellGenerator → TypeLandShape}，
     * 于是所有 legacy 键都被判成"morph 也读"，链路分类全部退化成 SHARED
     * （168/168，等于没分类）。它是声明处，不是调用边。</p>
     */
    private static final String CONFIG_HUB = "com/geogenesis/config/GeoGenesisConfig.java";

    /**
     * 从生产入口做文件级 BFS。边 = A 的<b>去注释后</b>标识符集合里出现 B 的简单类名。
     *
     * <p>两个必须的去噪（都是实测踩出来的，不是预防性设计）：</p>
     * <ol>
     *   <li><b>去注释</b>：{@code MorphRegionPartition} 的 javadoc 里有一句
     *       「正是本项目旧 {@code TypeLandShape} 的老问题」，纯散文造出一条假边，
     *       把 legacy 类判成 morph 可达。</li>
     *   <li><b>跳过 {@link #CONFIG_HUB}</b>：见该常量的说明。</li>
     * </ol>
     * <p>除这两点外仍是过近似 ⇒ {@code UNREACHABLE} 可信。</p>
     */
    static Set<String> reachableFiles(Path root) throws IOException {
        return reachableFiles(root, PRODUCTION_ROOTS);
    }

    static Set<String> reachableFiles(Path root, List<String> roots) throws IOException {
        Path mainRoot = root.resolve(MAIN_REL);
        Map<String, Set<String>> wordsByFile = new HashMap<>();
        Map<String, List<String>> filesByType = new HashMap<>();
        try (Stream<Path> walk = Files.walk(mainRoot)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = mainRoot.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(CLIENT_PREFIX)) {
                    continue;
                }
                String text = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Set<String> words = new HashSet<>();
                Matcher m = JAVA_WORD.matcher(text);
                while (m.find()) {
                    words.add(m.group());
                }
                wordsByFile.put(rel, words);
                String simple = p.getFileName().toString();
                filesByType.computeIfAbsent(simple.substring(0, simple.length() - ".java".length()),
                        x -> new ArrayList<>()).add(rel);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        for (String r : roots) {
            if (wordsByFile.containsKey(r) && seen.add(r)) {
                queue.add(r);
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            String cur = queue.get(i);
            if (CONFIG_HUB.equals(cur)) {
                continue;
            }
            Set<String> words = wordsByFile.get(cur);
            if (words == null) {
                continue;
            }
            for (String w : words) {
                List<String> targets = filesByType.get(w);
                if (targets == null) {
                    continue;
                }
                for (String tgt : targets) {
                    if (seen.add(tgt)) {
                        queue.add(tgt);
                    }
                }
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ 分类

    private static final class Tally {
        int live;
        int clientOnly;
        int carrierOnly;
        int configOnly;
        int unread;
        int unreachable;
        /** 链路口径（仅报告）：LIVE 里读取点是否落在 morph 生产链上。 */
        int liveMorphOnly;
        int liveNoChain;
        final List<String> violations = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, List<String>> unreachableKeys = new TreeMap<>();
        final Map<String, List<String>> morphOnlyKeys = new TreeMap<>();
        final Map<String, List<String>> noChainKeys = new TreeMap<>();
    }

    private static Tally classify(Set<String> keys, Hits hits, Map<String, Boolean> readInsideConfig,
                                  Map<String, String> allow, Set<String> reachable, List<String> problems,
                                  Set<String> morphChain) {
        Tally t = new Tally();
        for (String k : keys) {
            List<String> prod = hits.production().get(k);
            boolean isLive = !prod.isEmpty();
            boolean isClientOnly = !isLive && !hits.client().get(k).isEmpty();
            boolean isCarrierOnly = !isLive && !isClientOnly && !hits.carrier().get(k).isEmpty();
            boolean isConfigOnly = !isLive && !isClientOnly && !isCarrierOnly && readInsideConfig.get(k);
            boolean waived = allow.containsKey(k);
            if (isLive) {
                t.live++;
                if (waived) {
                    problems.add("白名单条目已失效（该键现在被生产消费）：" + k + "  ->  "
                            + prod.get(0) + "；请从白名单删除");
                }
                if (reachable != null && prod.stream().noneMatch(reachable::contains)) {
                    t.unreachable++;
                    t.unreachableKeys.put(k, prod);
                    t.warnings.add("UNREACHABLE  " + k + "：被 " + String.join(", ", prod)
                            + " 读取，但这些文件从生产入口不可达（调用图 BFS）");
                }
                if (morphChain != null) {
                    // ★ 2026-11-07：legacy 链已整体删除，故只剩"morph 内 / morph 外"两档。
                    boolean inMorph = prod.stream().anyMatch(morphChain::contains);
                    if (inMorph) {
                        t.liveMorphOnly++;
                        t.morphOnlyKeys.put(k, prod);
                    } else {
                        t.liveNoChain++;
                        t.noChainKeys.put(k, prod);
                    }
                }
            } else if (isClientOnly) {
                t.clientOnly++;
                if (!waived) {
                    t.violations.add("CLIENT_ONLY  " + k + "：只在 " + String.join(", ", hits.client().get(k))
                            + " 里被读 —— 生产路径不读它，即「开关无效果」");
                }
            } else if (isCarrierOnly) {
                t.carrierOnly++;
                if (!waived) {
                    t.violations.add("CARRIER_ONLY " + k + "：只在数据载体 "
                            + String.join(", ", hits.carrier().get(k))
                            + " 里出现（组件声明 / 透传）—— 没有任何算法读取它，即死键");
                }
            } else if (isConfigOnly) {
                t.configOnly++;
                t.warnings.add("CONFIG_ONLY  " + k + "：只在 GeoGenesisConfig 内部被读（疑似惰性构建器），"
                        + "请人工确认该构建器是否接到生产；确认无关就加进白名单");
            } else {
                t.unread++;
                if (!waived) {
                    t.violations.add("UNREAD       " + k + "：整个 src/main 都没有读取（键是死的）");
                }
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ 输出

    private static void printReport(Path root, Path configFile, Set<String> keys, Hits hits, Tally t,
                                    Map<String, String> allow, List<String> problems,
                                    boolean reachabilityRan, boolean verbose) {
        System.out.println("=== CONFIG KEY GUARD ===");
        System.out.println("  配置源              : " + root.relativize(configFile).toString().replace('\\', '/'));
        System.out.println("  键总数              : " + keys.size());
        System.out.println("  LIVE（生产消费）    : " + t.live);
        System.out.println("  CLIENT_ONLY         : " + t.clientOnly);
        System.out.println("  CARRIER_ONLY        : " + t.carrierOnly + "（只被数据载体透传 = 死键）");
        System.out.println("  CONFIG_ONLY（警告）  : " + t.configOnly);
        System.out.println("  UNREAD              : " + t.unread);
        System.out.println("  UNREACHABLE（警告）  : " + (reachabilityRan ? String.valueOf(t.unreachable) : "未测"));
        System.out.println("  载体白名单          : " + CARRIER_FILES.size() + " 个文件 -> "
                + String.join(", ", new TreeSet<>(CARRIER_FILES).stream()
                        .map(s -> s.substring(s.lastIndexOf('/') + 1)).toList()));
        System.out.println("  白名单              : " + allow.size()
                + (allow.isEmpty() ? "" : " -> " + String.join(", ", allow.keySet())));
        if (reachabilityRan) {
            System.out.println();
            System.out.println("  链路口径（LIVE 的细分；仅报告，不判 FAIL）:");
            System.out.println("    只被 morph 链读    : " + t.liveMorphOnly);
            System.out.println("    两条链都不读       : " + t.liveNoChain + "（通常是嵌在生成器/门面层的开关）");
        }
        System.out.println();

        if (!t.violations.isEmpty()) {
            System.out.println("FAIL: " + t.violations.size() + " 个键没有驱动生产代码");
            for (String v : t.violations) {
                System.out.println("  - " + v);
            }
            System.out.println();
        }
        if (!t.warnings.isEmpty()) {
            System.out.println("WARN: " + t.warnings.size()
                    + " 个键需人工确认（UNREACHABLE 为何仍不判 FAIL，见类 javadoc「为什么现在仍不能判 FAIL」）");
            int shown = 0;
            for (String w : t.warnings) {
                if (!verbose && shown >= 3) {
                    continue;
                }
                System.out.println("  - " + w);
                shown++;
            }
            if (!verbose && t.warnings.size() > 3) {
                System.out.println("  … 其余 " + (t.warnings.size() - 3) + " 个略（加 --verbose 看全部）");
            }
            System.out.println();
        }
        if (!problems.isEmpty()) {
            System.out.println("FAIL: " + problems.size() + " 个结构/白名单问题");
            for (String p : problems) {
                System.out.println("  - " + p);
            }
            System.out.println();
        }
        if (verbose) {
            System.out.println("--- 明细（production 命中）---");
            for (String k : keys) {
                List<String> prod = hits.production().get(k);
                System.out.println("  " + k + " -> " + (prod.isEmpty() ? "(none)" : String.join(", ", prod)));
            }
            System.out.println();
            if (!t.noChainKeys.isEmpty()) {
                System.out.println("--- 两条链都不读（嵌在生成器/门面/诊断层）---");
                for (Map.Entry<String, List<String>> e : t.noChainKeys.entrySet()) {
                    System.out.println("  " + e.getKey() + " -> " + String.join(", ", e.getValue()));
                }
                System.out.println();
            }
        }
    }

    // ------------------------------------------------------------------ 白名单

    /**
     * 读白名单并校验证据行号：文件必须存在、行号必须在范围内、
     * 且该行<b>去掉注释后仍有内容</b>（证明证据指向代码而非注释）。
     */
    private static Map<String, String> readAllowlist(Path file, Path root, List<String> problems) throws IOException {
        Map<String, String> allow = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return allow;
        }
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\t|\\s{2,}", 2);
            String key = parts[0].strip();
            String reason = parts.length > 1 ? parts[1].strip() : "";
            if (key.isEmpty()) {
                continue;
            }
            if (reason.isEmpty()) {
                problems.add("白名单条目缺少理由：" + key + "（每个豁免都必须写清为什么不算死键）");
            } else {
                String ev = validateEvidence(reason, root);
                if (ev != null) {
                    problems.add("白名单条目证据无效：" + key + " -> " + ev);
                }
            }
            allow.put(key, reason);
        }
        return allow;
    }

    /** @return null = 证据有效；否则返回问题描述。 */
    private static String validateEvidence(String reason, Path root) {
        Matcher m = EVIDENCE.matcher(reason);
        if (!m.find()) {
            return "理由里没有 File.java:NN 形式的证据行号";
        }
        String rel = m.group(1).replace('\\', '/');
        int lineNo = Integer.parseInt(m.group(2));
        Path candidate = locate(root, rel);
        if (candidate == null) {
            return "证据文件不存在：" + rel;
        }
        String text;
        try {
            text = Files.readString(candidate, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "证据文件不可读：" + rel;
        }
        String[] lines = text.split("\n", -1);
        if (lineNo < 1 || lineNo > lines.length) {
            return "证据行号越界：" + rel + ":" + lineNo + "（文件共 " + lines.length + " 行）";
        }
        String stripped = stripComments(lines[lineNo - 1]).strip();
        if (stripped.isEmpty()) {
            return "证据行是注释或空行（" + rel + ":" + lineNo + "）—— 必须指向代码读取";
        }
        return null;
    }

    private static Path locate(Path root, String rel) {
        for (String base : List.of("src/main/java/", "src/diagnostics/java/", "src/acceptance/java/", "")) {
            Path p = root.resolve(base + rel);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String simple = rel.substring(rel.lastIndexOf('/') + 1);
        try (Stream<Path> walk = Files.walk(root.resolve(MAIN_REL))) {
            return walk.filter(p -> p.getFileName().toString().equals(simple)).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 抹掉 {@code configFingerprint()} 方法体：它为了做缓存身份会读几乎所有键，
     * 但读它不代表该键驱动任何产出（恰恰相反，指纹读死键是常见现象）。
     * 用大括号配对定位方法体，找不到就原样返回（保守：宁可多报也不漏报）。
     */
    static String redactFingerprint(String src) {
        int marker = src.indexOf("configFingerprint()");
        if (marker < 0) {
            return src;
        }
        int open = src.indexOf('{', marker);
        if (open < 0) {
            return src;
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(0, marker) + " " + src.substring(i + 1);
                }
            }
        }
        return src;
    }

    /**
     * 供同包其它探针复用的"整词包含"判定（{@link ConfigPanelCoverageProbe} 用它扫面板源码）。
     *
     * <p>为什么暴露成包内可见而不是各自复制：判定口径（Java 标识符边界）必须只有一份，
     * 否则两个探针会对同一段源码给出不同结论。</p>
     */
    static boolean containsWordForTest(String text, String word) {
        return containsWord(text, word);
    }

    private static boolean containsWord(String text, String word) {        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) {
                return false;
            }
            boolean leftOk = i == 0 || !isJavaWordChar(text.charAt(i - 1));
            int end = i + word.length();
            boolean rightOk = end >= text.length() || !isJavaWordChar(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isJavaWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * 去掉 // 与 块注释，字符串与字符字面量原样保留（键名不会出现在注释里被误判）。
     * 只做词法层面的粗剥，不追求完整 Java 语法。
     */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == '*') {
                    int j = s.indexOf("*/", i + 2);
                    if (j < 0) {
                        break;
                    }
                    i = j + 2;
                    out.append(' ');
                    continue;
                }
                if (d == '/') {
                    int j = s.indexOf('\n', i);
                    if (j < 0) {
                        break;
                    }
                    i = j;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char e = s.charAt(i);
                    out.append(e);
                    i++;
                    if (e == '\\' && i < n) {
                        out.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (e == c) {
                        break;
                    }
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void fail(String message) {
        System.out.println("=== CONFIG KEY GUARD ===");
        System.out.println("FAIL: " + message);
        System.out.println("CONFIG KEY GUARD FAIL");
        System.exit(1);
    }
}
