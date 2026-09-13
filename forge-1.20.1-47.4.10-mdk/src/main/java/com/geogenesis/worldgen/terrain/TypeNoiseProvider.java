package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

public final class TypeNoiseProvider {

    private final Noise plainNoise;

    // v6.0: HILLS — 参考 TF hills_1.json 双层 Warp + Perlin×Billow^0.5
    private final Noise hillsNoise;

    // v8c (2026-08-06)：山脉改丘陵式多频叠加配方（参考 HILLS），移除脊线网络+折叠细节
    // v8b 的 foldHills(1/50) 35% 在实机上产生规律性条纹/沙丘状图案（高频折叠线主导），且山体过小；
    // v8c 只用平滑多频主体（无折叠）→ 丘陵式大圆润山体，1/700 主频 = 山体远大于丘陵
    // ★ 2026-08-12 P0 重写：引入 RWG terrainMountain 三招（二次放大+高度相关细节+高度帽），
    //   解决"山的宽高不真实"——山脚宽缓、峰体突出、越高越崎岖、峰顶不捅天。
    private final Noise mountMain;   // 1/700 主体
    private final Noise mountSub;    // 1/220 中频细节
    private final Noise mountDet;    // 1/80 破碎细节

    private final Noise platNoise;
    private final Noise basinNoise;

    // 造山带起伏振幅（e 单位），由 TerrainParams.beltReliefAmp 注入；缩放山脉脊线相对高度，让山脉起伏可调（复活死参数）
    private final double beltReliefAmp;

    // ★ 2026-09-12 地质 Phase T3：接线盆地基准（原为死配置）
    /**
     * 盆地基准（TerrainParams.basinBase，默认 0.02）—— 碗形盆底的下限。
     *
     * <p><b>注</b>：同批的 {@code plateauSteps} / {@code plateauStepStrength} <b>未接线</b>——
     * 它们对应的 Terrace 空间量化已被本项目实测否决（环状台阶伪影），
     * T3 改用值域幂压缩实现平顶（见 {@link #computePlateau}）。</p>
     */
    private final double basinBase;

