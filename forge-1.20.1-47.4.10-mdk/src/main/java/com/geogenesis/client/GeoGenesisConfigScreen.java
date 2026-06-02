package com.geogenesis.client;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.NoiseEngine;
import com.geogenesis.worldgen.biome.ClimateBiomeMapper;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.geology.PlateTectonics;
import com.geogenesis.worldgen.hydrology.HydrologySystem;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class GeoGenesisConfigScreen extends Screen {

    private static final int PREVIEW_RES = 256;
    private static final int SAMPLE_RES = 64;
    private static final int COLOR_ALPHA = 0xFF000000;

    private final CreateWorldScreen parent;
    private NoiseEngine previewEngine;
    private ErosionEngine previewErosionEngine;
    private HydrologySystem previewHydrology;
    private PlateTectonics plateSystem;
    private int previewSeed;
    private NativeImage previewImg;
    private DynamicTexture previewTexture;
    private boolean previewDirty = true;

    private float[][] heightCache;
    private float[][] continentCache;
    private float[][] tempCache;
    private float[][] moistCache;
    private float[][] plateBoundaryCache;
    private float[][] plateBiasCache;
    private float[][] plateConvCache;

    private double paramTerrainScale   = 1.00;
    private double paramContinentBias  = 0.00;
    private double paramRidgeWeight    = 0.42;
    private double paramErosionStr     = 0.50;
    private double paramHeightScale    = 1.00;
    private int    paramSeaLevel       = 63;
    private int    paramMaxY           = 256;
    private int    paramOceanDepth     = 32;
    private int    viewMode            = 0;

    private float panX = 0, panZ = 0;
    private float zoomStep = 8f;
    private boolean dragging = false;
    private int dragStartX, dragStartY;
    private float dragStartPanX, dragStartPanZ;
    private int hoverPX = -1, hoverPY = -1;

    private int previewX, previewY, previewW, previewH;
    private int leftPanelW;
    private int toolbarY;

    private static final String[] VIEW_NAMES = {"地形高度", "生物群系", "温度分布", "湿度分布", "板块构造", "侵蚀强度"};
    private static final String[] VIEW_TOOLTIPS = {
        "地形高度图 - 真实地形图配色",
        "生物群系图 - 使用原版群系配色",
        "温度分布图 - 蓝(冷)到红(热)",
        "湿度分布图 - 棕(干)到蓝(湿)",
        "板块边界强度图",
        "侵蚀强度分布图"
    };

    public GeoGenesisConfigScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(Component.literal("GeoGenesis - 世界配置"));
        this.parent = parent;
        PreviewDataLoader.load();
        previewSeed = new Random().nextInt(Integer.MAX_VALUE);
        previewEngine = new NoiseEngine(previewSeed);
        previewErosionEngine = new ErosionEngine(previewEngine, previewSeed);
        previewHydrology = new HydrologySystem(previewSeed, previewEngine);
        plateSystem = new PlateTectonics(previewSeed);
    }

    @Override
    protected void init() {
        int w = width, h = height;

        leftPanelW = Math.max(140, Math.min(200, w / 4));
        int mapLeft = leftPanelW + 8;
        int mapRight = w - 8;
        int mapTop = 36;
        int mapBottom = h - 58;

        previewX = mapLeft;
        previewY = mapTop;
        previewW = mapRight - mapLeft;
        previewH = mapBottom - mapTop;

        toolbarY = 8;

        int sx = 8;
        int sy = 42;
        int sw = leftPanelW - 12;

        sy = addSlider(sx, sy, sw, "地形缩放",   0.5, 2.0, paramTerrainScale,  v -> { paramTerrainScale  = v; markDirty(); });
        sy = addSlider(sx, sy, sw, "高度缩放",   0.5, 1.5, paramHeightScale,   v -> { paramHeightScale   = v; markDirty(); });
        sy = addSlider(sx, sy, sw, "大陆偏置",  -0.3, 0.3, paramContinentBias, v -> { paramContinentBias = v; markDirty(); });
        sy += 6;
        sy = addSlider(sx, sy, sw, "山脊权重",   0.0, 0.8, paramRidgeWeight,   v -> { paramRidgeWeight   = v; markDirty(); });
        sy = addSlider(sx, sy, sw, "侵蚀强度",   0.0, 1.5, paramErosionStr,     v -> { paramErosionStr    = v; markDirty(); });
        sy += 6;
        addRenderableWidget(new IntSlider(sx, sy, sw, 18, "海平面", 0, 128, paramSeaLevel, v -> { paramSeaLevel = (int)Math.round(v); markDirty(); }));
        sy += 20;
        addRenderableWidget(new IntSlider(sx, sy, sw, 18, "地形最高点", 128, 1024, paramMaxY, v -> { paramMaxY = (int)Math.round(v); markDirty(); }));
        sy += 20;
        addRenderableWidget(new IntSlider(sx, sy, sw, 18, "海洋深度", 8, 64, paramOceanDepth, v -> { paramOceanDepth = (int)Math.round(v); markDirty(); }));
        sy += 26;

        int btnW = (sw - 8) / 3;
        for (int i = 0; i < 6; i++) {
            final int fi = i;
            int bx = sx + (i % 3) * (btnW + 4);
            int by = sy + (i / 3) * 22;
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(i + 1)), b -> {
                    viewMode = fi;
                    markDirty();
                }).bounds(bx, by, btnW, 20)
                .tooltip(Tooltip.create(Component.literal(VIEW_TOOLTIPS[i])))
                .build());
        }
        sy += 50;

        addRenderableWidget(Button.builder(Component.literal("随机种子"), b -> {
                previewSeed = new Random().nextInt(Integer.MAX_VALUE);
                previewEngine = new NoiseEngine(previewSeed);
                previewErosionEngine = new ErosionEngine(previewEngine, previewSeed);
                previewHydrology = new HydrologySystem(previewSeed, previewEngine);
                plateSystem = new PlateTectonics(previewSeed);
                panX = 0; panZ = 0;
                markDirty();
            }).bounds(sx, h - 52, sw / 2 - 2, 20).build());

        addRenderableWidget(Button.builder(Component.literal("重置视角"), b -> {
                panX = 0; panZ = 0; zoomStep = 8f;
                markDirty();
            }).bounds(sx + sw / 2 + 2, h - 52, sw / 2 - 2, 20).build());

        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
            .bounds(w - 80, h - 28, 72, 20).build());

        buildPreview();
    }

    private int addSlider(int x, int y, int w, String label,
                          double min, double max, double current,
                          java.util.function.Consumer<Double> onChange) {
        addRenderableWidget(new GeoSlider(x, y, w, 18, label, min, max, current, onChange));
        return y + 20;
    }

    private void markDirty() {
        previewDirty = true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && isInPreview((int)mx, (int)my)) {
            dragging = true;
            dragStartX = (int)mx;
            dragStartY = (int)my;
            dragStartPanX = panX;
            dragStartPanZ = panZ;
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (btn == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging) {
            panX = dragStartPanX - (float)(mx - dragStartX) * zoomStep;
            panZ = dragStartPanZ - (float)(my - dragStartY) * zoomStep;
            markDirty();
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    public boolean mouseScrolled(double mx, double my, double scrollDelta) {
        if (isInPreview((int)mx, (int)my)) {
            float oldStep = zoomStep;
            zoomStep *= (scrollDelta > 0) ? 0.85f : 1.176f;
            zoomStep = Math.max(1f, Math.min(64f, zoomStep));

            float ratio = zoomStep / oldStep;
            panX = (float)(mx - previewX - previewW/2) * (1f - ratio) * oldStep + panX * ratio;
            panZ = (float)(my - previewY - previewH/2) * (1f - ratio) * oldStep + panZ * ratio;
            markDirty();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollDelta);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (isInPreview((int)mx, (int)my)) {
            int lx = (int)((mx - previewX) * PREVIEW_RES / previewW);
            int ly = (int)((my - previewY) * PREVIEW_RES / previewH);
            hoverPX = Math.max(0, Math.min(PREVIEW_RES-1, lx));
            hoverPY = Math.max(0, Math.min(PREVIEW_RES-1, ly));
        } else {
            hoverPX = -1;
            hoverPY = -1;
        }
    }

    private boolean isInPreview(int mx, int my) {
        return mx >= previewX && mx < previewX + previewW
            && my >= previewY && my < previewY + previewH;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int w = width, h = height;
        renderBackground(g);

        g.fill(0, 0, w, 32, 0xCC000000);
        g.fill(0, 32, w, 33, 0xFFAA8800);
        g.drawString(font, this.title.getString(), 8, 10, 0xFFFFFF);
        g.drawString(font, "种子: " + previewSeed + "  |  视图: " + VIEW_NAMES[viewMode] + "  |  缩放: " + String.format("%.1f", zoomStep), 8, 22, 0xAAAAAA);

        g.fill(0, 36, leftPanelW, h - 58, 0x33000000);
        g.fill(leftPanelW, 36, leftPanelW + 1, h - 58, 0x55FFFFFF);
        g.fill(0, 36, leftPanelW, 37, 0xFFAA8800);
        g.drawString(font, "§7控制面板", 6, 38, 0xFFCCCCCC);

        if (previewDirty) {
            previewDirty = false;
            buildPreview();
        }

        if (previewTexture != null) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, previewTexture.getId());
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            Tesselator t = Tesselator.getInstance();
            BufferBuilder bb = t.getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            int x0 = previewX, y0 = previewY, x1 = previewX + previewW, y1 = previewY + previewH;
            bb.vertex(x0, y1, 0).uv(0, 1).endVertex();
            bb.vertex(x1, y1, 0).uv(1, 1).endVertex();
            bb.vertex(x1, y0, 0).uv(1, 0).endVertex();
            bb.vertex(x0, y0, 0).uv(0, 0).endVertex();
            BufferUploader.drawWithShader(bb.end());
        }

        int bp = 0x88AAAAAA;
        g.fill(previewX - 1, previewY - 1, previewX + previewW + 1, previewY, bp);
        g.fill(previewX + previewW, previewY, previewX + previewW + 1, previewY + previewH, bp);
        g.fill(previewX - 1, previewY + previewH, previewX + previewW + 1, previewY + previewH + 1, bp);
        g.fill(previewX - 1, previewY, previewX, previewY + previewH, bp);

        g.drawString(font, "§7" + VIEW_NAMES[viewMode] + "  §8[拖拽平移 | 滚轮缩放]", previewX + 4, previewY - 11, 0xCCCCCC);

        if (hoverPX >= 0 && hoverPY >= 0 && heightCache != null) {
            float wx = (float)((hoverPX - PREVIEW_RES / 2f) * zoomStep + panX);
            float wz = (float)((hoverPY - PREVIEW_RES / 2f) * zoomStep + panZ);
            float hVal = heightCache[hoverPY][hoverPX];
            float cVal = continentCache[hoverPY][hoverPX];
            float tVal = tempCache[hoverPY][hoverPX];
            float mVal = moistCache[hoverPY][hoverPX];
            int worldY = (int)(hVal * (paramMaxY - (-64)) + (-64));

            float pBound = plateBoundaryCache[hoverPY][hoverPX];
            float pConv = plateConvCache[hoverPY][hoverPX];
            String[] lines;
            if (viewMode == 4) {
                lines = new String[]{
                    String.format("X: %.0f  Z: %.0f", wx, wz),
                    String.format("边界强度: %.2f 会聚: %.2f", pBound, pConv),
                    String.format("地壳厚度: %.2f", continentCache[hoverPY][hoverPX] > 0 ? 0.5f + cVal * 0.5f : 0.2f + cVal * 0.3f)
                };
            } else {
                lines = new String[]{
                    String.format("X: %.0f  Z: %.0f  Y: %d", wx, wz, worldY),
                    String.format("高度: %.3f  大陆性: %+.2f", hVal, cVal),
                    String.format(pConv > 0.3f ? "温度: %.2f  湿度: %.2f  §c会聚§r" : "温度: %.2f  湿度: %.2f", tVal, mVal)
                };
            }

            int tw = 0;
            for (String line : lines) {
                tw = Math.max(tw, font.width(line));
            }
            int th = lines.length * 10 + 6;
            int tx = Math.min(previewX + previewW - tw - 8, mx + 12);
            int ty = Math.min(previewY + previewH - th - 4, my + 12);

            g.fill(tx - 4, ty - 3, tx + tw + 6, ty + th, 0xDD000000);
            g.fill(tx - 4, ty - 3, tx + tw + 6, ty - 2, 0xFFAA8800);

            for (int i = 0; i < lines.length; i++) {
                g.drawString(font, lines[i], tx, ty + 2 + i * 10, 0xEEEEEE);
            }
        }

        drawLegendPanel(g);
        drawViewModeBar(g);

        super.render(g, mx, my, pt);
    }

    private void drawViewModeBar(GuiGraphics g) {
        int barX = previewX;
        int barY = previewY + previewH + 6;
        int btnSize = 22;
        int gap = 2;

        for (int i = 0; i < 6; i++) {
            int bx = barX + i * (btnSize + gap);
            boolean active = viewMode == i;
            int bg = active ? 0xFFAA8800 : 0x44000000;
            int fg = active ? 0xFFFFFFFF : 0xFFAAAAAA;
            g.fill(bx, barY, bx + btnSize, barY + btnSize, bg);
            g.fill(bx, barY, bx + btnSize, barY + 1, 0x88FFFFFF);
            g.fill(bx, barY + btnSize - 1, bx + btnSize, barY + btnSize, 0x44000000);
            g.fill(bx, barY, bx + 1, barY + btnSize, 0x88FFFFFF);
            g.fill(bx + btnSize - 1, barY, bx + btnSize, barY + btnSize, 0x44000000);
            if (active) {
                g.fill(bx + 1, barY + btnSize - 1, bx + btnSize - 1, barY + btnSize, 0xFFAA8800);
                g.fill(bx + btnSize - 1, barY + 1, bx + btnSize, barY + btnSize - 1, 0xFFAA8800);
            }

            String label;
            switch (i) {
                case 0: label = "H"; break;
                case 1: label = "B"; break;
                case 2: label = "T"; break;
                case 3: label = "M"; break;
                case 4: label = "P"; break;
                default: label = "E"; break;
            }
            int lw = font.width(label);
            g.drawString(font, label, bx + (btnSize - lw) / 2, barY + 7, fg);
        }

        g.drawString(font, "§7" + VIEW_NAMES[viewMode], barX + 6 * (btnSize + gap) + 8, barY + 6, 0xFFCCCCCC);
    }

    private void drawLegendPanel(GuiGraphics g) {
        int px = 4;
        int py = 290;
        int pw = leftPanelW - 8;
        int lx = px + 4;
        int maxEntries = 12;

        LegendEntry[] entries = getLegendEntries();
        if (entries == null || entries.length == 0) return;

        int totalH = maxEntries * 13 + 4;
        int bottomBtnTop = height - 56;

        if (py + totalH > bottomBtnTop) {
            int maxRows = Math.max(1, (bottomBtnTop - py - 4) / 13);
            LegendEntry[] clipped = new LegendEntry[Math.min(maxRows, entries.length)];
            System.arraycopy(entries, 0, clipped, 0, clipped.length);
            entries = clipped;
        }

        int panelH = entries.length * 13 + 18;
        g.fill(px, py, px + pw, py + panelH, 0x44000000);

        int[] borderColor = {0xFFAA8800, 0xFF886644, 0xFF446688, 0xFF668844, 0xFF884444, 0xFF666644};
        int bc = borderColor[viewMode % borderColor.length];
        g.fill(px, py, px + pw, py + 1, bc);
        g.fill(px, py + panelH - 1, px + pw, py + panelH, bc);
        g.fill(px, py, px + 1, py + panelH, bc);
        g.fill(px + pw - 1, py, px + pw, py + panelH, bc);

        g.drawString(font, "§6⌕ " + VIEW_NAMES[viewMode], lx, py + 4, 0xFFCCCCCC);

        int rowY = py + 17;
        for (int i = 0; i < entries.length; i++) {
            int c = entries[i].color;
            g.fill(lx, rowY, lx + 10, rowY + 10, c);
            g.fill(lx, rowY, lx + 11, rowY + 1, 0x66FFFFFF);
            g.fill(lx, rowY, lx + 1, rowY + 11, 0x66FFFFFF);
            g.fill(lx + 9, rowY, lx + 10, rowY + 11, 0x44000000);
            g.fill(lx, rowY + 9, lx + 10, rowY + 10, 0x44000000);
            g.drawString(font, entries[i].label, lx + 14, rowY + 1, 0xFFDDDDDD);
            rowY += 13;
        }
    }

    private LegendEntry[] getLegendEntries() {
        switch (viewMode) {
            case 0: return new LegendEntry[]{
                new LegendEntry(0xFF1A4A7A, "深海"),
                new LegendEntry(0xFF4A9ACA, "浅海"),
                new LegendEntry(0xFF5A9A4E, "平原"),
                new LegendEntry(0xFF8BC47A, "丘陵"),
                new LegendEntry(0xFFD4B85A, "中山"),
                new LegendEntry(0xFFB06C2A, "高山"),
                new LegendEntry(0xFFF0EDE8, "雪峰")
            };
            case 1: return new LegendEntry[]{
                new LegendEntry(0xFF3068A0, "深海"),
                new LegendEntry(0xFF5088B0, "海洋"),
                new LegendEntry(0xFFF0E8C8, "沙滩"),
                new LegendEntry(0xFFE8D878, "沙漠"),
                new LegendEntry(0xFF90B060, "平原"),
                new LegendEntry(0xFF609060, "森林"),
                new LegendEntry(0xFF58A040, "丛林"),
                new LegendEntry(0xFF587848, "沼泽"),
                new LegendEntry(0xFF809068, "针叶林"),
                new LegendEntry(0xFFD8A858, "恶地"),
                new LegendEntry(0xFFB0B0B0, "山地"),
                new LegendEntry(0xFFE8E8F0, "雪地")
            };
            case 2: return new LegendEntry[]{
                new LegendEntry(0xFF440154, "极寒"),
                new LegendEntry(0xFF31688E, "寒冷"),
                new LegendEntry(0xFF21918C, "凉爽"),
                new LegendEntry(0xFF35B779, "温和"),
                new LegendEntry(0xFF90D743, "温暖"),
                new LegendEntry(0xFFFDE725, "炎热")
            };
            case 3: return new LegendEntry[]{
                new LegendEntry(0xFF8B4513, "干旱"),
                new LegendEntry(0xFFA0522D, "偏干"),
                new LegendEntry(0xFFCD853F, "适中"),
                new LegendEntry(0xFF6B8E6B, "偏湿"),
                new LegendEntry(0xFF4682B4, "湿润"),
                new LegendEntry(0xFF191970, "潮湿")
            };
            case 4: return new LegendEntry[]{
                new LegendEntry(0xFF333333, "板块内部"),
                new LegendEntry(0xFF886644, "离散/走滑"),
                new LegendEntry(0xFF885522, "弱会聚"),
                new LegendEntry(0xFFBB6633, "中会聚"),
                new LegendEntry(0xFFDD4444, "强会聚"),
                new LegendEntry(0xFFFF5533, "碰撞带")
            };
            default: return new LegendEntry[]{
                new LegendEntry(0xFF336633, "微弱"),
                new LegendEntry(0xFF447744, "轻度"),
                new LegendEntry(0xFF668866, "中度"),
                new LegendEntry(0xFF999966, "较强"),
                new LegendEntry(0xFFBB8844, "强烈"),
                new LegendEntry(0xFFCC6644, "剧烈")
            };
        }
    }

    private record LegendEntry(int color, String label) {}

    private void buildPreview() {
        if (previewImg == null || previewImg.getWidth() != PREVIEW_RES) {
            if (previewImg != null) previewImg.close();
            if (previewTexture != null) previewTexture.close();
            previewImg = new NativeImage(NativeImage.Format.RGBA, PREVIEW_RES, PREVIEW_RES, true);
            previewTexture = new DynamicTexture(previewImg);
        }
        if (heightCache == null || heightCache.length != PREVIEW_RES) {
            heightCache = new float[PREVIEW_RES][PREVIEW_RES];
            continentCache = new float[PREVIEW_RES][PREVIEW_RES];
            tempCache = new float[PREVIEW_RES][PREVIEW_RES];
            moistCache = new float[PREVIEW_RES][PREVIEW_RES];
            plateBoundaryCache = new float[PREVIEW_RES][PREVIEW_RES];
            plateBiasCache = new float[PREVIEW_RES][PREVIEW_RES];
            plateConvCache = new float[PREVIEW_RES][PREVIEW_RES];
        }

        float step = zoomStep;

        float[][] sHeight = new float[SAMPLE_RES][SAMPLE_RES];
        float[][] sContinent = new float[SAMPLE_RES][SAMPLE_RES];
        float[][] sTemp = new float[SAMPLE_RES][SAMPLE_RES];
        float[][] sMoist = new float[SAMPLE_RES][SAMPLE_RES];
        float[][] sPlate = new float[SAMPLE_RES][SAMPLE_RES];
        float[][] sPlateBias = new float[SAMPLE_RES][SAMPLE_RES];
        float[][] sPlateConv = new float[SAMPLE_RES][SAMPLE_RES];

        float pxScale = step * PREVIEW_RES / SAMPLE_RES;
        for (int sy = 0; sy < SAMPLE_RES; sy++) {
            for (int sx = 0; sx < SAMPLE_RES; sx++) {
                float wx = (sx - SAMPLE_RES / 2f) * pxScale + panX;
                float wz = (sy - SAMPLE_RES / 2f) * pxScale + panZ;
                PlateTectonics.PlateData pd = plateSystem.sample(wx, wz);
                float rawContinent = previewEngine.sampleContinentRaw(wx, wz) + (float)paramContinentBias;
                float biasedContinent = rawContinent + pd.continentBias() * 0.2f;
                sHeight[sy][sx] = computeHeight(wx, wz, biasedContinent);
                sContinent[sy][sx] = biasedContinent;
                float t = previewEngine.sampleTerrainBase(wx, wz);
                sTemp[sy][sx] = previewEngine.sampleTemperature(wx, wz, t * 0.5f);
                sMoist[sy][sx] = previewEngine.sampleMoisture(wx, wz, (biasedContinent + 1f) * 0.5f, sTemp[sy][sx]);
                sPlate[sy][sx] = pd.boundaryStrength();
                sPlateBias[sy][sx] = pd.continentBias();
                sPlateConv[sy][sx] = pd.convergence();
            }
        }

        for (int py = 0; py < PREVIEW_RES; py++) {
            float v = (float)py / (PREVIEW_RES - 1);
            for (int px = 0; px < PREVIEW_RES; px++) {
                float u = (float)px / (PREVIEW_RES - 1);
                heightCache[py][px] = sampleBilinear(sHeight, u, v);
                continentCache[py][px] = sampleBilinear(sContinent, u, v);
                tempCache[py][px] = sampleBilinear(sTemp, u, v);
                moistCache[py][px] = sampleBilinear(sMoist, u, v);
                plateBoundaryCache[py][px] = sampleBilinear(sPlate, u, v);
                plateBiasCache[py][px] = sampleBilinear(sPlateBias, u, v);
                plateConvCache[py][px] = sampleBilinear(sPlateConv, u, v);
            }
        }

        for (int py = 0; py < PREVIEW_RES; py++) {
            for (int px = 0; px < PREVIEW_RES; px++) {
                float h = heightCache[py][px];
                float cAdj = continentCache[py][px];
                cAdj = Math.max(-1f, Math.min(1f, cAdj));

                int color;
                switch (viewMode) {
                    case 1:
                        color = computeBiomeColor(worldX(px), worldZ(py), h);
                        break;
                    case 2:
                        color = computeTempColor(px, py);
                        break;
                    case 3:
                        color = computeMoistColor(px, py);
                        break;
                    case 4:
                        color = computePlateColor(px, py);
                        break;
                    case 5:
                        color = computeErosionColor(px, py);
                        break;
                    default:
                        color = computeHeightmapColor(h, cAdj, px, py);
                        break;
                }

                int a = 255, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
                previewImg.setPixelRGBA(px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        previewTexture.upload();
    }

    private static float sampleBilinear(float[][] grid, float u, float v) {
        float sx = u * (SAMPLE_RES - 1);
        float sy = v * (SAMPLE_RES - 1);
        int ix = Math.max(0, Math.min(SAMPLE_RES - 2, (int)sx));
        int iy = Math.max(0, Math.min(SAMPLE_RES - 2, (int)sy));
        float fx = sx - ix;
        float fy = sy - iy;
        float top = grid[iy][ix] + (grid[iy][ix + 1] - grid[iy][ix]) * fx;
        float bot = grid[iy + 1][ix] + (grid[iy + 1][ix + 1] - grid[iy + 1][ix]) * fx;
        return top + (bot - top) * fy;
    }

    private int computeHeightmapColor(float h, float continent, int px, int py) {
        if (continent < 0.05f) {
            return oceanDepthColor(continent);
        }
        return PreviewDataLoader.getElevationColor(h);
    }

    private int oceanDepthColor(float continent) {
        float depth = Math.max(0f, (0.05f - continent) / 1.05f);
        float t = Math.max(0, Math.min(1, depth));
        if (t < 0.15f) return lerpColor(0x6BB5D6, 0x4A9ACA, t / 0.15f);
        if (t < 0.35f) return lerpColor(0x4A9ACA, 0x2A6A9A, (t - 0.15f) / 0.20f);
        if (t < 0.60f) return lerpColor(0x2A6A9A, 0x1A4A7A, (t - 0.35f) / 0.25f);
        return lerpColor(0x1A4A7A, 0x0A2A4A, (t - 0.60f) / 0.40f);
    }

    private int computePlateColor(int px, int py) {
        float b = plateBoundaryCache[py][px];
        float cv = plateConvCache[py][px];
        if (b < 0.02f) return 0x333333;
        float base = Math.max(0f, Math.min(1f, b));
        if (cv > 0.3f) {
            if (base < 0.15f) return lerpColor(0x444433, 0x885522, base / 0.15f);
            if (base < 0.35f) return lerpColor(0x885522, 0xBB6633, (base - 0.15f) / 0.20f);
            if (base < 0.55f) return lerpColor(0xBB6633, 0xDD4444, (base - 0.35f) / 0.20f);
            return lerpColor(0xDD4444, 0xFF5533, Math.min(1f, (base - 0.55f) / 0.45f));
        } else {
            if (base < 0.15f) return lerpColor(0x444444, 0x666644, base / 0.15f);
            if (base < 0.35f) return lerpColor(0x666644, 0x886644, (base - 0.15f) / 0.20f);
            if (base < 0.55f) return lerpColor(0x886644, 0xAA6644, (base - 0.35f) / 0.20f);
            return lerpColor(0xAA6644, 0xCC8844, Math.min(1f, (base - 0.55f) / 0.45f));
        }
    }

    private int computeErosionColor(int px, int py) {
        float wx = (px - PREVIEW_RES / 2f) * zoomStep + panX;
        float wz = (py - PREVIEW_RES / 2f) * zoomStep + panZ;
        float e = previewEngine.sampleErosionNoise(wx, wz);
        float t = Math.max(0, Math.min(1, e / 0.3f));
        if (t < 0.2f) return lerpColor(0x336633, 0x447744, t / 0.2f);
        if (t < 0.4f) return lerpColor(0x447744, 0x668866, (t - 0.2f) / 0.2f);
        if (t < 0.6f) return lerpColor(0x668866, 0x999966, (t - 0.4f) / 0.2f);
        if (t < 0.8f) return lerpColor(0x999966, 0xBB8844, (t - 0.6f) / 0.2f);
        return lerpColor(0xBB8844, 0xCC6644, (t - 0.8f) / 0.2f);
    }

    private int computeTempColor(int px, int py) {
        float temp = tempCache[py][px];
        float t = Math.max(0, Math.min(1, temp));
        if (t < 0.2f) return lerpColor(0x440154, 0x31688E, t / 0.2f);
        if (t < 0.4f) return lerpColor(0x31688E, 0x21918C, (t - 0.2f) / 0.2f);
        if (t < 0.6f) return lerpColor(0x21918C, 0x35B779, (t - 0.4f) / 0.2f);
        if (t < 0.8f) return lerpColor(0x35B779, 0x90D743, (t - 0.6f) / 0.2f);
        return lerpColor(0x90D743, 0xFDE725, (t - 0.8f) / 0.2f);
    }

    private int computeMoistColor(int px, int py) {
        float m = moistCache[py][px];
        float t = Math.max(0, Math.min(1, m));
        if (t < 0.2f) return lerpColor(0x8B4513, 0xA0522D, t / 0.2f);
        if (t < 0.4f) return lerpColor(0xA0522D, 0xCD853F, (t - 0.2f) / 0.2f);
        if (t < 0.6f) return lerpColor(0xCD853F, 0x6B8E6B, (t - 0.4f) / 0.2f);
        if (t < 0.8f) return lerpColor(0x6B8E6B, 0x4682B4, (t - 0.6f) / 0.2f);
        return lerpColor(0x4682B4, 0x191970, (t - 0.8f) / 0.2f);
    }

    private static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0, Math.min(1, t));
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int)(r1 + (r2 - r1) * t);
        int g = (int)(g1 + (g2 - g1) * t);
        int b = (int)(b1 + (b2 - b1) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static float smoothstep(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return v * v * (3f - 2f * v);
    }

    private static int applyShade(int color, float shade) {
        int r = (int)Math.min(255, Math.max(0, ((color >> 16) & 0xFF) * shade));
        int g = (int)Math.min(255, Math.max(0, ((color >> 8) & 0xFF) * shade));
        int b = (int)Math.min(255, Math.max(0, (color & 0xFF) * shade));
        return (r << 16) | (g << 8) | b;
    }

    private float computeHeight(float wx, float wz, float biasedContinent) {
        float continent = Math.max(-1f, Math.min(1f, biasedContinent));

        float terrain = previewEngine.sampleTerrainBase(wx, wz);
        float relief = previewEngine.sampleElevation(wx, wz);
        float plateauW = previewEngine.samplePlateauWeight(wx, wz);
        float karstW = previewEngine.sampleKarstWeight(wx, wz);
        float glacierW = previewEngine.sampleGlacierWeight(wx, wz);

        float rf = previewEngine.sampleRidge(wx, wz);
        float cf = previewEngine.sampleCellNoise(wx, wz);
        float hf = previewEngine.sampleTerrainHills(wx, wz);
        float gf = previewEngine.sampleGullyErosion(wx, wz);

        float detail = rf * (float)paramRidgeWeight * 0.8f
                     + cf * 0.28f
                     + hf * 0.14f
                     + gf * 0.08f;
        float baseType = terrain * 0.5f + detail * 0.5f;
        baseType = Math.min(1f, baseType);

        float plateauAmount = smoothstep(plateauW);
        float plateauThreshold = 0.4f + relief * 0.2f;
        if (baseType > plateauThreshold && plateauAmount > 0.01f) {
            float excess = (baseType - plateauThreshold) / (1f - plateauThreshold);
            float flatTop = plateauThreshold + excess * 0.3f;
            float plateauLift = (flatTop - baseType) * plateauAmount;
            baseType += plateauLift;
        }

        float karstAmount = smoothstep(karstW) * (1f - smoothstep(continent / 0.5f))
                          * smoothstep(relief - 0.3f) * (1f - smoothstep((relief - 0.8f) / 0.2f));
        if (karstAmount > 0.01f) {
            float peak = Math.max(0f, previewEngine.sampleTerrainDetail(wx, wz) * terrain * 0.6f);
            baseType += peak * karstAmount;
        }

        float glacierAmount = smoothstep(glacierW) * smoothstep(1f - terrain)
                            * smoothstep(relief - 0.6f);
        if (glacierAmount > 0.01f) {
            float valley = previewEngine.sampleValleyLarge(wx, wz);
            float valleyCenter = 1f - Math.abs(valley * 2f - 1f);
            float uFill = valleyCenter * 0.12f;
            float peakCut = Math.max(0f, baseType - 0.7f) * valleyCenter * 0.3f;
            baseType += (uFill - peakCut) * glacierAmount * 0.5f;
        }

        baseType = Math.max(0f, Math.min(1f, baseType));

        float amplitudeFactor = 0.06f + relief * relief * 1.6f;
        amplitudeFactor = Math.min(amplitudeFactor, 2.0f);
        float minY = -64f;
        float worldHeight = (float)paramMaxY - minY;
        float seaNorm = ((float)paramSeaLevel - minY) / worldHeight;
        float terrainRange = 1f - seaNorm;

        float shapeHeight = baseType * terrainRange * amplitudeFactor;
        shapeHeight = Math.min(shapeHeight, 1f - seaNorm);

        float h = seaNorm + shapeHeight;

        if (paramHeightScale != 1.0) {
            h = seaNorm + (h - seaNorm) * (float)paramHeightScale;
            h = Math.max(0f, Math.min(1f, h));
        }
        if (paramTerrainScale != 1.0) {
            h = seaNorm * 0.5f + (h - seaNorm * 0.5f) * (float)paramTerrainScale;
            h = Math.max(0f, Math.min(1f, h));
        }
        return h;
    }

    private float sampleH(int py, int px) {
        int x = Math.max(0, Math.min(PREVIEW_RES - 1, px));
        int y = Math.max(0, Math.min(PREVIEW_RES - 1, py));
        return heightCache[y][x];
    }

    private float worldX(int px) { return (px - PREVIEW_RES / 2f) * zoomStep + panX; }
    private float worldZ(int py) { return (py - PREVIEW_RES / 2f) * zoomStep + panZ; }

    private int computeBiomeColor(float wx, float wz, float h) {
        float c = previewEngine.sampleContinentRaw(wx, wz) + (float)paramContinentBias;
        c = Math.max(-1f, Math.min(1f, c));
        float t = previewEngine.sampleTerrainBase(wx, wz);
        float temp = previewEngine.sampleTemperature(wx, wz, t * 0.5f);
        float moist = previewEngine.sampleMoisture(wx, wz, (c + 1f) * 0.5f, temp);

        float riverPotential = previewHydrology.getRiverDepthAt(wx, wz);
        float riverDepth = riverPotential * 0.1f;

        float glacierW = sampleGlacierFeature(wx, wz);
        float karstW = sampleKarstFeature(wx, wz);
        float danxiaW = sampleDanxiaFeature(wx, wz);

        ResourceKey<Biome> biome = ClimateBiomeMapper.selectBiome(temp, moist, h, c, riverDepth,
            glacierW, karstW, danxiaW);
        return PreviewDataLoader.getBiomeColor(biome.location().toString());
    }

    private float sampleGlacierFeature(float wx, float wz) {
        float elevation = previewEngine.sampleElevation(wx, wz);
        float temp = previewEngine.sampleTemperature(wx, wz, elevation * 0.5f);
        if (elevation < 0.5f || temp > 0.4f) return 0f;
        float noise = (float) previewEngine.climateNoise().noise(wx * 0.003f, 0, wz * 0.003f);
        float weight = (noise + 1f) * 0.5f * (1f - temp) * elevation;
        return Math.min(1f, Math.max(0f, weight * 2f));
    }

    private float sampleKarstFeature(float wx, float wz) {
        float continent01 = (previewEngine.sampleContinentRaw(wx, wz) + 1f) * 0.5f;
        float elevation = previewEngine.sampleElevation(wx, wz);
        if (continent01 < 0.3f || elevation < 0.3f) return 0f;
        float noise = (float) previewEngine.ridgeNoise().noise(wx * 0.008f, 0, wz * 0.008f);
        float weight = (noise + 1f) * 0.5f * elevation;
        return Math.min(1f, Math.max(0f, weight * 2f));
    }

    private float sampleDanxiaFeature(float wx, float wz) {
        float temp = previewEngine.sampleTemperature(wx, wz, 0);
        float continent01 = (previewEngine.sampleContinentRaw(wx, wz) + 1f) * 0.5f;
        float moist = previewEngine.sampleMoisture(wx, wz, continent01, temp);
        float elevation = previewEngine.sampleElevation(wx, wz);
        if (temp < 0.45f || moist > 0.45f) return 0f;
        float noise = (float) previewEngine.continentNoise().noise(wx * 0.005f, 0, wz * 0.005f);
        float weight = (noise + 1f) * 0.5f;
        float tempF = (temp - 0.45f) / 0.3f;
        float aridF = (0.45f - moist) / 0.45f;
        weight *= tempF * aridF;
        return Math.min(1f, Math.max(0f, weight * 3f));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 82) {
            previewSeed = new Random().nextInt(Integer.MAX_VALUE);
            previewEngine = new NoiseEngine(previewSeed);
            plateSystem = new PlateTectonics(previewSeed);
            panX = 0; panZ = 0;
            markDirty();
            return true;
        }
        if (keyCode >= 49 && keyCode <= 54) {
            viewMode = keyCode - 49;
            markDirty();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        GeoGenesisConfig.COMMON.seaLevel.set(paramSeaLevel);
        GeoGenesisConfig.COMMON.maxY.set(paramMaxY);
        GeoGenesisConfig.COMMON.oceanDepthMax.set(paramOceanDepth);
        GeoGenesisConfig.COMMON_SPEC.save();
        Minecraft.getInstance().setScreen(parent);
    }

    private static class GeoSlider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final java.util.function.Consumer<Double> onApply;

        GeoSlider(int x, int y, int w, int h, String label,
                  double min, double max, double current,
                  java.util.function.Consumer<Double> onApply) {
            super(x, y, w, h, Component.empty(), (current - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onApply = onApply;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double val = min + (max - min) * this.value;
            setMessage(Component.literal(label + ": " + String.format("%.2f", val)));
        }

        @Override
        protected void applyValue() {
            double val = min + (max - min) * this.value;
            onApply.accept(val);
            updateMessage();
        }
    }

    private static class IntSlider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final java.util.function.Consumer<Double> onApply;

        IntSlider(int x, int y, int w, int h, String label,
                  double min, double max, double current,
                  java.util.function.Consumer<Double> onApply) {
            super(x, y, w, h, Component.empty(), (current - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onApply = onApply;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int val = (int)Math.round(min + (max - min) * this.value);
            setMessage(Component.literal(label + ": " + val));
        }

        @Override
        protected void applyValue() {
            double val = Math.round(min + (max - min) * this.value);
            onApply.accept(val);
            updateMessage();
        }
    }
}
