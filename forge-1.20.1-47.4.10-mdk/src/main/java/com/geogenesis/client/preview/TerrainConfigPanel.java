package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.ControlPoint;
import com.geogenesis.client.preview.mixer.DualRangeChart;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.client.preview.mixer.SnowLineChart;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 地形配置面板（地形页签）。
 *
 * <p>布局：
 * - 标题栏「▸/▾ 地形高度图」（可点击折叠）
 * - 展开：DualRangeChart（9 个类型，全方形控制点） + 每类型 lo/hi 双滑块
 *
 * <p>所有 9 类型均可自由配置：
 * - 海洋 lo = 上一个类型的 depth（深海 lo 固定 -1），hi = 当前类型的 depth
 * - 陆地 lo = center - halfRange，hi = center + halfRange
 * - 滑块 ↔ 控制点双向同步
 */
public class TerrainConfigPanel extends ConfigPanel {

    private final DualRangeChart chart = new DualRangeChart();
    private final List<ParamSlider> loSliders = new ArrayList<>();
    private final List<ParamSlider> hiSliders = new ArrayList<>();
    private final List<DualRangeChart.Slot> slots = new ArrayList<>();

    private boolean collapsed = false;

    // 雪线
    private final SnowLineChart snowChart = new SnowLineChart();
    private final List<ParamSlider> snowSliders = new ArrayList<>();
    private final MixerPanel snowPanel = new MixerPanel("雪线");

    private static final int ROW_H = 16;
    private static final int CHART_H = 130;
    private static final int HEADER_H = 18;
    private static final int ROW_LABEL_W = 38;
    private static final int Y_VAL_W = 30;
    private static final int SLIDER_GAP = 4;
    private static final int SIDE_PAD = 8;

    public TerrainConfigPanel() {
        snowPanel.setContentHeight(182);
        chart.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        // 控制点拖动每帧：实时同步当前滑块（不写 config，不触发 markDirty）
        chart.setOnValueChanged(() -> {
            int idx = chart.getDraggingSlotIndex();
            if (idx < 0) return;
            double e = chart.yToEForDragging();
            boolean isHi = chart.isDraggingHi();
            // 更新当前拖动滑块
            if (isHi) hiSliders.get(idx).setCurrentValue(e);
            else loSliders.get(idx).setCurrentValue(e);
            // 注意：海洋类型独立控制，不耦合相邻 slot
        });
        // 控制点 release → 同步所有滑块 + 触发预览重建
        chart.setOnControlPointCommitted(() -> {
            // 诊断：记录所有槽位提交前的值
            StringBuilder sb = new StringBuilder();
            sb.append("CP_COMMIT [");
            for (int i = 0; i < slots.size(); i++) {
                DualRangeChart.Slot s = slots.get(i);
                double lo = s.loGetter().getAsDouble();
                double hi = s.hiGetter().getAsDouble();
                sb.append(s.name()).append("=").append(String.format("%.4f/%.4f", lo, hi));
                if (i < slots.size() - 1) sb.append(", ");
            }
            sb.append("]");
            System.out.println(sb.toString());

            chart.refreshFromConfig();
            for (int i = 0; i < loSliders.size(); i++) {
                DualRangeChart.Slot s = slots.get(i);
                loSliders.get(i).setCurrentValue(s.loGetter().getAsDouble());
                hiSliders.get(i).setCurrentValue(s.hiGetter().getAsDouble());
            }
            if (onMarkDirty != null) onMarkDirty.run();
        });
    }

    public int getY() { return baseY; }
    public boolean isCollapsed() { return collapsed; }
    public void setCollapsed(boolean v) { this.collapsed = v; }

    public void buildFromConfig() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        Consumer<Double> onChange = v -> { if (onMarkDirty != null) onMarkDirty.run(); };
        int seaLevel = c.seaLevel.get();
        int maxY = c.maxY.get();
        int minY = c.minY.get();
        double peakFrac = c.peakHeightFraction.get();
        chart.setYWorldRange(minY, maxY);
        // 与 HeightCurve.heightFromE() 一致：e≤0 用 (seaLevel - minY)，e>0 用 (maxY - seaLevel) × peakFraction
        chart.setEToWorldY(e -> {
            if (e <= 0.0) {
                return seaLevel - (-e) * (seaLevel - minY);
            } else {
                return seaLevel + e * (maxY - seaLevel) * peakFrac;
            }
        });

