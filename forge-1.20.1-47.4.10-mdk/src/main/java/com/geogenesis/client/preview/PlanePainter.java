package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.terrain.Cell;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.resources.language.I18n;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>平面精确管线</b>（★ 2026-11-09 B4b：从 {@code PreviewDisplay} 剪切出来）。
 *
 * <h2>为什么要独立</h2>
 * <p>{@code PreviewDisplay} 原本 2281 行，同时承担：采样管线 + 平面绘制 + 沙盘绘制 +
 * 图例 + HUD + 交互。用户原话：「明明我都说了要重构重构，结果各自完全独立分裂。」</p>
 *
 * <h2>★ 本类只做<b>剪切</b>，零逻辑改动</h2>
 * <p>所有方法<b>原样搬过来</b>，通过 {@code host} 访问 {@code PreviewDisplay} 的
 * 包级可见成员 —— 因此平面视觉<b>逐像素不变</b>（验收靠指纹 + 截图对拍）。</p>
 *
 * <h2>「预览 = 游戏」的保证在这里</h2>
 * <p>{@link #paintAvailableChunks} + {@link #applySlopeShading} 是精确管线的核心：
 * 逐 chunk 采样（{@code sampleCell} 完整链，含侵蚀）→ 写纹理 → 坡度阴影。
 * 任何改动都必须跑 {@code runDefaultOutputFingerprintProbe} 对拍。</p>
 */
public final class PlanePainter {

    /** 宿主（提供状态与纹理）。 */
    final PreviewDisplay host;

    PlanePainter(PreviewDisplay host) {
        this.host = host;
    }


    // ---- paintLargeArea ----
    void paintLargeArea(int originWx, int originWz, int blocksWide, int blocksHigh) {
        long id = (long) originWx * 31L + originWz;
        id = id * 131L + blocksWide;
        id = id * 131L + blocksHigh;
        if (host.largeSampler == null) {
            host.largeSampler = new ProgressiveSampler(ProgressiveSampler.DEFAULT_GRID,
                    ProgressiveSampler.DEFAULT_GRID);
        }
        // ★ S2：视口变了先重开一轮。
        //   平面大范围窗口可达数万格 ⇒ 必须走廉价路径（水文会到分钟级，见 SandboxLod.usesHydroPath）。
        host.largeSampler.reset(host.terrain, originWx, originWz, blocksWide, blocksHigh, id, false);

        TerrainFieldData d = host.largeSampler.data();
        if (d == null) return;
        int gw = d.gridW, gh = d.gridH;
        Cell reuse = host.paintedCell;                 // 复用同一实例，避免每像素 new
        for (int py = 0; py < host.texH; py++) {
            int gz = Math.min(gh - 1, py * gh / host.texH);
            int wz = originWz + py * host.scaleBlockPos;
            for (int px = 0; px < host.texW; px++) {
                int gx = Math.min(gw - 1, px * gw / host.texW);
                Cell c = d.toDisplayCell(gx, gz, reuse);
                int color = 0;
                if (c != null) {
                    int wx = originWx + px * host.scaleBlockPos;   // 真实世界坐标（供纬度/坐标类图层）
                    color = GeoPalette.color(host.activeLayer, c, wx, wz, host.minY, host.maxY, true);
                }
                host.image.setPixelRGBA(px, py, GeoPalette.toABGR(color));
            }
        }
    }

    // ---- applySlopeShading ----
    void applySlopeShading(NativeImage img, int originWx, int originWz) {
        // 标准地图学光源：屏幕左上方（lx<0 左、lz<0 屏幕上方），符合人眼"光从左上来"的直觉，
        // 避免旧 (0.5,1,0.3)=右下方光源造成的"陆地凹陷/海洋突出"错觉。
        float lx = -0.5f, ly = 1.0f, lz = -0.3f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len; ly /= len; lz /= len;

        int w = img.getWidth(), h = img.getHeight();
        int total = w * h;
        int[] effH = new int[total];       // 有效高度：水域填海平面，陆地填 worldY
        boolean[] valid = new boolean[total];
        // 水下掩码：e<0 为海平面以下（海洋）。水面是平的，海底起伏不应被 hillshade 雕出浮雕，
        // 否则在左上方光源下仍会显"海洋突出"的错觉。水域像素保留纯色带（按深度渐变），不做明暗调制。
        boolean[] water = new boolean[total];

        // 1. 提取高度（必须与着色层同一世界坐标映射：纹理像素 px → 世界块 originWx + px*host.scaleBlockPos，
        //    否则缩放后阴影层相对着色层产生线性位移 → 视差/剪切错位）
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int wx = originWx + px * host.scaleBlockPos;
                int wz = originWz + py * host.scaleBlockPos;
                int cx = wx >> 4, cz = wz >> 4;
                int lx2 = wx & 15, lz2 = wz & 15;
                Cell c = host.cellCache.getCell(cx, cz, lx2, lz2);
                if (c != null) {
                    boolean isWater = c.e < 0.0;
                    water[py * w + px] = isWater;
                    // 水域用海平面高度代替海床：海岸处 陆地(≈seaLevel)−水域(seaLevel)≈0，
                    // 消除海底深度在海岸线陆地投下的伪阴影"悬崖"（海岸阴影截断）。
                    effH[py * w + px] = isWater ? host.seaLevel : (int) c.height;
                    valid[py * w + px] = true;
                }
            }
        }

        // 2. 3×3 盒型平滑（消除块边界跳变）；水域以 effH=seaLevel 参与模糊，
        //    使海岸处 陆地(≈seaLevel)−水域(seaLevel)≈0，海岸阴影从岸边平滑过渡到内陆起伏（不再截断）。
        int[] smooth = effH.clone();
        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                if (!valid[py * w + px]) continue;
                long sum = 0; int cnt = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int idx = (py + dy) * w + (px + dx);
                        if (valid[idx]) { sum += effH[idx]; cnt++; }
                    }
                }
                if (cnt >= 3) smooth[py * w + px] = (int) (sum / cnt);
            }
        }

        // 3. 坡度计算 + 亮度修正（跳过水域像素，海面保持平涂）
        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                int idx = py * w + px;
                if (water[idx] || !valid[idx] || !valid[idx - 1] || !valid[idx + 1]
                        || !valid[idx - w] || !valid[idx + w]) continue;
                // 斜率按 host.scaleBlockPos 归一化到「每世界块」，使缩放各级阴影强度一致
                // （相邻纹理像素实际间隔 host.scaleBlockPos 个世界块）
                float inv = 0.5f / host.scaleBlockPos;
                float dhdx = (smooth[idx + 1] - smooth[idx - 1]) * inv;
                float dhdz = (smooth[idx + w] - smooth[idx - w]) * inv;
                float nx = -dhdx, ny = 1.0f, nz = -dhdz;
                float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nLen < 0.001f) continue;
                nx /= nLen; ny /= nLen; nz /= nLen;
                float diff = Math.max(0, nx * lx + ny * ly + nz * lz) * 0.6f + 0.4f;

                int abgr = img.getPixelRGBA(px, py);
                int a = (abgr >> 24) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int g2 = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                img.setPixelRGBA(px, py, (a << 24) | (Math.min(255, (int)(b * diff)) << 16)
                        | (Math.min(255, (int)(g2 * diff)) << 8) | Math.min(255, (int)(r * diff)));
            }
        }
    }

    // ---- paintAvailableChunks ----
    int paintAvailableChunks(int originWx, int originWz, int blocksWide, int blocksHigh) {
        int minCX = originWx >> 4;
        int maxCX = (originWx + blocksWide) >> 4;
        int minCZ = originWz >> 4;
        int maxCZ = (originWz + blocksHigh) >> 4;

        int step = Math.min(16, Math.max(1, host.scaleBlockPos));
        int paintedCount = 0;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Cell[] cells = host.cellCache.get(cx, cz);
                if (cells == null) continue;

                for (int lx = 0; lx < 16; lx += step) {
                    for (int lz = 0; lz < 16; lz += step) {
                        int wx = (cx << 4) + lx;
                        int wz = (cz << 4) + lz;
                        int tx = (wx - originWx) / host.scaleBlockPos;
                        int tz = (wz - originWz) / host.scaleBlockPos;
                        if (tx < 0 || tx >= host.texW || tz < 0 || tz >= host.texH) continue;
                        // ★ 2026-08-08：修复索引转置——generateChunk 写 cells[lx*16+lz]（X 行 Z 列），
                        //   此处必须同约定读，否则 chunk 内 X/Z 交换 → 16 块间距网格（19df17e 后引入）。
                        Cell cell = cells[lx * 16 + lz];
                        if (cell == null) continue;
                        int color = GeoPalette.color(host.activeLayer, cell, wx, wz, host.minY, host.maxY, true);
                        // 离散图层：过滤模式 → 未勾选压暗；否则选中高亮其余压暗（每帧重绘，实时生效）
                        if (host.activeLayer.kind == GeoPalette.Kind.DISCRETE) {
                            int id = GeoPalette.discreteIdForCell(host.activeLayer, cell);
                            if (host.filterMode) {
                                if (!host.filterIds.contains(id)) color = grayTint(color);
                            } else if (host.selectedLayer == host.activeLayer && host.selectedId >= 0 && id != host.selectedId) {
                                color = grayTint(color);
                            }
                        }
                        // ★ 细胞边界叠加（诊断子图层）：与左/上邻居主导地形类型不同处画深色线。
                        //   对齐阴影等叠加层的表现方式——不改变数据色，仅压暗边界像素。
                        if (host.showCellBorders) {
                            int cxc = wx >> 4, czc = wz >> 4;
                            int lxc = wx & 15, lzc = wz & 15;
                            // X 主序：cells[lx*16+lz]（与 generateChunk 一致）
                            Cell left = (lxc > 0) ? cells[(lxc - 1) * 16 + lzc]
                                    : host.cellCache.getCell(cxc - 1, czc, 15, lzc);
                            Cell up = (lzc > 0) ? cells[lxc * 16 + (lzc - 1)]
                                    : host.cellCache.getCell(cxc, czc - 1, lxc, 15);
                            boolean leftDiff = left != null && left.terrainType != cell.terrainType;
                            boolean upDiff = up != null && up.terrainType != cell.terrainType;
                            if (leftDiff || upDiff) {
                                int r = (color >> 16) & 0xFF, gg = (color >> 8) & 0xFF, b = color & 0xFF;
                                r = r * 3 / 10; gg = gg * 3 / 10; b = b * 3 / 10;
                                color = (r << 16) | (gg << 8) | b;
                            }
                        }
                        // ★ 2026-08-14 tile 边界叠加（诊断侵蚀 tile 网格/边缘错位用）：
                        //   线在块坐标 % (48×host.horizontalScale) == 0 处（ERODE_TILE_CENTER=48wu）。
                        //   用比细胞边界更暗的压暗（×2/10），一眼可辨 48wu 网格线。
                        if (host.showTileBorders) {
                            int tb = (int) Math.round(48 * host.horizontalScale);
                            if (tb > 0 && (wx % tb == 0 || wz % tb == 0)) {
                                int r = (color >> 16) & 0xFF, gg = (color >> 8) & 0xFF, b = color & 0xFF;
                                r = r * 2 / 10; gg = gg * 2 / 10; b = b * 2 / 10;
                                color = (r << 16) | (gg << 8) | b;
                            }
                        }
                        host.image.setPixelRGBA(tx, tz, GeoPalette.toABGR(color));
                        paintedCount++;
                    }
                }
            }
        }
        return paintedCount;
    }

    // ---- grayTint ----
    //   ★ v68：实现搬到 {@link GeoPalette#dimForFilter}（沙盘复用同一份）。
    private static int grayTint(int rgb) {
        return GeoPalette.dimForFilter(rgb);
    }

    // ---- alignDown ----
    static int alignDown(int v, int scale) {
        return Math.floorDiv(v, scale) * scale;
    }

    // ---- heightFromE ----
    double heightFromE(double e) {
        if (e <= 0.0) return host.seaLevel - (-e) * (host.seaLevel - host.minY);
        double t = Math.max(0.0, Math.min(1.0, e * host.verticalScale));
        return host.seaLevel + t * (host.maxY - host.seaLevel) * host.peakFraction;
    }

    // ---- drawSpawnMarker ----
    void drawSpawnMarker(GuiGraphics g) {
        if (Minecraft.getInstance().level == null) return;
        BlockPos spawn = Minecraft.getInstance().level.getSharedSpawnPos();
        if (spawn == null) return;
        // 计算出生点在纹理中的坐标（与渲染同源：对齐后的统一原点）
        int blocksWide = host.texW * host.scaleBlockPos;
        int blocksHigh = host.texH * host.scaleBlockPos;
        int originWx = alignDown(host.centerX + (int) host.totalDragX - blocksWide / 2, host.scaleBlockPos);
        int originWz = alignDown(host.centerZ + (int) host.totalDragZ - blocksHigh / 2, host.scaleBlockPos);
        int sx = spawn.getX(), sz = spawn.getZ();
        int texSx = (sx - originWx) / host.scaleBlockPos;
        int texSz = (sz - originWz) / host.scaleBlockPos;
        if (texSx < 0 || texSx >= host.texW || texSz < 0 || texSz >= host.texH) return;

        // 映射到屏幕坐标
        int x = host.getX(), y = host.getY(), w = host.getWidth(), h = host.getHeight();
        int screenSx = x + (int)((double)texSx / host.texW * w);
        int screenSz = y + (int)((double)texSz / host.texH * h);
        int markerSize = 6;

        // 金色 X 形罗盘标记（移除白边框方框，避免与正常方块误判）
        int gold = 0xFFFFAA00;
        g.fill(screenSx - markerSize, screenSz - 1, screenSx - 1, screenSz + 1, gold);
        g.fill(screenSx + 1, screenSz - 1, screenSx + markerSize, screenSz + 1, gold);
        g.fill(screenSx - 1, screenSz - markerSize, screenSx + 1, screenSz - 1, gold);
        g.fill(screenSx - 1, screenSz + 1, screenSx + 1, screenSz + markerSize, gold);
    }

    // ---- drawFrameTime ----
    void drawFrameTime(GuiGraphics g, int rightX, int topY) {
        if (host.frameTimes.isEmpty()) return;
        long sum = 0;
        for (long t : host.frameTimes) sum += t;
        long avg = sum / host.frameTimes.size();
        int color;
        if (avg < 16) color = 0xFF44CC44;      // 绿：流畅
        else if (avg < 33) color = 0xFFFFAA00;  // 黄：可接受
        else color = 0xFFFF5555;                 // 红：卡顿
        String txt = avg + " ms";
        // ★ v8：浮层底改圆角（旧版是直角半透明方块）
        int txtW = Minecraft.getInstance().font.width(txt);
        UiKit.roundRect(g, rightX - txtW - 8, topY + 2, rightX - 2, topY + 13, UiKit.RADIUS_FIELD, 0x88000000);
        g.drawString(Minecraft.getInstance().font, txt, rightX - txtW - 5, topY + 3, color, false);
    }

    // ---- drawPlayerMarker ----
    void drawPlayerMarker(GuiGraphics g) {
        if (Minecraft.getInstance().player == null) return;  // 非游戏世界（无玩家）不画
        double px = Minecraft.getInstance().player.getX();
        double pz = Minecraft.getInstance().player.getZ();
        int blocksWide = host.texW * host.scaleBlockPos;
        int blocksHigh = host.texH * host.scaleBlockPos;
        // 与渲染同源：统一对齐原点
        int originWx = alignDown(host.centerX + (int) host.totalDragX - blocksWide / 2, host.scaleBlockPos);
        int originWz = alignDown(host.centerZ + (int) host.totalDragZ - blocksHigh / 2, host.scaleBlockPos);
        int texX = (int) ((px - originWx) / host.scaleBlockPos);
        int texZ = (int) ((pz - originWz) / host.scaleBlockPos);
        if (texX < 0 || texX >= host.texW || texZ < 0 || texZ >= host.texH) return;  // 玩家在视口外

        int x = host.getX(), y = host.getY(), w = host.getWidth(), h = host.getHeight();
        int sx = x + (int) ((double) texX / host.texW * w);
        int sy = y + (int) ((double) texZ / host.texH * h);

        // 深色描边（5×5 圆环） + 白色中心（3×3 圆点）
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 <= 4) {
                    int col = (d2 <= 1) ? 0xFFFFFFFF : 0xFF1a1a2a;
                    g.fill(sx + dx, sy + dy, sx + dx + 1, sy + dy + 1, col);
                }
            }
        }
        // 姓名标签（顶部）—— ★ v8：圆角浮层
        String name = Minecraft.getInstance().player.getGameProfile().getName();
        int tw = Minecraft.getInstance().font.width(name);
        UiKit.roundRect(g, sx - tw / 2 - 3, sy - 15, sx + tw / 2 + 3, sy - 3, UiKit.RADIUS_FIELD, 0xAA000000);
        g.drawString(Minecraft.getInstance().font, name, sx - tw / 2, sy - 12, 0xFFFFFFFF, false);
    }

    // ---- drawStructureMarkers ----
    void drawStructureMarkers(GuiGraphics g, int mx, int my) {
        int blocksWide = host.texW * host.scaleBlockPos;
        int blocksHigh = host.texH * host.scaleBlockPos;
        int originWx = alignDown(host.centerX + (int) host.totalDragX - blocksWide / 2, host.scaleBlockPos);
        int originWz = alignDown(host.centerZ + (int) host.totalDragZ - blocksHigh / 2, host.scaleBlockPos);
        int x = host.getX(), y = host.getY(), w = host.getWidth(), h = host.getHeight();
        // 悬停命中检测
        String hoverName = null;
        for (var entry : host.structureScanner.allHits()) {
            for (var hit : entry.getValue()) {
                int wx = (hit.chunkX() << 4) + 8;
                int wz = (hit.chunkZ() << 4) + 8;
                int texX = (wx - originWx) / host.scaleBlockPos;
                int texZ = (wz - originWz) / host.scaleBlockPos;
                if (texX < 0 || texX >= host.texW || texZ < 0 || texZ >= host.texH) continue;
                int sx = x + (int) ((double) texX / host.texW * w);
                int sy = y + (int) ((double) texZ / host.texH * h);
                int color = structureColor(hit.id());
                g.fill(sx - 2, sy - 2, sx + 2, sy + 2, color);
                g.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFFFFFFF);
                if (mx >= sx - 3 && mx <= sx + 3 && my >= sy - 3 && my <= sy + 3) {
                    hoverName = structureName(hit.id());
                }
            }
        }
        if (hoverName != null) {
            int tw = Minecraft.getInstance().font.width(hoverName);
            // ★ v8：圆角浮层
            UiKit.roundRect(g, mx + 10, my + 8, mx + 14 + tw, my + 21, UiKit.RADIUS_FIELD, 0xCC000000);
            g.drawString(Minecraft.getInstance().font, hoverName, mx + 13, my + 10,
                    0xFFFFFFFF, false);
        }
    }

    // ---- structureColor ----
    private static int structureColor(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return 0xFFFFAA00;
        String s = id.getPath();
        if (s.contains("village")) return 0xFF00FF88;      // 村庄绿
        if (s.contains("stronghold")) return 0xFF8844FF;   // 要塞紫
        if (s.contains("ancient_city")) return 0xFF00CCFF; // 古城青
        if (s.contains("mineshaft")) return 0xFF888888;    // 矿井灰
        if (s.contains("ruined")) return 0xFFCC8844;      // 遗迹棕
        if (s.contains("ocean_ruin")) return 0xFF2266AA;   // 海底遗迹蓝
        if (s.contains("shipwreck")) return 0xFFCC6644;    // 沉船橙
        if (s.contains("desert_pyramid")) return 0xFFFFCC44; // 沙漠神殿黄
        if (s.contains("jungle_pyramid")) return 0xFF33CC66; // 丛林神殿绿
        if (s.contains("igloo")) return 0xFFAAEEFF;        // 冰屋浅蓝
        if (s.contains("pillager")) return 0xFFCC5555;     // 掠夺者红
        if (s.contains("monument")) return 0xFF22AAAA;     // 海底神殿青
        if (s.contains("fortress")) return 0xFFAA4444;     // 下界要塞红
        if (s.contains("bastion")) return 0xFF883333;      // 猪灵堡垒棕
        if (s.contains("trail")) return 0xFFBBBB88;        // 古迹灰黄
        return 0xFF00AAFF;                                  // 其他蓝
    }

    // ---- structureName ----
    private static String structureName(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return "结构";
        // 转换 minecraft:village_plains → Village Plains
        String p = id.getPath().replace('_', ' ');
        if (p.isEmpty()) return id.toString();
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    // ---- drawCopiedToast ----
    void drawCopiedToast(GuiGraphics g, int px, int py, int pw, int ph) {
        if (host.copiedToastMsg == null || host.copiedToastTime == null) return;
        long elapsed = Duration.between(host.copiedToastTime, Instant.now()).toMillis();
        if (elapsed >= 8000) {
            host.copiedToastMsg = null;
            host.copiedToastTime = null;
            return;
        }
        int alpha = Math.max(0, Math.min(255, 220 - (int)(elapsed * 220 / 8000)));
        if (alpha <= 0) return;
        // 绘制居中底栏消息（★ v8：圆角胶囊）
        int textW = Minecraft.getInstance().font.width(host.copiedToastMsg);
        int bx = px + (pw - textW) / 2 - 6;
        int by = py + ph - 32;
        UiKit.roundRect(g, bx, by, bx + textW + 12, by + 14, 7, (alpha << 24) | 0x181C20);
        g.drawString(Minecraft.getInstance().font, host.copiedToastMsg, bx + 6, by + 3,
                (alpha << 24) | 0xFFFFFF, false);
    }

    // ---- localize ----
    String localize(String key) {
        String s = I18n.get(key);
        return s.equals(key) ? GeoPalette.englishLabel(key) : s;
    }

    // ---- sampleHoverCell ----
    Cell sampleHoverCell(int wx, int wz) {
        int cx = wx >> 4, cz = wz >> 4;
        int lx = wx & 15, lz = wz & 15;
        // 缓存 miss → 返回 null（tooltip 不显示）。地形采样一律交给后台 Worker。
        return host.cellCache.getCell(cx, cz, lx, lz);
    }

    // ---- drawHoverInfo ----
    void drawHoverInfo(GuiGraphics g, int mx, int my) {
        if (!overWidget(mx, my)) return;
        Cell c = sampleHoverCell(host.hoverX, host.hoverZ);
        if (c == null) return;

        // 跟随光标 tooltip（偏移 14px，夹取在屏幕内避免越界）
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int lx = (int) mx + 14;
        int ly = (int) my + 14;
        // 第一行：坐标 + 高度
        String line0 = String.format("X=%d  Z=%d  Y=%d", host.hoverX, host.hoverZ, (int) Math.round(c.height));
        // 第二行：群系名 + 地形类型
        BiomeClassifier.BiomeClass biome = BiomeClassifier.classify(c);
        String biomeName = "—";
        if (biome != null) {
            String key = "geogenesis.biome." + biome.name();
            String localized = I18n.get(key);
            biomeName = localized.equals(key) ? biome.name() : localized;
        }
        String line1 = biomeName + "  " + typeLine(c);
        // 第三行：温度/湿度/大陆性
        String line2 = String.format("temp=%.2f  hum=%.2f  cont=%.2f", c.temperature, c.humidity, c.continentNoise);
        // 第四行：纬度 + 地形起伏
        String line3 = String.format("lat=%.2f  relief=%.2f",
            com.geogenesis.worldgen.climate.Latitude.latitude01(host.hoverZ),
            (c.shape + 1) * 0.5);
        // 第五行：水文信息（河流系统已清除，仅保留湖泊）
        String line4 = c.lakeMask ? I18n.get("geogenesis.preview.lake") : "—";

        String line5 = host.activeLayer.kind == GeoPalette.Kind.DISCRETE
            ? "左键选中色块 · 右键复制坐标"
            : "右键复制坐标";
        String[] lines = {line0, line1, line2, line3, line4, line5};
        // 自动计算面板宽度
        int maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, Minecraft.getInstance().font.width(l));
        maxW = Math.max(maxW, 160);
        int panelH = lines.length * 13 + 4;
        if (lx + maxW + 10 > screenW) lx = (int) mx - maxW - 18;
        if (ly + panelH > screenH) ly = (int) my - panelH - 14;
        lx = Math.max(2, lx);
        ly = Math.max(2, ly);
        // ★ v8：悬停信息浮层改圆角（旧版直角黑块）
        UiKit.roundRect(g, lx, ly, lx + maxW + 12, ly + panelH + 2, UiKit.RADIUS_CARD, 0xCC000000);
        for (int i = 0; i < lines.length; i++) {
            int col = (i == lines.length - 1) ? 0xFF88CCAA : 0xFFF2F2F2;
            g.drawString(Minecraft.getInstance().font, lines[i], lx + 6, ly + 5 + i * 13,
                    col, false);
        }
    }

    // ---- typeLine ----
    private static String typeLine(Cell c) {
        return switch (c.terrainType) {
            case OCEAN -> I18n.get("geogenesis.preview.type.ocean");
            case DEEP_OCEAN -> I18n.get("geogenesis.preview.type.deep_ocean");
            case CONTINENTAL_SHELF -> I18n.get("geogenesis.preview.type.continental_shelf");
            case SUBMARINE_RIDGE -> I18n.get("geogenesis.preview.type.submarine_ridge");
            case SEAMOUNT -> I18n.get("geogenesis.preview.type.seamount");
            case BEACH -> I18n.get("geogenesis.preview.type.beach");
            case PLAIN -> I18n.get("geogenesis.preview.type.plain");
            case HILLS -> I18n.get("geogenesis.preview.type.hills");
            case PLATEAU -> I18n.get("geogenesis.preview.type.plateau");
            case MOUNTAINS -> I18n.get("geogenesis.preview.type.mountains");
            case PEAK -> I18n.get("geogenesis.preview.type.peak");
            case BASIN -> I18n.get("geogenesis.preview.type.basin");
            case RIVER -> I18n.get("geogenesis.preview.type.river");
            case LAKE -> I18n.get("geogenesis.preview.type.lake");
            default -> I18n.get("geogenesis.preview.type.land");
        };
    }

    /** 鼠标是否在宿主 widget 内（{@code isMouseOver} 是 protected，无法跨类访问）。 */
    private boolean overWidget(double mx, double my) {
        return mx >= host.getX() && mx < host.getX() + host.getWidth()
                && my >= host.getY() && my < host.getY() + host.getHeight();
    }

    // ---- border ----
    private static void border(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int w) {
        g.fill(x1, y1, x2, y1 + w, color);
        g.fill(x1, y2 - w, x2, y2, color);
        g.fill(x1, y1 + w, x1 + w, y2 - w, color);
        g.fill(x2 - w, y1 + w, x2, y2 - w, color);
    }

    // ---- drawZoomLabel ----
    void drawZoomLabel(GuiGraphics g, int x, int y, int w, int h) {
        // ★★ 2026-11-08【修"该有的功能都没有"· 第二处根因】：
        //
        //   GeoGenesisConfigScreen 为了"全屏时由屏幕 HUD 代劳"，
        //   调用了 preview.setDrawZoomLabel(false) —— 于是这个方法直接 return，
        //   我新写的整套沙盘 HUD（左上标题/种子/操作说明、右缘进度条、
        //   左下悬停信息、右下比例尺）**一条都没画出来**。
        //   而 ConfigScreen 那边只有一条右下状态栏 ⇒ 实机看起来"功能全没了"。
        //
        //   ⇒ 沙盘 HUD 是这个模式的核心信息层，<b>不受 drawZoomLabel 开关影响</b>。
        if (host.sandboxMode) {
            host.drawSandboxHints(g, x, y, w, h);
            return;
        }
        if (!host.drawZoomLabel) return;
        String zoomTxt = "1:" + host.scaleBlockPos
                + (host.largeArea ? " · " + localize("geogenesis.preview.large_area") : "");
        int ztw = Minecraft.getInstance().font.width(zoomTxt);
        // ★ v8：圆角胶囊
        UiKit.roundRect(g, x + w - ztw - 9, y + h - 15, x + w - 2, y + h - 2, UiKit.RADIUS_FIELD, 0x88000000);
        g.drawString(Minecraft.getInstance().font, zoomTxt,
                x + w - ztw - 5, y + h - 13, 0xFFDCDCDC, false);
    }
}