    /**
     * @param beltReliefAmp 造山带起伏振幅
     * @param basinBase     盆地基准（★ T3 接线）
     */
    public TypeNoiseProvider(double beltReliefAmp, double basinBase) {
        this.beltReliefAmp = beltReliefAmp;
        this.basinBase = basinBase;
        // --- PLAIN: 1/800 单频 + 极弱 warp，真正平坦 ---
        Noise pSimplex = new Frequency(new Simplex(410), 1.0 / 800.0);
        Noise pWarpX = new Frequency(new Simplex(433), 1.0 / 500.0);
        Noise pWarpZ = new Frequency(new Simplex(434), 1.0 / 500.0);
        Noise pWarped = new Warp(pSimplex, pWarpX, pWarpZ, 60.0);
        this.plainNoise = new Map(pWarped, -1.0, 1.0, 0.42, 0.58);

        // --- HILLS v7.5: 两大主频 + 小 Warp，MC 正确尺度 ---
        // FIX 1: 删除 1/80+1/40 细节（太碎产生"粗糙地面"非丘陵）
        // 主频 1/400（25 chunks）：丘陵主体隆起
        // 次频 1/120（7.5 chunks）：次级起伏
        // 总振幅 ~1.5 确保 Map(-1,1,0,1) 输出接近全 [0,1] 范围
        Noise hMain      = new Frequency(new Simplex(412), 1.0 / 400.0);
        Noise hSub       = new Boost(new Frequency(new Simplex(413), 1.0 / 120.0), 0.5);
        Noise hBase      = new Add(hMain, hSub);       // [-1.5, 1.5], 典型 [-0.75, 0.75]
        // 小距离 Warp（distance=25，freq=1/80）— 仅做局部不规则，不做大距离扭曲
        // 参考 TF hills_1.json: Warp(distance=20) 打散圆润感但不产生"拉面"蜿蜒
        Noise hWX        = new Frequency(new Simplex(415), 1.0 / 80.0);
        Noise hWZ        = new Frequency(new Simplex(416), 1.0 / 80.0);
        Noise hWarped    = new Warp(hBase, hWX, hWZ, 25.0);
        this.hillsNoise  = new Map(hWarped, -1.0, 1.0, 0.0, 1.0);

        // --- MOUNTAINS v8（2026-08-06 用户决策：参考丘陵配方）---
        // 旧版（v7）：MountainRidgeNetwork 脊线乘数 + 三级形态（谷底/陡升/脊线目标）+ 方向扭曲。
        // 弃因：脊线网络仅覆盖 ±4000 块，且脊线段之间空白区 ridgeBoost=0 → 山体 ×(0.35+0.65×0)=×0.35
        // 塌矮 65% → 山脉被切割成条状、大片塌陷、网络边界突变（用户反馈"山脉地形不行"）。
        // 新版：丘陵式多频叠加（与 HILLS 同风格，自然连贯），尺度放大（主频 1/700 山体更大）+ 细节
        // 破碎度（1/80）+ warp(30)。beltReliefAmp 调制次/细节振幅。
        // ★ 2026-08-12：三频独立字段（mountMain/mountSub/mountDet），供 computeMountain RWG 三招使用
        double ampScale = 0.7 + beltReliefAmp * 0.85; // beltReliefAmp=0.35 → 1.0（默认行为）
        Noise mMain   = new Frequency(new Simplex(436), 1.0 / 700.0);
        Noise mSub    = new Boost(new Frequency(new Simplex(417), 1.0 / 220.0), 0.7 * ampScale);
        Noise mDet    = new Boost(new Frequency(new Simplex(442), 1.0 / 80.0), 0.45 * ampScale);
        Noise mBase   = new Add(new Add(mMain, mSub), mDet);   // 典型 [-2.15, 2.15]
        Noise mWX     = new Frequency(new Simplex(430), 1.0 / 150.0);
        Noise mWZ     = new Frequency(new Simplex(431), 1.0 / 150.0);
        Noise mWarped = new Warp(mBase, mWX, mWZ, 30.0);
        // ★ 2026-08-12 P0 重写：三频独立字段 + 主体 warp 后保存
        this.mountMain = new Map(mWarped, -2.15, 2.15, 0.0, 1.0);  // warp 后主体 [0,1]
        this.mountSub  = new Map(mSub, -1.5, 1.5, 0.0, 1.0);       // 中频细节 [0,1]
        this.mountDet  = new Map(mDet, -1.0, 1.0, 0.0, 1.0);       // 破碎细节 [0,1]

        // --- PLATEAU v8（2026-08-07 用户："你到底有没有认真看丘陵代码"）---
        // v7/v7.1/v7.2 迭代都是错层打转。真正参考丘陵：频率只比丘陵宽一点（1.25x），
        // 完全同结构（Map(-1,1,0,1)），高原 vs 丘陵只在频率和 lo/hi。
        // ★ 2026-09-12：原述"foldHills 全幅"已作废 —— 折叠已撤销（见 clampUnit），
        //   高原与丘陵现仅差 ① 频率 1/500 vs 1/400 ② lo/hi（0.41/0.71 vs 0.06/0.18）
        //   ③ 台顶压平（computePlateau 的 1−(1−c)^q）。
        Noise pMain      = new Frequency(new Simplex(422), 1.0 / 500.0);
        Noise pSub       = new Boost(new Frequency(new Simplex(423), 1.0 / 150.0), 0.5);
        Noise pBase      = new Add(pMain, pSub);
        Noise pWX        = new Frequency(new Simplex(425), 1.0 / 100.0);
        Noise pWZ        = new Frequency(new Simplex(426), 1.0 / 100.0);
        Noise platWarped = new Warp(pBase, pWX, pWZ, 31.0);
        this.platNoise   = new Map(platWarped, -1.0, 1.0, 0.0, 1.0);

        // --- BASIN：★ T3 改为【沉降势 + 碗形映射】（原为噪声取反，无构造语义）---
        // 原实现 Map(Invert(bBase), -1.5,1.5, 0,0.6) 只是"把噪声翻过来"，
        // 既不体现"盆地 = 沉降中心低 + 向边缘抬升"，也无盆底/盆缘之分。
        // 现保留低频噪声作为【沉降势】（s 大 = 沉降强 = 盆底），碗形映射见 computeBasin。
        Noise bOct1 = new Frequency(new Simplex(428), 1.0 / 300.0);
        Noise bOct2 = new Boost(new Frequency(new Simplex(429), 1.0 / 100.0), 0.3);
        Noise bBase = new Add(bOct1, bOct2);
        this.basinNoise = new Map(bBase, -1.0, 1.0, 0.0, 1.0);   // 沉降势 s ∈ [0,1]
    }

