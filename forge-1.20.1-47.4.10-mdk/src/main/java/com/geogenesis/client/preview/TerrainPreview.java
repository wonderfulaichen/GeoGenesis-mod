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
 * 采样引擎为 {@link LargeAreaSampler}：<b>固定采样数 + 步长缩放</b>（参考 FreeTerraForged
 * {@code TileGenerator.generateZoomed}），<b>开销与视野无关</b> → 可一眼看数万格的气候格局与纬度分带。
 * 拖拽/缩放时按视口标识增量重采（默认 64×64 ≈ 40 ms），图层切换无需重采。
 * <p>
 * 视图模式：数字键 1..9/0 选图层 0..9；[ / ] 前后切换；R 水文叠加；X 分辨率；C 清空搜索。
 * 图例：离散图层列出条目（按搜索框过滤），连续图层画渐变条。
 * 运行：gradlew runPreview --args=98765
 */
public final class TerrainPreview {

    private static final int PANEL = 600;
    private static final int[] QUALITY = {1, 2, 4}; // 渲染降采样（分辨率切换）

    private final GeoGenesisTerrain terrain;
    private final int seaLevel, snowLine, maxY, minY, mountainCap;
    private final double peakFraction, verticalScale, horizontalScale;
    /** 高程色阶映射的 e 区间（地形实际可达范围），供图例 Y 标签换算。 */
    private double elevEMin = -1.0, elevEMax = 1.0;
    private final long seed;

    private double originX = 0.0, originZ = 0.0;
    private double scale = 2.0; // blocks per pixel
    private boolean hydrology = true;
    /** ★ 大范围模式（L 切换）：走廉价管线，可查看数万格的气候格局 / 纬度分带。 */
    private boolean largeArea = false;
    /** ★ 坡度阴影（H 切换）：与游戏内 SHADE 一致的地图学光源（左上）→ 大范围下看山脉骨架。 */
    private boolean slopeShading = true;
    /** 图例滚动态（条目超出面板高度时启用；由 drawLegend 每帧回填）。 */
    private int legendScroll = 0, legendMaxRows = 0, legendRowCount = 0;
    private int layerIndex = 0;
    private int qualityIdx = 0;
    private String search = "";

    // === 大范围采样（★ 2026-09-11：取代已 @Deprecated 的 PreviewWorker + PreviewCache）===
    //   固定采样数 + 步长缩放 → 开销与视野无关，可看数万格（参考 FTF generateZoomed）。
    private LargeAreaSampler.Grid grid;

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
        this.peakFraction = params.peakHeightFraction();
        this.verticalScale = params.verticalScale();
        this.horizontalScale = params.horizontalScale(); // 2026-08-10 修复：去掉 (int) 强转（HS 非整数被截断）
        this.mountainCap = params.mountainCap();
        double[] er = params.elevationERange();
        this.elevEMin = er[0]; this.elevEMax = er[1];
        GeoPalette.setElevationERange(er[0], er[1]);
        GeoPalette.setSeaLevel(seaLevel);

