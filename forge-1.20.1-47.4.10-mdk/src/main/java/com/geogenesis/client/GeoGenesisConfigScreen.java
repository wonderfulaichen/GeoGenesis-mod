package com.geogenesis.client;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.client.preview.PreviewDisplay;
import com.geogenesis.client.preview.GeoPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * GeoGenesis 配置屏（最小可用版）。
 *
 * <p>把侵蚀前基础地形（海拔×起伏 地质过程模型）的全部关键参数暴露为可配置 UI：
 * 左侧分页 [地形]/[参数] 承载 ParamSlider 滑块组，右侧内嵌 PreviewDisplay 实时地图
 * （默认地形类型图层），顶部种子输入，底部 [保存]/[重置]。
 *
 * <p>滑块拖动实时回写 GeoGenesisConfig.INSTANCE（运行期生效），并用本地副本
 * buildParams() 防抖重建预览；[保存] 经 SPEC.save() 落盘 toml，[重置] 回退到打开时快照。
 */
public class GeoGenesisConfigScreen extends Screen {

    /** GeoPalette.PreviewLayer.TERRAIN_TYPE 的枚举序数（11 图层：0=ELEVATION … 9=TERRAIN_TYPE …） */
    private static final int TERRAIN_TYPE_LAYER = 9;

    private final Screen parent;
    private long seed = (long) (Math.random() * Long.MAX_VALUE);

    // 分页：0=地形，1=参数
    private int tab = 0;
    private final String[] TAB_NAMES = {"地形", "参数"};

    /** 滑块描述 */
    private static final class Spec {
        final ForgeConfigSpec.DoubleValue value;
        final String label;
        final double min, max;
        final String tip;
        Spec(ForgeConfigSpec.DoubleValue value, String label, double min, double max, String tip) {
            this.value = value; this.label = label; this.min = min; this.max = max; this.tip = tip;
        }
    }

    private final List<Spec> terrainSpecs = new ArrayList<>();
    private final List<Spec> paramSpecs = new ArrayList<>();
    private final Map<ForgeConfigSpec.DoubleValue, Double> snapshot = new HashMap<>();

    private final List<ParamSlider> sliders = new ArrayList<>();

    private PreviewDisplay preview;
    private EditBox seedBox;
    private Button saveBtn, resetBtn, tabBtns[], layerPrev, layerNext, hydroBtn;
    private int currentMode = TERRAIN_TYPE_LAYER;

    // 布局
    private int panelX, panelW, headerY, listTop, listBottom, rowH = 26;
    private int previewX, previewY, previewW, previewH;

    private int scroll = 0;
    private boolean dirty = false;
    private boolean saved = true;
    private int debounce = 0;

    public GeoGenesisConfigScreen(Screen parent) {
        super(Component.literal("GeoGenesis 配置"));
        this.parent = parent;
    }

    public GeoGenesisConfigScreen() { this(null); }

