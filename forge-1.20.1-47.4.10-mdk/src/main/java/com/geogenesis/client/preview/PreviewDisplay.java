package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 地形预览地图控件（纹理式渲染，11 个地理标准图层 + 水文叠加）。
 *
 * 修复拖拽卡顿：拖拽/缩放期间不触发全量重算，仅在交互暂停 150ms 或松手后
 * 经 {@link #renderWidget} 的防抖逻辑请求一次重算（early-abort），拖拽顺滑。
 *
 * 交互（由持有本控件的 Screen 绑定键盘）：
 * - 数字键 0..10 切换图层
 * - R 切换水文叠加
 * - 分辨率/高程色带由 Screen 上的控件设置
 */
public class PreviewDisplay extends AbstractWidget {

    private int texSize = 256;          // 分辨率设置（纹理宽度）
    private int texW = 256, texH = 256; // 实际纹理尺寸（按控件宽高比）

    private final Minecraft mc = Minecraft.getInstance();
    private ResourceLocation texLoc;
    private NativeImage image;
    private DynamicTexture texture;
    private boolean hasTexture = false;

    private GeoGenesisTerrain terrain;
    private long seed;
    private int horizontalScale;
    private int seaLevel, snowLine, maxY, minY, mountainCap;
    private int mode = 0;
    private boolean hydrology = false;
    private boolean showLegend = true;

    // 图例滚动与排序
    private int legendScrollOffset = 0;
    /** 0=默认(id), 1=名称, 2=分类 */
    private int legendSortMode = 0;
    private static final String[] LEGEND_SORT_NAMES = {"默认", "名称", "分类"};

    // 视图：方块坐标原点的屏幕映射 + 每像素方块数
    private double originBlockX = 0, originBlockZ = 0;
    private double scale = 6.0;
    // 当前已上传纹理对应的世界视口，重采样期间用它把旧帧仿射映射到新视口
    private double texOriginBlockX = 0, texOriginBlockZ = 0, texScale = 6.0;
    private int lastDragX, lastDragY;
    private boolean dragging = false;
    private int hoverX = -1, hoverZ = -1;

    private final AtomicBoolean computing = new AtomicBoolean(false);
    private volatile boolean needsResample = false;
    private volatile double renderProgress = 0.0;
    private volatile boolean done = true;
    private volatile String lastError = null;

    // 拖拽防抖：交互期间只更新视口，暂停后才重算
    private long lastInteractTime = 0;
    private boolean pendingResample = false;

    public PreviewDisplay(int x, int y, int w, int h, GeoGenesisTerrain terrain, long seed, TerrainParams params) {
        super(x, y, w, h, Component.literal("GeoGenesis Preview"));
        this.terrain = terrain;
        this.seed = seed;
        this.horizontalScale = (int) params.horizontalScale();
        this.seaLevel = params.seaLevel();
        this.snowLine = (int) new com.geogenesis.worldgen.terrain.HeightCurve(params, params.minY(), params.maxY()).heightFromE(params.snowLine());
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        // 高程色带按实际地形高度范围归一化，使高山始终映射到色带顶部
        GeoPalette.setElevationRange(minY, mountainCap);
        GeoPalette.setSeaLevel(seaLevel);
        this.texW = texSize;
        this.texH = Math.max(1, (int) Math.round(texSize * (double) height / width));
        this.image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
        this.texture = new DynamicTexture(image);
        this.texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
        mc.getTextureManager().register(texLoc, texture);
        requestResample();
    }

    public void setTerrain(GeoGenesisTerrain terrain, long seed, TerrainParams params) {
        this.terrain = terrain;
        this.seed = seed;
        this.horizontalScale = (int) params.horizontalScale();
        this.seaLevel = params.seaLevel();
        this.snowLine = (int) new com.geogenesis.worldgen.terrain.HeightCurve(params, params.minY(), params.maxY()).heightFromE(params.snowLine());
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        GeoPalette.setElevationRange(minY, mountainCap);
        GeoPalette.setSeaLevel(seaLevel);
        requestResample();
    }

    public void setMode(int mode) {
        this.mode = Math.max(0, Math.min(GeoPalette.PreviewLayer.values().length - 1, mode));
        legendScrollOffset = 0; // 切图层时重置图例滚动
        requestResample();
    }

    public int getMode() {
        return mode;
    }

    public GeoPalette.PreviewLayer getLayer() {
        return GeoPalette.PreviewLayer.values()[mode];
    }

    public void setHydrology(boolean on) {
        this.hydrology = on;
        requestResample();
    }

    public boolean isHydrology() {
        return hydrology;
    }

    public void toggleLegend() {
        showLegend = !showLegend;
    }

    public void setElevationColormap(String name) {
        GeoPalette.setElevationColormap(name);
        if (getLayer() == GeoPalette.PreviewLayer.ELEVATION) requestResample();
    }

    /** 分辨率预设（纹理边长）：128/256/512。 */
    public void setResolution(int tex) {
        if (tex == texSize) return;
        if (texture != null) {
            mc.getTextureManager().release(texLoc);
            texture.close();
        }
        this.texSize = tex;
        this.texW = tex;
        this.texH = Math.max(1, (int) Math.round(tex * (double) height / width));
        this.image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
        this.texture = new DynamicTexture(image);
        this.texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
        mc.getTextureManager().register(texLoc, texture);
        hasTexture = false; // 新纹理还是空白，等 requestResample 完成后再显示
        requestResample();
    }

    public int getResolution() {
        return texSize;
    }

    /** 重新定位控件（用于窗口大小变化或工具栏调整）。 */
    public void setPosition(int x, int y, int w, int h) {
        this.setX(x);
        this.setY(y);
        this.width = w;
        this.height = h;
        int newTexH = Math.max(1, (int) Math.round(texSize * (double) h / w));
        if (newTexH != texH) {
            if (texture != null) {
                mc.getTextureManager().release(texLoc);
                texture.close();
            }
            texW = texSize;
            texH = newTexH;
            image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
            texture = new DynamicTexture(image);
            texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
            mc.getTextureManager().register(texLoc, texture);
            hasTexture = false;
            requestResample();
        }
    }

    /** 视图 / 参数 / 种子变化时请求重采样（后台线程，避免卡 UI）。 */
    public void requestResample() {
        if (computing.get()) {
            needsResample = true;
            return;
        }
        done = false;
        renderProgress = 0.0;
        computing.set(true);

        // 捕获当前采样视口，重采样期间即使继续拖拽/缩放也不影响本次采样。
        // 注意：旧纹理的映射参数 texOriginBlockX/texOriginBlockZ/texScale 必须保持为
        // 旧纹理自身的采样视口，直到新纹理完成上传后再原子更新，否则重采样中旧帧
        // 会被错误仿射到新的视口，导致画面缩小/跳到左上角。
        final double sampleOriginX = originBlockX;
        final double sampleOriginZ = originBlockZ;
        final double sampleScale = (double) width * scale / texW; // 每个纹理像素对应方块数，覆盖整个控件
        final int sampleTexW = texW;
        final int sampleTexH = texH;

        // 在后台写入新的 backing image，不破坏旧纹理，避免重采样期间黑屏
        NativeImage newImage = new NativeImage(NativeImage.Format.RGBA, sampleTexW, sampleTexH, false);

        new Thread(() -> {
            try {
                int cellsX = Math.max(1, (int) Math.ceil((double) sampleTexW * sampleScale / horizontalScale));
                int cellsZ = Math.max(1, (int) Math.ceil((double) sampleTexH * sampleScale / horizontalScale));
                int obx = (int) Math.floor(sampleOriginX);
                int obz = (int) Math.floor(sampleOriginZ);
                Cell[][] cells = terrain.getRegionCells(obx, obz, cellsX, cellsZ);
                renderProgress = 0.85;

                GeoPalette.PreviewLayer layer = getLayer();
                for (int py = 0; py < sampleTexH; py++) {
                    double wz = sampleOriginZ + py * sampleScale;
                    for (int px = 0; px < sampleTexW; px++) {
                        double wx = sampleOriginX + px * sampleScale;
                        int cx = (int) Math.floor((wx - obx) / horizontalScale);
                        int cz = (int) Math.floor((wz - obz) / horizontalScale);
                        cx = Math.max(0, Math.min(cellsX - 1, cx));
                        cz = Math.max(0, Math.min(cellsZ - 1, cz));
                        Cell cell = cells[cx][cz];
                        int color = PreviewColor.color(layer, cell, (int) Math.round(wx), (int) Math.round(wz),
                                minY, maxY, hydrology);
                        newImage.setPixelRGBA(px, py, color);
                    }
                    renderProgress = 0.85 + (py + 1.0) / sampleTexH * 0.15;
                }
                renderProgress = 1.0;

                DynamicTexture newTexture = new DynamicTexture(newImage);
                ResourceLocation newLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());

                mc.execute(() -> {
                    if (texture != null) {
                        mc.getTextureManager().release(texLoc);
                        texture.close();
                    }
                    image = newImage;
                    texture = newTexture;
                    texLoc = newLoc;
                    // 新纹理准备就绪：旧纹理将不再被绘制，此时才把映射基准切换到新视口
                    texOriginBlockX = sampleOriginX;
                    texOriginBlockZ = sampleOriginZ;
                    texScale = sampleScale;
                    mc.getTextureManager().register(texLoc, texture);
                    texture.upload();
                    hasTexture = true;
                    done = true;
                    computing.set(false);
                    lastError = null;
                    if (needsResample) {
                        needsResample = false;
                        requestResample();
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                mc.execute(() -> {
                    done = true;
                    computing.set(false);
                });
            }
        }, "GG-Preview").start();
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF101418);
        if (hasTexture) {
            // 始终按旧纹理的采样视口（texOrigin/texScale）仿射映射到当前视口，
            // 拖拽/缩放时画面连续跟随，不会出现旧纹理“飘出去”的跳变。
            double drawW = texW * texScale / scale;
            double drawH = texH * texScale / scale;
            double drawX = getX() + (texOriginBlockX - originBlockX) / scale;
            double drawY = getY() + (texOriginBlockZ - originBlockZ) / scale;
            int dx = (int) Math.round(drawX);
            int dy = (int) Math.round(drawY);
            int dw = (int) Math.round(drawX + drawW) - dx;
            int dh = (int) Math.round(drawY + drawH) - dy;
            if (dw > 0 && dh > 0) {
                g.enableScissor(getX(), getY(), getX() + width, getY() + height);
                g.blit(texLoc, dx, dy, dw, dh, 0, 0, texW, texH, texW, texH);
                g.disableScissor();
            }
        }

        // 交互防抖：拖拽/缩放暂停 150ms 后才重算，期间不卡 UI
        long now = System.currentTimeMillis();
        if (pendingResample && !computing.get() && now - lastInteractTime > 150) {
            pendingResample = false;
            requestResample();
        }

        if (!done) {
            String label = I18n.get("geogenesis.preview.computing", seed);
            g.drawString(mc.font, label, getX() + 8, getY() + 8, 0xFFFFFF);
            double progress = Math.max(0.0, Math.min(1.0, renderProgress));
            int pct = (int) Math.round(progress * 100);
            int barX = getX() + 8, barY = getY() + 24, barW = width - 16 - 36, barH = 6;
            g.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            int fillW = (int) (barW * progress);
            if (fillW > 0) g.fill(barX, barY, barX + fillW, barY + barH, 0xFF44CCFF);
            g.drawString(mc.font, pct + "%", barX + barW + 6, barY - 2, 0xCCCCCC);
        }

        if (lastError != null) {
            g.fill(getX() + 6, getY() + 6, getX() + width - 12, getY() + 30, 0xCC330000);
            g.drawString(mc.font, "预览错误: " + lastError, getX() + 12, getY() + 12, 0xFF5555);
        }

        if (showLegend) drawLegend(g, mx, my);

        if (isMouseOver(mx, my) && hoverX >= 0) {
            Cell c = terrain.sampleCell(hoverX, hoverZ);
            int lx = getX() + 6, ly = getY() + height - 72;
            String[] lines = {
                    String.format("X=%d  Z=%d  Y=%d", hoverX, hoverZ, (int) Math.round(c.height)),
                    typeLine(c),
                    String.format("temp=%.2f  hum=%.2f  cont=%.2f", c.temperature, c.humidity, c.continentNoise),
                    String.format("lat=%.2f  relief=%.2f", com.geogenesis.worldgen.climate.Latitude.latitude01(hoverZ), (c.shape + 1) * 0.5),
                    c.riverIsWaterfall ? I18n.get("geogenesis.preview.waterfall")
                            : (c.riverSourceType == 3 ? I18n.get("geogenesis.preview.sourceLake")
                            : (c.riverSourceType == 2 ? I18n.get("geogenesis.preview.spring")
                            : (c.riverSourceType == 1 ? I18n.get("geogenesis.preview.creek")
                            : (c.riverMask ? I18n.get("geogenesis.preview.river")
                            : (c.riverDistance < 0.15 ? I18n.get("geogenesis.preview.valley")
                            : (c.lakeMask ? I18n.get("geogenesis.preview.lake") : "—"))))))
            };
            g.fill(lx, ly, lx + 180, ly + 72, 0xAA000000);
            for (int i = 0; i < lines.length; i++) {
                g.drawString(mc.font, lines[i], lx + 4, ly + 4 + i * 13, 0xFFFFFF);
            }
        }
    }

    /** 右侧图例：离散图层列条目（支持滚动 + 分类排序）；连续图层画渐变条 + 上下限标注。 */
    private void drawLegend(GuiGraphics g, int mx, int my) {
        GeoPalette.PreviewLayer layer = getLayer();
        int lx = getX() + width - 132, ly = getY() + 8;
        if (layer.legendable && layer.kind == GeoPalette.Kind.DISCRETE) {
            List<GeoPalette.LegendEntry> entries = getSortedEntries(layer);
            int panelW = 128, rowH = 14, titleH = 14, modeH = 14;
            int maxVisibleH = getHeight() - 40; // 保留顶部空间
            int contentH = entries.size() * rowH;
            int panelH = Math.min(titleH + modeH + contentH + 6, maxVisibleH);

            // 面板背景
            g.fill(lx - 4, ly - 4, lx - 4 + panelW, ly - 4 + panelH, 0xAA101418);

            // 标题
            g.drawString(mc.font, I18n.get(layer.labelKey), lx, ly, 0x66CCFF);

            // 排序模式按钮行（可点击、悬停高亮）
            int modeY = ly + titleH;
            boolean hoverMode = mx >= lx && mx <= lx + panelW && my >= modeY && my <= modeY + modeH;
            String modeLabel = "排序: " + LEGEND_SORT_NAMES[legendSortMode];
            // 背景高亮
            if (hoverMode) {
                g.fill(lx - 2, modeY, lx + mc.font.width(modeLabel) + 8, modeY + modeH, 0x4400c896);
            }
            g.drawString(mc.font, modeLabel, lx, modeY + 2, hoverMode ? 0xFF00c896 : 0xFF888888);
            // 悬停时显示提示
            if (hoverMode) {
                String tip = "点击切换排序方式";
                int tipW = mc.font.width(tip) + 8;
                int tipX = lx + mc.font.width(modeLabel) + 12;
                int tipY = modeY;
                if (tipX + tipW > lx + panelW) tipX = lx + panelW - tipW;
                g.fill(tipX, tipY, tipX + tipW, tipY + 12, 0xFF000000);
                g.drawString(mc.font, tip, tipX + 4, tipY + 2, 0xFFcccccc);
            }

            // 条目区域（可裁剪）
            int entryY = modeY + modeH;
            int entryMaxH = panelH - titleH - modeH - 6;
            g.enableScissor(lx - 4, entryY, lx - 4 + panelW, entryY + entryMaxH);

            int cy = entryY - legendScrollOffset;
            for (GeoPalette.LegendEntry e : entries) {
                if (cy + rowH > entryY - rowH && cy < entryY + entryMaxH + rowH) {
                    g.fill(lx, cy, lx + 10, cy + 10, GeoPalette.toABGR(e.color));
                    String name = I18n.get(e.labelKey);
                    if (name.equals(e.labelKey)) name = GeoPalette.englishLabel(e.labelKey);
                    // 截断过长名称
                    if (mc.font.width(name) > panelW - 18) {
                        while (mc.font.width(name + "...") > panelW - 18 && name.length() > 1) {
                            name = name.substring(0, name.length() - 1);
                        }
                        name += "...";
                    }
                    g.drawString(mc.font, name, lx + 14, cy, 0xEEEEEE);
                }
                cy += rowH;
            }
            g.disableScissor();

            // 滚动条
            int scrollableH = contentH - entryMaxH;
            if (scrollableH > 0) {
                int barH = Math.max(12, entryMaxH * entryMaxH / contentH);
                int barY = entryY + legendScrollOffset * (entryMaxH - barH) / scrollableH;
                g.fill(lx + panelW - 3, barY, lx + panelW - 1, barY + barH, 0xFF00c896);
            }
        } else {
            // 连续图层：渐变条
            int bx = getX() + width - 22, by = getY() + 24, bh = 200, bw = 12;
            for (int i = 0; i < bh; i++) {
                double p = 1.0 - (double) i / (bh - 1);
                int rgb = GeoPalette.continuous(layer, p);
                g.fill(bx, by + i, bx + bw, by + i + 1, GeoPalette.toABGR(rgb));
            }
            String topLabel = (layer == GeoPalette.PreviewLayer.ELEVATION) ? ("Y=" + mountainCap) : "1.0";
            String botLabel = (layer == GeoPalette.PreviewLayer.ELEVATION) ? ("Y=" + minY) : "0.0";
            g.drawString(mc.font, topLabel, bx - 28, by, 0xCCCCCC);
            g.drawString(mc.font, botLabel, bx - 28, by + bh - 8, 0xCCCCCC);
            g.drawString(mc.font, I18n.get(layer.labelKey), lx, ly, 0x66CCFF);
        }
    }

    /** 根据当前排序模式返回图例条目 */
    private List<GeoPalette.LegendEntry> getSortedEntries(GeoPalette.PreviewLayer layer) {
        List<GeoPalette.LegendEntry> entries = new ArrayList<>(GeoPalette.discreteEntries(layer));
        switch (legendSortMode) {
            case 1: // 按名称排序
                entries.sort(Comparator.comparing(e -> {
                    String n = I18n.get(e.labelKey);
                    return n.equals(e.labelKey) ? GeoPalette.englishLabel(e.labelKey) : n;
                }));
                break;
            case 2: // 按分类排序（按 labelKey 前缀分组）
                entries.sort(Comparator.comparing((GeoPalette.LegendEntry e) -> e.labelKey).thenComparingInt(e -> e.id));
                break;
            default: // 按 id
                entries.sort(Comparator.comparingInt(e -> e.id));
                break;
        }
        return entries;
    }

    /** 图例区域是否包含坐标 */
    private boolean isOverLegend(double mx, double my) {
        if (!showLegend) return false;
        GeoPalette.PreviewLayer layer = getLayer();
        if (!layer.legendable || layer.kind != GeoPalette.Kind.DISCRETE) return false;
        int lx = getX() + width - 136, ly = getY() + 4;
        int lw = 132, lh = getHeight() - 40;
        return mx >= lx && mx <= lx + lw && my >= ly && my <= ly + lh;
    }

    private static String typeLine(Cell c) {
        switch (c.terrainType) {
            case OCEAN:              return I18n.get("geogenesis.preview.type.ocean");
            case DEEP_OCEAN:         return I18n.get("geogenesis.preview.type.deep_ocean");
            case CONTINENTAL_SHELF:  return I18n.get("geogenesis.preview.type.continental_shelf");
            case SUBMARINE_RIDGE:    return I18n.get("geogenesis.preview.type.submarine_ridge");
            case SEAMOUNT:           return I18n.get("geogenesis.preview.type.seamount");
            case BEACH:      return I18n.get("geogenesis.preview.type.beach");
            case PLAIN:      return c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.plain");
            case HILLS:      return c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.hills");
            case PLATEAU:    return c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.plateau");
            case MOUNTAINS:  return c.isSnow ? I18n.get("geogenesis.preview.type.snowy_slopes") : I18n.get("geogenesis.preview.type.mountains");
            case PEAK:       return c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.peak");
            case BASIN:      return I18n.get("geogenesis.preview.type.basin");
            case RIVER:      return I18n.get("geogenesis.preview.type.river");
            case LAKE:       return I18n.get("geogenesis.preview.type.lake");
            case SNOW:       return I18n.get("geogenesis.preview.type.snow");
            default:         return I18n.get("geogenesis.preview.type.land");
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my)) return false;

        // 图例区域点击：切换排序模式
        if (isOverLegend(mx, my) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            GeoPalette.PreviewLayer layer = getLayer();
            if (layer.legendable && layer.kind == GeoPalette.Kind.DISCRETE) {
                int lx = getX() + width - 136, ly = getY() + 8;
                int modeY = ly + 14; // titleH = 14
                // 点击排序按钮行
                if (my >= modeY && my <= modeY + 14 && mx >= lx && mx <= lx + 100) {
                    legendSortMode = (legendSortMode + 1) % 3;
                    legendScrollOffset = 0; // 切换排序后重置滚动
                    return true;
                }
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Minecraft.getInstance().keyboardHandler.setClipboard(String.format("%d, %d", hoverX, hoverZ));
            return true;
        }
        dragging = true;
        lastDragX = (int) mx;
        lastDragY = (int) my;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        // 松手后尽快重算一次
        lastInteractTime = System.currentTimeMillis();
        pendingResample = true;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && !isOverLegend(mx, my)) {
            originBlockX -= (mx - lastDragX) * scale;
            originBlockZ -= (my - lastDragY) * scale;
            lastDragX = (int) mx;
            lastDragY = (int) my;
            lastInteractTime = System.currentTimeMillis();
            pendingResample = true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;

        // 图例区域滚动优先
        if (isOverLegend(mx, my)) {
            GeoPalette.PreviewLayer layer = getLayer();
            if (layer.legendable && layer.kind == GeoPalette.Kind.DISCRETE) {
                List<GeoPalette.LegendEntry> entries = getSortedEntries(layer);
                int rowH = 14, modeH = 14, titleH = 14;
                int contentH = entries.size() * rowH;
                int entryMaxH = Math.min(titleH + modeH + contentH + 6, getHeight() - 40) - titleH - modeH - 6;
                int scrollableH = Math.max(0, contentH - entryMaxH);
                legendScrollOffset = Math.max(0, Math.min(scrollableH,
                        legendScrollOffset - (int)(delta * 20)));
                return true;
            }
        }

        double ns = Math.max(0.5, Math.min(64.0, scale * (delta > 0 ? 0.8 : 1.25)));
        double wx = originBlockX + (mx - getX()) * scale;
        double wz = originBlockZ + (my - getY()) * scale;
        scale = ns;
        originBlockX = wx - (mx - getX()) * scale;
        originBlockZ = wz - (my - getY()) * scale;
        lastInteractTime = System.currentTimeMillis();
        pendingResample = true;
        return true;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (isMouseOver(mx, my)) {
            hoverX = (int) Math.round(originBlockX + (mx - getX()) * scale);
            hoverZ = (int) Math.round(originBlockZ + (my - getY()) * scale);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, Component.literal("GeoGenesis terrain preview"));
    }

    public void close() {
        mc.getTextureManager().release(texLoc);
        texture.close();
    }
}
