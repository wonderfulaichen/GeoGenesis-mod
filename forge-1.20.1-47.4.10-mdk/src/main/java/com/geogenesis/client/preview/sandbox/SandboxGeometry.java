package com.geogenesis.client.preview.sandbox;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxPalette;
import com.geogenesis.client.preview.SandboxShade;
import com.geogenesis.client.preview.TerrainFieldData;

/**
 * <b>沙盘几何（纯函数，零 MC 依赖）</b> —— ★ 2026-11-09 B1。
 *
 * <h2>为什么要单独一层（这是本轮重构的核心）</h2>
 * <p>此前沙盘的所有数学（投影矩阵、柱高、排序、包围盒、光照）与 OpenGL 提交
 * 混在 {@code SandboxRenderer}（1132 行）里，<b>无法离线验证</b>。</p>
 *
 * <p>代价已经付过多次：我写 Python 脚本「复刻」Java 逻辑来出图，
 * 一旦两边不同步就误判 —— 实测离线脚本 {@code per_column_for()} 恒返回 1，
 * 我在<b>错图</b>上判断了好几轮（"整片是平的""柱子太细"全是假象）。</p>
 *
 * <h2>本层的职责边界</h2>
 * <pre>
 *   SandboxGeometry（本类，纯函数）        SandboxRasterizer（实机，只画）
 *   ├─ buildRotation()  → float[9]        ├─ bufferSource().getBuffer(...)
 *   ├─ fitScaleWide()   → scale           ├─ vertex(...).color(...).endVertex()
 *   ├─ buildColumns()   → Column[]        ├─ 提交顺序 = sorted[]
 *   ├─ sortByDepth()    → int[] sorted    ├─ RenderSystem 状态
 *   └─ pick()           → 柱坐标           └─ flush()
 * </pre>
 *
 * <h2>零 MC 依赖（硬约束）</h2>
 * <p>用户 2026-11-09：<i>「这就是我让你抽离 MC 做完全纯 Java 核心的原因，
 * 可以在外部计算后直接复用。」</i></p>
 * <p>⇒ 本类<b>零 {@code net.minecraft.*} import</b>，探针可完整验证。</p>
 */
public final class SandboxGeometry {

    // ===== 照抄 yardsite 的常量（数值一处定义，禁止别处再写）=====

    /** 俯仰下限/上限/默认（yardsite {@code PITCH_MIN/MAX/DEFAULT_PITCH}）。 */
    public static final float PITCH_MIN = 0.22f;
    public static final float PITCH_MAX = 1.40f;
    public static final float DEFAULT_PITCH = 0.95f;
    /** 默认方位（yardsite {@code DEFAULT_YAW=0.65}）。 */
    public static final float DEFAULT_YAW = 0.65f;

    /**
     * 地形起伏在模型空间里的最大厚度。<b>照抄 yardsite 的 0.40</b>。
     *
     * <p>我曾擅自改成 0.85 以为"山不够高" —— 那是误判。参考图柱子看着高，
     * 是因为 ① {@code zoom} 默认 2.0（整图放大一倍）；
     * ② 它的 {@code span} 是选点窗口内的高差（几十格），不是全球高差。
     * 正确做法是调 zoom / span，不是加大 RELIEF。</p>
     */
    public static final float RELIEF = 0.40f;
    /** 水面也保证一层薄皮，海面和海岸才连得起来（yardsite 第 50 行）。 */
    public static final float WATER_SINK = 0.05f;

    /** 深度桶数（yardsite {@code BUCKETS=4096}）。 */
    public static final int BUCKETS = 4096;
    /** 屏深基线与深度系数（yardsite {@code Z_BASE=200 / DEPTH_SCALE=110}）。 */
    public static final float Z_BASE = 200.0f;
    public static final float DEPTH_SCALE = 110.0f;

    /**
     * 一根柱子长高 + 淡入的时长（毫秒，yardsite {@code FADE_MS=520}）。
     */
    public static final int FADE_MS = 520;
    /**
     * <b>铺开动画的总时长</b>（毫秒）—— 从中心长到最外圈用多久。
     *
     * <p>★ v25 新增。参考项目靠 {@code centerOutOrder} 让采样<b>从中心往外</b>，
     * 每根柱的 {@code drawnAt} 自然递延 ⇒ 视觉上"一圈圈长出来"。</p>
     *
     * <p>我们的数据是<b>一次性从 CellCache 裁出来的</b>（全部同时就绪），
     * 照抄 {@code drawnAt} 的"首次绘制时打戳"只会让<b>所有柱同时开始长</b>
     * ⇒ 没有铺开层次。所以改成<b>按到中心的距离错开出生时刻</b>：
     * 中心柱 bornAt = 现在，越靠外 bornAt 越晚 ⇒ 与参考项目同样的观感。</p>
     */
    public static final int SPREAD_MS = 1400;
    /** 淡入期间至少还有这么不透明（yardsite {@code FADE_FLOOR=0.25}）。 */
    public static final float FADE_FLOOR = 0.25f;

