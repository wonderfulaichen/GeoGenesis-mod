package com.geogenesis.client.preview;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.Latitude;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.Size;
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
 * 独立地形预览窗口（Swing，零 MC 依赖）。复用零依赖 {@link GeoPalette} 输出 11 个地理标准图层。
 *
 * 视图模式：数字键 1..9/0 选图层 0..9；[ / ] 前后切换；R 水文叠加；X 分辨率；C 清空搜索。
 * 图例：离散图层列出条目（按搜索框过滤），连续图层画渐变条。
 * 运行：gradlew runPreview --args=98765
 */
public final class TerrainPreview {

    private static final int PANEL = 600;
    private static final int[] QUALITY = {1, 2, 4}; // 渲染降采样（分辨率切换）

    private final GeoGenesisTerrain terrain;
    private final int seaLevel, snowLine, maxY, minY, horizontalScale, mountainCap, trenchDepth;
    private final long seed;

    private double originX = 0.0, originZ = 0.0;
    private double scale = 2.0; // blocks per pixel
    private boolean hydrology = true;
    private int layerIndex = 0;
    private int qualityIdx = 0;
    private String search = "";

    private Cell[][] cachedCells;
    private int cachedOriginX = Integer.MAX_VALUE, cachedOriginZ = Integer.MAX_VALUE, cachedCellsX = -1, cachedCellsZ = -1;
    private volatile Thread computeThread = null;