        slots.clear();
        loSliders.clear();
        hiSliders.clear();
        
        // 存储每个 slot 的默认值
        List<Double> defaultLoValues = new ArrayList<>();
        List<Double> defaultHiValues = new ArrayList<>();

        // 海洋类型（独立 lo/hi，与陆地类型一致）
        slots.add(oceanIndependentSlot("深海", 0xFF1A4D80, c.deepOceanLo, c.deepOceanHi));
        defaultLoValues.add(c.deepOceanLo.getDefault());
        defaultHiValues.add(c.deepOceanHi.getDefault());
        
        slots.add(oceanIndependentSlot("大陆架", 0xFF3070A0, c.shelfLo, c.shelfHi));
        defaultLoValues.add(c.shelfLo.getDefault());
        defaultHiValues.add(c.shelfHi.getDefault());
        
        slots.add(oceanIndependentSlot("洋中脊", 0xFF5A8FB0, c.subRidgeLo, c.subRidgeHi));
        defaultLoValues.add(c.subRidgeLo.getDefault());
        defaultHiValues.add(c.subRidgeHi.getDefault());
        
        slots.add(oceanIndependentSlot("海山", 0xFF4A90B0, c.seamountLo, c.seamountHi));
        defaultLoValues.add(c.seamountLo.getDefault());
        defaultHiValues.add(c.seamountHi.getDefault());
        
        slots.add(oceanIndependentSlot("湖泊", 0xFF2080D0, c.lakeLo, c.lakeHi));
        defaultLoValues.add(c.lakeLo.getDefault());
        defaultHiValues.add(c.lakeHi.getDefault());
        
        slots.add(oceanIndependentSlot("河流", 0xFF3090E0, c.riverLo, c.riverHi));
        defaultLoValues.add(c.riverLo.getDefault());
        defaultHiValues.add(c.riverHi.getDefault());

        // 陆地类型（可编辑：绑定 spline 控制点字段 Val0+Val1，同步写保持水平线，拖动真正影响地形）
        slots.add(landSplineSlot("平原", 0xFF44AA66, c.plainLoVal0, c.plainLoVal1, c.plainHiVal0, c.plainHiVal1));
        defaultLoValues.add(c.plainLoVal0.getDefault());
        defaultHiValues.add(c.plainHiVal0.getDefault());

        slots.add(landSplineSlot("丘陵", 0xFF88CC44, c.hillsLoVal0, c.hillsLoVal1, c.hillsHiVal0, c.hillsHiVal1));
        defaultLoValues.add(c.hillsLoVal0.getDefault());
        defaultHiValues.add(c.hillsHiVal0.getDefault());

        slots.add(landSplineSlot("山脉", 0xFF884422, c.mountLoVal0, c.mountLoVal1, c.mountHiVal0, c.mountHiVal1));
        defaultLoValues.add(c.mountLoVal0.getDefault());
        defaultHiValues.add(c.mountHiVal0.getDefault());

        slots.add(landSplineSlot("高原", 0xFFCC8844, c.platLoVal0, c.platLoVal1, c.platHiVal0, c.platHiVal1));
        defaultLoValues.add(c.platLoVal0.getDefault());
        defaultHiValues.add(c.platHiVal0.getDefault());

        slots.add(landSplineSlot("盆地", 0xFFAA6644, c.basinLoVal0, c.basinLoVal1, c.basinHiVal0, c.basinHiVal1));
        defaultLoValues.add(c.basinLoVal0.getDefault());
        defaultHiValues.add(c.basinHiVal0.getDefault());

        chart.setSlots(slots);

        Function<Double, String> fmt = v -> String.format("%.4f", v);