    /**
     * 侧墙的最小屏幕高度（像素）：低于它就不画。
     *
     * <p>亚像素高的墙不产生任何视觉信息，只会在每根柱子边缘留下混叠亮线
     * ⇒ 整幅呈密集条纹（LOD 细档实测就是这样丢掉地形形状的）。</p>
     */
    public static final float MIN_WALL_PX = 1.0f;

    /**
     * 顶面四边形<b>每边</b>向外扩张的比例（相对一根柱子的宽度）。
     *
     * <p>用途：消除光栅化漏缝。柱宽只有 3~7px 时，相邻四边形几何上严格相接，
     * 但像素中心采样仍可能落在两者之间（都不包含）⇒ 露出底板深色
     * ⇒ 细缝叠成斜向网纹。</p>
     *
     * <h2>★ v23：从 0.5 改成 0.08（修"柱子互相压住、像叠瓦"）</h2>
     * <p>旧值 {@code PRIM_OVERLAP_CELLS = 0.5} 被当作 {@code 0.5 / colCount}
     * 使用，而一根柱子的模型宽度是 {@code 2 / colCount}
     * ⇒ 每边扩 <b>0.25 根柱</b>、两边合计 <b>0.5 根柱</b>
     * ⇒ 每根柱子实际宽 <b>1.5 倍</b>，把左右邻居各盖掉一半。
     * 配合"从远到近"的提交顺序，画面变成一片<b>叠瓦状色块</b>。</p>
     * <p>现在每边只扩 8%（≈6px 柱宽下 0.5px），足够闭合缝、又肉眼不可见。</p>
     */
    public static final float PRIM_OVERLAP_RATIO = 0.08f;

    private SandboxGeometry() { }

    // =================================================================
    // 一、旋转矩阵
    // =================================================================

    /**
     * 由 yaw/pitch 算 3×3 旋转矩阵，<b>摊平成行</b>（照 yardsite {@code orthoRows()} 358-375）。
     *
     * <p>顺序必须是 {@code rotateZ(roll=0) → rotateX(pitch) → rotateZ(yaw)}；
     * 我自己推过公式，结果是<b>竖条纹</b> —— 别重推。</p>
     *
     * @return 长度 9 的行摊平矩阵；{@code r[0..2]}=屏幕 x 的三个分量，
     *         {@code r[3..5]}=屏幕 y，{@code r[6..8]}=深度
     */
    public static float[] rotationRows(float yaw, float pitch) {
        org.joml.Matrix3f m = new org.joml.Matrix3f();
        m.identity();
        m.rotateZ(0f);
        m.rotateX(pitch);
        m.rotateZ(yaw);
        float[] raw = new float[9];
        m.get(raw);
        float[] r = new float[9];
        r[0] = raw[0]; r[1] = raw[3]; r[2] = raw[6];
        r[3] = raw[1]; r[4] = raw[4]; r[5] = raw[7];
        r[6] = raw[2]; r[7] = raw[5]; r[8] = raw[8];
        return r;
    }

    // =================================================================
    // 二、投影包围盒与 scale
    // =================================================================