        // 大范围采样无需初始化：LargeAreaSampler 在 render 内按视口标识增量采样

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
                // ★ 光标在图例内且图例可滚动 → 滚图例，不改缩放（对齐游戏内 isOverLegend 做法）
                if (e.getX() >= PANEL - 156 && legendRowCount > legendMaxRows && legendMaxRows > 0) {
                    int maxScroll = Math.max(0, legendRowCount - legendMaxRows);
                    legendScroll = Math.max(0, Math.min(maxScroll,
                            legendScroll + e.getWheelRotation()));
                    canvas.repaint();
                    return;
                }
                scale *= (e.getWheelRotation() < 0) ? 0.8 : 1.25;
                scale = Math.max(0.25, Math.min(largeArea ? 512.0 : 64.0, scale));
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
                    canvas.repaint();
                } else if (c == 'l' || c == 'L') {
                    // ★ 大范围模式：放宽滚轮缩放上限 → 可看数万格的气候格局/纬度分带
                    largeArea = !largeArea;
                    canvas.repaint();
                } else if (c == 'h' || c == 'H') {
                    // ★ 坡度阴影开关（对齐游戏内 SHADE；大范围下用来看山脉骨架）
                    slopeShading = !slopeShading;
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

        // 无需 Worker 回调：采样在 render 内同步完成后直接重绘

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

    /** 图层切换：采样与图层无关（Cell 已含全部图层所需数据），直接重绘。 */
    private void switchLayer() {
        canvas.repaint();
    }

    /** 视口变化：直接重绘（采样在 render 内按视口标识增量执行，固定 64×64 ≈ 40ms）。 */
    private void requestResample() {
        canvas.repaint();
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

        // ★ 视口变化才重采：固定 64×64 采样数，视野再大成本不变（FTF 同款做法）
        long vid = viewportId();
        if (grid == null || grid.viewportId() != vid) {
            int blocksWide = (int) Math.round(PANEL * scale);
            grid = LargeAreaSampler.sample(terrain,
                    (int) Math.floor(originX), (int) Math.floor(originZ),
                    blocksWide, blocksWide,
                    LargeAreaSampler.DEFAULT_GRID, LargeAreaSampler.DEFAULT_GRID, vid);
        }

        try {
            int res = PANEL / QUALITY[qualityIdx];
            int quality = QUALITY[qualityIdx];
            int gw = grid.gridW(), gh = grid.gridH();
            double stepX = grid.blocksWide() / (double) gw;
            double stepZ = grid.blocksHigh() / (double) gh;

            BufferedImage img = new BufferedImage(res, res, BufferedImage.TYPE_INT_RGB);
            // ★ 坡度阴影（对齐游戏内 SHADE）：先在采样网格上算明暗因子，再逐像素映射
            double[] shade = slopeShading ? shadeFactors(gw, gh, stepX, stepZ) : null;
            for (int py = 0; py < res; py++) {
                for (int px = 0; px < res; px++) {
                    int gx = Math.min(gw - 1, px * gw / res);
                    int gz = Math.min(gh - 1, py * gh / res);
                    Cell c = grid.at(gx, gz);
                    int rgb = 0;
                    if (c != null) {
                        int wx = grid.originX() + (int) Math.round(gx * stepX);
                        int wz = grid.originZ() + (int) Math.round(gz * stepZ);
                        rgb = GeoPalette.color(layer, c, wx, wz, minY, maxY, hydrology);
                        if (shade != null) rgb = applyShade(rgb, shade[gx * gh + gz]);
                    }
                    img.setRGB(px, py, rgb);
                }
            }
            g.drawImage(img, 0, 0, PANEL, PANEL, null);
            lastFrame = img; lastOriginX = originX; lastOriginZ = originZ; lastScale = scale;

            drawLegend(g, layer);
            drawTooltip(g, layer);
            // ★ 诊断要点：视野(格)随 scale 增长，但【采样数与耗时保持不变】—— FTF 同款"开销与缩放无关"
            info.setText(String.format("seed=%d scale=%.2f 视野=%d格  layer=%s hydro=%s large=%s shade=%s  采样=%dx%d/%dms  res=%dx%d q=%d  [1-9/0]图层 [ ]切换 [R]河 [L]大范围 [H]阴影 [X]分辨率 [/]搜索",
                    seed, scale, (int) Math.round(PANEL * scale),
                    GeoPalette.englishLabel(layer.labelKey), hydrology ? "ON" : "OFF",
                    largeArea ? "ON" : "OFF", slopeShading ? "ON" : "OFF",
                    grid.gridW(), grid.gridH(), grid.costMs(), res, res, quality));
        } catch (Throwable t) {
            t.printStackTrace();
            if (lastFrame != null) g.drawImage(lastFrame, 0, 0, null);
            else { g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL); }
            g.setColor(Color.RED); g.drawString("渲染错误: " + t.getMessage(), 10, 30);
        }
    }

    // ============================================================
    // 坡度阴影（对齐游戏内 TerrainUnderlay.SHADE）
    // ============================================================

    /**
     * 采样网格上的明暗因子（地图学光源：左上方，符合"光从左上来"的直觉）。
     *
     * <p>水域与湖泊返回 1.0（水面是平的，做明暗会雕出虚假浮雕 —— 与游戏内一致）；
     * 其余按法线 · 光源点乘，映射到 [0.65, 1.0] 以避免过暗。</p>
     */
    private double[] shadeFactors(int gw, int gh, double stepX, double stepZ) {
        double[] f = new double[gw * gh];
        double sx = Math.max(1.0, stepX), sz = Math.max(1.0, stepZ);
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                Cell c = grid.at(gx, gz);
                if (c == null || c.e < 0.0 || c.lakeMask) { f[gx * gh + gz] = 1.0; continue; }
                double dhx = (hAt(gx + 1, gz) - hAt(gx - 1, gz)) / (2.0 * sx);
                double dhz = (hAt(gx, gz + 1) - hAt(gx, gz - 1)) / (2.0 * sz);
                // 法线 ∝ (−dh/dx, −dh/dz, 1)；光源指向屏幕左上（−x、−z）且向上
                double nx = -dhx, ny = -dhz, nz = 1.0;
                double nlen = Math.sqrt(nx * nx + ny * ny + 1.0);
                double lx = -0.5, lz = -0.3, ly = 1.0;
                double llen = Math.sqrt(lx * lx + lz * lz + ly * ly);
                double dot = (nx * lx + ny * lz + nz * ly) / (nlen * llen);
                f[gx * gh + gz] = 0.65 + 0.35 * Math.max(0.0, dot);
            }
        }
        return f;
    }

    /** 采样网格点高度（越界返回 0）。 */
    private double hAt(int gx, int gz) {
        Cell c = grid.at(gx, gz);
        return c == null ? 0.0 : c.height;
    }

    /** 按因子压暗/提亮一个 RGB。 */
    private static int applyShade(int rgb, double f) {
        if (f >= 0.999) return rgb;
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
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
            // ★ 修复（2026-09-11）：面板高度上限 = 屏幕内可用高度，否则条目多时溢出屏幕
            //   （群系图层 42 条 × 16 = 694 px > PANEL 600）。超高时改为滚动。
            int maxRows = Math.max(1, (PANEL - ly - titleH - 20) / rowH);
            legendMaxRows = maxRows;
            legendRowCount = vis.size();
            legendScroll = Math.max(0, Math.min(Math.max(0, vis.size() - maxRows), legendScroll));
            int shown = Math.min(maxRows, Math.max(0, vis.size() - legendScroll));
            int panelH = titleH + shown * rowH + 6 + (vis.size() > maxRows ? 10 : 0);
            g.setColor(new Color(0, 0, 0, 180)); g.fillRect(lx - 6, ly - 4, panelW, panelH);
            g.setColor(Color.CYAN);
            String title = GeoPalette.englishLabel(layer.labelKey);
            if (vis.size() > maxRows) title += "  (" + (legendScroll + 1) + "-"
                    + (legendScroll + shown) + "/" + vis.size() + " 滚轮)";
            g.drawString(title, lx, ly + 6);
            int cy = ly + titleH;
            for (int i = legendScroll; i < legendScroll + shown && i < vis.size(); i++) {
                GeoPalette.LegendEntry e = vis.get(i);
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
        double t = Math.max(0.0, Math.min(1.0, e * verticalScale));
        return seaLevel + t * (maxY - seaLevel) * peakFraction;
    }

    // ============================================================
    // 悬停提示
    // ============================================================

    private void drawTooltip(Graphics2D g, GeoPalette.PreviewLayer layer) {
        if (grid == null || hoverPx < 0 || hoverPy < 0) return;
        if (hoverPx >= PANEL || hoverPy >= PANEL) return;
        double wx = originX + hoverPx * scale, wz = originZ + hoverPy * scale;
        // 从大范围采样网格取最近点：网格步长 = 世界尺寸 / 网格数（与 scale 无关）
        int gw = grid.gridW(), gh = grid.gridH();
        double stepX = grid.blocksWide() / (double) gw;
        double stepZ = grid.blocksHigh() / (double) gh;
        int gx = Math.max(0, Math.min(gw - 1, (int) Math.floor((wx - grid.originX()) / stepX)));
        int gz = Math.max(0, Math.min(gh - 1, (int) Math.floor((wz - grid.originZ()) / stepZ)));
        Cell cell = grid.at(gx, gz);
        if (cell == null) return;
        String water = cell.lakeMask ? "湖泊" : "无";   // 河流系统已清除，仅湖泊
        String[] lines = {
                String.format("x=%d  z=%d", (int) Math.round(wx), (int) Math.round(wz)),
                "图层: " + layer.labelKey,
                "高度: Y=" + (int) Math.round(cell.height),
                String.format("地形: %s  e=%.3f", englishTerrainType(cell), cell.e),
                String.format("温度=%.2f 湿度=%.2f 大陆=%.2f", cell.temperature, cell.humidity, cell.continentNoise),
                String.format("纬度=%.2f 起伏=%.2f", Latitude.latitude01((int) Math.round(wz)), (cell.shape + 1) * 0.5),
                "水: " + water,
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