    @Override
    protected void init() {
        super.init();
        int w = this.width, h = this.height;

        panelX = 10;
        panelW = (w - 40) / 2;
        headerY = 64;
        listTop = headerY + 26;
        listBottom = h - 44;
        previewX = panelX + panelW + 20;
        previewY = 70;
        previewW = w - previewX - 10;
        previewH = listBottom - previewY;

        buildSpecs();

        // 种子输入框
        seedBox = new EditBox(Minecraft.getInstance().font, panelX, 36, panelW, 18,
            Component.literal("Seed"));
        seedBox.setResponder(s -> {
            try { seed = Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        });
        seedBox.setValue(String.valueOf(seed));
        addRenderableWidget(seedBox);

        // 分页标签按钮
        tabBtns = new Button[2];
        for (int i = 0; i < 2; i++) {
            final int ti = i;
            tabBtns[i] = Button.builder(Component.literal(TAB_NAMES[i]), b -> { tab = ti; rebuildSliders(); })
                .pos(panelX + i * 90, headerY).size(84, 20).build();
            addRenderableWidget(tabBtns[i]);
        }

        // 底部保存/重置
        saveBtn = Button.builder(Component.literal("保存"), b -> doSave())
            .pos(panelX, listBottom + 6).size(90, 20).build();
        resetBtn = Button.builder(Component.literal("重置"), b -> doReset())
            .pos(panelX + 100, listBottom + 6).size(90, 20).build();
        addRenderableWidget(saveBtn);
        addRenderableWidget(resetBtn);

        // 预览工具栏：图层切换 + 水文
        layerPrev = Button.builder(Component.literal("◀ 图层"), b -> cycleLayer(-1))
            .pos(previewX, previewY - 28).size(70, 20).build();
        layerNext = Button.builder(Component.literal("图层 ▶"), b -> cycleLayer(1))
            .pos(previewX + previewW - 70, previewY - 28).size(70, 20).build();
        hydroBtn = Button.builder(Component.literal("水文: 关"), b -> toggleHydro())
            .pos(previewX + previewW / 2 - 45, previewY - 28).size(90, 20).build();
        addRenderableWidget(layerPrev);
        addRenderableWidget(layerNext);
        addRenderableWidget(hydroBtn);

        rebuildPreview();      // 构建预览（同时建 terrain）
        rebuildSliders();      // 构建当前分页滑块
    }

    // ===== 参数定义（六处同步铁律：与 GeoGenesisConfig / TerrainParams / toml 一致） =====
    private void buildSpecs() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        // 地形页：省权重 + 省海拔剖面 + 起伏振幅
        terrainSpecs.add(new Spec(c.cratonWeight, "克拉通权重", 0, 5, "平原/丘陵省占比"));
        terrainSpecs.add(new Spec(c.beltWeight, "造山带权重", 0, 5, "山脉省占比（高→崎岖山脉主导）"));
        terrainSpecs.add(new Spec(c.plateauWeight, "高原权重", 0, 5, "高原省占比"));
        terrainSpecs.add(new Spec(c.basinWeight, "盆地权重", 0, 5, "盆地省占比"));
        terrainSpecs.add(new Spec(c.plainBase, "平原基底", 0, 0.3, "克拉通平均海拔基面"));
        terrainSpecs.add(new Spec(c.hillsLow, "丘陵低", 0, 0.5, "丘陵低段海拔"));
        terrainSpecs.add(new Spec(c.hillsHigh, "丘陵高", 0, 0.7, "丘陵高段海拔"));
        terrainSpecs.add(new Spec(c.beltFoothill, "山麓", 0, 0.5, "山脉 foothill 海拔"));
        terrainSpecs.add(new Spec(c.beltPeak, "山峰", 0, 1.0, "山峰极限海拔（≈世界顶）"));
        terrainSpecs.add(new Spec(c.plateauBase, "高原基底", 0, 1.0, "高原低缘海拔"));
        terrainSpecs.add(new Spec(c.plateauTop, "高原顶", 0, 1.0, "高原平顶海拔"));
        terrainSpecs.add(new Spec(c.basinBase, "盆地基底", 0, 0.3, "盆地低洼海拔"));
        terrainSpecs.add(new Spec(c.cratonReliefAmp, "克拉通起伏", 0, 0.6, "平原/丘陵局部起伏"));
        terrainSpecs.add(new Spec(c.beltReliefAmp, "山脉起伏", 0, 0.6, "山脉崎岖度"));
        terrainSpecs.add(new Spec(c.plateauReliefAmp, "高原起伏", 0, 0.6, "高原平顶（低起伏）"));
        terrainSpecs.add(new Spec(c.basinReliefAmp, "盆地起伏", 0, 0.6, "盆地局部起伏"));
        terrainSpecs.add(new Spec(c.beltSharpness, "山脉尖钝", 0.5, 5.0, "pow(n,k)：>1尖峰、=1圆、<1宽缓"));
        terrainSpecs.add(new Spec(c.beltWarpAmp, "山脉扭曲", 0, 500.0, "warp蜿蜒度（0=无扭曲）"));
        terrainSpecs.add(new Spec(c.provMixSharpness, "省混合锐化", 1.0, 5.0, "省权重幂次：>1主导省占优、完整表达峰/谷"));
        terrainSpecs.add(new Spec(c.mountainMaskScale, "山脉区域尺度", 400, 8000, "低频团块限定山脉分布（真实感关键）"));
        terrainSpecs.add(new Spec(c.microDetailScale, "纹理尺度", 200, 2000, "表面纹理周期（大周期舒展）"));
        terrainSpecs.add(new Spec(c.microDetailAmp, "纹理振幅", 0.0, 0.08, "表面纹理振幅（<0.03纹理级）"));

        // 参数页：省尺度 + 分类阈值 + 雪线 + 海陆
        paramSpecs.add(new Spec(c.provinceScale, "省尺度", 500, 10000, "地质省噪声尺度（块）"));
        paramSpecs.add(new Spec(c.elevHigh, "高海拔阈值", 0, 1.0, "海拔×起伏：高海拔分界"));
        paramSpecs.add(new Spec(c.reliefHigh, "高起伏阈值", 0, 0.6, "海拔×起伏：崎岖分界"));
        paramSpecs.add(new Spec(c.peakE, "峰阈值", 0, 1.0, "峰（山脉子型）海拔分界"));
        paramSpecs.add(new Spec(c.snowLine, "雪线海拔", 0, 1.0, "高海拔积雪起始（e 单位）"));
        paramSpecs.add(new Spec(c.snowLatitudeInfluence, "雪线纬度耦合", 0, 0.6, "暖端雪线抬升量"));
        paramSpecs.add(new Spec(c.continentScale, "大陆尺度", 200, 10000, "大陆性噪声尺度"));
        paramSpecs.add(new Spec(c.continentBias, "海陆偏置", -0.6, 0.6, "正=海多，负=陆多"));
        paramSpecs.add(new Spec(c.seabedDetail, "海床细节", 0, 0.2, "海床起伏振幅"));

        // 快照（打开时所有可配值）
        for (Spec s : allSpecs()) snapshot.put(s.value, s.value.get());
    }

    private List<Spec> allSpecs() {
        List<Spec> all = new ArrayList<>(terrainSpecs);
        all.addAll(paramSpecs);
        return all;
    }

