package com.geogenesis.worldgen.ore;

import com.geogenesis.worldgen.generator.VanillaDecorationFilter;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 【矿物过滤行为】端到端门禁（★ 2026-09-18）。
 *
 * <h2>为什么需要它（用户驱动的设计）</h2>
 * <p>用户明确要求：<b>「这些我实机是看不出变化的……这个判断只能你来」</b>。
 * 故本探针的作用是：<b>把"开关打开/关闭后到底会剔除什么"完整、可复现地判定出来</b>，
 * 不依赖人工观察、不依赖实机截图。</p>
 *
 * <h2>验证内容（4 组，全部必须 PASS）</h2>
 * <ol>
 *   <li><b>VANILLA 模式</b>（{@code override=false}）：金属矿<b>一律保留</b>
 *       + 岩块团块仍剔除 ⇒ 「默认零行为变更」成立。</li>
 *   <li><b>OVERRIDE 模式</b>（{@code override=true}）：<b>全部 19 项</b>金属矿被剔除
 *       + 岩块团块仍剔除 ⇒ 「接管」成立。</li>
 *   <li><b>★ 多模组兼容</b>：任意非 {@code minecraft} 命名空间的特征
 *       <b>在两种模式下都保留</b>（含<b>冒充原版名</b>的 {@code somemod:ore_coal_upper}）
 *       ⇒ 这是用户最关心的需求，必须由机器判定。</li>
 *   <li><b>模式切换即生效</b>：切换后判定结果确实改变
 *       ⇒ 防止"开关写了但没接到底"（本项目已多次记录此类缺陷）。</li>
 * </ol>
 *
 * <h2>与 {@link OreOverrideProbe} 的分工</h2>
 * <ul>
 *   <li>{@code OreOverrideProbe}：验<b>白名单内容</b>（19 项是否与 1.20.1 快照一致、有无误伤）。</li>
 *   <li>本探针：验<b>过滤行为</b>（在真值表上，两种模式各自剔除/保留什么）。</li>
 * </ul>
 *
 * <p>⚠ 本探针用真实 {@code minecraft:} / 虚构模组 id 构造 {@link ResourceLocation}
 * —— 只解析字符串、<b>不访问注册表</b> ⇒ 可独立运行，且<b>无需加载 MC</b>。</p>
 *
 * <pre>{@code gradlew runOreFilterBehaviorProbe}</pre>
 */
public final class OreFilterBehaviorProbe {

    /** 岩块团块（应【始终】剔除，与开关无关）。 */
    private static final List<String> BLOCK_CLUMPS = List.of(
            "ore_dirt", "ore_gravel", "ore_tuff",
            "ore_granite_upper", "ore_granite_lower",
            "ore_diorite_upper", "ore_diorite_lower",
            "ore_andesite_upper", "ore_andesite_lower");

    /** 金属矿（VANILLA 保留 / OVERRIDE 剔除）。19 项，与 {@code VanillaDecorationFilter} 对齐。 */
    private static final List<String> METAL_ORES = List.of(
            "ore_coal_upper", "ore_coal_lower",
            "ore_iron_upper", "ore_iron_middle", "ore_iron_small",
            "ore_gold", "ore_gold_lower", "ore_gold_extra",
            "ore_redstone", "ore_redstone_lower",
            "ore_diamond", "ore_diamond_large", "ore_diamond_buried",
            "ore_lapis", "ore_lapis_buried",
            "ore_copper", "ore_copper_large",
            "ore_emerald", "ore_infested");

    /** 非金属原版特征（两种模式都应保留）。 */
    private static final List<String> MUST_KEEP_VANILLA = List.of(
            "disk_sand", "disk_clay", "disk_gravel", "disk_grass",
            "underwater_magma", "ore_clay",
            "amethyst_geode", "trees_jungle", "freeze_top_layer");

