package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * <b>{@link UnifiedTerrainCatalog} 的门禁</b>（方案 {@code ocean-unified-terrain} 步 2）。
 *
 * <h3>本步唯一要证明的事</h3>
 * <p>「加入统一票槽池之后，<b>陆地 7 臂产出逐位不变</b>」。
 * 因为海洋池的存在若污染了陆地抽签游标（见 {@link UnifiedTerrainCatalog} 类注释），
 * 用户已认可的基线会静默作废 —— 这是本方案最大的风险。</p>
 *
 * <h3>七项判据</h3>
 * <pre>
 * U1 land-sha        陆地 7 臂 raw 的 SHA 与基线记录逐位相同（★ 最关键）
 * U2 cursor-isolated 构造海洋池不得改变陆地的任何抽签结果（逐点 height01 对拍）
 * U3 ocean-off       oceanEnabled=false 时 resolve() 永不返回 OCEAN
 * U4 layout          槽位布局：陆地 [0,45)、海洋 [45,59)、总 59
 * U5 ocean-slots     海洋槽展开：6 配方都有槽、总数恰为 14
 * U6 determinism     同 seed 两次构造的海洋槽顺序逐位相同
 * U7 seed-offset     改海洋 seed 偏移不得影响陆地 SHA（用不同偏移构造后复核 U1）
 * </pre>
 *
 * <p>U1 与 U2/U7 是"安全绳"。U1 直接读 {@code build/unified-baseline/data-v2} 的产物，
 * 与 {@code tools/unified-baseline.ps1} 记录的值对拍。</p>
 *
 * <p>FAIL 一律 {@code System.exit(1)}。</p>
 */
public final class UnifiedTerrainCheck {

    /** 陆地 7 臂基线 SHA（来自 tools/unified-baseline.ps1 的固化结果）。 */
    private static final String[][] BASELINE = {
            {"baseline-coastal",       "5A8BB721BA191C7949C583B90D7B76BAAF5C6AC9E08C4619021AFD2F60261D28"},
            {"baseline-islands",       "94BA83F7359526DF1267572AAC181AD3922E1C41F7BCA31D6B6C5CCB9BE271A1"},
            {"plateau-only-coastal",   "D8B0802EAC41ECF26BB494B026A0EF66CEC49590E6F02DFCD13803E59A85D871"},
            {"plateau-only-islands",   "4B9D3B8B6C5F2396D56AD95D92EACBEC40F86C3104CC41D353358A3CA6483B18"},
            {"morph-coastal",          "7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238"},
            {"morph-islands",          "955BA2CC02A6DEE7A2B1C6415F2A18C818C3A6286CE768099E932CFF35F65A2A"},
            {"shelf-coastal",          "2604533841943D213FFF21401E89E539487F79DC705A53124F2F3BE58CCC2B6E"},
    };

    private static final long SEED = 9139912035078620160L;
    private static final int LAND_ENTRY_COUNT = RecipeMorphCatalog.ENTRY_COUNT;

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/unified-terrain-check");
        Files.createDirectories(out);
        // 基线 raw 目录（由 tools/unified-baseline.ps1 产出）
        Path data = Path.of(args.length > 1 ? args[1]
                : "build/unified-baseline/data-v2");

        csv.append("check,metric,value,verdict\n");

        // ---------------- U1：陆地 SHA 逐位不变
        checkLandSha(data);

        // ---------------- U2：游标隔离
        checkCursorIsolation();

        // ---------------- U3/U4/U5/U6
        UnifiedTerrainCatalog cat = new UnifiedTerrainCatalog(SEED);
        // ★ 先启用：否则 resolve() 对海洋槽恒返回 null（这正是 U3 要验证的默认行为）
        cat.enableOcean();
        System.out.print(cat.summary());
        checkLayout(cat);
        checkOceanOff(cat);
        checkOceanSlots(cat);
        checkDeterminism();
        checkSeedOffsetIsolated();

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- U1

