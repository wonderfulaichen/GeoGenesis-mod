package com.geogenesis.client.preview;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.Latitude;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立地形预览窗口（Swing，零 MC 依赖）。
 * <p>
 * 使用 {@link PreviewCache} + {@link PreviewWorker} 异步采样引擎：
 * 拖拽/缩放时后台渐进式计算（低分→高分），图层切换仅重新填充像素，无需重采样 Cell 网格。
 * <p>
 * 视图模式：数字键 1..9/0 选图层 0..9；[ / ] 前后切换；R 水文叠加；X 分辨率；C 清空搜索。
 * 图例：离散图层列出条目（按搜索框过滤），连续图层画渐变条。
 * 运行：gradlew runPreview --args=98765
 */
public final class TerrainPreview {

    private static final int PANEL = 600;
    private static final int[] QUALITY = {1, 2, 4}; // 渲染降采样（分辨率切换）

    private final GeoGenesisTerrain terrain;
    private final int seaLevel, snowLine, maxY, minY, horizontalScale, mountainCap;
    /** 高程色阶映射的 e 区间（地形实际可达范围），供图例 Y 标签换算。 */
    private double elevEMin = -1.0, elevEMax = 1.0;
    private final long seed;

    private double originX = 0.0, originZ = 0.0;
    private double scale = 2.0; // blocks per pixel
    private boolean hydrology = true;
    private int layerIndex = 0;
    private int qualityIdx = 0;
    private String search = "";

    // === 缓存引擎 ===
    private final PreviewCache cache = new PreviewCache();
    private final PreviewWorker worker;

    // === Swing 组件 ===
    private final JFrame frame;
    private final JPanel canvas;
    private final JLabel info;
    private final JTextField searchBox;
    private BufferedImage lastFrame;
    private double lastOriginX, lastOriginZ, lastScale;
    private int hoverPx = -1, hoverPy = -1;
    private int lastX, lastY;