    /**
     * ★ 多模组兼容测试集：非 {@code minecraft} 命名空间。
     *
     * <p>关键用例：<b>冒充原版名字</b>（{@code ore_coal_upper} / {@code ore_dirt}）
     * —— 若命名空间闸门失效，它们会被误删。这正是本组要抓的错。</p>
     */
    private static final List<String> MOD_NAMESPACES = List.of(
            "mekanism", "thermal", "create", "immersiveengineering", "tconstruct",
            "orestages", "geolosys", "mycustommod");

    private static final List<String> MOD_FEATURES = List.of(
            "ore_tin", "ore_silver", "ore_lead", "ore_uranium", "ore_osmium",
            // ★ 冒充原版名（最关键的误删面）
            "ore_coal_upper", "ore_dirt", "ore_granite_upper",
            // 与岩块团块同名
            "ore_tuff");

    public static void main(String[] args) {
        System.out.println("=== OreFilterBehaviorProbe —— 矿物过滤【行为】门禁 ===");
        System.out.println("验证：两种模式各剔什么、多模组是否安全、开关是否真生效");
        System.out.println();

        boolean t1 = testVanillaMode();
        boolean t2 = testOverrideMode();
        boolean t3 = testModCompatibility();
        boolean t4 = testToggleTakesEffect();

        boolean all = t1 && t2 && t3 && t4;
        System.out.println();
        System.out.printf("总判定: %s%n", all
                ? "ALL PASS（两模式行为符合真值表；多模组矿零误删；开关真生效）"
                : "FAILURES（过滤行为与预期不符 ⇒ 接管/兼容存在缺陷）");
        System.exit(all ? 0 : 1);
    }

    // ==================================================================
    // [1] VANILLA 模式：金属矿全保留 + 团块仍剔
    // ==================================================================

    private static boolean testVanillaMode() {
        System.out.println("[1] VANILLA 模式（override=false）—— 应为默认、零行为变更");
        boolean prev = VanillaDecorationFilter.setOverrideVanillaOre(false);

        List<String> metalRemoved = new ArrayList<>();
        for (String p : METAL_ORES) {
            if (wouldRemove("minecraft", p)) metalRemoved.add(p);
        }
        List<String> clumpsKept = new ArrayList<>();
        for (String p : BLOCK_CLUMPS) {
            if (!wouldRemove("minecraft", p)) clumpsKept.add(p);
        }
        List<String> keepRemoved = new ArrayList<>();
        for (String p : MUST_KEEP_VANILLA) {
            if (wouldRemove("minecraft", p)) keepRemoved.add(p);
        }

        boolean pass = metalRemoved.isEmpty() && clumpsKept.isEmpty() && keepRemoved.isEmpty();
        System.out.printf("    金属矿被剔 = %d（须为 0）%s%n", metalRemoved.size(),
                metalRemoved.isEmpty() ? "" : "  ✗ " + metalRemoved);
        System.out.printf("    团块已保留 = %d（须为 0，团块应【始终】被剔）%s%n", clumpsKept.size(),
                clumpsKept.isEmpty() ? "" : "  ✗ " + clumpsKept);
        System.out.printf("    应保留项被剔 = %d（须为 0）%s%n", keepRemoved.size(),
                keepRemoved.isEmpty() ? "" : "  ✗ " + keepRemoved);
        System.out.printf("    判定: %s（切换前状态=%s）%n%n", pass ? "PASS" : "FAIL", prev ? "OVERRIDE" : "VANILLA");
        return pass;
    }

    // ==================================================================
    // [2] OVERRIDE 模式：全部 19 项金属矿被剔 + 团块仍剔
    // ==================================================================