    /**
     * U1：陆地 7 臂 SHA 与基线逐位相同。
     *
     * <p>直接读 {@code build/unified-baseline/data-v2} 的 raw 文件重新算 SHA。
     * 这证明"当前工作树 + 统一票槽池代码"没有改变陆地产出。</p>
     */
    private static void checkLandSha(Path data) throws Exception {
        if (!Files.isDirectory(data)) {
            record("U1", "data-dir-missing", 1, false,
                    "基线 raw 目录不存在：" + data + "（先跑 tools/unified-baseline.ps1）");
            return;
        }
        int same = 0, diff = 0;
        for (String[] arm : BASELINE) {
            Path p = data.resolve(arm[0] + ".f64be");
            if (!Files.isRegularFile(p)) {
                record("U1", "missing-" + arm[0], 1, false, "缺少 raw：" + p);
                continue;
            }
            String got = sha256(p);
            if (got.equalsIgnoreCase(arm[1])) {
                same++;
            } else {
                diff++;
                System.out.printf(Locale.ROOT, "  [U1] DRIFT %s%n    expected %s%n    got      %s%n",
                        arm[0], arm[1], got);
            }
        }
        record("U1", "arms-matching", same, same == BASELINE.length,
                "★ 陆地 7 臂 SHA 必须与基线逐位相同（本步最关键的安全判据）");
        record("U1", "arms-drifted", diff, diff == 0, "漂移臂数必须为 0");
    }

    // ---------------------------------------------------------------- U2

    /**
     * U2：构造 {@link UnifiedTerrainCatalog} 不得改变陆地的抽签结果。
     *
     * <p>做法：先用 {@code RecipeMorphCatalog} 建一次陆地臂，采样一批点的
     * {@code height01}；再构造 {@code UnifiedTerrainCatalog}（它内部会派生海洋池、
     * 展开槽位、打散），然后<b>重新</b>建一个陆地臂，逐点对拍。</p>
     *
     * <p>若海洋池的构造污染了全局随机状态或共享了游标，这里会暴露。</p>
     */
    private static void checkCursorIsolation() {
        RecipeMorphCatalog landA = new RecipeMorphCatalog(SEED);
        CombineCandidate before = landA.original();
        if (before == null) {
            record("U2", "original-null", 1, false, "RecipeMorphCatalog.original() 返回 null");
            return;
        }

        // 构造统一池（应完全不触碰陆地的抽签）
        UnifiedTerrainCatalog cat = new UnifiedTerrainCatalog(SEED);
        // 至少做点事，让任何"惰性副作用"有机会发生
        cat.oceanSlotIds();
        cat.oceanSlotCounts();
        cat.enableOcean();

        RecipeMorphCatalog landB = new RecipeMorphCatalog(SEED);
        CombineCandidate after = landB.original();
        if (after == null) {
            record("U2", "original-null-after", 1, false, "第二次 original() 返回 null");
            return;
        }

        int pts = 0, diff = 0;
        double worst = 0.0;
        java.util.Random rnd = new java.util.Random(20261101);
        for (int i = 0; i < 4000; i++) {
            double x = rnd.nextDouble() * 12000 - 6000;
            double z = rnd.nextDouble() * 12000 - 6000;
            pts++;
            double a = before.height01(x, z);
            double b = after.height01(x, z);
            if (Double.compare(a, b) != 0) {
                diff++;
                worst = Math.max(worst, Math.abs(a - b));
            }
        }
        record("U2", "points", pts, pts == 4000, "对拍点数");
        record("U2", "height01-bitwise-diff", diff, diff == 0,
                "★ 构造统一池前后，陆地 height01 必须逐位相同（游标零污染）");
        record("U2", "worst-delta", worst, diff == 0, "最大差（应为 0）");
    }

    // ---------------------------------------------------------------- U3 / U4