    public void seed(long worldSeed) {
        Noises.seedAll(plainNoise, worldSeed, 0);
        Noises.seedAll(hillsNoise, worldSeed, 1);
        Noises.seedAll(mountMain, worldSeed, 2);  // 遍历整棵树（含 mountSub/mountDet 共享节点）
        Noises.seedAll(platNoise,  worldSeed, 3);
        Noises.seedAll(basinNoise, worldSeed, 4);
    }

    /**
     * 值域钳制（把噪声输出收进 [0,1]）。
     *
     * <p><b>★★★ 2026-09-12 第四次伪影修复（撤销折叠）★★★</b></p>
     *
     * <p>本函数<b>曾</b>实现绝对值折叠 {@code |2n−1|}（2026-08-01 起的方案）。
     * 折叠的几何副作用被低估了，它是「密集波浪状平行细线」的直接来源：</p>
     * <ol>
     *   <li><b>频率翻倍</b>：{@code |2n−1|} 把单个原周期拆成两条脊 + 两条沟
     *       → 等值线（等高线）数量直接翻倍、间距减半。</li>
     *   <li><b>梯度恒定</b>：正弦在极值附近 {@code d/dx→0}（等高线自然稀疏），
     *       而 {@code |2n−1|} 在<b>处处</b>保持满梯度 {@code 2A·2π/λ}
     *       → 本该稀疏的缓坡区也铺满等高线 ⇒ <b>“密集”且“均匀”</b>。</li>
     *   <li><b>crest 等值线成对</b>：n=0.5 处 V 形折痕两侧的等值线成对平行出现
     *       → <b>“平行细线”</b>，且折痕轨迹本身是蜿蜒曲线 ⇒ <b>“波浪状”</b>。</li>
     * </ol>
     * <p>三者叠加正好复现用户反馈的“密集波浪状平行细线”。</p>
     *
     * <p><b>决定性实测证据</b>（同 seed 同渲染，{@code build/stripe/typeHP_*.png}，
     * 高通幅值 = 减 12wu 窗口均值）：</p>
     * <pre>
     *   PLAIN     0.0025   ← 无折叠
     *   MOUNTAINS 0.120    ← RWG 配方，不用折叠
     *   BASIN     0.056    ← 碗形映射，不用折叠
     *   HILLS     <b>0.425</b>    ← 用折叠，异常 170×
     *   PLATEAU   <b>0.464</b>    ← 用折叠 + 顶部压平，异常 185×
     * </pre>
     * <p>HILLS 与 PLATEAU 的<b>唯一共同点</b>就是折叠 ⇒ 根因即折叠。</p>
     *
     * <p><b>为何上一版“圆化折痕”无效</b>：把折角用 {@code sqrt(x²+r²)−r} 圆化
     * 只消除了二阶不连续，<b>脊谷交替结构与频率翻倍原样保留</b>
     * （实测高通幅值仍 0.42）。方向错了 —— 正解是<b>去掉折叠</b>，不是软化折叠。
     * 本项目 v8b 早有同类记载（折痕层在实机产生“规律性条纹/沙丘状图案”，
     * v8c 靠删掉那层规避）。</p>
     *
     * <p><b>修复后代价与取舍</b>：丘体密度降回噪声自身的多频尺度
     * （hillsNoise = 1/400 + 1/120 两频 + 25wu 域扭曲），不再有“频率翻倍”的密集小丘。
     * 这是<b>有意</b>的取舍 —— 那正是伪影来源。值域仍为 [0,1]，下游高度分布不漂移。</p>
     */
    private static double clampUnit(double n) {
        return n < 0.0 ? 0.0 : (n > 1.0 ? 1.0 : n);
    }

    public double computeNoise(TerrainClass type, double wx, double wz) {
        return switch (type) {
            case PLAIN     -> plainNoise.compute(wx, wz);
            case HILLS     -> clampUnit(hillsNoise.compute(wx, wz));
            case MOUNTAINS -> computeMountain(wx, wz);
            case PLATEAU   -> computePlateau(wx, wz);
            case BASIN     -> computeBasin(wx, wz);   // ★ T3：碗形沉降
            default        -> 0.5;
        };
    }

