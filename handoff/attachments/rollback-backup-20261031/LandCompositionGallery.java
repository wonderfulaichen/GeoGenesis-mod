package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainCharacterField;
import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.TypeLandShape;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 陆地类型<b>合成</b>出图：现行生产合成 vs 新合成（完整配方加权）。
 *
 * <p>产出（同一窗口、同一 seed，可直接并排比）：</p>
 * <ul>
 *   <li>{@code compose-production.f64be.raw} —— 现行 {@link TypeLandShape} 的 eLand 落成 Y。</li>
 *   <li>{@code compose-recipe.f64be.raw} / {@code compose-recipe-sharp.f64be.raw} —— 新合成两种锐化度。</li>
 *   <li>{@code types-<arm>.u8} —— 每像素主导类型下标（0..4 = BASIN/PLAIN/HILLS/PLATEAU/MOUNTAINS），
 *       用来单独审视权重本身是否合理（面积、边界形状），不受配方形态干扰。</li>
 * </ul>
 *
 * <p>用法：{@code <toml> <outDir> <size> <stepBlocks> <cx> <cz>}</p>
 */
public final class LandCompositionGallery {

    private static final long SEED = 9139912035078620160L;

    private LandCompositionGallery() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 6) {
            throw new IllegalArgumentException("usage: <toml> <outDir> <size> <stepBlocks> <cx> <cz>");
        }
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int size = Integer.parseInt(args[2]);
        int step = Integer.parseInt(args[3]);
        double cx = Double.parseDouble(args[4]);
        double cz = Double.parseDouble(args[5]);
        Files.createDirectories(out);

        Path copy = out.resolve("config-input.toml");
        Files.copy(toml, copy, StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) {
            throw new IllegalStateException("frozen TOML unexpectedly requires correction");
        }
        GeoGenesisConfig.SPEC.setConfig(cfg);
        TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
        HeightCurve curve = new HeightCurve(params, -64, 320);

        TypeLandShape production = new TypeLandShape(params);
        production.seed(SEED);

        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        TerrainCharacterField character = new TerrainCharacterField(continent, params.continentBias());
        character.seed(SEED);

        // 复刻臂：参考的组装方式（区域划分 + 公共边界过渡），不是加权混合。
        RegionCompositionCandidate regionRef = new RegionCompositionCandidate();
        regionRef.seed(SEED);

        // 完整复刻臂：combine 两两复合 + worleyEdge 山形遮罩覆盖层。
        CombineCandidate combine = buildCombine(regionRef);

        double originX = cx - (size / 2) * (double) step;
        double originZ = cz - (size / 2) * (double) step;

        arm("compose-production", out, size, step, cx, cz, curve,
                (x, z) -> {
                    TerrainCharacterField.BlendResult b = production.sampleBlend(x, z);
                    double c = production.sample(b, x, z);
                    return curve.heightFromE(normalize(c));
                },
                (x, z) -> production.sampleBlend(x, z).dominantType);

        arm("compose-region-ref", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(regionRef.e(x, z)),
                (x, z) -> slotToClass(regionRef.debugSlot(x, z)));

        arm("compose-blender", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(regionRef.eBlender(x, z)),
                (x, z) -> slotToClass(regionRef.blenderSlot(x, z)));

        arm("compose-combine", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(combine.absoluteE(x, z)),
                (x, z) -> combineSlotToClass(combine.category(x, z)));

        // 补齐共享地面基准后的最终口径：e = GROUND_E + h·E_SPAN，
        // 族输出被当作"相对基准的增量"（等价 TerrainPopulator 的 max(base+height, 0)）。
        arm("compose-ground-base", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(combine.e(x, z)),
                (x, z) -> combineSlotToClass(combine.category(x, z)));

        // 第 6 步：海陆衔接（ContinentLerper3 + ContinentLerper2）
        OceanAssemblyCandidate ocean = new OceanAssemblyCandidate(combine, continent, SEED);
        arm("compose-ocean", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(ocean.e(x, z)),
                (x, z) -> combineSlotToClass(combine.category(x, z)));

        // 方案 C 的 A/B 对照：同一标定与海陆，只把"全部参与复合"换回"只复合低地"
        CombineCandidate allMixed = buildCombineAllMixed(SEED);
        OceanAssemblyCandidate oceanAll = new OceanAssemblyCandidate(allMixed, continent, SEED + 1);
        arm("compose-ocean-allmixed", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanAll.e(x, z)),
                (x, z) -> combineSlotToClass(allMixed.category(x, z)));

        // 构造偏置臂：σ=80 的 orogen/rift 剖面按连续强度插值到山地/盆地高度
        CombineCandidate tectonic = buildCombineTectonic(SEED);
        OceanAssemblyCandidate oceanTect = new OceanAssemblyCandidate(tectonic, continent, SEED);
        arm("compose-tectonic", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanTect.e(x, z)),
                (x, z) -> combineSlotToClass(tectonic.category(x, z)));

        // 连带放置臂：格子 id 改为低频噪声（相邻格高概率同类型），带沿 x 拉长
        CombineCandidate coherent = buildCombineCoherent(SEED, 1800f, 0.42f, 1f);
        OceanAssemblyCandidate oceanCoh = new OceanAssemblyCandidate(coherent, continent, SEED);
        arm("compose-coherent", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanCoh.e(x, z)),
                (x, z) -> combineSlotToClass(coherent.category(x, z)));

        // 边界反转臂：RegionLerper 的边界地形从平原换成山地（worldgen 语义）
        CombineCandidate inverted = buildCombineInverted(SEED);
        OceanAssemblyCandidate oceanInv = new OceanAssemblyCandidate(inverted, continent, SEED + 2);
        arm("compose-inverted", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanInv.e(x, z)),
                (x, z) -> combineSlotToClass(inverted.category(x, z)));

        // 按区域概率选择边界模式：成片的单元用"边界=山地"，其余保持"边界=平原"
        CombineCandidate regional = buildCombineRegional(SEED, 3600f);
        OceanAssemblyCandidate oceanReg = new OceanAssemblyCandidate(regional, continent, SEED + 3);
        arm("compose-regional", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanReg.e(x, z)),
                (x, z) -> combineSlotToClass(regional.category(x, z)));

        // 同一陆地，但海侧走"三段海岸 + cliffFactor + 水下细节带"
        OceanAssemblyCandidate oceanCoast = new OceanAssemblyCandidate(regional, continent, SEED + 3);
        oceanCoast.enableCoastalDetail(SEED);
        arm("compose-coastal", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanCoast.e(x, z)),
                (x, z) -> combineSlotToClass(regional.category(x, z)));

        // ★ 浅海群岛：地基(FBM 平方) + 类型地形 + 离岸过渡 + 纯加法叠到 compose-coastal 上。
        //   对照臂 compose-islands-only 只画抬升增量本身（0 = 无群岛），用来单独看地基与类型分布。
        ShallowSeaIslandCandidate islands = new ShallowSeaIslandCandidate(SEED + 900);
        ShallowSeaIslandCandidate.Seabed seabed = oceanCoast::e;
        ShallowSeaIslandCandidate.OffshoreSampler offshore = continent::sample;
        arm("compose-coastal-islands", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(islands.apply(seabed, offshore, x, z)),
                (x, z) -> islandClass(islands, seabed, offshore, x, z),
                (x, z) -> islandClassAlways(islands, x, z));

        // ★ 类型过渡 A/B：同一类型分布（RegionPartition + slots 完全不变），
        //   只把 edge 上的插值从【线性】换成【Cubic Hermite 样条】。
        //   三臂对照 d0=d1 三档，看"两端收坡"对过渡带观感的影响。
        for (double[] dd : new double[][]{{0.0, 0.0}, {0.5, 0.5}, {1.5, 1.5}}) {
            String tag = String.format(java.util.Locale.ROOT, "d%.1f", dd[0]).replace('.', 'p');
            CombineCandidate splineArm = buildCombineRegional(SEED, 3600f);
            splineArm.useSplineTransition(dd[0], dd[1]);
            OceanAssemblyCandidate oceanSp = new OceanAssemblyCandidate(splineArm, continent, SEED + 3);
            oceanSp.enableCoastalDetail(SEED);
            arm("compose-spline-" + tag, out, size, step, cx, cz, curve,
                    (x, z) -> curve.heightFromE(oceanSp.e(x, z)),
                    (x, z) -> combineSlotToClass(splineArm.category(x, z)));
        }

        // ★★ 包络过渡：两类型之间插值 lo/hi 包络，共用噪声，平原 border 不参与。
        //    这是用户 2026-10-31 指出的正确做法 —— 旧版是"类型 ↔ 公共平原"过渡，
        //    所有类型交界都退化成平原；新版是"边界两侧真实两类型"之间过渡。
        CombineCandidate envelopeArm = buildCombineRegional(SEED, 3600f);
        envelopeArm.useEnvelopeTransition();
        OceanAssemblyCandidate oceanEnv = new OceanAssemblyCandidate(envelopeArm, continent, SEED + 3);
        oceanEnv.enableCoastalDetail(SEED);
        arm("compose-envelope", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanEnv.e(x, z)),
                (x, z) -> combineSlotToClass(envelopeArm.category(x, z)));

        // ★★★ 生产 WIE 加权混合（沙箱复刻）：高斯权重场 + 4 频共享噪声 + 差异调制。
        //     依据生产 TypeLandShape.java:213-244 / TerrainCharacterField:136-193
        //         / TypeGenerators:124-139。
        //     与 compose-envelope 的区别：这里是【全类型加权混合】（生产做法），
        //     不是"两类型之间插值"。
        PlainsCandidate wPlains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        HillsCandidate wHills = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        PlateauCandidate wPlateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        MountainsCandidate wMtn = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        wPlains.seed(SEED);
        wHills.seed(SEED + 11);
        wPlateau.seed(SEED + 44);
        wMtn.seed(SEED + 77);
        CombineCandidate.Family[] wieFamilies = {
                wPlains::height01, wHills::height01, wPlateau::height01, wMtn::height01
        };
        WeightFieldCandidate wieWeights = new WeightFieldCandidate(SEED);
        SharedNoiseCandidate wieShared =
                new SharedNoiseCandidate((int) (SEED ^ (SEED >>> 32)) + 800);

        // WIE 输出 h01 → e（与 CombineCandidate 同口径：GROUND_E + h·E_SPAN）
        // 三档 BLEND_SHARPEN A/B：生产定稿 1.5，原版曾用 3.0（因"硬阶梯"降下来）。
        // 沙箱 4 类 + 实测包络（MOUNTAINS hi=0.617 vs 生产 0.95）⇒ 需重标。
        for (double sharpen : new double[]{1.5, 3.0, 5.0}) {
            String tag = String.format(java.util.Locale.ROOT, "s%.1f", sharpen).replace('.', 'p');
            WieBlendCandidate wie = new WieBlendCandidate(wieWeights, wieShared, wieFamilies, sharpen);
            OceanAssemblyCandidate.HeightFn wieE = (x, z) ->
                    CombineCandidate.GROUND_E + wie.sample(x, z) * CombineCandidate.E_SPAN;
            OceanAssemblyCandidate oceanWie = new OceanAssemblyCandidate(wieE, continent::sample, SEED + 3);
            oceanWie.enableCoastalDetail(SEED);
            final WieBlendCandidate wieRef = wie;
            arm("compose-wie-" + tag, out, size, step, cx, cz, curve,
                    (x, z) -> curve.heightFromE(oceanWie.e(x, z)),
                    (x, z) -> wieClass(wieRef, x, z));
        }
        arm("compose-islands-only", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(islands.delta(seabed, offshore, x, z)),
                (x, z) -> islandClassAlways(islands, x, z));

        // ★ 大陆层替换：Voronoi 领地（65% 陆地，锚点 0.0445）—— 已否决，仅保留作对照
        ProvinceContinentField province = new ProvinceContinentField(SEED);
        OceanAssemblyCandidate oceanProv = new OceanAssemblyCandidate(regional, province, SEED + 3);
        oceanProv.enableCoastalDetail(SEED);
        arm("compose-province", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanProv.e(x, z)),
                (x, z) -> combineSlotToClass(regional.category(x, z)));

        // 同一地形，但类型图补上构造带宽判据
        arm("compose-regional-typed", out, size, step, cx, cz, curve,
                (x, z) -> curve.heightFromE(oceanReg.e(x, z)),
                (x, z) -> combineSlotToClass(regional.categoryWithBorders(x, z)));

        writeGeometry(out, size, step, cx, cz, originX, originZ);
        System.out.println("out=" + out.toAbsolutePath());
    }

    /**
     * 按区域概率选择边界模式：模式场相关长度 3600wu（≈3 个格距），
     * 使成片的 Voronoi 单元共享"边界=山地"，其余保持参考原样。
     */
    private static CombineCandidate buildCombineRegional(long seed, float modeScale) {
        CombineCandidate c = buildCombine(null);
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m1.seed(seed + 77);
        c.regionalBorder(m1::height01, modeScale);
        return c;
    }

    /**
     * 边界反转臂：与 {@link #buildCombine} 完全同配置，唯一差别是
     * {@code RegionLerper} 的 border 从平原换成山地 —— 即 worldgen-master
     * {@code elevation.rs:279} 的语义（边界处最高）。
     */
    private static CombineCandidate buildCombineInverted(long seed) {
        CombineCandidate c = buildCombine(null);
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m1.seed(seed + 77);
        c.invertBorder(m1::height01);
        return c;
    }

    /** 与 {@link #buildCombine} 同配置，但格子 id 改由低频噪声给出（连带成带）。 */
    private static CombineCandidate buildCombineCoherent(long seed, float scale,
                                                         float anisoX, float anisoZ) {
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate h1 = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        HillsCandidate h2 = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        HillsCandidate dales = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        BadlandsCandidate badlands = new BadlandsCandidate();
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        plains.seed(seed);
        steppe.seed(seed);
        h1.seed(seed + 11);
        h2.seed(seed + 22);
        dales.seed(seed + 33);
        plateau.seed(seed + 44);
        badlands.seed(seed + 55);
        m1.seed(seed + 77);

        CombineCandidate.Family[] mixable = {
                plains::height01, steppe::height01, dales::height01,
                h1::height01, h2::height01
        };
        CombineCandidate.Category[] mixCats = {
                CombineCandidate.Category.PLAIN, CombineCandidate.Category.PLAIN,
                CombineCandidate.Category.HILLS, CombineCandidate.Category.HILLS,
                CombineCandidate.Category.HILLS
        };
        float[] mixWeights = {2.0f, 1.0f, 1.5f, 2.0f, 2.0f};

        CombineCandidate.Family[] unmixable = {
                plateau::height01, badlands::height01, m1::height01
        };
        CombineCandidate.Category[] unmixCats = {
                CombineCandidate.Category.PLATEAU, CombineCandidate.Category.BADLANDS,
                CombineCandidate.Category.MOUNTAINS
        };
        float[] unmixWeights = {5.0f, 3.0f, 5.0f};

        return new CombineCandidate(seed, mixable, mixCats, mixWeights,
                unmixable, unmixCats, unmixWeights, borderRecipe(),
                RegionPartition.IdMode.COHERENT, scale, anisoX, anisoZ);
    }

    /**
     * 与 {@link #buildCombine} 同配置，但额外启用 σ=80 的 orogen/rift 构造偏置。
     *
     * <p><b>2026-10-30</b>：盆地族已删除（参考无对应，自创），rift 端原来接
     * {@code basinNorm}，现改用<b>低地组复合</b>作对照端 —— 这保留"构造低地"的语义，
     * 但不再依赖自创的盆地配方。</p>
     */
    private static CombineCandidate buildCombineTectonic(long seed) {
        CombineCandidate c = buildCombine(null);
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        m1.seed(seed + 77);
        plains.seed(seed);
        com.geogenesis.worldgen.terrain.TectonicField field =
                new com.geogenesis.worldgen.terrain.TectonicField(seed);
        // rift 端：低地（平原压到 0.35，与盆地原本在族序中的低位相当）
        c.enableTectonicBias(field, m1::height01, (x, z) -> plains.height01(x, z) * 0.35);
        return c;
    }

    /**
     * 合成高度 0..1 → e。
     *
     * <p>区间取 [-0.06, +0.90]：下界略低于海平面（盆地能凹到水下成湖），上界对应
     * 理论峰顶附近的 e，使 {@code peakHeightFraction} 仍有意义。这是<b>沙箱取用的区间</b>，
     * 不是生产默认值。</p>
     */
    private static double toE(double h01) {
        return -0.06 + h01 * 0.96;
    }

    /** 现行生产的 eLand 本来就在近似 [-0.35, 1] 里，这里只做显示用截断。 */
    private static double normalize(double e) {
        return e;
    }

    /**
     * 按参考 {@code TerrainProvider.generateTerrain} 的阵容搭出 combine 臂。
     *
     * <p>参考的 8 个 mixable 是 steppe/plains/dales/hills1/hills2/torridonian/plateau/badlands，
     * unmixable 是 badlands + mountains1..3 + volcano。我们用已定稿的族顶替：
     * 平原→plains-geo、草原→steppe、谷地→dales-geo、丘陵→hills1-geo/hills2、
     * 台地→plateau、劣地→badlands、盆地→B1（参考无对应，作为额外 mixable）。</p>
     */
    private static CombineCandidate buildCombine(RegionCompositionCandidate src) {
        long seed = SEED;
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate h1 = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        HillsCandidate h2 = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        HillsCandidate dales = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        BadlandsCandidate badlands = new BadlandsCandidate();
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        plains.seed(seed);
        steppe.seed(seed);
        h1.seed(seed + 11);
        h2.seed(seed + 22);
        dales.seed(seed + 33);
        plateau.seed(seed + 44);
        badlands.seed(seed + 55);
        m1.seed(seed + 77);

        // 权重取自 Presets.java:37-45（默认预设）：
        //   steppe 1.0 · plains 2.0 · hills 2.0 · dales 1.5 · plateau 1.5
        //   badlands 1.0 · torridonian 2.0 · mountains 2.5 · volcano 5.0
        //
        // 【方案 C】mixable 只保留低地组（平原/草原/谷地/丘陵），其余作为独立条目。
        // 理由：combine 会把两个族的形态取中值，把山地/台地/劣地的极端形态抹平。
        // 低地组之间形态差异小（都是缓起伏），复合反而是有益的"质地多样"。
        CombineCandidate.Family[] mixable = {
                plains::height01, steppe::height01, dales::height01,
                h1::height01, h2::height01
        };
        CombineCandidate.Category[] mixCats = {
                CombineCandidate.Category.PLAIN, CombineCandidate.Category.PLAIN,
                CombineCandidate.Category.HILLS, CombineCandidate.Category.HILLS,
                CombineCandidate.Category.HILLS
        };
        float[] mixWeights = {2.0f, 1.0f, 1.5f, 2.0f, 2.0f};

        // 独立条目：台地/劣地/山地 —— 不进复合，保留各自的原生形态。
        CombineCandidate.Family[] unmixable = {
                plateau::height01, badlands::height01, m1::height01
        };
        CombineCandidate.Category[] unmixCats = {
                CombineCandidate.Category.PLATEAU, CombineCandidate.Category.BADLANDS,
                CombineCandidate.Category.MOUNTAINS
        };
        // 【方案 C1】权重按票数反推，不是拍脑袋：
        //   combine 把 5 个低地原始扩成 15 条（5 原始 + 10 复合），票数从 9 涨到 28，
        //   独立三类只有 13 票 ⇒ 低地占 68%，台地/劣地被压到个位数。
        //   目标配比：低地 62%、台地 11%、劣地 7%、山地 11%（盆地已删，其 9% 按比例并入）。
        //   总票数 = 28 / 0.62 ≈ 45 ⇒ 独立三类需 17 票：
        //   台地 5、劣地 3、山地 9（山地面积另受山形遮罩主导，见 Blender.category）。
        float[] unmixWeights = {5.0f, 3.0f, 9.0f};

        // 边界地形 = makeBorder（Populators.java:419-421）= makePlains(plains 噪声, steppe 缩放)
        return new CombineCandidate(seed, mixable, mixCats, mixWeights,
                unmixable, unmixCats, unmixWeights, borderRecipe());
    }

    /** 旧方案（全部 8 类都参与复合），仅用于 A/B 对照。 */
    private static CombineCandidate buildCombineAllMixed(long seed) {
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate h1 = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        HillsCandidate h2 = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        HillsCandidate dales = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        BadlandsCandidate badlands = new BadlandsCandidate();
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        plains.seed(seed);
        steppe.seed(seed);
        h1.seed(seed + 11);
        h2.seed(seed + 22);
        dales.seed(seed + 33);
        plateau.seed(seed + 44);
        badlands.seed(seed + 55);
        m1.seed(seed + 77);

        CombineCandidate.Family[] mixable = {
                plains::height01, steppe::height01, dales::height01,
                h1::height01, h2::height01, plateau::height01,
                badlands::height01
        };
        CombineCandidate.Category[] mixCats = {
                CombineCandidate.Category.PLAIN, CombineCandidate.Category.PLAIN,
                CombineCandidate.Category.HILLS, CombineCandidate.Category.HILLS,
                CombineCandidate.Category.HILLS, CombineCandidate.Category.PLATEAU,
                CombineCandidate.Category.BADLANDS
        };
        float[] mixWeights = {2.0f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f};
        CombineCandidate.Family[] unmixable = {m1::height01};
        CombineCandidate.Category[] unmixCats = {CombineCandidate.Category.MOUNTAINS};
        float[] unmixWeights = {2.5f};
        return new CombineCandidate(seed, mixable, mixCats, mixWeights,
                unmixable, unmixCats, unmixWeights, borderRecipe());
    }

    /**
     * {@code makeBorder}（{@code Populators.java:419-421}）：
     * {@code makePlains(seed, ground, plainsSettings, steppeSettings, verticalScale)} ——
     * <b>地貌噪声取 plains 设置、缩放系数取 steppe 设置</b>。
     *
     * <p>参考里 plains 的 {@code baseScale=2.0}、steppe 的 {@code baseScale=1.0}，
     * 所以边界地形的共享基准只有普通平原的一半高，正好把区域边缘的落差压下来。
     * 上一版直接用普通平原当边界地形，导致每个区域被一圈深沟包围。</p>
     */
    /**
     * {@code makeBorder}（{@code Populators.java:419-421}）：
     * <pre>
     * makeBorder(seed, ground, plainsSettings, steppeSettings, verticalScale)
     *   → makePlains(seed, ground, noiseSettings=plainsSettings,
     *                scalingSettings=steppeSettings, verticalScale)
     * </pre>
     * <p>{@code makePlains} 内部（{@code Populators.java:95-113}）：</p>
     * <ul>
     *   <li>{@code noiseSettings} 只决定噪声尺度（{@code scaleH = 250 × horizontalScale}）</li>
     *   <li>{@code scalingSettings} 决定 {@code baseScale} / {@code verticalScale}
     *       （传给 {@code TerrainPopulator.make}）</li>
     * </ul>
     *
     * <h3>★ 2026-10-30 修正：{@code ×0.5} 去掉</h3>
     * <p>此前返回 {@code plains.height01 * 0.5}，注释称"steppe 的缩放＝基准 ×0.5（2.0 → 1.0）"。
     * 核对两个预设的真实数值后发现<b>那是 legacy 的系数</b>：</p>
     * <pre>
     *   RTF 默认预设（Presets.java:35-46）  steppe baseScale=2.0, plains baseScale=2.0 ⇒ ×1.0
     *   legacy 预设  （Presets.java:81-92）  steppe baseScale=1.0, plains baseScale=2.0 ⇒ ×0.5
     * </pre>
     * <p>本沙箱对齐的是 <b>RTF 默认预设</b>（{@code makeRTFDefault}），所以边界地形
     * 应当就是 {@code plains.height01}，<b>不乘任何系数</b>。</p>
     *
     * <h3>影响量化（{@code BorderScaleImpactProbe}，30 万样本）</h3>
     * <pre>
     *   边界带面积（edge&lt;0.999）: 70.4%
     *   mean|ΔY|                 : 2.51 格
     *   max|ΔY|                  : 18.39 格
     *   |ΔY|&gt;1 的像素            : 55.4%
     *   |ΔY|&gt;5 的像素            : 19.9%
     * </pre>
     * <p>幅度可控：边界带抬升但不失控。</p>
     */
    private static CombineCandidate.Family borderRecipe() {
        PlainsCandidate noise = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        noise.seed(SEED);
        // 参考 RTF 默认预设：steppe 与 plains 的 baseScale 同为 2.0 ⇒ 边界地形 = plains.height01
        return noise::height01;
    }

    private static TerrainClass combineSlotToClass(CombineCandidate.Category c) {
        return switch (c) {
            case BASIN -> TerrainClass.BASIN;
            case HILLS -> TerrainClass.HILLS;
            case PLATEAU -> TerrainClass.PLATEAU;
            case MOUNTAINS -> TerrainClass.MOUNTAINS;
            default -> TerrainClass.PLAIN;
        };
    }

    /** 区域槽位 → 五类陆地类型（类型图用）。 */
    private static TerrainClass slotToClass(int slot) {
        return switch (RegionCompositionCandidate.slotKind(slot)) {
            case "basin" -> TerrainClass.BASIN;
            case "hills1", "hills2", "dales" -> TerrainClass.HILLS;
            case "plateau" -> TerrainClass.PLATEAU;
            case "m1", "m2", "m3" -> TerrainClass.MOUNTAINS;
            default -> TerrainClass.PLAIN;
        };
    }

    private interface HeightFn {
        double at(double x, double z);
    }

    private interface TypeFn {
        TerrainClass at(double x, double z);
    }

    private interface IslandClassFn {
        TerrainClass at(double x, double z);
    }

    /** WIE 主导类型下标 → 五类陆地类型（沙箱已删盆地，故无 BASIN 分支）。 */
    private static TerrainClass wieClass(WieBlendCandidate wie, double x, double z) {
        return switch (wie.dominantType(x, z)) {
            case WeightFieldCandidate.T_PLAIN -> TerrainClass.PLAIN;
            case WeightFieldCandidate.T_HILLS -> TerrainClass.HILLS;
            case WeightFieldCandidate.T_PLATEAU -> TerrainClass.PLATEAU;
            default -> TerrainClass.MOUNTAINS;
        };
    }

    /**
     * 海岛臂的类型：陆地判据与水面一致（读<b>实际高度</b>），抬出水面才记岛的类型，
     * 否则沿用陆地层原本的类型（海侧本来就会被抹成 PLAIN 位，与其它臂口径相同）。
     */
    private static TerrainClass islandClass(ShallowSeaIslandCandidate islands,
                                            ShallowSeaIslandCandidate.Seabed seabed,
                                            ShallowSeaIslandCandidate.OffshoreSampler offshore,
                                            double x, double z) {
        if (!islands.emerges(seabed, offshore, x, z)) return TerrainClass.PLAIN;
        return ShallowSeaIslandCandidate.terrainClassOf(islands.type(x, z));
    }

    /** 只看地基分布用：无视是否出水，直接按档位记类型（数清抬升带的形状与面积）。 */
    private static TerrainClass islandClassAlways(ShallowSeaIslandCandidate islands,
                                                  double x, double z) {
        return ShallowSeaIslandCandidate.terrainClassOf(islands.type(x, z));
    }

    private static void arm(String id, Path out, int size, int step, double cx, double cz,
                            HeightCurve curve, HeightFn height, TypeFn type) throws Exception {
        arm(id, out, size, step, cx, cz, curve, height, type, null);
    }

    private static void arm(String id, Path out, int size, int step, double cx, double cz,
                            HeightCurve curve, HeightFn height, TypeFn type,
                            IslandClassFn islandType) throws Exception {
        double[] ys = new double[size * size];
        byte[] types = new byte[size * size];
        long[] islandCount = new long[5];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int z = 0; z < size; z++) {
            double wz = cz + (z - size / 2.0) * step;
            for (int x = 0; x < size; x++) {
                double wx = cx + (x - size / 2.0) * step;
                double y = height.at(wx, wz);
                if (!Double.isFinite(y)) throw new AssertionError("non-finite height at " + wx + "," + wz);
                ys[z * size + x] = y;
                if (y < lo) lo = y;
                if (y > hi) hi = y;
                types[z * size + x] = (byte) typeIndex(type.at(wx, wz));
                if (islandType != null) {
                    switch (islandType.at(wx, wz)) {
                        case PLAIN -> islandCount[1]++;
                        case HILLS -> islandCount[2]++;
                        case MOUNTAINS -> islandCount[3]++;
                        default -> islandCount[0]++;
                    }
                }
            }
        }
        try (DataOutputStream s = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out.resolve(id + ".f64be.raw")), 1 << 16))) {
            for (double v : ys) s.writeDouble(v);
        }
        Files.write(out.resolve("types-" + id + ".u8"), types);
        System.out.printf(Locale.ROOT, "%-22s Y=%.2f..%.2f relief=%.2f%n", id, lo, hi, hi - lo);
        if (islandType != null) {
            double total = (double) size * size;
            System.out.printf(Locale.ROOT,
                    "  islands: none=%.1f%% plain=%.1f%% hills=%.1f%% mountains=%.1f%%%n",
                    islandCount[0] * 100 / total, islandCount[1] * 100 / total,
                    islandCount[2] * 100 / total, islandCount[3] * 100 / total);
        }
    }

    /**
     * 主导类型 → 0..4。
     *
     * <p>生产轨道上 {@code dominantType} 可能返回海洋或水域类型（PLAIN 位兜底）；
     * 这里统一折到五个陆地类，海洋/未归类一律记为 PLAIN 位，避免类型图上出现第六色。</p>
     */
    private static int typeIndex(TerrainClass c) {
        return switch (c) {
            case BASIN -> 0;
            case PLAIN -> 1;
            case HILLS -> 2;
            case PLATEAU -> 3;
            case MOUNTAINS -> 4;
            default -> 1;
        };
    }

    private static void writeGeometry(Path out, int size, int step, double cx, double cz,
                                      double originX, double originZ) throws Exception {
        // ★ 动态列出实际产出的臂（archOnly 模式只产一个群岛臂，写死列表会让
        //   绘图脚本去读不存在的文件）
        StringBuilder arms = new StringBuilder("[");
        try (var stream = java.nio.file.Files.list(out)) {
            java.util.List<String> ids = stream
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".f64be.raw"))
                    .map(n -> n.substring(0, n.length() - ".f64be.raw".length()))
                    .sorted()
                    .toList();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) arms.append(',');
                arms.append('"').append(ids.get(i)).append('"');
            }
        }
        arms.append(']');
        Files.writeString(out.resolve("compose-geometry.json"), String.format(Locale.ROOT,
                "{\"size\":%d,\"stepBlocks\":%d,\"centerBlocks\":[%.1f,%.1f],"
                        + "\"originBlocks\":[%.1f,%.1f],\"arms\":%s}%n",
                size, step, cx, cz, originX, originZ, arms));
    }
}