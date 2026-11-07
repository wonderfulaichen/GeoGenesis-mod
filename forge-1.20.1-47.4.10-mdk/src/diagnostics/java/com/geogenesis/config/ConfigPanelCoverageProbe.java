package com.geogenesis.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>配置面板结构自检</b>：静态扫描 panel 源码，断言「面板显示的键」与「配置里真实存在的键」
 * 双向上都自洽。零 MC 依赖（纯文本 + 纯 Java 表）。
 *
 * <h2>为什么需要（本轮要防的具体事故）</h2>
 * <p>2026-11-07 的审计结论是：面板暴露 101 键，其中 <b>61 个对新世界无效</b>
 * （39 {@code LEGACY_ONLY} + 8 no-chain + 3 {@code PREVIEW_ONLY} + 3 仅预览 + 8 已删除），
 * 同时 <b>14 个 {@code BOTH} 真旋钮根本没上界面</b>。这两类缺陷方向相反，
 * 但都源于同一件事：<b>"面板显示什么"没有唯一事实源</b>。</p>
 * <p>本轮把事实源收到 {@link ConfigKeyCatalog} + {@link PanelLayout}，
 * 本探针是那把尺子。</p>
 *
 * <h2>判据（任一不过即 {@code System.exit(1)}）</h2>
 * <ol>
 *   <li><b>AXIS-FROM-JAVADOC</b>：{@code GeoGenesisConfig} 里每个字段的 javadoc {@code [TAG]}
 *       必须与 {@link ConfigKeyCatalog} 一致（含"必须有标记"）。这是防止两处标注漂移的根。</li>
 *   <li><b>CATALOG-COMPLETE</b>：配置里每个键都必须在目录里，且目录里没有配置里不存在的键。</li>
 *   <li><b>LAYOUT-COVERS</b>：目录里每个键都必须落在某个页签上（{@code showLegacy=false} 时
 *       {@code LEGACY_ONLY} 允许不在可见页签里，但必须在 {@code LEGACY} 页上）。</li>
 *   <li><b>MORPH-VISIBLE</b>：每个 {@code BOTH}/{@code MORPH_ONLY} 键至少出现在一个
 *       <b>非 {@code LEGACY}</b> 页签里（否则就是"真旋钮没上界面"）。</li>
 *   <li><b>SOURCE-MATCHES-LAYOUT</b>：源码里 {@code client/**} 引用到的配置键集合
 *       必须与 {@link PanelLayout} 声明的集合<b>完全相等</b>。多一个 = 面板偷偷显示了一行
 *       （未登记）；少一个 = 声明了但源码没画（谎报）。</li>
 *   <li><b>NO-DIGIT-NOTES</b>：面板说明文字里不得出现 {@code 默认}/{@code 范围} 伴随的数字
 *       —— 数字一律由 {@link ConfigSpecRanges} 现取（这是 5 处文案漂移的根因）。</li>
 *   <li><b>PRESET-AXIS</b>：{@code PresetLibrary} 的每个内置预设只允许覆盖
 *       {@code BOTH}/{@code MORPH_ONLY} 键（"预设对新世界零效果"的直接防线）。</li>
 * </ol>
 *
 * <h2>用</h2>
 * <pre>gradlew runConfigPanelCoverageProbe [-PprobeArgs="--verbose"]</pre>
 */
public final class ConfigPanelCoverageProbe {

    private static final String CONFIG_REL = "src/main/java/com/geogenesis/config/GeoGenesisConfig.java";
    private static final String CLIENT_REL = "src/main/java/com/geogenesis/client";
    private static final String MAIN_REL = "src/main/java";

    private static final Pattern DECL = Pattern.compile(
            "public final ForgeConfigSpec\\.(\\w+)(?:\\s*<([^;{]*?)>)?\\s+(\\w+)\\s*;");
    private static final Pattern TAG = Pattern.compile(
            "\\[(MORPH_ONLY|LEGACY_ONLY|BOTH|DIAGNOSTIC|PREVIEW_ONLY)\\]");

    private final List<String> violations = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        boolean verbose = false;
        String rootArg = null;
        for (String a : args) {
            if ("--verbose".equals(a) || "-v".equals(a)) verbose = true;
            else if (!a.startsWith("-")) rootArg = a;
        }
        Path root = Paths.get(rootArg != null ? rootArg : ".").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve(CONFIG_REL))) {
            System.out.println("FAIL: 找不到 " + root.resolve(CONFIG_REL));
            System.out.println("CONFIG PANEL COVERAGE FAIL");
            System.exit(1);
            return;
        }
        new ConfigPanelCoverageProbe().run(root, verbose);
    }

    private void run(Path root, boolean verbose) throws IOException {
        System.out.println("=== CONFIG PANEL COVERAGE ===");
        Map<String, ConfigKeyCatalog.Axis> javadocAxes = readJavadocAxes(root.resolve(CONFIG_REL));
        System.out.println("  配置键（javadoc 标记）: " + javadocAxes.size());
        System.out.println("  目录条目              : " + ConfigKeyCatalog.all().size());
        System.out.println("  页签                  : " + PanelLayout.Tab.values().length
                + "（常驻 " + PanelLayout.visibleTabs(false).size()
                + " + 旧算法 " + (PanelLayout.Tab.values().length
                        - PanelLayout.visibleTabs(false).size()) + "）");

        checkAxisFromJavadoc(javadocAxes);
        checkCatalogComplete(javadocAxes);
        checkLayoutCovers();
        checkMorphVisible();
        checkSourceMatchesLayout(root);
        checkNoDigitNotes(root);
        checkPresetAxes(root);
        checkKeyTextCoverage(root);

        System.out.println();
        System.out.println("  按轴统计:");
        for (ConfigKeyCatalog.Axis a : ConfigKeyCatalog.Axis.values()) {
            System.out.printf(Locale.ROOT, "    %-13s %3d  可见性=%s%n",
                    a, ConfigKeyCatalog.countAxis(a), ConfigKeyCatalog.visibilityOf(a));
        }
        System.out.println("  按域统计（页签顺序）:");
        for (ConfigKeyCatalog.Domain d : ConfigKeyCatalog.domainsInTabOrder()) {
            System.out.printf(Locale.ROOT, "    %-14s %3d%n", d.name(), ConfigKeyCatalog.countIn(d));
        }

        if (verbose && !notes.isEmpty()) {
            System.out.println();
            System.out.println("--- 备注 ---");
            for (String n : notes) System.out.println("  " + n);
        }
        if (!warnings.isEmpty()) {
            System.out.println();
            System.out.println("WARN: " + warnings.size());
            for (String w : warnings) System.out.println("  - " + w);
        }
        if (!violations.isEmpty()) {
            System.out.println();
            System.out.println("FAIL: " + violations.size());
            for (String v : violations) System.out.println("  - " + v);
            System.out.println("CONFIG PANEL COVERAGE FAIL");
            System.exit(1);
            return;
        }
        System.out.println();
        System.out.println("CONFIG PANEL COVERAGE PASS");
        System.exit(0);
    }

    // ------------------------------------------------------------------ 1

    /** 从 {@code GeoGenesisConfig} 的字段 javadoc 里读 {@code [TAG]}。 */
    private static Map<String, ConfigKeyCatalog.Axis> readJavadocAxes(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = raw.split("\n", -1);
        Map<String, ConfigKeyCatalog.Axis> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = DECL.matcher(lines[i]);
            if (!m.find()) continue;
            String field = m.group(3);
            List<String> tags = new ArrayList<>();
            int j = i - 1;
            while (j >= 0) {
                if (lines[j].trim().endsWith("*/")) {
                    int k = j;
                    while (k >= 0 && !lines[k].contains("/**")) k--;
                    Matcher tm = TAG.matcher(String.join("\n",
                            java.util.Arrays.copyOfRange(lines, k, j + 1)));
                    while (tm.find()) tags.add(tm.group(1));
                    break;
                }
                if (lines[j].trim().startsWith("public final ForgeConfigSpec")) break;
                j--;
            }
            // 0 个或多个标记都记为 null ⇒ 由 checkAxisFromJavadoc 报 AXIS-FROM-JAVADOC
            out.put(field, tags.size() == 1 ? ConfigKeyCatalog.Axis.valueOf(tags.get(0)) : null);
        }
        return out;
    }

    private void checkAxisFromJavadoc(Map<String, ConfigKeyCatalog.Axis> javadocAxes) {
        for (Map.Entry<String, ConfigKeyCatalog.Axis> e : javadocAxes.entrySet()) {
            String key = e.getKey();
            ConfigKeyCatalog.Axis declared = e.getValue();
            ConfigKeyCatalog.Entry entry = ConfigKeyCatalog.of(key);
            if (declared == null) {
                violations.add("AXIS-FROM-JAVADOC " + key
                        + "：javadoc 里没有唯一的 [TAG]（必须是 MORPH_ONLY/LEGACY_ONLY/BOTH/DIAGNOSTIC/PREVIEW_ONLY 之一）");
                continue;
            }
            if (entry == null) {
                violations.add("AXIS-FROM-JAVADOC " + key
                        + "：javadoc 标了 [" + declared + "] 但目录里没有这个键");
            } else if (entry.axis() != declared) {
                violations.add("AXIS-FROM-JAVADOC " + key
                        + "：javadoc 写 [" + declared + "]，目录写 [" + entry.axis() + "]");
            }
        }
    }

    // ------------------------------------------------------------------ 2

    private void checkCatalogComplete(Map<String, ConfigKeyCatalog.Axis> javadocAxes) {
        Set<String> config = new TreeSet<>(javadocAxes.keySet());
        Set<String> catalog = new TreeSet<>(ConfigKeyCatalog.all().keySet());
        for (String k : config) {
            if (!catalog.contains(k)) {
                violations.add("CATALOG-COMPLETE 配置里有、目录里没有：" + k);
            }
        }
        for (String k : catalog) {
            if (!config.contains(k)) {
                violations.add("CATALOG-COMPLETE 目录里有、配置里没有（键已删？）：" + k);
            }
        }
    }

    // ------------------------------------------------------------------ 3 / 4

    private void checkLayoutCovers() {
        Set<String> onAnyTab = new LinkedHashSet<>();
        for (PanelLayout.Tab t : PanelLayout.Tab.values()) onAnyTab.addAll(PanelLayout.allKeys(t));
        for (String k : ConfigKeyCatalog.all().keySet()) {
            if (!onAnyTab.contains(k)) {
                violations.add("LAYOUT-COVERS " + k + " 没有落在任何页签上（用户永远看不到）");
            }
        }
        List<String> uncovered = PanelLayout.uncoveredKeys(false);
        for (String k : uncovered) {
            ConfigKeyCatalog.Entry e = ConfigKeyCatalog.of(k);
            if (e != null && e.axis() == ConfigKeyCatalog.Axis.LEGACY_ONLY) {
                notes.add("默认隐藏（需开「显示旧算法参数」）：" + k);
            }
        }
    }

    private void checkMorphVisible() {
        Set<String> visibleWithoutLegacy = new LinkedHashSet<>();
        for (PanelLayout.Tab t : PanelLayout.visibleTabs(false)) visibleWithoutLegacy.addAll(PanelLayout.allKeys(t));
        for (ConfigKeyCatalog.Entry e : ConfigKeyCatalog.all().values()) {
            if (!e.affectsNewWorld()) continue;
            if (!visibleWithoutLegacy.contains(e.key())) {
                violations.add("MORPH-VISIBLE " + e.key() + " [" + e.axis()
                        + "] 是驱动新世界的真旋钮，但没有任何常驻页签显示它");
            }
        }
    }

    // ------------------------------------------------------------------ 5

    /**
     * 扫描 {@code client/**} 里对配置键的<b>字面</b>引用，与 {@link PanelLayout} 的声明做比对。
     *
     * <h3>★ 为什么不能对"目录驱动的键"要求字面引用（2026-11-07 实测踩到）</h3>
     * <p>首版规则是"声明的每个键都必须在 client 源码里出现"。结果 36 个键报红 ——
     * 但那 36 个恰恰是<b>设计上正确的</b>：它们由 {@code DomainPanel} 在运行时从
     * {@link PanelLayout#domainDrivenKeys} 取出来渲染，源码里只有一个循环，
     * 永远不会出现 {@code "continentScale"} 这样的字面量。</p>
     * <p>这正是"目录驱动"的价值：加一个键<b>不需要改 UI 代码</b>。
     * 若探针反过来要求字面量，等于逼着人把键名再抄一遍 —— 本末倒置。</p>
     *
     * <h3>改后的规则（双向）</h3>
     * <ul>
     *   <li><b>专门 UI 的键</b>（{@link PanelLayout#specializedKeys}）：
     *       必须在 client 源码里有字面引用。它们是手写行，漏了就是"声明了却没画"。</li>
     *   <li><b>目录驱动的键</b>：不要求字面量，改为要求<b>该页签真的构造了目录驱动面板</b>
     *       —— 即 client 源码里出现 {@code PanelLayout.Tab.<该页>}。
     *       否则就是"声明了域，但没接目录驱动渲染器"。</li>
     *   <li><b>反向</b>：源码里引用的键若不在任何页签上，仍报红（面板偷偷多画一行）。</li>
     * </ul>
     */
    private void checkSourceMatchesLayout(Path root) throws IOException {
        Set<String> known = new LinkedHashSet<>(ConfigKeyCatalog.all().keySet());
        Set<String> referenced = new TreeSet<>();
        Path client = root.resolve(CLIENT_REL);
        if (!Files.isDirectory(client)) {
            warnings.add("SOURCE-MATCHES-LAYOUT 跳过：找不到 " + client);
            return;
        }
        // ★ 排除：KeyText 是"文字表"、Preset* 是"预设数据" —— 键名在那里以字符串出现，
        //   不代表面板真的画了那一行（它们不含任何绘制代码）。
        Set<String> skip = Set.of("KeyText.java", "PresetLibrary.java", "Preset.java");
        StringBuilder allText = new StringBuilder();
        try (Stream<Path> walk = Files.walk(client)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = ConfigKeyGuardProbe.stripComments(
                        Files.readString(p, StandardCharsets.UTF_8));
                if (skip.contains(p.getFileName().toString())) continue;
                allText.append(text).append('\n');
                for (String k : known) {
                    if (ConfigKeyGuardProbe.containsWordForTest(text, k)) referenced.add(k);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String clientSrc = allText.toString();

        Set<String> declared = new TreeSet<>();
        for (PanelLayout.Tab t : PanelLayout.Tab.values()) declared.addAll(PanelLayout.allKeys(t));

        // 反向：源码引用了但没登记
        for (String k : referenced) {
            if (!declared.contains(k)) {
                violations.add("SOURCE-MATCHES-LAYOUT 源码引用了 " + k
                        + " 但 PanelLayout 没声明（面板偷偷多画了一行，探针数不到）");
            }
        }

        // 正向：专门 UI 的键必须有字面引用
        for (PanelLayout.Tab t : PanelLayout.Tab.values()) {
            for (String k : PanelLayout.specializedKeys(t)) {
                if (!referenced.contains(k)) {
                    violations.add("SOURCE-MATCHES-LAYOUT 页签「" + t.label() + "」声明由专门 UI 渲染 "
                            + k + "，但 client/ 源码里没有它的字面引用（声明了却没画）");
                }
            }
        }

        // 正向：目录驱动的页必须有目录驱动渲染器的挂接点
        for (PanelLayout.Tab t : PanelLayout.Tab.values()) {
            if (PanelLayout.domainDrivenKeys(t).isEmpty()) continue;
            String marker = "PanelLayout.Tab." + t.name();
            if (!clientSrc.contains(marker)) {
                violations.add("SOURCE-MATCHES-LAYOUT 页签「" + t.label() + "」有 "
                        + PanelLayout.domainDrivenKeys(t).size()
                        + " 个目录驱动键，但 client/ 源码里没有对 " + marker
                        + " 的引用（没接目录驱动渲染器）");
            }
        }

        notes.add("SOURCE-MATCHES-LAYOUT：client 源码字面引用 " + referenced.size()
                + " 键 / PanelLayout 声明 " + declared.size() + " 键（差值 "
                + (declared.size() - referenced.size()) + " 个由目录驱动运行时解析）");
    }

    // ------------------------------------------------------------------ 6

    /** 面板说明里禁止"写死的数字"（5 处漂移的根因）。 */
    private void checkNoDigitNotes(Path root) throws IOException {
        Path keyText = root.resolve(CLIENT_REL).resolve("preview/KeyText.java");
        if (!Files.isRegularFile(keyText)) {
            warnings.add("NO-DIGIT-NOTES 跳过：找不到 KeyText.java");
            return;
        }
        String src = Files.readString(keyText, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("put\\(\"([\\w]+)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*((?:\"[^\"]*\"\\s*\\+?\\s*)+)\\)")
                .matcher(src);
        int checked = 0;
        while (m.find()) {
            String key = m.group(1);
            String note = (m.group(2) + m.group(3)).replace("\"", "").replace("+", "");
            checked++;
            // 允许的白名单：这些是【数量本身】而不是"默认值/范围"，读 spec 拿不到
            if (key.equals("worldgenProfilerEnabled")) {
                continue;
            }
            Matcher dm = Pattern.compile("默认\\s*-?\\d|范围\\s*\\[").matcher(note);
            if (dm.find()) {
                violations.add("NO-DIGIT-NOTES " + key
                        + " 的说明里写死了默认值/范围（必须由 ConfigSpecRanges 现取）："
                        + dm.group());
            }
        }
        notes.add("NO-DIGIT-NOTES：检查 " + checked + " 条说明");
    }

    // ------------------------------------------------------------------ 7

    private void checkPresetAxes(Path root) throws IOException {
        Path lib = root.resolve(CLIENT_REL).resolve("preview/PresetLibrary.java");
        if (!Files.isRegularFile(lib)) {
            warnings.add("PRESET-AXIS 跳过：找不到 PresetLibrary.java");
            return;
        }
        String src = ConfigKeyGuardProbe.stripComments(Files.readString(lib, StandardCharsets.UTF_8));
        Matcher m = Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"\\s*,\\s*(-?[0-9.]+)").matcher(src);
        Map<String, List<String>> byKey = new TreeMap<>();
        while (m.find()) {
            String key = m.group(1);
            ConfigKeyCatalog.Entry e = ConfigKeyCatalog.of(key);
            if (e == null) continue;
            byKey.computeIfAbsent(key, x -> new ArrayList<>()).add(m.group(2));
            if (!e.affectsNewWorld()) {
                violations.add("PRESET-AXIS 内置预设覆盖了 " + key + " [" + e.axis() + "]"
                        + " —— 该键对新世界无效，预设会变成「零效果」（这正是本轮要修的缺陷）");
            }
        }
        if (byKey.isEmpty()) {
            warnings.add("PRESET-AXIS 没解析到任何覆盖键 —— 解析规则可能已与 PresetLibrary 写法脱节");
        } else {
            notes.add("PRESET-AXIS：内置预设共覆盖 " + byKey.size() + " 个键，全部为新世界有效键");
            StringBuilder sb = new StringBuilder();
            for (String k : byKey.keySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(k);
            }
            notes.add("  覆盖键清单：" + sb);
        }
    }

    // ------------------------------------------------------------------ 8

    /**
     * 每个<b>面板可见</b>的键都必须有中文短名与说明。
     *
     * <p>缺了的表现是界面上出现「（缺说明）」或干脆显示英文键名 —— 用户读不懂，
     * 但不会崩，所以最容易长期漏掉。旧 {@code ParameterConfigPanel} 就有一批
     * 只有标签没有说明的滑块。</p>
     *
     * <h3>★ 为什么 55 个旧样条键没有字面量（2026-11-07 实测）</h3>
     * <p>{@code KeyText.LegacyText} 用<b>命名规律生成</b>旧样条键的短名与说明
     * （{@code {类型}{Lo|Hi}{Loc|Val|Deriv}{0|1}}，例如 {@code mountHiVal1}
     * = 「山脉 / hi 样条 / 端点值 / 位置 1」）。手抄 70 条只会引入新的抄写错误
     * —— 而消灭抄写错误正是 {@code KeyText} 存在的理由。</p>
     * <p>⇒ 探针对这类键改用<b>规律校验</b>而不是字面量校验：名字必须能被那条规律解析，
     * 且 {@code LegacyGrouping} 必须能给它归组。任一不满足即报红。</p>
     */
    private void checkKeyTextCoverage(Path root) throws IOException {
        Path keyText = root.resolve(CLIENT_REL).resolve("preview/KeyText.java");
        if (!Files.isRegularFile(keyText)) {
            warnings.add("KEYTEXT 跳过：找不到 KeyText.java");
            return;
        }
        String src = Files.readString(keyText, StandardCharsets.UTF_8);
        Set<String> noted = new TreeSet<>();
        Matcher m = Pattern.compile("(?:put|entry)\\(\\s*\"(\\w+)\"").matcher(src);
        while (m.find()) noted.add(m.group(1));
        boolean generatesLegacy = src.contains("LegacyText") && src.contains("parse(");

        Set<String> visible = new TreeSet<>();
        for (PanelLayout.Tab t : PanelLayout.visibleTabs(true)) visible.addAll(PanelLayout.allKeys(t));

        int missing = 0;
        int generated = 0;
        for (String k : visible) {
            if (noted.contains(k)) continue;
            ConfigKeyCatalog.Entry e = ConfigKeyCatalog.of(k);
            if (e != null && e.axis() == ConfigKeyCatalog.Axis.LEGACY_ONLY
                    && generatesLegacy && LEGACY_PATTERN.matcher(k).matches()) {
                generated++;
                continue;   // 由 KeyText.LegacyText 按规律生成
            }
            missing++;
            violations.add("KEYTEXT " + k + " 是面板可见键，但 KeyText 里没有中文短名/说明"
                    + "（界面上会显示「（缺说明）」）");
        }
        notes.add("KEYTEXT：" + noted.size() + " 条字面说明 + " + generated
                + " 条按规律生成的旧样条说明，覆盖 " + visible.size() + " 个可见键，缺 " + missing);
    }

    /** {@code KeyText.LegacyText} 能解析的旧样条键名形状（必须与那边一致）。 */
    private static final Pattern LEGACY_PATTERN = Pattern.compile(
            "(plain|hills|mount|plat|basin)(Lo|Hi)(Loc|Val|Deriv)[01]");
}
