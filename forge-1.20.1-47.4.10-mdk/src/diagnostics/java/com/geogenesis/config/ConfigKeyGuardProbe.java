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
 *       {@code config/GeoGenesisConfig.java} 自身（声明处与指纹都在这里，读它不代表生效）
 *       与 {@code client/**}（UI 只是展示/编辑，不构成"影响产出"）。</li>
 *   <li><b>LIVE</b>：在 production 里至少出现一次 → 通过。</li>
 *   <li><b>CLIENT_ONLY</b>：只在 {@code client/**} 出现 → 即"假开关"，FAIL。</li>
 *   <li><b>UNREAD</b>：整个 {@code src/main} 都没有 → FAIL。</li>
 *   <li><b>CONFIG_ONLY</b>：只在 {@code GeoGenesisConfig} 内部被读（典型是
 *       {@code buildSplineConfig()} 这类惰性构建器）→ <b>只警告不失败</b>。
 *       这类键是否真影响产出取决于那个构建器的调用方，机器判不了，留给人工。</li>
 * </ul>
 *
 * <p><b>白名单</b>：{@code tools/config-key-guard-allowlist.txt}，每行
 * {@code key<TAB>理由}（{@code #} 开头为注释）。用于"键确实不驱动产出、但删除会牵连
 * 其它源集"的已知情况。白名单本身也被守卫：条目若已变成 LIVE，或键已从配置里消失，
 * 都会 FAIL —— 防止白名单腐烂成永久豁免。</p>
 *
 * <p><b>零 MC 依赖</b>：本探针只用 JDK，不加载 Forge，也不读 TOML（探针进程没有 Forge 环境，
 * {@code GeoGenesisConfig.INSTANCE} 为 null）。全部判据来自源码文本。</p>
 *
 * <p>用法：{@code gradlew runConfigKeyGuardProbe}
 * （可选参数：仓库根，默认取工作目录）。任一违规 → {@code System.exit(1)}。</p>
 */
public final class ConfigKeyGuardProbe {

    private static final String CONFIG_REL = "src/main/java/com/geogenesis/config/GeoGenesisConfig.java";
    private static final String MAIN_REL = "src/main/java";
    /** {@code MAIN_REL} 之下的相对路径前缀 —— 注意 base 是 src/main/java 而不是仓库根。 */
    private static final String CLIENT_PREFIX = "com/geogenesis/client/";
    private static final String ALLOWLIST_REL = "tools/config-key-guard-allowlist.txt";

    private static final Pattern DECL = Pattern.compile(
            "public final ForgeConfigSpec\\.\\w+Value\\s+(\\w+)\\s*;");
    private static final Pattern ASSIGN = Pattern.compile("^\\s*(\\w+)\\s*=\\s*builder\\b");
    private static final Pattern DEFINE = Pattern.compile("\\.define(?:InRange|InList)?\\(\\s*\"([^\"]+)\"");

    private ConfigKeyGuardProbe() {}

    public static void main(String[] args) throws Exception {
        boolean verbose = false;
        String rootArg = null;
        for (String a : args) {
            if ("--verbose".equals(a) || "-v".equals(a)) {
                verbose = true;
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

        Map<String, String> keyOf = new LinkedHashMap<>();   // field -> key
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

        List<String> problems = new ArrayList<>();
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

        Map<String, List<String>> productionHits = new TreeMap<>();
        Map<String, List<String>> clientHits = new TreeMap<>();
        for (String k : keys) {
            productionHits.put(k, new ArrayList<>());
            clientHits.put(k, new ArrayList<>());
        }

        Path mainRoot = root.resolve(MAIN_REL);
        Path self = configFile;
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
                        for (String k : keys) {
                            if (!containsWord(text, k)) {
                                continue;
                            }
                            (inClient ? clientHits : productionHits).get(k).add(rel);
                        }
                    });
        }

        Map<String, String> allow = readAllowlist(root.resolve(ALLOWLIST_REL), problems);

        // 配置类内部是否还读过该键（buildSplineConfig 之类的惰性构建器）：
        // 先抹掉"声明 / 赋值 / .define 字符串 / 指纹方法体"这些非消费出现，剩下的才算一次读。
        String cfgResidual = redactFingerprint(configSrc);
        Map<String, Boolean> readInsideConfig = new LinkedHashMap<>();
        for (String k : keys) {
            String residual = cfgResidual
                    .replaceAll("public final ForgeConfigSpec\\.\\w+Value\\s+" + k + "\\s*;", " ")
                    .replaceAll("\\b" + k + "\\s*=\\s*builder", " ")
                    .replaceAll("\\.define(?:InRange|InList)?\\(\\s*\"" + k + "\"", " ");
            readInsideConfig.put(k, containsWord(residual, k));
        }

        int live = 0;
        int clientOnly = 0;
        int unread = 0;
        int configOnly = 0;
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String k : keys) {
            boolean isLive = !productionHits.get(k).isEmpty();
            boolean isClientOnly = !isLive && !clientHits.get(k).isEmpty();
            boolean isConfigOnly = !isLive && !isClientOnly && readInsideConfig.get(k);
            boolean waived = allow.containsKey(k);
            if (isLive) {
                live++;
                if (waived) {
                    problems.add("白名单条目已失效（该键现在被生产消费）：" + k + "  ->  "
                            + productionHits.get(k).get(0) + "；请从白名单删除");
                }
            } else if (isClientOnly) {
                clientOnly++;
                if (!waived) {
                    violations.add("CLIENT_ONLY  " + k + "：只在 " + String.join(", ", clientHits.get(k))
                            + " 里被读 —— 生产路径不读它，即「开关无效果」");
                }
            } else if (isConfigOnly) {
                configOnly++;
                warnings.add("CONFIG_ONLY  " + k + "：只在 GeoGenesisConfig 内部被读（疑似惰性构建器），"
                        + "请人工确认该构建器是否接到生产；确认无关就加进白名单");
            } else {
                unread++;
                if (!waived) {
                    violations.add("UNREAD       " + k + "：整个 src/main 都没有读取（键是死的）");
                }
            }
        }
        for (String k : allow.keySet()) {
            if (!keys.contains(k)) {
                problems.add("白名单条目指向不存在的键：" + k);
            }
        }

        System.out.println("=== CONFIG KEY GUARD ===");
        System.out.println("  配置源            : " + root.relativize(configFile).toString().replace('\\', '/'));
        System.out.println("  键总数            : " + keys.size());
        System.out.println("  LIVE（生产消费）  : " + live);
        System.out.println("  CLIENT_ONLY       : " + clientOnly);
        System.out.println("  CONFIG_ONLY（警告）: " + configOnly);
        System.out.println("  UNREAD            : " + unread);
        System.out.println("  白名单            : " + allow.size()
                + (allow.isEmpty() ? "" : " -> " + String.join(", ", allow.keySet())));
        System.out.println();

        if (!violations.isEmpty()) {
            System.out.println("FAIL: " + violations.size() + " 个键没有驱动生产代码");
            for (String v : violations) {
                System.out.println("  - " + v);
            }
            System.out.println();
        }
        if (!warnings.isEmpty()) {
            System.out.println("WARN: " + warnings.size()
                    + " 个键只能在配置类内部被读（不判失败，需人工确认其构建器是否接到生产）");
            int shown = 0;
            for (String w : warnings) {
                if (!verbose && shown >= 3) {
                    continue;
                }
                System.out.println("  - " + w);
                shown++;
            }
            if (!verbose && warnings.size() > 3) {
                System.out.println("  … 其余 " + (warnings.size() - 3) + " 个略（加 --verbose 看全部）");
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

        if (violations.isEmpty() && problems.isEmpty()) {
            System.out.println("CONFIG KEY GUARD PASS");
            System.exit(0);
        }
        System.out.println("CONFIG KEY GUARD FAIL");
        System.exit(1);
    }

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

    private static Map<String, String> readAllowlist(Path file, List<String> problems) throws IOException {
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
            }
            allow.put(key, reason);
        }
        return allow;
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
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