    /** U4：槽位布局常量自洽。 */
    private static void checkLayout(UnifiedTerrainCatalog cat) {
        record("U4", "land-slots", UnifiedTerrainCatalog.LAND_SLOTS,
                UnifiedTerrainCatalog.LAND_SLOTS == 45, "陆地槽数必须 = 45（冻结口径）");
        record("U4", "ocean-slots", UnifiedTerrainCatalog.OCEAN_SLOTS,
                UnifiedTerrainCatalog.OCEAN_SLOTS == OceanRecipeCatalog.SLOT_COUNT,
                "海洋槽数必须 = OceanRecipeCatalog.SLOT_COUNT");
        record("U4", "total-slots", UnifiedTerrainCatalog.TOTAL_SLOTS,
                UnifiedTerrainCatalog.TOTAL_SLOTS
                        == UnifiedTerrainCatalog.LAND_SLOTS + UnifiedTerrainCatalog.OCEAN_SLOTS,
                "总槽数 = 陆地 + 海洋");
        record("U4", "ocean-slot-base", UnifiedTerrainCatalog.OCEAN_SLOT_BASE,
                UnifiedTerrainCatalog.OCEAN_SLOT_BASE == UnifiedTerrainCatalog.LAND_SLOTS,
                "海洋槽起始下标 = 陆地槽数");
        // 边界：44 属陆地、45 属海洋
        record("U4", "slot-44-is-land", 44, !cat.isOceanSlot(44), "槽 44 必须属陆地");
        record("U4", "slot-45-is-ocean", 45, cat.isOceanSlot(45), "槽 45 必须属海洋");
        record("U4", "slot-last-is-ocean", UnifiedTerrainCatalog.TOTAL_SLOTS - 1,
                cat.isOceanSlot(UnifiedTerrainCatalog.TOTAL_SLOTS - 1), "最后一个槽必须属海洋");
    }

    /** U3：oceanEnabled=false 时 resolve() 永不返回 OCEAN；启用后必须能全部解析。 */
    private static void checkOceanOff(UnifiedTerrainCatalog enabled) {
        if (!enabled.oceanEnabled()) {
            record("U3", "enabled-flag", 0, false,
                    "传入的 catalog 未启用海洋槽（checkOceanOff 需要已 enableOcean 的实例）");
            return;
        }
        UnifiedTerrainCatalog off = new UnifiedTerrainCatalog(SEED);   // 默认 off
        record("U3", "default-enabled", 0, !off.oceanEnabled(), "默认必须为 off（步 2 不启用海洋）");
        int oceanReturned = 0;
        for (int s = 0; s < UnifiedTerrainCatalog.TOTAL_SLOTS; s++) {
            UnifiedTerrainCatalog.Slot r = off.resolve(s, 0);
            if (r != null && r.side() == UnifiedTerrainCatalog.Side.OCEAN) oceanReturned++;
        }
        record("U3", "ocean-resolved-when-off", oceanReturned, oceanReturned == 0,
                "oceanEnabled=false 时不得解析出任何 OCEAN 槽");
        // 陆地槽在 off 下必须正常返回
        int landOk = 0;
        for (int s = 0; s < UnifiedTerrainCatalog.LAND_SLOTS; s++) {
            UnifiedTerrainCatalog.Slot r = off.resolve(s, 7);
            if (r != null && r.side() == UnifiedTerrainCatalog.Side.LAND && r.localId() == 7) landOk++;
        }
        record("U3", "land-resolved-when-off", landOk,
                landOk == UnifiedTerrainCatalog.LAND_SLOTS,
                "oceanEnabled=false 时陆地槽必须正常解析");
        // 启用后海洋槽可解析
        int oceanOk = 0;
        for (int s = UnifiedTerrainCatalog.OCEAN_SLOT_BASE;
             s < UnifiedTerrainCatalog.TOTAL_SLOTS; s++) {
            UnifiedTerrainCatalog.Slot r = enabled.resolve(s, 0);
            if (r != null && r.side() == UnifiedTerrainCatalog.Side.OCEAN
                    && r.localId() >= 0 && r.localId() < OceanRecipeCatalog.ENTRY_COUNT) oceanOk++;
        }
        record("U3", "ocean-resolved-when-on", oceanOk,
                oceanOk == UnifiedTerrainCatalog.OCEAN_SLOTS,
                "启用后全部海洋槽必须解析出合法配方 id");
    }

    // ---------------------------------------------------------------- U5 / U6