    /**
     * v7（2026-08-06 用户方案，严格对照 HILLS）：PLATEAU = foldHills 丘链 + 顶部削平。
     * <p>
     * 与 HILLS 同链路：foldHills(|2n-1|) 产生丘谷交替（密集圆丘夹细沟），倍频放宽
     * （主频 1/1000 vs 丘陵 1/400）→ 丘体开阔；压缩 80%（×0.2 收向中位 0.50）→ 顶部削平，
     * 台地内部 ±0.1e 微起伏 = "丘陵倍频放宽 + 顶部平" 的高原特征。
     */
    // v7.2（2026-08-07 用户反馈"丘陵比高原自然"，要求"参考丘陵写法"）：
    // 直接返回 foldHills 全幅输出（与 HILLS 完全同结构），不压缩——丘沟结构完整保留。
    // 高原 vs 丘陵的差异只在频率（1/1000 vs 1/400 → 波长更长 → 视觉上丘沟更"缓"）+
    // platMod 轻微收敛（1.5 倍，比丘陵的无收敛略平）。"顶部平一点"由倍频放宽实现
    //（丘沟波长 200 块 vs 丘陵 80 块 → 相同振幅但斜率更低 = 更平缓）。
    /**
     * ★ 2026-09-12 地质 Phase T3：<b>高原平顶</b>（值域幂压缩，非空间量化）。
     *
     * <p><b>为何不用 Terrace 阶地化</b>：本项目已实测否决过 Terrace
     * （见 {@code TerrainParams.plateauSteps} 注释："<i>Terrace 算子已否决（环状台阶伪影）</i>"）。
     * 原因是空间量化会把噪声的<b>等值线</b>变成台阶，而等值线是闭合曲线
     * → 产生<b>同心环梯田</b>，视觉上极假。本次重新尝试确认该结论成立，故改用值域变换。
     *
     * <p><b>值域幂压缩</b> {@code v^p}（p&lt;1，只压高端、保留低端动态范围）：
     * <ul>
     *   <li>高值端密集于顶部 → <b>台顶平</b>（梯度 dv'/dv 随 v 增大而减小）</li>
     *   <li>低值端梯度相对保留 → <b>台缘/崖线有起伏</b></li>
     * </ul>
     * 因为是<b>逐点值域变换</b>（不含任何空间量化），<b>不产生同心环伪影</b>。
     *
     * <p>与 v7 失败的"整体压缩到中位（×0.2）"不同：那只压整体、抹平地貌对比；
     * 本式<b>保序且保低端范围</b>，只重塑高低端的梯度分配。
     */
    private double computePlateau(double wx, double wz) {
        // ★ 2026-09-12：原为 foldHills(...)——折叠已撤销（见 clampUnit 注释），
        //   与 HILLS 完全同构：platNoise 自带 1/500 + 1/150 两频 + 31wu 域扭曲。
        //   高原 vs 丘陵的差异由此收敛为【纯频率差异】（1/500 vs 1/400）+ 下方压平。
        double c = clampUnit(platNoise.compute(wx, wz));
        // ★★★ 2026-09-12 第三次伪影修复：pow(c, 0.55) → 1−(1−c)^q ★★★
        //
        //   【为何必须改】pow(c, p) 在 p<1 时于 c→0 处导数 → ∞（数学奇点）。
        //   而 c=0 恰是 foldHills 的折痕（谷底）——两者叠加把折痕放大成
        //   【无限梯度的锐利细线】，这是"平行细线"伪影的直接放大器
        //   （即使 foldHills 已圆化，pow 仍会把 c→0 附近重新拉成奇点）。
        //
        //   【新式】1−(1−c)^q（q>1）：同样"压高端使台顶变平"，但
        //     · c→1 端导数 →0  → 台顶平（与原意一致）
        //     · c→0 端导数 = q → 【有界】，无奇点
        //   保留了 T3 的设计意图（台顶平、台缘有起伏），同时杜绝细线。
        return 1.0 - Math.pow(1.0 - c, PLATEAU_TOP_FLATTEN);
    }

    /**
     * 高原台顶压平指数（&gt;1 = 压高端使台顶变平）。
     *
     * <p>由原 {@code PLATEAU_TOP_POWER = 0.55} 换算：{@code q ≈ 1/0.55 ≈ 1.8}，
     * 保持台顶压平力度与 T3 标定接近（见 {@code TerrainShapeProbe} 的"台顶梯度 vs 台缘梯度"判据）。</p>
     */
    private static final double PLATEAU_TOP_FLATTEN = 1.8;

    /** 盆地碗形幂：>1 → 盆底平阔、向边缘快速抬升（真实沉积盆地的形态特征）。 */
    private static final double BASIN_BOWL_POWER = 2.2;