    private void render(Graphics2D g) {
        int originBlockX = (int) Math.floor(originX);
        int originBlockZ = (int) Math.floor(originZ);
        int cellsX = Math.max(1, (int) Math.ceil((double) (PANEL * scale) / horizontalScale));
        int cellsZ = Math.max(1, (int) Math.ceil((double) (PANEL * scale) / horizontalScale));

        boolean needsCompute = cachedCells == null || cachedOriginX != originBlockX || cachedOriginZ != originBlockZ
                || cachedCellsX != cellsX || cachedCellsZ != cellsZ;

        if (needsCompute) {
            scheduleCompute(originBlockX, originBlockZ, cellsX, cellsZ);
            // 新数据未就绪：显示变换后的旧帧（AffineTransform 瞬间完成，不卡）
            if (lastFrame != null) {
                g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL);
                double sx = lastScale / scale, tx = (lastOriginX - originX) / scale, ty = (lastOriginZ - originZ) / scale;
                AffineTransform at = new AffineTransform(); at.translate(tx, ty); at.scale(sx, sx);
                g.drawImage(lastFrame, at, null);
            } else {
                g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL);
                g.setColor(Color.WHITE); g.drawString("计算中... (seed=" + seed + ")", 10, 30);
            }
            return;
        }

        // 拍快照：避免后台线程更新 cachedCells 时渲染读到的数组尺寸不匹配
        Cell[][] cells = cachedCells;
        if (cells == null) return;

        try {
            int res = PANEL / QUALITY[qualityIdx];
            BufferedImage img = new BufferedImage(res, res, BufferedImage.TYPE_INT_RGB);
            GeoPalette.PreviewLayer layer = GeoPalette.PreviewLayer.values()[layerIndex];
            for (int py = 0; py < res; py++) {
                double wz = originZ + (py * scale * QUALITY[qualityIdx]);
                for (int px = 0; px < res; px++) {
                    double wx = originX + (px * scale * QUALITY[qualityIdx]);
                    int cellX = Math.max(0, Math.min(cells.length - 1, (int) Math.floor((wx - originBlockX) / horizontalScale)));
                    int cellZ = Math.max(0, Math.min(cells[0].length - 1, (int) Math.floor((wz - originBlockZ) / horizontalScale)));
                    Cell cell = cells[cellX][cellZ];
                    int rgb = GeoPalette.color(layer, cell, (int) Math.round(wx), (int) Math.round(wz), minY, maxY, hydrology);
                    img.setRGB(px, py, rgb);
                }
            }
            g.drawImage(img, 0, 0, PANEL, PANEL, null);
            lastFrame = img; lastOriginX = originX; lastOriginZ = originZ; lastScale = scale;

            drawLegend(g, layer);
            drawTooltip(g, layer);
            info.setText(String.format("seed=%d  scale=%.2f  layer=%s hydro=%s  res=%dx%d  q=%d  [1-9/0]图层 [ ]切换 [R]河 [X]分辨率 [/]搜索 [Esc]退出搜索",
                    seed, scale, GeoPalette.englishLabel(layer.labelKey), hydrology ? "ON" : "OFF", res, res, QUALITY[qualityIdx]));
        } catch (Throwable t) {
            t.printStackTrace();
            if (lastFrame != null) g.drawImage(lastFrame, 0, 0, null);
            else { g.setColor(Color.BLACK); g.fillRect(0, 0, PANEL, PANEL); }
            g.setColor(Color.RED); g.drawString("渲染错误: " + t.getMessage(), 10, 30);
        }
    }

    private final JFrame frame;
    private final JPanel canvas;
    private final JLabel info;
    private final JTextField searchBox;
    private BufferedImage lastFrame;
    private double lastOriginX, lastOriginZ, lastScale;
    private int hoverPx = -1, hoverPy = -1;

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
        this.trenchDepth = params.trenchDepth();
        // 高程色带按世界全高度范围归一化（minY 到 mountainCap），
        // 使深海（Y=minY）到山脊（Y=mountainCap）的颜色层次可见
        GeoPalette.setElevationRange(minY, mountainCap);

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
                lastX = e.getX(); lastY = e.getY(); canvas.repaint();
            }
            @Override public void mouseMoved(MouseEvent e) { hoverPx = e.getX(); hoverPy = e.getY(); canvas.repaint(); }
        });
        canvas.addMouseWheelListener(new MouseAdapter() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                scale *= (e.getWheelRotation() < 0) ? 0.8 : 1.25;
                scale = Math.max(0.25, Math.min(64.0, scale));
                canvas.repaint();
            }
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                char c = e.getKeyChar();
                int n = GeoPalette.PreviewLayer.values().length;
                if (c >= '1' && c <= '9') { layerIndex = c - '1'; canvas.repaint(); }
                else if (c == '0') { layerIndex = 9; canvas.repaint(); }
                else if (c == '[') { layerIndex = (layerIndex - 1 + n) % n; canvas.repaint(); }
                else if (c == ']') { layerIndex = (layerIndex + 1) % n; canvas.repaint(); }
                else if (c == 'r' || c == 'R') { hydrology = !hydrology; canvas.repaint(); }
                else if (c == 'x' || c == 'X') { qualityIdx = (qualityIdx + 1) % QUALITY.length; canvas.repaint(); }
                else if (c == 'c' || c == 'C') { search = ""; searchBox.setText(""); canvas.repaint(); }
                else if (c == '/') { activateSearch(); }
            }
        });

        searchBox = new JTextField();
        searchBox.setToolTipText("图例搜索过滤（按 '/' 激活，Esc 退出）");
        searchBox.setFocusable(false); // 默认不可聚焦，防止抢走快捷键焦点
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
        // 确保canvas获得初始焦点，使快捷键能正常工作
        canvas.requestFocusInWindow();
    }

    private int lastX, lastY;

    private int cellIndex(double worldCoord, int originBlock, int cells) {
        int idx = (int) Math.floor((worldCoord - originBlock) / horizontalScale);
        return Math.max(0, Math.min(cells - 1, idx));
    }

    /**
     * 调度后台计算：最多 1 个计算线程运行。
     * 新请求 → 中断旧线程（getRegionCells 每格检查 Thread.interrupted() → 快速退出）
     * → 启动新线程。线程最低优先级，避免 EDT 饿死。
     */
    private void scheduleCompute(int obx, int obz, int cellsX, int cellsZ) {
        // 中断正在计算的旧线程（若存在）
        Thread old = computeThread;
        if (old != null) old.interrupt();

        int fx = obx, fz = obz, fcX = cellsX, fcZ = cellsZ;
        Thread t = new Thread(() -> {
            try {
                Cell[][] result = terrain.getRegionCells(fx, fz, fcX, fcZ);
                if (result == null) return; // 被中断（新请求已取代）
                if (Thread.currentThread().isInterrupted()) return; // 硬防竞态
                cachedCells = result;
                cachedOriginX = fx; cachedOriginZ = fz; cachedCellsX = fcX; cachedCellsZ = fcZ;
                SwingUtilities.invokeLater(() -> canvas.repaint());
            } catch (Throwable ex) { ex.printStackTrace(); }
        }, "TerrainPreview-Compute");
        computeThread = t;
        t.start();
    }

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
                g.setColor(new Color(GeoPalette.continuous(layer, p)));
                g.fillRect(bx, by + i, bw, 1);
            }
            g.setColor(Color.WHITE); g.drawRect(bx, by, bw, bh);
            // 高程图层显示真实 Y 范围（海床最深 → 山脊最高），其余连续图层显示 0/1
            String topLabel = (layer == GeoPalette.PreviewLayer.ELEVATION) ? ("Y=" + mountainCap) : "1.0";
            String botLabel = (layer == GeoPalette.PreviewLayer.ELEVATION) ? ("Y=" + (seaLevel - trenchDepth)) : "0.0";
            g.drawString(topLabel, bx - 40, by + 8);
            g.drawString(botLabel, bx - 40, by + bh);
        }
    }

    private void drawTooltip(Graphics2D g, GeoPalette.PreviewLayer layer) {
        Cell[][] cells = cachedCells; // 拍快照
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

    public static void main(String[] args) {
        long seed = (args != null && args.length > 0) ? Long.parseLong(args[0]) : 12345L;
        SwingUtilities.invokeLater(() -> {
            // 独立预览脱离 Forge 运行时，配置未 load()，必须用 defaultParams()（读 spec 默认值）
            // 而非 buildParams()（读实时 ConfigValue.get()，会抛 IllegalStateException）。
            TerrainPreview p = new TerrainPreview(seed, GeoGenesisConfig.INSTANCE.defaultParams());
            p.showWindow();
        });
    }
}