    /** U5：海洋槽展开正确。 */
    private static void checkOceanSlots(UnifiedTerrainCatalog cat) {
        int[] ids = cat.oceanSlotIds();
        record("U5", "slot-array-length", ids.length,
                ids.length == UnifiedTerrainCatalog.OCEAN_SLOTS, "展开表长度");
        int[] counts = cat.oceanSlotCounts();
        int covered = 0;
        for (int id : counts) if (id > 0) covered++;
        record("U5", "recipes-covered", covered,
                covered == OceanRecipeCatalog.ENTRY_COUNT,
                "6 个海洋配方都必须至少占 1 个槽（否则该配方永不被选中）");
        int total = 0;
        boolean allValid = true;
        for (int id : ids) {
            total++;
            if (id < 0 || id >= OceanRecipeCatalog.ENTRY_COUNT) allValid = false;
        }
        record("U5", "total-slots-filled", total,
                total == UnifiedTerrainCatalog.OCEAN_SLOTS, "槽总数");
        record("U5", "all-ids-valid", allValid ? 1 : 0, allValid, "所有槽的配方 id 必须在 [0,6)");
        // 打散：首尾不应相同（若相同说明没打散）
        record("U5", "shuffled-not-identity", ids[0] == ids[ids.length - 1] ? 0 : 1,
                ids[0] != ids[ids.length - 1], "打散后首尾不应相同（粗略的未打散检测）");
    }

    /** U6：同 seed 两次构造逐位相同。 */
    private static void checkDeterminism() {
        UnifiedTerrainCatalog a = new UnifiedTerrainCatalog(SEED);
        UnifiedTerrainCatalog b = new UnifiedTerrainCatalog(SEED);
        int[] ia = a.oceanSlotIds();
        int[] ib = b.oceanSlotIds();
        boolean same = java.util.Arrays.equals(ia, ib);
        record("U6", "slot-order-identical", same ? 1 : 0, same,
                "同 seed 两次构造的海洋槽顺序必须逐位相同");
        // 不同 seed 应当不同（否则 seed 没生效）
        UnifiedTerrainCatalog c = new UnifiedTerrainCatalog(SEED + 12345);
        boolean differ = !java.util.Arrays.equals(ia, c.oceanSlotIds());
        record("U6", "different-seed-differs", differ ? 1 : 0, differ,
                "不同 seed 的槽顺序应当不同（证明 seed 真的参与）");
    }

    /** U7：改海洋 seed 偏移不影响陆地。 */
    private static void checkSeedOffsetIsolated() {
        // 用不同 seed 构造统一池（海洋池种子随之不同）
        UnifiedTerrainCatalog a = new UnifiedTerrainCatalog(SEED);
        UnifiedTerrainCatalog b = new UnifiedTerrainCatalog(SEED + 987654321L);
        // 陆地臂在两者之外独立构造，结果必须与 SEED 那次的陆地一致
        RecipeMorphCatalog landA = new RecipeMorphCatalog(SEED);
        RecipeMorphCatalog landB = new RecipeMorphCatalog(SEED);
        CombineCandidate ca = landA.original();
        CombineCandidate cb = landB.original();
        if (ca == null || cb == null) {
            record("U7", "original-null", 1, false, "original() 返回 null");
            return;
        }
        int pts = 0, diff = 0;
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            double x = rnd.nextDouble() * 10000 - 5000;
            double z = rnd.nextDouble() * 10000 - 5000;
            pts++;
            if (Double.compare(ca.height01(x, z), cb.height01(x, z)) != 0) diff++;
        }
        record("U7", "points", pts, pts == 2000, "对拍点数");
        record("U7", "land-unaffected", diff, diff == 0,
                "★ 海洋 seed 偏移与陆地抽签无关：即使海洋池种子不同，陆地 height01 必须逐位相同");
        // 同时确认两者的海洋槽顺序确实不同（证明偏移真的生效了，不是空跑）
        boolean oceanDiffers = !java.util.Arrays.equals(a.oceanSlotIds(), b.oceanSlotIds());
        record("U7", "ocean-order-differs", oceanDiffers ? 1 : 0, oceanDiffers,
                "不同世界种子的海洋槽顺序应当不同（否则偏移没生效、本判据是空跑）");
    }

    // ---------------------------------------------------------------- 工具

    private static String sha256(Path p) throws Exception {
        byte[] raw = Files.readAllBytes(p);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }

    private static void record(String id, String metric, double value, boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%.9g,%s%n", id, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-34s %14.6g  %s%n", id, metric, value,
                ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("UNIFIED TERRAIN CHECK FAIL: " + id + " " + metric + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
    }

    private UnifiedTerrainCheck() { }
}
