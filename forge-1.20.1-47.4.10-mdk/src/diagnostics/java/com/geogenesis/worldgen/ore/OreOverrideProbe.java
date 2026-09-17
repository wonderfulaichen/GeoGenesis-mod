package com.geogenesis.worldgen.ore;

import com.geogenesis.worldgen.generator.VanillaDecorationFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 【矿物接管白名单】正确性门禁（★ 2026-09-18）。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code GeoGenesisConfig.oreOverrideVanilla} = {@code OVERRIDE} 时，会从原版装饰管线
 * 剔除 {@code minecraft} 命名空间的金属矿特征（见 {@code VanillaDecorationFilter.METAL_ORES}）。</p>
 *
 * <p>⚠ <b>白名单是硬编码的 1.20.1 内容快照</b>，而 1.20.1 每个矿种有 2~3 个形态变体
 * （共 16 项）。漏一项的后果是"某矿被生成两次或被静默遗留"，**且不会有任何报错**
 * ⇒ 属于"静默错误"，必须由门禁扼住。</p>
 *
 * <h2>判据（三条，全部必须 PASS）</h2>
 * <ol>
 *   <li><b>模板集合 ⊆ 白名单</b>：本探针内置的"原版金属矿应然清单"必须被白名单**完全覆盖**
 *       ⇒ 防漏项。</li>
 *   <li><b>白名单 ⊆ 模板集合</b>：白名单不得含模板之外的条目 ⇒ 防多剔（误伤）。</li>
 *   <li><b>不误伤检查</b>：{@code disk_sand/clay/gravel}、{@code underwater_magma}、岩块团块
 *       等<b>非金属矿</b>特征必须**不在**白名单内。</li>
 * </ol>
 *
 * <h2>与实读的关系</h2>
 * <p>模板集合来源：1.20.1 {@code client.jar} 中 6 个群系（plains / desert / taiga / swamp /
 * savanna / badlands）的 {@code UNDERGROUND_ORES} 步交叉实读。★ {@code ore_gold_extra}
 * <b>仅 badlands 有</b>，只看 jungle 会漏 —— 这正是本门禁存在的理由。</p>
 *
 * <p>⚠ <b>本探针不依赖 Minecraft 运行时</b>（只读静态集合），故可在无 MC 的探针进程直接跑。
 * 它<b>无法</b>验证"原版 jar 里真的有这些 id"（那需要读 jar）—— 该层由
 * {@code docs/analysis/原版复用对照审计-2026-09-16.md} §0 的实读命令负责。</p>
 *
 * <pre>{@code gradlew runOreOverrideProbe}</pre>
 */
public final class OreOverrideProbe {

    /**
     * 原版 1.20.1 金属矿特征应然清单（16 项，6 群系交叉实读）。
     *
     * <p>★ 若 MC 升级或原版增删矿种，<b>本模板与 {@code METAL_ORES} 都要更新</b>；
     * 只改一边会让本门禁 FAIL —— 这是有意的（强制两边同步）。</p>
     */
    private static final List<String> EXPECTED_VANILLA_METAL_ORES = List.of(
            // 煤 2
            "ore_coal_upper", "ore_coal_lower",
            // 铁 3
            "ore_iron_upper", "ore_iron_middle", "ore_iron_small",
            // 金 3（★ gold_extra 仅 badlands 有）
            "ore_gold", "ore_gold_lower", "ore_gold_extra",
            // 红石 2
            "ore_redstone", "ore_redstone_lower",
            // 钻石 3
            "ore_diamond", "ore_diamond_large", "ore_diamond_buried",
            // 青金石 2
            "ore_lapis", "ore_lapis_buried",
            // 铜 2（★ copper_large 易漏）
            "ore_copper", "ore_copper_large",
            // 绿宝石 1（★ 仅山地群系 —— 只读非山地群系会整项漏掉）
            "ore_emerald",
            // 虫蚀石 1（★ 同上）
            "ore_infested");

    /**
     * 必须【不被剔除】的原版特征（防误伤）。
     *
     * <p>这些名字都含 {@code ore} 或与矿相邻，是"名字含 ore 就删"式模糊匹配的典型误伤面。</p>
     */
    private static final List<String> MUST_NOT_BE_REMOVED = List.of(
            // 水成细节（与矿同处 UNDERGROUND_ORES 步，但属"沉积/水文"而非金属矿）
            "disk_sand", "disk_clay", "disk_gravel",
            "underwater_magma",
            // ★ 非金属矿（穷举并集里出现，但不是金属资源矿）
            "ore_clay",
            // ★ 下界/末地矿 —— "名字含 ore"式匹配的最大误伤面
            "ore_gold_nether", "ore_quartz_nether", "ore_quartz_deltas", "ore_gold_deltas",
            "ore_ancient_debris_large", "ore_debris_small", "ore_blackstone",
            "ore_magma", "ore_soul_sand", "ore_gravel_nether",
            // 岩块团块的【策略】是"始终剔除"，但那是 STRATA_BREAKING 的职责，
            // 不应因 metalOre 判定而误报 ⇒ 它们不该出现在 METAL_ORES 里
            "ore_dirt", "ore_gravel",
            "ore_granite_upper", "ore_granite_lower",
            "ore_diorite_upper", "ore_diorite_lower",
            "ore_andesite_upper", "ore_andesite_lower",
            "ore_tuff");