    private List<Spec> activeSpecs() { return tab == 0 ? terrainSpecs : paramSpecs; }

    // ===== 滑块构建（当前分页） =====
    private void rebuildSliders() {
        sliders.clear();
        int innerW = panelW - (ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP) - 6;
        Consumer<Double> onChange = v -> { dirty = true; saved = false; debounce = 8; };
        Function<Double, String> fmt = v -> String.format("%.2f", v);
        int i = 0;
        for (Spec s : activeSpecs()) {
            int sy = listTop + i * rowH - scroll;
            ParamSlider ps = new ParamSlider(panelX, sy, innerW, s.min, s.max,
                s.value.get(), onChange, fmt);
            ps.setTooltipText(s.label + "：" + s.tip);
            ps.setDefaultValue(snapshot.get(s.value));
            sliders.add(ps);
            i++;
        }
    }

    // ===== 预览构建（防抖） =====
    private void rebuildPreview() {
        TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        if (preview == null) {
            preview = new PreviewDisplay(previewX, previewY, previewW, previewH, terrain, seed, p);
            preview.setMode(currentMode);
            addRenderableWidget(preview);
        } else {
            preview.setTerrain(terrain, seed, p);
            preview.setMode(currentMode);
        }
        dirty = false;
    }

    private void cycleLayer(int d) {
        currentMode = (currentMode + d + GeoPalette.PreviewLayer.values().length) % GeoPalette.PreviewLayer.values().length;
        if (preview != null) preview.setMode(currentMode);
    }

    private void toggleHydro() {
        if (preview == null) return;
        preview.setHydrology(!preview.isHydrology());
        hydroBtn.setMessage(Component.literal("水文: " + (preview.isHydrology() ? "开" : "关")));
    }

    private void doSave() {
        GeoGenesisConfig.SPEC.save();
        saved = true;
        dirty = false;
    }

    private void doReset() {
        for (Spec s : allSpecs()) s.value.set(snapshot.get(s.value));
        rebuildSliders();
        rebuildPreview();
        saved = false;
        dirty = false;
    }

    private void restoreSnapshot() {
        for (Spec s : allSpecs()) s.value.set(snapshot.get(s.value));
    }

    @Override
    public void tick() {
        super.tick();
        if (dirty && debounce > 0) {
            debounce--;
            if (debounce <= 0) rebuildPreview();
        }
    }

    // ===== 渲染 =====
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        int w = this.width;

        // 标题
        g.drawCenteredString(this.font, "GeoGenesis 地形配置", w / 2, 12, 0xFFFFFF);
        g.drawString(this.font, "种子:", panelX, 22, 0xAAAAAA);

        // 分页激活态高亮
        for (int i = 0; i < 2; i++) {
            boolean on = tab == i;
            int bx = panelX + i * 90;
            g.fill(bx, headerY, bx + 84, headerY + 20, on ? 0x2a3340 : 0x1a1f28);
            g.fill(bx, headerY, bx + 84, headerY + 1, on ? 0x00c896 : 0x444444);
        }

        // 当前图层名
        String layerName = I18nSafe(GeoPalette.PreviewLayer.values()[currentMode].labelKey);
        g.drawString(this.font, "图层: " + layerName, previewX, previewY - 6, 0xAAAAAA);

        // 左栏滑块（裁剪）
        g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        for (ParamSlider s : sliders) s.render(g, mx, my, pt);
        g.disableScissor();
        // tooltip 覆盖层
        for (ParamSlider s : sliders) s.renderTooltip(g, mx, my);

        super.render(g, mx, my, pt);
    }

    private String I18nSafe(String key) {
        String v = net.minecraft.client.resources.language.I18n.get(key);
        return v.equals(key) ? key : v;
    }

    // ===== 鼠标转发（滑块在 scissor 内手动渲染，需手动转发交互） =====
    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (mx >= panelX && mx <= panelX + panelW && my >= listTop && my <= listBottom) {
            for (ParamSlider s : sliders) {
                if (s.isHoveringReset((int) mx, (int) my)) { s.resetToDefault(); dirty = true; saved = false; debounce = 8; return true; }
                if (s.isMouseOver(mx, my)) { return s.mouseClicked(mx, my, b); }
            }
        }
        return super.mouseClicked(mx, my, b);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int b) {
        for (ParamSlider s : sliders) if (s.isFocused()) s.mouseReleased(mx, my, b);
        return super.mouseReleased(mx, my, b);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        for (ParamSlider s : sliders) if (s.isFocused()) { s.mouseDragged(mx, my, b, dx, dy); return true; }
        return super.mouseDragged(mx, my, b, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= panelX && mx <= panelX + panelW) {
            int contentH = activeSpecs().size() * rowH;
            int visibleH = listBottom - listTop;
            int maxScroll = Math.max(0, contentH - visibleH);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 20)));
            rebuildSliders();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void onClose() {
        if (dirty && !saved) restoreSnapshot();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** 用于 ConfigScreenHandler 注册的工厂 */
    public static Screen create(Minecraft mc, Screen parent) {
        return new GeoGenesisConfigScreen();
    }
}