        // 创建 9 对滑块：全部可调（深海 lo 和海岸 hi 固定不可调）
        // 海洋 depth 字段范围 [-1,0]，所以海洋可调滑块范围用 [-1,0]（避免写入正数触发 Forge 修正循环）
        // 陆地滑块保持 [-1,1]
        for (int i = 0; i < slots.size(); i++) {
            DualRangeChart.Slot s = slots.get(i);
            boolean loRo = (s.loSetter() == null);
            boolean hiRo = (s.hiSetter() == null);
            double loVal = s.loGetter().getAsDouble();
            double hiVal = s.hiGetter().getAsDouble();
            Function<Double, String> formatter = fmt;  // 统一用 %.4f

            double slMax = (i < 4) ? 0.0 : 1.0;  // 海洋滑块上限 0.0，陆地保持 1.0
            ParamSlider lo = makeSlider(loVal, -1.0, slMax, formatter, s.loSetter(), false, i, false);
            lo.setDefaultValue(defaultLoValues.get(i));
            lo.active = !loRo;
            loSliders.add(lo);

            ParamSlider hi = makeSlider(hiVal, -1.0, slMax, formatter, s.hiSetter(), false, i, true);
            hi.setDefaultValue(defaultHiValues.get(i));
            hi.active = !hiRo;
            hiSliders.add(hi);
        }
        // 设置地形类型滑块 tooltip
        String[] typeTooltips = {
            "深海", "大陆架", "洋中脊", "海山", "湖泊", "河流",
            "平原", "丘陵", "山脉", "高原", "盆地"
        };
        for (int i = 0; i < loSliders.size() && i < typeTooltips.length; i++) {
            String name = typeTooltips[i];
            loSliders.get(i).setTooltipText(name + " 下限（地形 e-space 最低值，低于此值不留该类型）");
            hiSliders.get(i).setTooltipText(name + " 上限（地形 e-space 最高值，高于此值不留该类型）");
        }