    /**
     * ★ 2026-09-12 地质 Phase T3：<b>构造盆地 = 沉降中心 + 向边缘抬升</b>。
     *
     * <p>取代原"噪声取反"（那只是把噪声翻过来，既不体现盆底/盆缘之分，
     * 也没有"中心低、边缘高"的碗形）。
     *
     * <p>做法：低频噪声作为<b>沉降势</b> s ∈ [0,1]（s 大 = 沉降强 = 盆底），
     * 再做碗形映射 {@code (1-s)^p}：p&gt;1 使 s→1 附近变化平缓
     * → <b>大面积平阔盆底</b>，而 s→0 附近快速抬升 → <b>盆缘</b>。
     *
     * <p>下限取 {@code basinBase}（★ 复活死配置），上界 0.6 与原值域一致。
     */
    private double computeBasin(double wx, double wz) {
        double s = basinNoise.compute(wx, wz);                       // 沉降势 [0,1]
        double bowl = Math.pow(1.0 - Math.max(0.0, Math.min(1.0, s)), BASIN_BOWL_POWER);
        return basinBase + (0.6 - basinBase) * bowl;
    }

    /**
     * ★ 2026-08-12 P0 重写：山脉配方借鉴 RWG terrainMountain（ted80 移植）三招。
     * <p>
     * 参考：RTG-Community TerrainBase.terrainMountain (630-654行)
     * <pre>
     *   float h = simplex(x/300)*135*river;
     *   h *= h/32;                           // ① 二次放大：小值塌缩、大值爆炸
     *   if (h > 10) h += simplex(x/15)*5;   // ② 高度相关中频：越高越崎岖
     *   if (h > 35) h += cell(x/12)*15;     // ③ 高度相关破碎：cellular 碎裂
     *   if (h > 160) h -= (h-160)*0.75;     // ④ 高度帽：峰顶不捅天
     * </pre>
     * 三招的效果：
     * - 二次放大：山脚（base<0.5）塌缩为缓坡（h<0.25），山腰-山顶（base>0.7）爆炸为陡峭峰体
     * - 高度相关细节：h>0.3 才加中频（山脚保持宽缓），h>0.5 才加破碎（低山坡面不碎）
     * - 高度帽：>0.85 折减 50%（峰顶自然收尖，不捅天花板）
     * <p>
     * 我们的映射（e 空间 [0,1]）：
     * - base = mountMain.compute(wx, wz)  // 三频+warp 主体 [0,1]
     * - h = base²                         // 二次放大（RWG h*=h/32 语义）
     * - h += mid·0.15 (h>0.3)            // 高度相关中频
     * - h += det·0.10 (h>0.5)            // 高度相关破碎
     * - h = cap(h, 0.85)                 // 高度帽
     */
    private double computeMountain(double wx, double wz) {
        double base = mountMain.compute(wx, wz);  // 三频+warp 主体 [0,1]
        // ① 二次放大（RWG h*=h/32 语义）：山脚塌缩、峰体爆炸
        //   base=0.3→h=0.09（山脚极缓），base=0.5→h=0.25（山腰），base=0.8→h=0.64（峰体），base=1→h=1
        double h = base * base;

        // ② 高度相关中频细节（h>0.3 才激活，山脚保持宽缓）
        //   RWG: if (h>10) h += simplex(x/15)*5 —— 中频起伏只在山腰以上出现
        if (h > 0.3) {
            double midScale = Math.min((h - 0.3) * 1.5, 1.0);  // 0.3→0, 0.5→0.3, 0.97→1
            double mid = mountSub.compute(wx, wz);  // 1/220 中频 [0,1]
            // 将 mid 从 [0,1] 映射到 [-0.5,0.5] 再乘振幅
            h += (mid - 0.5) * 0.15 * midScale;
        }

        // ③ 高度相关破碎细节（h>0.5 才激活，低山坡面不碎）
        //   RWG: if (h>35) h += cell(x/12)*15 —— cellular 碎裂只在高山出现
        if (h > 0.5) {
            double detScale = Math.min((h - 0.5) * 2.0, 1.0);  // 0.5→0, 1.0→1
            double det = mountDet.compute(wx, wz);  // 1/80 破碎细节 [0,1]
            h += (det - 0.5) * 0.10 * detScale;
        }

        // ④ 高度帽（RWG: if (h>160) h -= (h-160)*0.75）
        //   峰顶自然收尖，不捅天花板——h>0.85 时折减 50%
        if (h > 0.85) {
            h = 0.85 + (h - 0.85) * 0.5;
        }

        return h;
    }

    public static final TerrainClass[] LAND_TYPES = {
        TerrainClass.PLAIN, TerrainClass.HILLS,
        TerrainClass.MOUNTAINS, TerrainClass.PLATEAU,
        TerrainClass.BASIN
    };
}