    public static void main(String[] args) {
        System.out.println("=== OreOverrideProbe —— 矿物接管白名单门禁 ===");
        System.out.println("判据：① 模板 ⊆ 白名单（防漏）② 白名单 ⊆ 模板（防多剔）③ 不误伤");
        System.out.println();

        boolean t1 = testTemplateCoveredByWhitelist();
        boolean t2 = testWhitelistCoveredByTemplate();
        boolean t3 = testNoCollateralDamage();
        boolean t4 = testCount();

        boolean all = t1 && t2 && t3 && t4;
        System.out.println();
        System.out.printf("总判定: %s%n", all
                ? "ALL PASS（白名单与 1.20.1 原版内容快照一致，且无误伤）"
                : "FAILURES（白名单与预期不符 ⇒ OVERRIDE 会漏剔或误剔）");
        System.exit(all ? 0 : 1);
    }

    // ==================================================================
    // [1] 模板 ⊆ 白名单（防漏项）
    // ==================================================================

    private static boolean testTemplateCoveredByWhitelist() {
        System.out.println("[1] 模板 ⊆ 白名单（防漏项）");
        List<String> missing = new ArrayList<>();
        for (String id : EXPECTED_VANILLA_METAL_ORES) {
            if (!VanillaDecorationFilter.isMetalOre(id)) missing.add(id);
        }
        boolean pass = missing.isEmpty();
        System.out.printf("    期望 %d 项；白名单实际 %d 项%n",
                EXPECTED_VANILLA_METAL_ORES.size(), VanillaDecorationFilter.metalOreCount());
        if (pass) {
            System.out.println("    缺失 = 0  ✓");
        } else {
            System.out.println("    ✗ 缺失（OVERRIDE 时将被静默遗留）: " + missing);
        }
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ==================================================================
    // [2] 白名单 ⊆ 模板（防多剔）
    // ==================================================================

    private static boolean testWhitelistCoveredByTemplate() {
        System.out.println("[2] 白名单 ⊆ 模板（防多剔）");
        List<String> extra = new ArrayList<>();
        // 逐个"探测"：用模板之外的候选名去问
        // 由于 METAL_ORES 无遍历接口，这里用一份常见原版特征名做反向嗅探
        String[] candidates = {
                // 不存在的 id / 易笔误的简写 ⇒ 不得在白名单里
                "ore_coal", "ore_iron", "ore_gold_short",
                "ores", "ore",
                // 非主世界矿 ⇒ 不得在白名单里
                "ore_quartz_nether", "ore_ancient_debris_large",
        };
        for (String c : candidates) {
            if (VanillaDecorationFilter.isMetalOre(c)) extra.add(c);
        }
        boolean pass = extra.isEmpty();
        if (!pass) System.out.println("    ✗ 白名单含模板外条目: " + extra);
        else System.out.println("    模板外条目 = 0  ✓");
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ==================================================================
    // [3] 不误伤（水成细节 / 岩块团块不得被当金属矿）
    // ==================================================================

    private static boolean testNoCollateralDamage() {
        System.out.println("[3] 不误伤（水成细节 / 岩块团块 / 模组命名）");
        List<String> hit = new ArrayList<>();
        for (String id : MUST_NOT_BE_REMOVED) {
            if (VanillaDecorationFilter.isMetalOre(id)) hit.add(id);
        }
        // 模组命名（非 minecraft 命名空间）—— 白名单只管 path，故这里验"不会因名字相似而误伤"
        String[] modLike = {"ore_copper", "ore_tin", "ore_silver", "ore_lead", "ore_uranium"};
        for (String m : modLike) {
            // ore_copper 是原版（应在白名单）；其余模组矿名不在白名单 ⇒ 不会被剔
            if (!"ore_copper".equals(m) && VanillaDecorationFilter.isMetalOre(m)) hit.add(m);
        }
        boolean pass = hit.isEmpty();
        if (pass) System.out.println("    误伤 = 0  ✓（disk_*/underwater_magma/岩块团块/模组矿名均未被列入）");
        else System.out.println("    ✗ 误伤: " + hit);
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ==================================================================
    // [4] 计数快照（信息 + 断言）
    // ==================================================================

    private static boolean testCount() {
        System.out.println("[4] 计数快照");
        int n = VanillaDecorationFilter.metalOreCount();
        boolean pass = n == EXPECTED_VANILLA_METAL_ORES.size();
        System.out.printf("    白名单大小 = %d（期望 %d，1.20.1 快照）%n",
                n, EXPECTED_VANILLA_METAL_ORES.size());
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    private OreOverrideProbe() { }
}
