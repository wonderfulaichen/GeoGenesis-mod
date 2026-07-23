package com.geogenesis.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构图标叠加层（资源包范式，可选、安全跳过）。
 *
 * <p>从 {@code config/geogenesis/structure_markers.json} 读取标记列表：
 * <pre>
 * { "markers": [ {"name":"村庄","x":123,"z":-456,"color":15467270,"glyph":"V"} ] }
 * </pre>
 * 文件不存在或为空时完全不渲染（符合"无资源则跳过"边界条件）。
 * 标记按世界坐标→预览屏幕投影绘制程序化字形（彩色方块+首字母），
 * 悬停返回标记名供预览显示（命中检测）。不依赖外部 PNG 资源。
 */
public class StructureIconOverlay {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final Path FILE = Path.of("config/geogenesis/structure_markers.json");
    private static final Gson GSON = new GsonBuilder().create();

    /** 单个结构标记（JSON 字段直接映射） */
    public static class Marker {
        public String name = "";
        public int x = 0;
        public int z = 0;
        public int color = 0x00C896;
        public String glyph = "?";
    }

    private static class MarkerData {
        public List<Marker> markers;
    }

    private final List<Marker> markers = new ArrayList<>();
    private boolean loaded = false;

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;
        try (FileReader r = new FileReader(FILE.toFile())) {
            MarkerData data = GSON.fromJson(r, MarkerData.class);
            if (data != null && data.markers != null) markers.addAll(data.markers);
        } catch (Exception e) {
            LOGGER.warn("加载结构标记失败: {}", e.getMessage());
        }
    }

    /**
     * 渲染当前视口内可见的标记。
     * @return 鼠标悬停的标记名（含坐标），无则 null
     */
    public String render(GuiGraphics g, int px, int py, int pw, int ph,
                         int originWx, int originWz, int blocksWide, int blocksHigh,
                         int mx, int my) {
        load();
        if (markers.isEmpty()) return null;

        Font font = Minecraft.getInstance().font;
        String hovered = null;
        int s = 10;
        for (Marker m : markers) {
            double fx = (double) (m.x - originWx) / blocksWide;
            double fz = (double) (m.z - originWz) / blocksHigh;
            if (fx < -0.05 || fx > 1.05 || fz < -0.05 || fz > 1.05) continue;
            int sx = px + (int) (fx * pw);
            int sy = py + (int) (fz * ph);
            // 彩色方块 + 高亮描边
            g.fill(sx - s / 2, sy - s / 2, sx + s / 2, sy + s / 2, 0xFF000000 | (m.color & 0xFFFFFF));
            g.fill(sx - s / 2, sy - s / 2, sx + s / 2, sy - s / 2 + 1, 0xFFFFFFFF);
            // 首字母字形
            String gl = (m.glyph != null && !m.glyph.isEmpty()) ? m.glyph.substring(0, 1) : "?";
            g.drawString(font, gl, sx - 3, sy - 4, 0xFF101010);
            // 命中检测（悬停）
            if (mx >= sx - s / 2 && mx <= sx + s / 2 && my >= sy - s / 2 && my <= sy + s / 2) {
                hovered = m.name + " (" + m.x + ", " + m.z + ")";
            }
        }
        return hovered;
    }
}