    public TerrainPreview(long seed, TerrainParams params) {
        this.seed = seed;
        com.geogenesis.worldgen.terrain.CellGenerator gen =
            new com.geogenesis.worldgen.terrain.CellGenerator(params, params.minY(), params.maxY());
        gen.seed(seed);
        this.terrain = new GeoGenesisTerrain(gen);
        this.seaLevel = params.seaLevel();
        this.snowLine = (int) new com.geogenesis.worldgen.terrain.HeightCurve(params, params.minY(), params.maxY()).heightFromE(params.snowLine());
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.horizontalScale = (int) params.horizontalScale();
        this.mountainCap = params.mountainCap();
        double[] er = params.elevationERange();
        this.elevEMin = er[0]; this.elevEMax = er[1];
        GeoPalette.setElevationERange(er[0], er[1]);
        GeoPalette.setSeaLevel(seaLevel);

        // 初始化缓存引擎（回调在 canvas 创建后设置）
        this.worker = new PreviewWorker(terrain, cache);
        worker.setHeightRange(minY, mountainCap);
        worker.setSeaLevel(seaLevel);
        worker.setHydrology(hydrology);

        frame = new JFrame("GeoGenesis Terrain Preview");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                render((Graphics2D) g);
            }
        };
        canvas.setPreferredSize(new Dimension(PANEL, PANEL));
        canvas.setFocusable(true);

        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { lastX = e.getX(); lastY = e.getY(); }
            @Override public void mouseExited(MouseEvent e) { if (hoverPx != -1) { hoverPx = hoverPy = -1; canvas.repaint(); } }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                originX -= (e.getX() - lastX) * scale; originZ -= (e.getY() - lastY) * scale;
                lastX = e.getX(); lastY = e.getY();
                requestResample();
            }
            @Override public void mouseMoved(MouseEvent e) { hoverPx = e.getX(); hoverPy = e.getY(); canvas.repaint(); }
        });
        canvas.addMouseWheelListener(new MouseAdapter() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                scale *= (e.getWheelRotation() < 0) ? 0.8 : 1.25;
                scale = Math.max(0.25, Math.min(64.0, scale));
                requestResample();
            }
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                char c = e.getKeyChar();
                int n = GeoPalette.PreviewLayer.values().length;
                if (c >= '1' && c <= '9') {
                    layerIndex = c - '1';
                    switchLayer();
                } else if (c == '0') {
                    layerIndex = 9;
                    switchLayer();
                } else if (c == '[') {
                    layerIndex = (layerIndex - 1 + n) % n;
                    switchLayer();
                } else if (c == ']') {
                    layerIndex = (layerIndex + 1) % n;
                    switchLayer();
                } else if (c == 'r' || c == 'R') {
                    hydrology = !hydrology;
                    worker.setHydrology(hydrology);
                    worker.computeLayer(currentLayer());
                    canvas.repaint();
                } else if (c == 'x' || c == 'X') {
                    qualityIdx = (qualityIdx + 1) % QUALITY.length;
                    canvas.repaint();
                } else if (c == 'c' || c == 'C') {
                    search = ""; searchBox.setText(""); canvas.repaint();
                } else if (c == '/') {
                    activateSearch();
                }
            }
        });

        // canvas 已初始化完成，设置 Worker 回调
        worker.setOnComplete(c -> SwingUtilities.invokeLater(() -> canvas.repaint()));

        searchBox = new JTextField();
        searchBox.setToolTipText("图例搜索过滤（按 '/' 激活，Esc 退出）");
        searchBox.setFocusable(false);
        searchBox.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    deactivateSearch();
                } else {
                    search = searchBox.getText().trim().toLowerCase();
                    canvas.repaint();
                }
            }
        });

        info = new JLabel();
        frame.add(searchBox, BorderLayout.NORTH);
        frame.add(canvas, BorderLayout.CENTER);
        frame.add(info, BorderLayout.SOUTH);
        frame.pack();
        canvas.requestFocusInWindow();

        // 初始渲染
        requestResample();
    }

    // ============================================================
    // 图层切换 / 视口变化
    // ============================================================

    private GeoPalette.PreviewLayer currentLayer() {
        return GeoPalette.PreviewLayer.values()[layerIndex];
    }

    /** 图层切换：若已缓存则直接重绘，否则让 Worker 计算。 */
    private void switchLayer() {
        GeoPalette.PreviewLayer layer = currentLayer();
        if (cache.isLayerReady(layer)) {
            canvas.repaint();
        } else {
            worker.computeLayer(layer);
        }
    }

    /** 视口变化：后台渐进式重采样（低→高）。 */
    private void requestResample() {
        double sampleScale = (double) PANEL * scale / PANEL; // = scale
        worker.setPixelToWorldScale(sampleScale);
        cache.invalidate();
        worker.queueProgressive(viewportId(),
                (int) Math.floor(originX), (int) Math.floor(originZ),
                PANEL, PANEL, currentLayer());
    }

    private long viewportId() {
        long h = 37;
        h = h * 97 + (int) originX;
        h = h * 97 + (int) originZ;
        h = h * 97 + Double.doubleToLongBits(scale);
        return h;
    }

    // ============================================================
    // 渲染
    // ============================================================

    private void render(Graphics2D g) {
        GeoPalette.PreviewLayer layer = currentLayer();

        if (!cache.isLayerReady(layer)) {
            // 数据未就绪：显示旧帧仿射映射（若有）或黑屏占位
            if (lastFrame != null) {
                g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL);
                double sx = lastScale / scale, tx = (lastOriginX - originX) / scale, ty = (lastOriginZ - originZ) / scale;
                AffineTransform at = new AffineTransform(); at.translate(tx, ty); at.scale(sx, sx);
                g.drawImage(lastFrame, at, null);
            } else {
                g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL);
                g.setColor(Color.WHITE); g.drawString("计算中... (seed=" + seed + ")", 10, 30);
            }
            info.setText("计算中...  seed=" + seed);
            return;
        }

        try {
            int res = PANEL / QUALITY[qualityIdx];
            int quality = QUALITY[qualityIdx];
            int[] pixels = cache.getLayerPixels(layer);
            int bufW = cache.texWidth();

            BufferedImage img = new BufferedImage(res, res, BufferedImage.TYPE_INT_RGB);
            for (int py = 0; py < res; py++) {
                for (int px = 0; px < res; px++) {
                    int sx = px * quality;
                    int sy = py * quality;
                    int srcIdx = (sy < bufW) ? sy * bufW + sx : py * res + px;
                    int rgb = (srcIdx >= 0 && srcIdx < pixels.length) ? pixels[srcIdx] : 0;
                    img.setRGB(px, py, rgb);
                }
            }
            g.drawImage(img, 0, 0, PANEL, PANEL, null);
            lastFrame = img; lastOriginX = originX; lastOriginZ = originZ; lastScale = scale;

            drawLegend(g, layer);
            drawTooltip(g, layer);
            info.setText(String.format("seed=%d  scale=%.2f  layer=%s hydro=%s  res=%dx%d  q=%d  [1-9/0]图层 [ ]切换 [R]河 [X]分辨率 [/]搜索 [Esc]退出搜索",
                    seed, scale, GeoPalette.englishLabel(layer.labelKey), hydrology ? "ON" : "OFF", res, res, quality));
        } catch (Throwable t) {
            t.printStackTrace();
            if (lastFrame != null) g.drawImage(lastFrame, 0, 0, null);
            else { g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL); }
            g.setColor(Color.RED); g.drawString("渲染错误: " + t.getMessage(), 10, 30);
        }
    }

    // ============================================================
    // 图例
    // ============================================================

    private void drawLegend(Graphics2D g, GeoPalette.PreviewLayer layer) {
        int lx = PANEL - 150, ly = 10;
        if (layer.legendable && layer.kind == GeoPalette.Kind.DISCRETE) {
            List<GeoPalette.LegendEntry> all = GeoPalette.discreteEntries(layer);
            List<GeoPalette.LegendEntry> vis = new ArrayList<>();
            for (GeoPalette.LegendEntry e : all) {
                String label = GeoPalette.englishLabel(e.labelKey);
                if (search.isEmpty() || label.toLowerCase().contains(search)) vis.add(e);
            }
            int rowH = 16, titleH = 16, panelW = 146;
            int panelH = titleH + vis.size() * rowH + 6;
            g.setColor(new Color(0, 0, 0, 180)); g.fillRect(lx - 6, ly - 4, panelW, panelH);
            g.setColor(Color.CYAN); g.drawString(GeoPalette.englishLabel(layer.labelKey), lx, ly + 6);
            int cy = ly + titleH;
            for (GeoPalette.LegendEntry e : vis) {
                g.setColor(new Color(e.color)); g.fillRect(lx, cy, 12, 12);
                g.setColor(Color.WHITE); g.drawString(GeoPalette.englishLabel(e.labelKey), lx + 16, cy + 11);
                cy += rowH;
            }
        } else {
            int bx = PANEL - 28, by = 12, bh = 220, bw = 14;
            for (int i = 0; i < bh; i++) {
                double p = 1.0 - (double) i / (bh - 1);
                g.setColor(new Color(GeoPalette.continuous(layer, GeoPalette.legendGradientPos(layer, p))));
                g.fillRect(bx, by + i, bw, 1);
            }
            g.setColor(Color.WHITE); g.drawRect(bx, by, bw, bh);
            String[] lbl;
            if (layer == GeoPalette.PreviewLayer.ELEVATION) {
                lbl = new String[]{"Y=" + (int) Math.round(heightFromE(elevEMax)),
                                   "Y=" + (int) Math.round(heightFromE(elevEMin))};
            } else {
                lbl = GeoPalette.continuousLegendLabels(layer);
            }
            g.drawString(lbl[0], bx - 40, by + 8);
            g.drawString(lbl[1], bx - 40, by + bh);
        }
    }

    /** e→世界高度 Y（与 HeightCurve.heightFromE 一致的非对称映射：e=0→海平面）。 */
    private double heightFromE(double e) {
        if (e <= 0.0) return seaLevel - (-e) * (seaLevel - minY);
        return seaLevel + e * (maxY - seaLevel);
    }

    // ============================================================
    // 悬停提示
    // ============================================================

    private void drawTooltip(Graphics2D g, GeoPalette.PreviewLayer layer) {
        Cell[][] cells = cache.getCells();
        if (hoverPx < 0 || hoverPy < 0 || cells == null) return;
        if (hoverPx >= PANEL || hoverPy >= PANEL) return;
        int originBlockX = (int) Math.floor(originX);
        int originBlockZ = (int) Math.floor(originZ);
        double wx = originX + hoverPx * scale, wz = originZ + hoverPy * scale;
        int cx = Math.max(0, Math.min(cells.length - 1, (int) Math.floor((wx - originBlockX) / horizontalScale)));
        int cz = Math.max(0, Math.min(cells[0].length - 1, (int) Math.floor((wz - originBlockZ) / horizontalScale)));
        Cell cell = cells[cx][cz];
        String water;
        if (cell.riverIsWaterfall) water = "瀑布";
        else if (cell.riverSourceType == 3) water = "源头湖";
        else if (cell.riverSourceType == 2) water = "山泉";
        else if (cell.riverSourceType == 1) water = "溪源";
        else if (cell.lakeMask) water = "湖泊";
        else if (cell.riverMask) water = "河流";
        else if (cell.riverDistance < 0.1) water = "河谷";
        else water = "无";
        String net = cell.riverNetOverflow ? "溢出泛洪"
                : (cell.riverNetDist < 0.35 ? "河网" : (cell.riverNetDist < 1.0 ? "河谷" : "无"));
        String[] lines = {
                String.format("x=%d  z=%d", (int) Math.round(wx), (int) Math.round(wz)),
                "图层: " + layer.labelKey,
                "高度: Y=" + (int) Math.round(cell.height),
                String.format("地形: %s  e=%.3f", englishTerrainType(cell), cell.e),
                String.format("温度=%.2f 湿度=%.2f 大陆=%.2f", cell.temperature, cell.humidity, cell.continentNoise),
                String.format("纬度=%.2f 起伏=%.2f", Latitude.latitude01((int) Math.round(wz)), (cell.shape + 1) * 0.5),
                "水: " + water,
                "河网: " + net + (cell.riverNetDischarge > 0 ? String.format(" 流量=%.0f", cell.riverNetDischarge) : ""),
        };
        int pad = 6, lh = 15, boxW = 0;
        g.setFont(g.getFont().deriveFont(11f));
        for (String ln : lines) boxW = Math.max(boxW, g.getFontMetrics().stringWidth(ln));
        int boxH = lines.length * lh + pad * 2 - 2;
        int bx = (hoverPx > PANEL / 2) ? hoverPx - boxW - pad * 2 - 12 : hoverPx + 12;
        int by = Math.min(hoverPy + 12, PANEL - boxH - 2);
        if (bx < 0) bx = 2;
        g.setColor(new Color(0, 0, 0, 190)); g.fillRect(bx, by, boxW + pad * 2, boxH);
        g.setColor(Color.WHITE); g.drawRect(bx, by, boxW + pad * 2, boxH);
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], bx + pad, by + pad + 11 + i * lh);
        g.setColor(new Color(255, 255, 0, 180));
        g.drawLine(hoverPx - 5, hoverPy, hoverPx + 5, hoverPy);
        g.drawLine(hoverPx, hoverPy - 5, hoverPx, hoverPy + 5);
    }

    // ============================================================
    // 搜索
    // ============================================================

    private void activateSearch() {
        searchBox.setFocusable(true);
        searchBox.requestFocusInWindow();
        searchBox.selectAll();
    }

    private void deactivateSearch() {
        searchBox.setFocusable(false);
        canvas.requestFocusInWindow();
    }

    public void showWindow() { frame.setVisible(true); }

    // ============================================================
    // 工具
    // ============================================================

    private static String englishTerrainType(Cell c) {
        if (c.riverMask) return "River";
        if (c.lakeMask) return "Lake";
        int id = c.terrainType.ordinal();
        return switch (id) {
            case 0 -> "Ocean";
            case 1 -> "Deep Ocean";
            case 2 -> "Lake";
            case 3 -> "River";
            case 4 -> "Beach";
            case 5 -> "Plain";
            case 6 -> "Hills";
            case 7 -> "Plateau";
            case 8 -> "Mountains";
            case 9 -> "Peak";
            case 10 -> "Basin";
            case 11 -> "Snow";
            default -> "???";
        };
    }

    public static void main(String[] args) {
        long seed = (args != null && args.length > 0) ? Long.parseLong(args[0]) : 12345L;
        SwingUtilities.invokeLater(() -> {
            TerrainPreview p = new TerrainPreview(seed, GeoGenesisConfig.INSTANCE.defaultParams());
            p.showWindow();
        });
    }
}