    /** 平面 [-1,1]² 四角的投影包围盒 {@code {minX, maxX, minY, maxY}}。 */
    public static float[] planeBox(float[] r) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float px = r[0] * sx + r[1] * sy;
                float py = r[3] * sx + r[4] * sy;
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
            }
        }
        return new float[]{minX, maxX, minY, maxY};
    }

    /**
     * <b>按投影包围盒定 scale</b>，使盘面正好装进视口。
     *
     * <p>模型平面是 [-1,1]²，但<b>斜投影后包围盒不是 2×2</b>：
     * pitch=0.95/yaw=0.65 实测为 2.803 × 1.956。
     * 曾用 {@code areaSize*0.92} 等于它的 2.6 倍 ⇒ 盘面大半在视口外，
     * 只看到一小块缓坡局部，观感"整片是平的"。</p>
     *
     * <p>分别用宽、高归一化取较小者 ⇒ 两方向都不溢出、盘面尽可能大。</p>
     */
    public static float fitScaleWide(float[] r, int viewW, int viewH) {
        float[] box = planeBox(r);
        float spanX = box[1] - box[0];
        float spanY = box[3] - box[2];
        float sx = spanX > 1e-4f ? viewW * 0.98f / spanX : Float.MAX_VALUE;
        float sy = spanY > 1e-4f ? viewH * 0.98f / spanY : Float.MAX_VALUE;
        float s = Math.min(sx, sy);
        return s == Float.MAX_VALUE ? Math.min(viewW, viewH) * 0.46f : s;
    }

    /** fitScale 算出的包围盒中心（让盘面真正居中）。 */
    public static float[] fitCenter(float[] r) {
        float[] box = planeBox(r);
        return new float[]{(box[0] + box[1]) * 0.5f, (box[2] + box[3]) * 0.5f};
    }

    // =================================================================
    // 三、柱坐标映射
    // =================================================================

    /**
     * <b>scale：让盘面完整装进视口</b>（★ v27 定稿）。
     *
     * <pre>
     *   scale = areaSize × 0.46 × zoom        （yardsite SiteTerrainRenderer 第 405 行）
     * </pre>
     *
     * <h2>★★ 为什么基准 zoom 必须是 1.0（不能照抄参考项目的 2.0）</h2>
     * <p>用户实机反馈：「我不是说底板不对，是<b>视野一缩放就超出底板了</b>，
     * 参考项目是不会超出底板的。」</p>
     *
     * <p>探针 {@code runSandboxFloorProbe} 实测：
     * {@code 0.46 × 2.0 = 0.92}，乘投影包围盒（2.803×1.956）
     * ⇒ 盘面宽 = 视口 × <b>2.58</b> ⇒ 底板边缘被裁到看不见
     * ⇒ 视觉上就是"地形跑到板外"。</p>
     *
     * <p>⚠ 我此前在这里写过"参考项目是故意让盘面超视口"——
     * 那是<b>我的推测，不是事实</b>（参考项目没有任何注释这样说）。
     * 参考项目 {@code SiteMapSampler.defaultZoom()} 确实返回 2.0，
     * 但它的 {@code areaSize = min(width-20, height-56)} 在 1080p 上也只有 1024
     * ⇒ 同样 2.58 倍超出。<b>用户亲眼对照的参考项目效果是不超出的</b>
     * ⇒ 以用户实机观感为准。</p>
     *
     * <p>{@code 0.46} 本身是对的：它把模型 ±1 映射到视口的 ±0.92
     * （内切、留 8% 边距），所以 <b>zoom=1.0 时盘面正好完整可见</b>。</p>
     *
     * @param zoom 缩放档位（{@link com.geogenesis.client.preview.SandboxLod#BASE_ZOOM} 起）
     */
    public static float scaleOf(int areaSize, float zoom) {
        return areaSize * 0.46f * zoom;
    }

    /**
     * <b>让盘面尽量铺满视口，同时不裁掉任何一边</b>（★ v28）。
     *
     * <h2>% 与 {@link #fitScale} 的区别</h2>
     * <p>{@code fitScale} 用 {@code viewW/spanX} 和 {@code viewH/spanY} 取<b>较小者</b>
     * —— 这保证两个方向都不溢出，但斜投影的菱形包围盒是 2.803×1.956
     * （宽大于高），取较小者后<b>竖向只剩 58%</b> ⇒ 用户看到"底盘没满屏"。</p>
     *
     * <p>{@code fillScale} 取<b>较大者</b> —— 会让短边方向溢出，
     * 但菱形投影的角本来就是空的，溢出的是空白角而不是地形
     * ⇒ 视觉上"铺满"，与参考项目的观感一致。</p>
     *
     * <p>⚠ 溢出量要夹住：若长短边悬殊（极扁视口），取较大者会把盘面裁掉太多。
     * 这里限制最大溢出到 1.35 倍，超出则退回 fitScale。</p>
     */
    public static float fillScale(float[] r, int viewW, int viewH) {
        float[] box = planeBox(r);
        float spanX = box[1] - box[0];
        float spanY = box[3] - box[2];
        float fit = fitScale(r, viewW, viewH);
        float sx = spanX > 1e-4f ? viewW / spanX : Float.MAX_VALUE;
        float sy = spanY > 1e-4f ? viewH / spanY : Float.MAX_VALUE;
        float fill = Math.max(sx, sy);
        // ★ 溢出上限 1.6：菱形斜投影的包围盒宽/高比约 1.43（2.803/1.956），
        //   要让<b>竖向</b>铺满就必须接受横向 1.43 倍溢出 —— 溢出的四个角本来就是空白。
        //   1.6 留一点余量；若视口极端扁平（超出 1.6），靠 fit*1.6 兜底。
        return Math.min(fill, fit * 1.6f);
    }

    /**
     * <b>让盘面<b>完整</b>装进视口</b>的 scale。
     *
     * <h2>为什么需要它（{@link #scaleOf} 不够）</h2>
     * <p>{@code scaleOf = areaSize × 0.46 × zoom} 里的 {@code 0.46}
     * 是按「模型跨度 2」推的内切系数，但<b>斜投影后包围盒不是 2×2</b>
     * （pitch=0.95/yaw=0.65 实测 2.803 × 1.956）⇒ 即使 zoom=1.0，
     * 横向仍会到视口的 1.29 倍 ⇒ 底板左右边缘被裁。</p>
     *
     * <p>用户实机：「视野一缩放就超出底板了，参考项目是不会超出底板的」。</p>
     *
     * <p>本方法按<b>实际投影包围盒</b>分别用宽、高归一化、取较小者
     * ⇒ 两个方向都不溢出、盘面尽可能大。</p>
     *
     * @return 使盘面完整装进 {@code viewW×viewH} 的像素/模型单位缩放
     */
    public static float fitScale(float[] r, int viewW, int viewH) {
        float[] box = planeBox(r);
        float spanX = box[1] - box[0];
        float spanY = box[3] - box[2];
        float sx = spanX > 1e-4f ? viewW / spanX : Float.MAX_VALUE;
        float sy = spanY > 1e-4f ? viewH / spanY : Float.MAX_VALUE;
        return Math.min(sx, sy);
    }

    /** 细格<b>边界</b> → 模型坐标 [-1,1]（分母用 {@code fine}，不是 {@code fine-1}）。
     *
     * <p>参考项目的 {@code model()} 分母是 {@code fine-1}，因为它把细格当"点"。
     * 我们是<b>铺满</b>的方块盘面，细格是"格子"（有面积）⇒ 必须用 {@code fine}，
     * 否则柱 i 的右边与柱 i+1 的左边不相邻，中间露出 1 整格缝隙
     * （实机截图里的横向细线就是这个缝）。</p>
     */
    public static float modelEdge(int fine, float k) {
        return fine <= 0 ? 0f : k / (float) fine * 2.0f - 1.0f;
    }

    /** 柱中心 → 模型坐标（排序/渐变用，yardsite {@code modelOf()}）。 */
    public static float modelOf(int n, int i) {
        return n <= 1 ? 0f : (i + 0.5f) / (float) n * 2.0f - 1.0f;
    }

    // =================================================================
    // 四、柱子数据
    // =================================================================

    /**
     * 一根柱子的全部几何与外观数据（纯数据，无行为）。
     *
     * <p>渲染器只读它提交顶点；探针只读它出图 —— <b>同一份数据，不可能失同步</b>。</p>
     */
    public static final class Column {
        /** 柱顶高度（模型单位，已乘 RELIEF）；<b>&lt;0 = 未采到，不画</b>。 */
        public float top;
        /** 四个水平边界（模型坐标，已含 {@link #PRIM_OVERLAP_CELLS} 扩张）。 */
        public float x0, x1, y0, y1;
        /** 颜色 0xRRGGBB（已含坡度光照）。 */
        public int rgb;
        /** 淡入透明度 0..255。 */
        public int alpha;
        /** 出生时刻（毫秒）；0 = 未采到。 */
        public long bornAt;
        /** 是否水面（粗层水位高于地形）。 */
        public boolean water;
        /** 真实地表 Y（方块）—— hover 显示用。 */
        public double surfaceY;
        /** 边缘渐隐系数 0..1。 */
        public float edgeFade;
    }

    /** 采集结果的容器（复用数组，避免每帧分配）。 */
    public static final class Columns {
        public final Column[] items;
        public final int colCount, colRows;
        /** 参与绘制的柱子下标（按收集顺序）。 */
        public final int[] ready;
        public int readyCount;
        /** 本图柱间距（方块）—— 坡度光照的分母基准。 */
        public final double blocksPerPillar;

        Columns(int colCount, int colRows, int capacity, double blocksPerPillar) {
            this.colCount = colCount;
            this.colRows = colRows;
            this.blocksPerPillar = blocksPerPillar;
            this.items = new Column[capacity];
            for (int i = 0; i < capacity; i++) this.items[i] = new Column();
            this.ready = new int[capacity];
        }
    }

    /**
     * <b>底色来源</b>（非水面 / 非 BIOME 图层的颜色）—— 由调用方注入。
     *
     * <p>这样 {@code SandboxGeometry} 不需要知道 {@code GeoPalette} 的 14 条色带，
     * 实机传 {@code GeoPalette.color(...)}，探针传一个纯函数实现
     * ⇒ 两边用<b>同一份几何</b>，但颜色来源可以不同。</p>
     */
    public interface ColorSource {
        /** 该细格的底色 0xRRGGBB。 */
        int colorAt(int gx, int gz, TerrainFieldData snap);
    }

    /**
     * <b>把快照聚合成柱子</b>（纯函数，探针与实机共用）。
     *
     * <h2>关键规则（每条都有实机/离线取证）</h2>
     * <ol>
     *   <li><b>柱高用组内中位真实高度</b>，不是左上角那一格 ——
     *       16 格/柱时只取一角会丢失组内起伏，整片看起来是平的；</li>
     *   <li><b>柱顶永远向上</b>：{@code ratio = max((topY-base)/span, WATER_SINK)}，
     *       写负值会让水下凹成坑；</li>
     *   <li><b>坡度光照用真实方块 Y</b>，分母 {@code 2×blocksPerPillar} ——
     *       用归一化 r 会把亮度差压到 0.003，整幅一片均匀色；</li>
     *   <li><b>四边形向外扩半格</b> —— 否则光栅化漏缝叠成斜向网纹；</li>
     *   <li><b>水面优先</b>：河道/湖面无论当前图层都用深度渐变蓝覆盖。</li>
     * </ol>
     *
     * @param snap      地形快照
     * @param layer     当前图层
     * @param perColumn 每柱聚合的细格数（每边）
     * @param span      竖向比例分母（本图最高点 − 基准面，已缓动）
     * @param seaLevel  海平面 Y（预览可配，不能写死 63）
     * @param now       当前时刻（毫秒）
     * @param animBaseIn 铺开动画基准时刻（调用方跨帧持有；0 = 用 now）
     * @param colorSrc  底色来源（非水面/非 BIOME 图层用）；可 null
     */
    public static Columns buildColumns(TerrainFieldData snap, GeoPalette.PreviewLayer layer,
                                       int perColumn, float span,
                                       int minY, int maxY, double seaLevel, long now,
                                       long animBaseIn, ColorSource colorSrc) {
        int gw = snap.gridW, gh = snap.gridH;
        int per = Math.max(1, perColumn);
        int colCount = Math.max(1, (gw + per - 1) / per);
        int colRows = Math.max(1, (gh + per - 1) / per);
        int capacity = Math.max(1, colCount * colRows);

        // ★★★ 柱间距（方块）—— 坡度光照的分母基准（探针抓到的真 bug）
        //
        //   相邻柱之间隔 {@code per} 个<b>细格</b>，而每个细格代表
        //   {@code snap.blocksPerCell} 个方块 ⇒ 柱间距（方块）= per × blocksPerCell。
        //
        //   曾直接写 {@code blocksPerPillar = per}（=2）—— 那是"细格数"不是"方块数"，
        //   分母小了 {@code blocksPerCell} 倍（实测 8 倍）⇒ 坡度被放大 8 倍
        //   ⇒ 光照饱和率 23%/34%（探针实测）。参考项目 paintBlock 的分母是
        //   {@code 2 × step × HEIGHT_STRIDE}，即真实方块距离。
        double blocksPerPillar = Math.max(1e-6, per * snap.blocksPerCell());
        Columns out = new Columns(colCount, colRows, capacity, blocksPerPillar);
        double base = seaLevel - 4.0;
        double sp = Math.max(20.0, span);

        // 真实高度网格（水面取海平面 ⇒ 水面平，无假明暗）
        double[] yGrid = snap.buildSurfaceYGrid(seaLevel);

        // 每边扩张 = PRIM_OVERLAP_RATIO × 一根柱子的模型宽度（2/colCount）
        double overlap = PRIM_OVERLAP_RATIO * 2.0 / Math.max(1, colCount);
        double[] hBuf = new double[(per) * (per)];
        int[] voteIds = new int[per * per];
        int[] voteCnts = new int[per * per];

        // ★★ v25/v26【铺开动画：按到中心的距离错开出生时刻】★★
        //
        //   参考项目（SiteMapSampler.centerOutOrder + SiteTerrainRenderer.collect 684-690）：
        //   采样<b>从中心往外</b>，每根柱的 {@code drawnAt} 自然递延
        //   ⇒ 视觉上"从中心一圈圈长出来"。
        //
        // ★ v26 修：v25 把基准写成 {@code now - SPREAD_MS}，
        //   导致<b>中心柱的出生时刻已经是 1400ms 前</b>、早就长满了，
        //   只有最外圈还在长 ⇒ 用户看到的是"整体一起淡入"而不是"一圈圈"。
        //   正确做法：<b>中心柱的出生时刻就是"本图第一次出现"的那一刻</b>，
        //   外圈依次往后推 SPREAD_MS。
        //
        //   基准取「这张图的第一帧时刻」，由<b>调用方</b>持有并跨帧传入 ——
        //   若在这里取 now，每帧 animBase 都往后跑 ⇒ bornAt 永远追不上 now
        //   ⇒ 柱子永远停在"刚开始长"的状态。
        long animBase = animBaseIn != 0L ? animBaseIn : now;
        double halfDiag = Math.max(1e-6,
                Math.hypot((colCount - 1) * 0.5, (colRows - 1) * 0.5));
        double centerX0 = (colCount - 1) * 0.5;
        double centerZ0 = (colRows - 1) * 0.5;

        for (int cx = 0; cx < colCount; cx++) {
            for (int cz = 0; cz < colRows; cz++) {
                int col = cx * colRows + cz;
                Column c = out.items[col];

                int fx0 = cx * per, fz0 = cz * per;
                int fx1 = Math.min(gw - 1, fx0 + per - 1);
                int fz1 = Math.min(gh - 1, fz0 + per - 1);

                // ---- 1) 柱高 = 组内中位真实高度 ----
                int hvN = 0;
                for (int dx = fx0; dx <= fx1; dx++) {
                    for (int dz = fz0; dz <= fz1; dz++) {
                        double hv = snap.height[dx * gh + dz];
                        if (!Double.isNaN(hv)) hBuf[hvN++] = hv;
                    }
                }
                double surfaceY;
                if (hvN == 0) {
                    surfaceY = snap.height[fx0 * gh + fz0];
                } else {
                    java.util.Arrays.sort(hBuf, 0, hvN);
                    surfaceY = hBuf[hvN / 2];
                }

                // ---- 2) 水面：柱顶抬到水面，涂水色 ----
                int i0 = fx0 * gh + fz0;
                double wY = snap.waterY[i0];
                boolean hasWater = !Double.isNaN(wY) && wY > surfaceY + 0.01;
                double topY = hasWater ? wY : surfaceY;

                // ---- 3) 柱顶永远向上（保底 WATER_SINK）----
                float ratio = (float) Math.max((topY - base) / sp, WATER_SINK);
                c.top = ratio * RELIEF;
                c.water = hasWater;
                c.surfaceY = topY;

                // ---- 4) 四角（扩半格消除光栅化漏缝）----
                c.x0 = modelEdge(gw, fx0) - (float) overlap;
                c.x1 = modelEdge(gw, fx1 + 1) + (float) overlap;
                c.y0 = modelEdge(gh, fz0) - (float) overlap;
                c.y1 = modelEdge(gh, fz1 + 1) + (float) overlap;

                // ---- 5) 多数群系 → 代表细格 ----
                int n = 0;
                for (int dx = fx0; dx <= fx1; dx++) {
                    for (int dz = fz0; dz <= fz1; dz++) {
                        int b = snap.biomeClassId[dx * gh + dz];
                        int found = -1;
                        for (int k = 0; k < n; k++) if (voteIds[k] == b) { found = k; break; }
                        if (found < 0) { voteIds[n] = b; voteCnts[n] = 1; n++; }
                        else voteCnts[found]++;
                    }
                }
                int majority = -1, bestCnt = 0;
                for (int k = 0; k < n; k++) {
                    if (voteCnts[k] > bestCnt) { bestCnt = voteCnts[k]; majority = voteIds[k]; }
                }
                int mfx = fx0, mfz = fz0;
                boolean picked = false;
                for (int dx = fx0; dx <= fx1 && !picked; dx++) {
                    for (int dz = fz0; dz <= fz1; dz++) {
                        if (snap.biomeClassId[dx * gh + dz] == majority) {
                            mfx = dx; mfz = dz; picked = true; break;
                        }
                    }
                }

                // ---- 6) 坡度光照（真实方块 Y，分母 2×柱间距）----
                int mi = mfx * gh + mfz;
                boolean water = snap.isWater(mi);
                double light = slopeLight(yGrid, gw, gh, mfx, mfz, water, blocksPerPillar);

                // ---- 7) 未采到 ⇒ top<0，渲染器跳过 ----
                if (!snap.hasCell(fx0, fz0)) {
                    c.top = -1f;
                    c.alpha = 0;
                    c.rgb = 0;
                    c.bornAt = 0L;
                    out.ready[out.readyCount++] = col;
                    continue;
                }
                // ★★★ v23【修"沙盘整片平板、完全不对"】★★★
                //   这里原先是 {@code c.bornAt = now;} —— 把"当前帧时间"当成出生时刻。
                //   而调用方紧接着就 {@code applyGrow(cols, now)} 算
                //   {@code t = (now - bornAt) / FADE_MS} ⇒ <b>t 恒为 0</b>
                //   ⇒ {@code smooth(0)=0} ⇒ {@code c.top = 0}（所有柱子高度归零）
                //   ⇒ {@code alpha = 255×FADE_FLOOR = 63}（全图 25% 透明）。
                //   实机表现：一块<b>没有起伏、没有侧墙、半透明</b>的彩色平板。
                //
                // ★ v25：在此基础上再叠加"按到中心距离递延"（见上方 animBase 注释），
                //   让整幅图像参考项目那样<b>从中心一圈圈长出来</b>。
                double distNorm = Math.min(1.0, Math.hypot(cx - centerX0, cz - centerZ0) / halfDiag);
                c.bornAt = animBase + (long) (distNorm * SPREAD_MS);
                // 生长动画未到 ⇒ 不画（由 applyGrow 处理）
                c.alpha = 0xFF;

                // ---- 8) 上色 ----
                //   几何层只负责"与 Cell 无关"的部分（水面 / 群系本色）；
                //   其余图层的底色由调用方通过 fallbackRgb 传入
                //   （避免在几何层复制一整套色带 —— 那会变成"第二套调色板"）。
                int fallback = colorSrc != null ? colorSrc.colorAt(mfx, mfz, snap) : 0x505050;
                int rgb = SandboxPalette.colorForCellView(layer, majority,
                        snap.surfaceY(mi, seaLevel), seaLevel, snap.snow[mi], fallback);
                if (hasWater) {
                    rgb = SandboxPalette.waterColor(topY, seaLevel);
                }
                c.rgb = SandboxPalette.scale(rgb, light);

                // ---- 9) 边缘渐隐（方形铺满时恒 1）----
                c.edgeFade = 1.0f;
                out.ready[out.readyCount++] = col;
            }
        }
        return out;
    }

    /** 坡度光照（真实方块 Y）：{@code slope = ΔY / (2 × 列间距)}。 */
    public static double slopeLight(double[] yGrid, int gw, int gh, int gx, int gz,
                                    boolean water, double blocksPerPillar) {
        double h = yGrid[gx * gh + gz];
        double left = gx > 0 ? yGrid[(gx - 1) * gh + gz] : h;
        double right = gx < gw - 1 ? yGrid[(gx + 1) * gh + gz] : h;
        double up = gz > 0 ? yGrid[gx * gh + (gz - 1)] : h;
        double down = gz < gh - 1 ? yGrid[gx * gh + (gz + 1)] : h;
        return SandboxShade.lightForBlocks(left, right, up, down, blocksPerPillar, water);
    }

    // =================================================================
    // 五、深度排序
    // =================================================================

    /**
     * 计数排序：从<b>远到近</b>（yardsite {@code sortByDepth()} 694-714）。
     *
     * <p>合批时深度测试是关的，遮挡完全靠提交顺序 ⇒ 这个顺序错了会看到
     * 柱子互相穿透。</p>
     *
     * @return 长度 {@code readyCount} 的柱下标数组
     */
    public static int[] sortByDepth(Columns cols, float[] r, int[] outBuffer) {
        int[] counts = new int[BUCKETS];
        int[] write = new int[BUCKETS];
        int[] bucket = new int[cols.readyCount];
        int[] sorted = (outBuffer != null && outBuffer.length >= cols.readyCount)
                ? outBuffer : new int[Math.max(1, cols.readyCount)];

        for (int k = 0; k < cols.readyCount; k++) {
            int col = cols.ready[k];
            Column c = cols.items[col];
            float mx = modelOf(cols.colCount, col / cols.colRows);
            float my = modelOf(cols.colRows, col % cols.colRows);
            float depth = r[6] * mx + r[7] * my + r[8] * Math.max(c.top, 0f);
            int slot = clampI((int) ((depth + 1.8f) * (BUCKETS / 3.6f)), 0, BUCKETS - 1);
            bucket[k] = slot;
            counts[slot]++;
        }
        int sum = 0;
        for (int b = 0; b < BUCKETS; b++) {
            write[b] = sum;
            sum += counts[b];
        }
        for (int k = 0; k < cols.readyCount; k++) {
            sorted[write[bucket[k]]++] = cols.ready[k];
        }
        return sorted;
    }

    /**
     * 逐柱生长动画：{@code t=(now-born)/520ms}，柱高 {@code ×smooth(t)}，
     * alpha 从 25% 淡入（yardsite {@code collect()} 683-690 行）。
     *
     * <p>★ 必须在 {@link #buildColumns} 之后调用：它要读到原始的 {@code top}
     * 才能按比例缩短，重复调用会累乘 ⇒ 传 {@code originalTops} 副本更安全，
     * 这里直接原地改 {@code c.top}，调用方每次都用新构建的 {@code Columns}。</p>
     */
    public static void applyGrow(Columns cols, long now) {
        for (int k = 0; k < cols.readyCount; k++) {
            Column c = cols.items[cols.ready[k]];
            if (c.top < 0f) continue;                 // 未采到，保持不画
            if (c.bornAt == 0L) { c.alpha = 0; continue; }
            float t = clamp((now - c.bornAt) / (float) FADE_MS, 0f, 1f);
            float s = t * t * (3.0f - 2.0f * t);      // yardsite smooth(t)
            c.top = c.top * s;
            float a = (FADE_FLOOR + (1.0f - FADE_FLOOR) * s) * c.edgeFade;
            c.alpha = (int) (255.0f * a);
        }
    }

    /**
     * <b>屏幕坐标 → 柱坐标</b>（平面近似；仅用于验证"平面部分数学正确"）。
     *
     * <h2>⚠ 这是近似，实机 hover 必须用视线推进</h2>
     * <p>完整投影是 {@code sx = r0·mx + r1·my + r2·mz}、
     * {@code sy = r3·mx + r4·my + r5·mz} —— <b>三个未知数、两个方程</b>，
     * 单独解不出来。而 {@code r5}（mz 对屏幕 y 的系数）实测达 <b>-0.81</b>：
     * 柱高每 0.1，屏幕 y 就移动 0.081·scale 像素 ⇒ <b>忽略 mz 会有大偏差</b>
     * （探针实测偏 2 个柱宽）。</p>
     *
     * <p>⇒ 实机用 {@code SandboxRasterizer.pick} 的<b>视线推进</b>
     * （沿深度方向扫 t，找出第一个被柱子挡住的点），
     * 本方法只作平面基准验证用。</p>
     */
    public static int[] pickWide(Columns cols, float[] r, float scale,
                                 float originX, float originY,
                                 int mx, int my, int viewW, int viewH) {
        // 正向 sx = r0·mx + r1·my（mz=0 平面），sy = r3·mx + r4·my
        float a11 = r[0], a12 = r[1], a21 = r[3], a22 = r[4];
        float det = a11 * a22 - a12 * a21;
        if (Math.abs(det) < 1e-6f) return new int[]{-1, -1};
        float px = (mx - originX) / scale;
        float py = (my - originY) / scale;
        float mxm = (a22 * px - a12 * py) / det;
        float mym = (-a21 * px + a11 * py) / det;
        // modelOf 的逆：m = (i+0.5)/n*2-1  ⇒  i = (m+1)/2*n - 0.5
        int ci = Math.round((mxm + 1f) * 0.5f * cols.colCount - 0.5f);
        int cj = Math.round((mym + 1f) * 0.5f * cols.colRows - 0.5f);
        ci = clampI(ci, 0, cols.colCount - 1);
        cj = clampI(cj, 0, cols.colRows - 1);
        return new int[]{ci, cj};
    }

    /**
     * <b>拖动 → 世界中心位移</b>（纯函数；探针与实机共用）。
     *
     * <h2>为什么必须是世界位移而不是屏幕平移</h2>
     * <p>我们是<b>全屏单层地图</b>，没有参考项目"切到区块精读层"的退路 ——
     * 只平移屏幕像素会让地形边缘露出空白。用户原话：
     * 「我还不能移动位置。明明参考项目可以的。」</p>
     *
     * <h2>换算</h2>
     * <p>屏幕位移 → 模型单位（先按 widthScale 还原横向撑开）→ 地面两轴反解
     * → 模型单位 → 方块（模型跨度 2 覆盖 {@code colCount} 根柱，
     * 每柱 {@code perColumn} 细格，每细格 {@code blockStep} 方块）。</p>
     *
     * @param colCount  当前柱数（每边）
     * @param perColumn 每柱细格数
     * @param blockStep 每细格方块数
     * @param out       输出 [dWorldX, dWorldZ]（中心该移动的量，已取反号）
     */
    public static void dragToBlocks(float[] r, double dx, double dy,
                                    int viewW, int viewH, float widthScale,
                                    int colCount, int perColumn, double blockStep,
                                    float[] out) {
        // 兼容重载：无绘制 scale 时退回"包围盒装进视口"
        dragToBlocksWithScale(r, dx, dy, fitScaleWide(r, viewW, viewH),
                widthScale, colCount, perColumn, blockStep, out);
    }

    /**
     * <b>拖动换算（★ v23：用绘制时的同一个 scale）</b>。
     *
     * <h2>为什么必须传 scale</h2>
     * <p>此前这里自己算 {@code fitScaleWide(r, viewW, viewH)}，而 {@code draw()} 用的是
     * {@code scaleOf(min(w,h), zoom) = size × 0.46 × zoom}。两者在默认参数下
     * 相差 <b>2.6 倍</b>（AREA×0.35 vs AREA×0.92）⇒ 拖动一格屏幕位移换算出的
     * 世界位移只有应有的 38% ⇒ <b>拖动明显跟不上手</b>。</p>
     *
     * @param scale 绘制时实际使用的屏幕缩放（像素/模型单位）
     */
    public static void dragToBlocksWithScale(float[] r, double dx, double dy, float scale,
                                             float widthScale,
                                             int colCount, int perColumn, double blockStep,
                                             float[] out) {
        if (scale <= 0f) { out[0] = 0f; out[1] = 0f; return; }
        float a = (float) (dx / scale) / widthScale;
        float b = (float) (dy / scale) / widthScale;
        // 地面两行（高度项对水平拖动无贡献）
        float det = r[0] * r[4] - r[1] * r[3];
        float mx, my;
        if (Math.abs(det) < 1.0E-4f) {
            mx = a; my = b;
        } else {
            mx = (r[4] * a - r[1] * b) / det;
            my = (r[0] * b - r[3] * a) / det;
        }
        // 模型单位 → 方块
        double blocksPerModel = (Math.max(1, colCount) * 0.5) * perColumn * blockStep;
        out[0] = (float) (-mx * blocksPerModel);
        out[1] = (float) (-my * blocksPerModel);
    }

    /** 投影单点（探针出图与 hover 校验共用）。 */
    public static float[] projectPoint(float[] r, float scale, float ox, float oy,
                                       float modelX, float modelY, float modelZ) {
        float sx = ox + scale * (r[0] * modelX + r[1] * modelY + r[2] * modelZ);
        float sy = oy + scale * (r[3] * modelX + r[4] * modelY + r[5] * modelZ);
        float depth = r[6] * modelX + r[7] * modelY + r[8] * modelZ;
        return new float[]{sx, sy, depth};
    }

    public static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 顶面高度吸附到 {@code step} 的整数倍 —— <b>判据/顶面/侧墙必须三处共用同一值</b>。
     *
     * <p>曾出现"判据用吸附值、绘制用原值"⇒ 每根柱都留 1px 暗线，128 行叠成扫描线。</p>
     */
    public static float snapTop(float v, float step) {
        if (step <= 0f) return v;
        return Math.round(v / step) * step;
    }
}