    private static boolean testOverrideMode() {
        System.out.println("[2] OVERRIDE 模式（override=true）—— 应为【接管】");
        VanillaDecorationFilter.setOverrideVanillaOre(true);

        List<String> metalKept = new ArrayList<>();
        for (String p : METAL_ORES) {
            if (!wouldRemove("minecraft", p)) metalKept.add(p);
        }
        List<String> clumpsKept = new ArrayList<>();
        for (String p : BLOCK_CLUMPS) {
            if (!wouldRemove("minecraft", p)) clumpsKept.add(p);
        }
        List<String> keepRemoved = new ArrayList<>();
        for (String p : MUST_KEEP_VANILLA) {
            if (wouldRemove("minecraft", p)) keepRemoved.add(p);
        }

        boolean pass = metalKept.isEmpty() && clumpsKept.isEmpty() && keepRemoved.isEmpty();
        System.out.printf("    金属矿漏剔 = %d（须为 0，共 %d 项）%s%n", metalKept.size(), METAL_ORES.size(),
                metalKept.isEmpty() ? "" : "  ✗ " + metalKept);
        System.out.printf("    团块已保留 = %d（须为 0）%s%n", clumpsKept.size(),
                clumpsKept.isEmpty() ? "" : "  ✗ " + clumpsKept);
        System.out.printf("    水成细节被误剔 = %d（须为 0）%s%n", keepRemoved.size(),
                keepRemoved.isEmpty() ? "" : "  ✗ " + keepRemoved);
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ==================================================================
    // [3] ★ 多模组兼容（用户最关心的需求）
    // ==================================================================

    private static boolean testModCompatibility() {
        System.out.println("[3] ★ 多模组兼容 —— 非 minecraft 命名空间【两种模式下都不得剔除】");
        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (boolean override : new boolean[]{false, true}) {
            VanillaDecorationFilter.setOverrideVanillaOre(override);
            String mode = override ? "OVERRIDE" : "VANILLA ";
            for (String ns : MOD_NAMESPACES) {
                for (String path : MOD_FEATURES) {
                    checked++;
                    if (wouldRemove(ns, path)) {
                        violations.add(mode + " " + ns + ":" + path);
                    }
                }
            }
            System.out.printf("    [%s] 检查 %d 个模组特征（%d 命名空间 × %d 名）⇒ 误删 %d%n",
                    mode, MOD_NAMESPACES.size() * MOD_FEATURES.size(),
                    MOD_NAMESPACES.size(), MOD_FEATURES.size(),
                    (int) violations.stream().filter(v -> v.startsWith(mode)).count());
        }

        boolean pass = violations.isEmpty();
        System.out.printf("    合计 %d 次检查；误删 = %d（须为 0）%s%n", checked, violations.size(),
                violations.isEmpty() ? "" : "  ✗ " + violations);
        System.out.println("    ★ 含『冒充原版名』用例（somemod:ore_coal_upper / :ore_dirt）—— 命名空间闸门必须挡住");
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ==================================================================
    // [4] 模式切换真生效（防"开关没接到底"）
    // ==================================================================

    private static boolean testToggleTakesEffect() {
        System.out.println("[4] 模式切换真生效（防『开关写了但没接到底』）");
        String probe = "ore_coal_upper";
        VanillaDecorationFilter.setOverrideVanillaOre(false);
        boolean inVanilla = wouldRemove("minecraft", probe);
        VanillaDecorationFilter.setOverrideVanillaOre(true);
        boolean inOverride = wouldRemove("minecraft", probe);

        boolean pass = !inVanilla && inOverride;
        System.out.printf("    minecraft:%s —— VANILLA 剔除=%s / OVERRIDE 剔除=%s（须 false/true）%n",
                probe, inVanilla, inOverride);
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        // 复原默认（VANILLA）—— 避免影响同进程后续测试
        VanillaDecorationFilter.setOverrideVanillaOre(false);
        return pass;
    }

    private static boolean wouldRemove(String namespace, String path) {
        return VanillaDecorationFilter.wouldRemove(new ResourceLocation(namespace, path));
    }

    private OreFilterBehaviorProbe() { }
}