        // ===== 雪线（双曲线：温度×湿度） =====
        buildSnowSection(c, onChange);
    }

    private void buildSnowSection(GeoGenesisConfig c, Consumer<Double> onChange) {
        snowChart.setXRange(-1.0, 1.0);
        snowChart.setYRange(c.minY.get(), c.maxY.get());
        snowChart.setSeaLevel(c.seaLevel.get());
        snowChart.setConfigBindings(
            () -> c.snowLine.get(), v -> { c.snowLine.set(v); onChange.accept(0.0); },
            () -> c.snowLatitudeInfluence.get(), v -> { c.snowLatitudeInfluence.set(v); onChange.accept(0.0); },
            () -> c.snowHumidityInfluence.get(), v -> { c.snowHumidityInfluence.set(v); onChange.accept(0.0); }
        );
        final int finalSnowMinY = c.minY.get();
        final int finalSnowMaxY = c.maxY.get();
        snowChart.setOnPointsChanged(v -> {
            if (snowSliders.size() >= 6) {
                snowSliders.get(0).setCurrentValue(v[0]);
                snowSliders.get(1).setCurrentValue(v[1]);
                snowSliders.get(2).setCurrentValue(v[2]);
                snowSliders.get(3).setCurrentValue(v[3]);
                snowSliders.get(4).setCurrentValue(v[4]);
                snowSliders.get(5).setCurrentValue(v[5]);
            }
        });

        snowSliders.clear();

        // 冷端中点（温度=-1 时的中曲线雪线高度）
        ParameterConfigPanel.addYSlider(snowSliders, "寒带中心",
            () -> (snowChart.eval(-1.0, 0.0)),
            v -> {
                double warmCenter = snowChart.eval(1.0, 0.0);
                double seaLevelY = c.seaLevel.get();
                double landRange = (double)(finalSnowMaxY - seaLevelY);
                double ratioCold = Math.max(0, Math.min(1, (v - seaLevelY) / landRange));
                double ratioWarm = Math.max(0, Math.min(1, (warmCenter - seaLevelY) / landRange));
                c.snowLine.set((ratioCold + ratioWarm) / 2);
                c.snowLatitudeInfluence.set(Math.max(0, Math.min(0.6, ratioWarm - ratioCold)));
                snowChart.refreshPoints();
                onChange.accept(0.0);
            },
            finalSnowMinY, finalSnowMaxY, v -> String.format("%.0f", v));

        // 暖端中点（温度=+1 时的中曲线雪线高度）
        ParameterConfigPanel.addYSlider(snowSliders, "暖带中心",
            () -> (snowChart.eval(1.0, 0.0)),
            v -> {
                double coldCenter = snowChart.eval(-1.0, 0.0);
                double seaLevelY = c.seaLevel.get();
                double landRange = (double)(finalSnowMaxY - seaLevelY);
                double ratioCold = Math.max(0, Math.min(1, (coldCenter - seaLevelY) / landRange));
                double ratioWarm = Math.max(0, Math.min(1, (v - seaLevelY) / landRange));
                c.snowLine.set((ratioCold + ratioWarm) / 2);
                c.snowLatitudeInfluence.set(Math.max(0, Math.min(0.6, ratioWarm - ratioCold)));
                snowChart.refreshPoints();
                onChange.accept(0.0);
            },
            finalSnowMinY, finalSnowMaxY, v -> String.format("%.0f", v));

        // 湿度带宽（干燥-湿润曲线之间的高度差）
        ParameterConfigPanel.addYSlider(snowSliders, "湿度带宽",
            () -> (snowChart.eval(-1.0, -1.0) - snowChart.eval(-1.0, 1.0)),
            v -> {
                double seaLevelY = c.seaLevel.get();
                double landRange = (double)(finalSnowMaxY - seaLevelY);
                double bwRatio = Math.max(0, Math.min(0.5, v / landRange));
                c.snowHumidityInfluence.set(bwRatio);
                snowChart.refreshPoints();
                onChange.accept(0.0);
            },
            0, finalSnowMaxY - finalSnowMinY, v -> String.format("%.0f", v));

        double seaLev = c.seaLevel.getDefault();
        double landRange = (double)(finalSnowMaxY - seaLev);
        for (int i = 0; i < 3; i++) {
            snowSliders.get(i).setTooltipText(i == 0 ? "寒带中心（温度=-1，湿度=0 时的雪线高度）" :
                i == 1 ? "暖带中心（温度=+1，湿度=0 时的雪线高度）" :
                "湿度带宽（干燥-湿润曲线之间的高度差）");
            snowSliders.get(i).setDefaultValue(c.snowLine.getDefault() * landRange + seaLev);
        }
        // 雪线滑块重置时恢复全部三个配置字段
        for (ParamSlider s : snowSliders) {
            s.setOnReset(() -> {
                c.snowLine.set(c.snowLine.getDefault());
                c.snowLatitudeInfluence.set(c.snowLatitudeInfluence.getDefault());
                c.snowHumidityInfluence.set(c.snowHumidityInfluence.getDefault());
                snowChart.refreshPoints();
                onChange.accept(0.0);
            });
        }
        snowChart.refreshPoints();
    }

    /** 刷新技术含量相关图表（雪线Y范围、地形图e→Y映射）。由 GeoGenesisConfigScreen tab 切换时调用 */
    public void refreshHeightDependent() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        int seaLevel = c.seaLevel.get();
        int maxY = c.maxY.get();
        int minY = c.minY.get();
        double peakFrac = c.peakHeightFraction.get();

        // 地形图 e→Y 映射（与 HeightCurve.heightFromE 一致，陆地侧乘 peakFraction）
        chart.setYWorldRange(minY, maxY);
        chart.setEToWorldY(e -> {
            if (e <= 0.0) return seaLevel - (-e) * (seaLevel - minY);
            else return seaLevel + e * (maxY - seaLevel) * peakFrac;
        });
        chart.refreshFromConfig();

        // 雪线图 Y 范围
        snowChart.setYRange(minY, maxY);
        snowChart.setSeaLevel(seaLevel);
        snowChart.refreshPoints();
    }

    /** 创建带联动钩子的 ParamSlider：onChange 实时同步 chart 自身+钳制 lo≤hi，onValueCommitted 写配置 */
    private ParamSlider makeSlider(double val, double min, double max,
                                    Function<Double, String> fmt,
                                    DoubleConsumer setter, boolean qmark,
                                    int slotIdx, boolean isHi) {
        ParamSlider[] holder = new ParamSlider[1];
        // onChange: 拖动时实时同步 chart 对应控制点 + 钳制 lo ≤ hi 不翻转（不写 config）
        holder[0] = new ParamSlider(0, 0, 100, min, max, val, v -> {
            // 实时钳制 lo ≤ hi
            DualRangeChart.Slot slot = slots.get(slotIdx);
            if (!isHi) {
                // 拖动 lo：不能超过当前 hi
                double hiVal = slot.hiGetter().getAsDouble();
                if (v > hiVal) { v = hiVal; holder[0].setCurrentValue(v); }
            } else {
                // 拖动 hi：不能低于当前 lo
                double loVal = slot.loGetter().getAsDouble();
                if (v < loVal) { v = loVal; holder[0].setCurrentValue(v); }
            }
            chart.updateControlPoint(slotIdx, isHi, v);
        }, fmt);
        // onValueCommitted: release 时写 config + 触发预览
        if (setter != null) {
            holder[0].setOnValueCommitted(() -> {
                // 诊断：记录滑块提交前所有槽位值
                StringBuilder sb = new StringBuilder("SLIDER_COMMIT [");
                for (int i = 0; i < slots.size(); i++) {
                    DualRangeChart.Slot s = slots.get(i);
                    double lo = s.loGetter().getAsDouble();
                    double hi = s.hiGetter().getAsDouble();
                    sb.append(s.name()).append("=").append(String.format("%.4f/%.4f", lo, hi));
                    if (i < slots.size() - 1) sb.append(", ");
                }
                sb.append("]");
                System.out.println(sb.toString());

                setter.accept(holder[0].getCurrentValue());
                chart.refreshFromConfig();
                if (onMarkDirty != null) onMarkDirty.run();
            });
        }
        return holder[0];
    }

    /** 海洋 slot：lo = prevDepth（null 表示固定 -1），hi = depth（null 表示固定 0.02） */
    private static DualRangeChart.Slot oceanSlot(String name, int color,
        ForgeConfigSpec.DoubleValue prevDepthCfg, ForgeConfigSpec.DoubleValue depthCfg) {
        double loVal = (prevDepthCfg != null) ? prevDepthCfg.get() : -1.0;
        double hiVal = (depthCfg != null) ? depthCfg.get() : 0.02;
        // setter 加邻接约束 clamp：保证 lo ≤ hi (即当前 slot 的 lo 不超过 hi)
        // loSetter 写入 prevDepthCfg (F)：clamp 到 [-1, current depthCfg] (即 F ≤ G)
        DoubleConsumer loSet = (prevDepthCfg != null) ? v -> {
            double upper = (depthCfg != null) ? depthCfg.get() : 0.0;
            prevDepthCfg.set(clampOceanLo(v, upper));
        } : null;
        // hiSetter 写入 depthCfg (G)：clamp 到 [current prevDepthCfg, 0] (即 G ≥ F)
        DoubleConsumer hiSet = (depthCfg != null) ? v -> {
            double lower = (prevDepthCfg != null) ? prevDepthCfg.get() : -1.0;
            depthCfg.set(clampOceanHi(v, lower));
        } : null;
        DoubleSupplier loGet = (prevDepthCfg != null) ? prevDepthCfg::get : () -> -1.0;
        DoubleSupplier hiGet = (depthCfg != null) ? depthCfg::get : () -> 0.02;
        return new DualRangeChart.Slot(name, color, loVal, hiVal, loSet, hiSet, loGet, hiGet);
    }

    /** 海洋 lo setter 写入：clamp 到 [-1, upper]，保证 lo ≤ upper (即当前 slot lo ≤ 当前 slot hi) */
    private static double clampOceanLo(double v, double upper) {
        return Math.max(-1.0, Math.min(upper, v));
    }

    /** 海洋 hi setter 写入：clamp 到 [lower, 0]，保证 hi ≥ lower (即当前 slot hi ≥ 当前 slot lo) */
    private static double clampOceanHi(double v, double lower) {
        return Math.max(lower, Math.min(0.0, v));
    }

    /** 海洋类型独立 slot：使用独立的 lo/hi 配置字段（与陆地类型一致） */
    private static DualRangeChart.Slot oceanIndependentSlot(String name, int color,
        ForgeConfigSpec.DoubleValue loCfg, ForgeConfigSpec.DoubleValue hiCfg) {
        double loVal = loCfg.get();
        double hiVal = hiCfg.get();
        // loSetter：clamp 到 [-1, current hi]，保证 lo ≤ hi
        DoubleConsumer loSet = v -> {
            double upper = hiCfg.get();
            loCfg.set(clampOceanLo(v, upper));
        };
        // hiSetter：clamp 到 [current lo, 0]，保证 hi ≥ lo
        DoubleConsumer hiSet = v -> {
            double lower = loCfg.get();
            hiCfg.set(clampOceanHi(v, lower));
        };
        return new DualRangeChart.Slot(name, color, loVal, hiVal, loSet, hiSet, loCfg::get, hiCfg::get);
    }

    /**
     * 陆地类型可编辑 slot：绑定 spline 控制点配置字段（lo/hi 各 Val0+Val1）。
     * <p>
     * 2026-08-05 修复：原仅写 Val0，但 TypeLandShape 高度混合的
     * {@code hi_t = sampleByType(c, i, 1.0)} 读取 hiSpline.sample(1.0) = <b>Val1</b>
     * （loc1=1.0 控制点）→ 滑块设的"最高"不参与高度计算（blendHi 上限仍是默认 hiVal1，
     * 实机表现为"拉到 150 实际 197"）。修复：setter 同步写 Val0+Val1，样条恒为水平线
     * （常数区间语义），blendHi / maxLandHi / 图例 / softCap 全部一致。
     */
    private static DualRangeChart.Slot landSplineSlot(String name, int color,
        ForgeConfigSpec.DoubleValue loVal0Cfg, ForgeConfigSpec.DoubleValue loVal1Cfg,
        ForgeConfigSpec.DoubleValue hiVal0Cfg, ForgeConfigSpec.DoubleValue hiVal1Cfg) {
        double loVal = loVal0Cfg.get();
        double hiVal = hiVal0Cfg.get();
        // 实时钳制 lo ≤ hi 由 makeSlider.onChange 负责；setter 同步写 Val0+Val1 并触发预览重建
        DoubleConsumer loSet = v -> { loVal0Cfg.set(v); loVal1Cfg.set(v); };
        DoubleConsumer hiSet = v -> { hiVal0Cfg.set(v); hiVal1Cfg.set(v); };
        return new DualRangeChart.Slot(name, color, loVal, hiVal, loSet, hiSet,
            loVal0Cfg::get, hiVal0Cfg::get);
    }



    /** 计算单个滑块宽度 */
    private int sliderWidth() {
        int fixed = ROW_LABEL_W + 2 * (ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP)
                  + 2 * Y_VAL_W + SLIDER_GAP + 2 * SIDE_PAD;
        int slW = (w - fixed) / 2;
        if (slW < 40) slW = 40;
        return slW;
    }

    public int getHeight() {
        int h = 0;
        if (!collapsed) {
            h = HEADER_H + 2 + CHART_H + 6 + loSliders.size() * (ROW_H + 1) + 4;
        } else {
            h = HEADER_H + 2;
        }
        h += snowPanel.getFullHeight() + 4;
        return h;
    }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int ty = top();

        // 标题栏（可折叠）
        int hdrBg = collapsed ? 0xFF2a2f3a : 0xFF1e222c;
        g.fill(x, ty, x + w, ty + HEADER_H, hdrBg);
        g.fill(x, ty + HEADER_H - 1, x + w, ty + HEADER_H, 0xFF333333);
        String icon = collapsed ? "▸" : "▾";
        g.drawString(f, icon, x + 4, ty + (HEADER_H - 8) / 2, 0xFFAAAAAA);
        g.drawString(f, "地形高度图", x + 16, ty + (HEADER_H - 8) / 2, 0xFF66CCFF);

        if (collapsed) return;

        // 高度图
        int chartY = ty + HEADER_H + 2;
        chart.setBounds(x + 4, chartY, w - 8, CHART_H);
        chart.render(g, mx, my);
        chart.renderTooltips(g, mx, my);

        // 滑块行（9 行：深海～盆地）
        int rowTop = chartY + CHART_H + 4;
        int slW = sliderWidth();
        for (int i = 0; i < loSliders.size(); i++) {
            DualRangeChart.Slot s = slots.get(i);
            int rowY = rowTop + i * (ROW_H + 1);
            int cx = x + 4;
            if (i % 2 == 0) g.fill(cx, rowY, cx + w - 8, rowY + ROW_H, 0x14FFFFFF);
            int nameY = rowY + (ROW_H - 8) / 2;
            boolean ro = !loSliders.get(i).active;
            int nameColor = ro ? (s.color() & 0xFFFFFF) | 0x80FFFFFF : s.color();
            g.drawString(f, s.name(), cx, nameY, nameColor);

            // lo 滑块
            ParamSlider lo = loSliders.get(i);
            int loX = cx + ROW_LABEL_W;
            lo.setX(loX); lo.setY(rowY); lo.setWidth(slW);
            lo.render(g, mx, my, 0);
            int loResetX = loX + slW + ParamSlider.RESET_GAP;
            int loValX = loResetX + ParamSlider.RESET_BTN_W + 2;
            int loWy = (int) Math.round(chart.worldY(lo.getCurrentValue()));
            int loColor = ro ? 0xFF556677 : 0xFF88BBDD;
            g.drawString(f, "↓" + signed(loWy), loValX, nameY, loColor);

            // hi 滑块
            ParamSlider hi = hiSliders.get(i);
            int hiX = loValX + Y_VAL_W + SLIDER_GAP;
            hi.setX(hiX); hi.setY(rowY); hi.setWidth(slW);
            hi.render(g, mx, my, 0);
            int hiResetX = hiX + slW + ParamSlider.RESET_GAP;
            int hiValX = hiResetX + ParamSlider.RESET_BTN_W + 2;
            int hiWy = (int) Math.round(chart.worldY(hi.getCurrentValue()));
            int hiColor = ro ? 0xFF887755 : 0xFFFFCC88;
            g.drawString(f, "↑" + signed(hiWy), hiValX, nameY, hiColor);
        }

        // 雪线段
        int snowTop = ty + HEADER_H + 2 + (collapsed ? 0 : (CHART_H + 6 + loSliders.size() * (ROW_H + 1) + 4));
        renderSection(g, mx, my, snowPanel, snowTop, () -> {
            snowChart.setBounds(x + 4, snowPanel.getY() + 16, w - 8, 100);
            snowChart.refreshPoints();
            snowChart.render(g, mx, my);
            snowChart.renderTooltips(g, mx, my);
            int sy = snowPanel.getY() + 16 + snowChart.getHeight() + 4;
            int snowSlW = w - 70;
            for (int i = 0; i < snowSliders.size(); i++) {
                ParamSlider ps = snowSliders.get(i);
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(snowSlW);
                ps.render(g, mx, my, 0);
                ps.renderTooltip(g, mx, my);
            }
        });
    }

    private static final int CHART_SLIDER_H = 20;
    private static String signed(int v) { return v >= 0 ? "+" + v : String.valueOf(v); }

    private void renderSection(GuiGraphics g, int mx, int my, MixerPanel panel, int py,
                               Runnable contentRenderer) {
        panel.setBounds(x + 4, py, w - 8);
        panel.renderHeader(g, mx, my);
        if (!panel.isCollapsed()) contentRenderer.run();
        panel.renderTooltip(g, mx, my);
    }

    // ===== 鼠标 =====

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        int ty = top();
        // 标题栏折叠
        if (mx >= x && mx <= x + w && my >= ty && my <= ty + HEADER_H) {
            collapsed = !collapsed;
            return true;
        }
        if (collapsed) return false;

        if (chart.mouseClicked(mx, my, btn)) return true;

        int slW = sliderWidth();
        int rowTop = ty + HEADER_H + 2 + CHART_H + 4;
        for (int i = 0; i < loSliders.size(); i++) {
            int rowY = rowTop + i * (ROW_H + 1);

            ParamSlider lo = loSliders.get(i);
            if (!lo.active) continue;
            int loX = x + 4 + ROW_LABEL_W;
            lo.setX(loX); lo.setY(rowY); lo.setWidth(slW);
            if (lo.isHoveringReset((int) mx, (int) my)) { lo.resetToDefault(); chart.refreshFromConfig(); return true; }
            if (lo.isMouseOver(mx, my)) return lo.mouseClicked(mx, my, btn);

            int loResetX = loX + slW + ParamSlider.RESET_GAP;
            int loValX = loResetX + ParamSlider.RESET_BTN_W + 2;
            int hiX = loValX + Y_VAL_W + SLIDER_GAP;

            ParamSlider hi = hiSliders.get(i);
            if (!hi.active) continue;
            hi.setX(hiX); hi.setY(rowY); hi.setWidth(slW);
            if (hi.isHoveringReset((int) mx, (int) my)) { hi.resetToDefault(); chart.refreshFromConfig(); return true; }
            if (hi.isMouseOver(mx, my)) return hi.mouseClicked(mx, my, btn);
        }
        // 雪线面板
        int snowTop = ty + HEADER_H + 2 + (collapsed ? 0 : (CHART_H + 6 + loSliders.size() * (ROW_H + 1) + 4));
        snowPanel.setBounds(x + 4, snowTop, w - 8);
        if (snowPanel.hitTestHeader((int) mx, (int) my)) return snowPanel.mouseClicked(mx, my, btn);
        if (!snowPanel.isCollapsed()) {
            if (snowChart.mouseClicked(mx, my, btn)) return true;
            int snowSlW = w - 70;
            int sy = snowTop + 16 + snowChart.getHeight() + 4;
            for (int i = 0; i < snowSliders.size(); i++) {
                ParamSlider ps = snowSliders.get(i);
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(snowSlW);
                if (ps.isHoveringReset((int) mx, (int) my)) { ps.resetToDefault(); return true; }
                if (ps.isMouseOver(mx, my)) return ps.mouseClicked(mx, my, btn);
            }
        }
        return false;
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (chart.mouseDragged(mx, my, btn, dx, dy)) return true;
        for (ParamSlider s : loSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : hiSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        if (!snowPanel.isCollapsed()) {
            if (snowChart.mouseDragged(mx, my, btn, dx, dy)) return true;
            for (ParamSlider s : snowSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        }
        return false;
    }
    public boolean mouseReleased(double mx, double my, int btn) {
        if (chart.mouseReleased(mx, my, btn)) return true;
        for (ParamSlider s : loSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : hiSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        if (!snowPanel.isCollapsed()) {
            snowChart.mouseReleased(mx, my, btn);
            for (ParamSlider s : snowSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        }
        return false;
    }

    /** 渲染标题栏 tooltip */
    public void renderHeaderTooltip(GuiGraphics g, int mx, int my) {
        int ty = top();
        if (mx >= x && mx <= x + w && my >= ty && my <= ty + HEADER_H) {
            var f = Minecraft.getInstance().font;
            String tip = collapsed ? "点击展开" : "点击折叠";
            int tw = f.width(tip) + 8;
            int tx = Math.min(mx + 10, x + w - tw);
            g.fill(tx, my - 14, tx + tw, my - 2, 0xEE000000);
            g.drawString(f, tip, tx + 4, my - 12, 0xFFFFFF);
        }
    }
}
