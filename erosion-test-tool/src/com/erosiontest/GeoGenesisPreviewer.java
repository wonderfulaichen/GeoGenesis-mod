package com.erosiontest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 移植 TerraForged 的 Previewer (MIT License) + 多视图切换。
 * 键盘：b=群系图  h=高度图  c=大陆性  t=温度  m=湿度
 * 鼠标：拖拽平移、滚轮缩放
 */
public class GeoGenesisPreviewer extends JPanel {
    public interface Shader { int getRGB(float x, float y); }

    private final ExecutorService executor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors());
    private final Shader[] modes;
    private final String[] modeNames = {
        "原版群系", "地形类型", "地形高度", "温度带", "干湿分布"
    };
    private int currentMode = 0;
    private int lastX, lastY;
    private float posX, posY;
    private float zoom = 1f;

    private static final int MAP_W = 800, MAP_H = 600, LEGEND_W = 180;

    public GeoGenesisPreviewer(Shader[] shaders) {
        this.modes = shaders;
        setFocusable(true);
        setPreferredSize(new Dimension(MAP_W + LEGEND_W, MAP_H + 50));
        setBackground(new Color(30, 30, 30));

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.getX() < MAP_W) { lastX = e.getX(); lastY = e.getY(); }
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (e.getX() < MAP_W) {
                    posX += (lastX - e.getX()) * zoom;
                    posY += (lastY - e.getY()) * zoom;
                    lastX = e.getX(); lastY = e.getY();
                    repaint();
                }
            }
        });
        addMouseWheelListener(new MouseAdapter() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                zoom = Math.max(0.0001f, zoom + e.getWheelRotation() * 0.75f);
                repaint();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                char c = e.getKeyChar();
                if (c == 'z') { zoom = 1f; repaint(); return; }
                int[] keys = {'b', 'g', 'h', 't', 'w'};
                for (int i = 0; i < keys.length; i++) {
                    if (c == keys[i]) { currentMode = i; repaint(); return; }
                }
            }
        });
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        // 地图区
        float ox = posX - (MAP_W >> 1) * zoom;
        float oy = posY - (MAP_H >> 1) * zoom;
        var image = new BufferedImage(MAP_W, MAP_H, BufferedImage.TYPE_INT_RGB);
        renderTiled(ox, oy, zoom, 0, 0, MAP_W, MAP_H, modes[currentMode], image).join();
        g.drawImage(image, 0, 25, MAP_W, MAP_H + 25, null);

        // 标题+操作提示
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospace", Font.PLAIN, 12));
        int mw = (int)(MAP_W * zoom), mh = (int)(MAP_H * zoom);
        g.drawString(String.format("[%s] %dx%d  b=群系  g=地形  h=高度  t=温度  w=湿度  z=重置", modeNames[currentMode], mw, mh), 2, 14);

        // 图例区
        drawLegend(g, MAP_W, 25);
    }

    private void drawLegend(Graphics g, int lx, int ly) {
        int barW = 24, barH = MAP_H - 60, barX = lx + 20, barY = ly + 40;
        Font font = new Font("SansSerif", Font.PLAIN, 11);
        g.setFont(font);

        switch (currentMode) {
            case 0: // 群系 - 离散色块
                drawDiscreteLegend(g, barX, ly + 10, new String[]{
                    "深海", "浅海", "沙滩", "雪原", "Taiga", "平原",
                    "森林", "暗林", "沼泽", "Savanna", "丛林", "沙漠", "恶地", "高山"
                }, new int[]{
                    0x143C78, 0x2864A0, 0xBEB43C, 0xC8C8D2, 0x78968C, 0x96AB64,
                    0x509646, 0x3C8C3C, 0x649678, 0xBEAA50, 0x3C964E, 0xDCBE50, 0xC8AA64, 0x828C82
                });
                break;
            case 1: // 地形类型
                drawDiscreteLegend(g, barX, ly + 10, new String[]{"深海沟", "深海", "海滩", "平原", "丘陵", "山脉"},
                    new int[]{0x08193C, 0x143C78, 0xBEB446, 0x8CB95A, 0x7DA555, 0x648C46});
                break;
            case 2: // 高度 - 离散色块
                drawDiscreteLegend(g, barX, ly + 10, new String[]{"极深海", "深海", "中深海", "浅海(≤32)", "海滩",
                    "平原", "丘陵", "山麓", "山地", "高山", "雪峰"},
                    new int[]{0x051437, 0x0A2350, 0x0F376E, 0x1E5591, 0xBEB446,
                        0x8CB95A, 0x7DA555, 0x9B874B, 0x877355, 0x9B9187, 0xDCDCE6});
                break;
            case 3: // 温度 - 离散色块
                drawDiscreteLegend(g, barX, ly + 10, new String[]{"寒冷", "冷凉", "温凉", "温和", "暖热", "炎热"},
                    new int[]{0x648CB4, 0x78AF8C, 0x82BE6E, 0xA5C35E, 0xC8AA50, 0xD79150});
                break;
            case 4: // 干湿 - 离散色块
                drawDiscreteLegend(g, barX, ly + 10, new String[]{"干燥", "偏干", "适中", "偏湿", "湿润", "潮湿"},
                    new int[]{0xD2BE5A, 0xB9C873, 0x8CC882, 0x64BE8C, 0x41AAA5, 0x328CC3});
                break;
        }
    }

    private void drawDiscreteLegend(Graphics g, int x, int y, String[] labels, int[] colors) {
        for (int i = 0; i < labels.length; i++) {
            int cy = y + i * 20;
            g.setColor(new Color(colors[i]));
            g.fillRect(x, cy, 14, 14);
            g.setColor(Color.LIGHT_GRAY);
            g.drawRect(x, cy, 14, 14);
            g.drawString(labels[i], x + 20, cy + 12);
        }
    }

    private void drawGradientLegend(Graphics g, int x, int y, int w, int h, int[] stops, String[] labels) {
        // 绘制渐变色条
        for (int py = 0; py < h; py++) {
            float t = (float)py / h;
            int idx = (int)(t * (stops.length - 1));
            float frac = t * (stops.length - 1) - idx;
            if (idx >= stops.length - 1) { idx = stops.length - 2; frac = 1; }
            int c0 = stops[idx], c1 = stops[idx + 1];
            int r = (int)(((c0>>16)&0xFF) * (1-frac) + ((c1>>16)&0xFF) * frac);
            int gr = (int)(((c0>>8)&0xFF) * (1-frac) + ((c1>>8)&0xFF) * frac);
            int b = (int)((c0&0xFF) * (1-frac) + (c1&0xFF) * frac);
            g.setColor(new Color(r, gr, b));
            g.fillRect(x, y + py, w, 1);
        }
        g.setColor(Color.GRAY);
        g.drawRect(x, y, w, h);
        // 标注
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        for (int i = 0; i < labels.length; i++) {
            int ly2 = y + h * i / (labels.length - 1) - 4;
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(labels[i], x + w + 6, ly2 + 10);
        }
    }

    private CompletableFuture<Void> renderTiled(float ox, float oy, float zoom,
            int x0, int y0, int x1, int y1, Shader shader, BufferedImage image) {
        int divs = (int)Math.floor(Math.sqrt(Runtime.getRuntime().availableProcessors()));
        int tszX = (x1 - x0 + divs - 1) / divs;
        int tszY = (y1 - y0 + divs - 1) / divs;
        var tasks = new CompletableFuture[divs * divs];
        for (int ty = 0; ty < divs; ty++) {
            int minY = y0 + ty * tszY;
            int maxY = Math.min(minY + tszY, y1);
            for (int tx = 0; tx < divs; tx++) {
                int minX = x0 + tx * tszX;
                int maxX = Math.min(minX + tszX, x1);
                int fx0 = minX, fx1 = maxX, fy0 = minY, fy1 = maxY;
                tasks[ty * divs + tx] = CompletableFuture.runAsync(() -> {
                    for (int y = fy0; y < fy1; y++) {
                        float py = oy + y * zoom;
                        for (int x = fx0; x < fx1; x++) {
                            float px = ox + x * zoom;
                            image.setRGB(x, y, shader.getRGB(px, py));
                        }
                    }
                }, executor);
            }
        }
        return CompletableFuture.allOf(tasks);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    // 高度图：11个等分高度区 + 温度相关雪线
    // h归一化[0,1]（0→-64, 0.397≈海平面Y=63, 1.0→Y=256）
    private static int topoColor(float h, float temp) {
        if (h < 0.31f) return rgb(5, 20, 55);         // 极深海 Y<35
        if (h < 0.34f) return rgb(10, 35, 80);        // 深海   Y<44
        if (h < 0.37f) return rgb(15, 55, 110);       // 中深海 Y<54
        if (h < 0.397f) return rgb(30, 85, 145);      // 浅海   Y<63
        if (h < 0.45f) return rgb(190, 180, 70);      // 海滩   Y<85
        if (h < 0.55f) return rgb(140, 185, 90);      // 平原   Y<117
        if (h < 0.65f) return rgb(125, 165, 85);      // 丘陵   Y<150
        if (h < 0.75f) return rgb(155, 135, 75);      // 山麓   Y<182
        if (h < 0.80f) return rgb(135, 115, 85);      // 山地   Y<192
        if (h < 0.90f) return rgb(155, 145, 135);     // 高山   Y<224
        // 雪线：冷区低(Y=96)，热区高(Y=192)
        float snowLine = 0.40f + temp * 0.40f;
        if (h < snowLine) return rgb(155, 145, 135);   // 无雪高山
        return rgb(220, 220, 235);                      // 雪峰
    }

    // 温度带色：钟形分布(偏0.5)，按17%/17%/17%/17%/17%/17%百分比分段
    private static int tempColor(float t) {
        if (t < 0.35f) return rgb(95, 135, 175);      // 寒冷  ~17%
        if (t < 0.43f) return rgb(115, 170, 145);     // 冷凉  ~17%
        if (t < 0.50f) return rgb(135, 190, 115);     // 温凉  ~17%
        if (t < 0.57f) return rgb(160, 195, 100);     // 温和  ~17%
        if (t < 0.65f) return rgb(195, 170, 90);      // 暖热  ~17%
        return rgb(210, 145, 85);                       // 炎热  ~17%
    }

    // 干湿色：同样钟形百分比分段
    private static int moistColor(float m) {
        if (m < 0.35f) return rgb(205, 185, 95);      // 干燥  ~17%
        if (m < 0.43f) return rgb(180, 195, 120);     // 偏干  ~17%
        if (m < 0.50f) return rgb(140, 200, 135);     // 适中  ~17%
        if (m < 0.57f) return rgb(100, 190, 155);     // 偏湿  ~17%
        if (m < 0.65f) return rgb(70, 170, 175);      // 湿润  ~17%
        return rgb(55, 140, 195);                       // 潮湿  ~17%
    }

    public static void main(String[] args) {
        int seed = args.length > 0 ? Integer.parseInt(args[0]) : 273651;
        int scale = 4;
        StandalonePreview e = new StandalonePreview(seed);

        Shader biome = (x, y) -> {
            float wx = x * scale, wz = y * scale;
            float c = e.sampleContinentRaw(wx, wz);
            float h = e.computeHeight(wx, wz);
            float t = e.sampleTemperature(wx, wz);
            float m = e.sampleMoisture(wx, wz, (c+1f)*0.5f, t);
            float r = e.sampleElevation(wx, wz);
            return e.minecraftBiomeColor(c, t, m, h, r);
        };
        Shader terrainType = (x, y) -> {
            float wx = x * scale, wz = y * scale;
            float c = e.sampleContinentRaw(wx, wz);
            float r = e.sampleElevation(wx, wz);
            if (c < -0.20f) return rgb(8, 25, 60);       // 深海沟
            if (c < 0.0f)   return rgb(20, 60, 120);     // 深海
            if (c < 0.05f)  return rgb(190, 180, 70);    // 海滩
            if (r < 0.35f)  return rgb(140, 185, 90);    // 平原
            if (r < 0.65f)  return rgb(125, 165, 85);    // 丘陵
            return rgb(100, 140, 70);                      // 山脉
        };
        Shader height = (x, y) -> {
            float wx = x * scale, wz = y * scale;
            return topoColor(e.computeHeight(wx, wz), e.sampleTemperature(wx, wz));
        };
        Shader temp = (x, y) -> tempColor(e.sampleTemperature(x * scale, y * scale));
        Shader moisture = (x, y) -> {
            float wx = x * scale, wz = y * scale;
            float c01 = (e.sampleContinentRaw(wx, wz) + 1f) * 0.5f;
            return moistColor(e.sampleMoisture(wx, wz, c01, e.sampleTemperature(wx, wz)));
        };

        JFrame frame = new JFrame("GeoGenesis seed=" + seed);
        frame.add(new GeoGenesisPreviewer(new Shader[]{biome, terrainType, height, temp, moisture}));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }
}
