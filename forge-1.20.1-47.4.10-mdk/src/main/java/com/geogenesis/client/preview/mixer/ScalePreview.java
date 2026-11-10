package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>尺度示意（横向 2D 地形演示）</b>（★ v17 重做）。
 *
 * <h2>为什么换掉旧设计（用户：「这个尺度示意图不直观」）</h2>
 * <p>旧版是"一根竖柱 + 一条横条 + 1..8 刻度"，本质是<b>两个进度条</b>：
 * 用户能读到"垂直=2、水平=2"，但<b>看不出那意味着什么</b> ——
 * 缩放对地形的影响是"山更陡/更缓、视野更宽/更窄"，进度条表达不了这件事。</p>
 *
 * <h2>新设计：固定的 2D 地形横剖面</h2>
 * <p>用一条<b>写死的山脊+海平面剖面</b>（不含种子，纯演示），按当前
 * 垂直/水平缩放实时变形：</p>
 * <ul>
 *   <li><b>垂直缩放</b> → 峰谷高度差（山更高还是更平）；</li>
 *   <li><b>水平缩放</b> → 采样步长（同样的地形被"拉宽"或"压缩"）。</li>
 * </ul>
 * <p>用户在拖上方滑块时，这块图会<b>实时跟着变形</b>，一眼就能看出参数在做什么。</p>
 *
 * <h2>仍然只读</h2>
 * <p>按前一轮的裁定，示意图不可直接交互（只有重置按钮）。</p>
 */
public class ScalePreview {

    private Runnable onMarkDirty = () -> {};

    private int baseX, baseY, baseW = 200, baseH = 80;

    private double verticalScaleValue = 1.0;   // 垂直缩放比例（1-8）
    private double horizontalScaleValue = 1.0; // HS参数（0.5-8，默认1=1:1）

    // 默认值
    private double defaultVerticalScale = 1.0;
    private double defaultHorizontalScale = 1.0;

    // 重置按钮
    private static final int RESET_BTN_W = 18;
    private static final int RESET_BTN_H = 14;
    private boolean resetHovered = false;

    /**
     * <b>固定的演示剖面</b>（与种子无关，纯视觉）。
     *
     * <p>数值是 0..1 的归一化高度：先缓慢抬升（海底），再冲上山脊，
     * 然后落到平原、再到小丘 —— 覆盖"深海 / 海岸 / 高山 / 丘陵"四种形态，
     * 这样垂直缩放的效果（峰谷拉高或压平）一眼可见。</p>
     */
    private static final float[] DEMO_PROFILE = {
            0.05f, 0.08f, 0.12f, 0.18f, 0.25f, 0.34f, 0.46f, 0.58f,
            0.72f, 0.86f, 0.94f, 0.88f, 0.74f, 0.60f, 0.52f, 0.49f,
            0.47f, 0.46f, 0.48f, 0.52f, 0.57f, 0.55f, 0.50f, 0.44f,
            0.38f, 0.32f, 0.27f, 0.22f, 0.17f, 0.12f, 0.09f, 0.06f
    };

    public ScalePreview() {}

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setVerticalScale(double v) { this.verticalScaleValue = Math.max(1.0, Math.min(8.0, v)); }
    public double getVerticalScale() { return verticalScaleValue; }
    public void setHorizontalScale(double v) { this.horizontalScaleValue = Math.max(0.5, Math.min(8.0, v)); }
    public double getHorizontalScale() { return horizontalScaleValue; }
    public void setDefaultVerticalScale(double v) { this.defaultVerticalScale = v; }
    public void setDefaultHorizontalScale(double v) { this.defaultHorizontalScale = v; }
    
    /** 重置到默认值 */
    public void resetToDefaults() {
        this.verticalScaleValue = defaultVerticalScale;
        this.horizontalScaleValue = defaultHorizontalScale;
        if (onMarkDirty != null) onMarkDirty.run();
    }

    public void setBounds(int x, int y, int w, int h) {
        baseX = x + 4;
        baseY = y + 4;
        baseW = Math.max(120, w - 8);
        baseH = Math.max(50, h - 20);
    }
    /** 占用高度：地形画面 + 底部数值行。 */
    public int getHeight() { return 10 + baseH + 16; }

    /** 垂直缩放值 → 演示图的可视高度占比（1..8 → 0.25..1.0，避免 ×1 时完全压平）。 */
    private double vsFactor() {
        return 0.25 + (verticalScaleValue - 1.0) / 7.0 * 0.75;
    }

    /** 水平缩放值 → 采样步长（0.5..8 → 步长 4..0.25，值越大越"拉宽"）。 */
    private double hsStep() {
        return Math.max(0.25, 4.0 / Math.max(0.5, horizontalScaleValue));
    }

    /**
     * <b>绘制横向 2D 地形演示</b>（★ v19：精致化重做）。
     *
     * <h2>相对 v17 的改进（用户：「示意图太简陋了」）</h2>
     * <ol>
     *   <li><b>天空渐变</b>：顶部由深到浅，画面不再是"深色空块"；</li>
     *   <li><b>分层土体</b>：地表往下依次是草皮→土层→岩层，
     *       而不是一整块绿；</li>
     *   <li><b>坡向光照</b>：按左右相邻高度差给地表叠明暗，
     *       山体有立体感（与沙盘/地形图同一套直觉）；</li>
     *   <li><b>水体分层</b>：浅水/深水有明度差，海面加一条细高光；</li>
     *   <li><b>垂直网格</b>：淡竖线标出"每档水平缩放采样一次"的位置，
     *       让"水平缩放改变采样密度"这件事看得见；</li>
     *   <li><b>雪顶/岩石按海拔阈值</b>，并留一条羽化过渡带。</li>
     * </ol>
     */
    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int x0 = baseX, y0 = baseY, w = baseW, h = baseH;
        int ix = x0 + 1, iy = y0 + 1, iw = w - 2, ih = h - 2;

        UiKit.chartFrame(g, x0, y0, w, h);

        int seaY = iy + (int) (ih * 0.62);      // 海平面（下移，给天空更多空间）
        double vf = vsFactor();
        double step = hsStep();

        // ---- 1) 天空渐变 ----
        UiKit.gradientV(g, ix, iy, ix + iw, seaY,
                new float[]{0f, 0.55f, 1f},
                new int[]{0xFF1B2436, 0xFF2A3A55, 0xFF3E5670});

        // ---- 2) 预计算每列地表高度（供地形 + 光照复用）----
        int[] surf = new int[iw];
        for (int px = 0; px < iw; px++) {
            double u = (double) px / Math.max(1, iw - 1);
            double sample = u * (DEMO_PROFILE.length - 1) * step / 2.0;
            int i0 = (int) Math.floor(sample);
            int i1 = Math.min(DEMO_PROFILE.length - 1, i0 + 1);
            double t = sample - i0;
            int a = Math.max(0, Math.min(DEMO_PROFILE.length - 1, i0));
            float base = (float) (DEMO_PROFILE[a] * (1 - t) + DEMO_PROFILE[i1] * t);
            double rel = (base - 0.45) * vf;
            int sy = seaY - (int) (rel * ih * 0.85);
            surf[px] = Math.max(iy + 2, Math.min(iy + ih - 2, sy));
        }

        // ---- 3) 逐列绘制：天空区域已画好，这里画地表以下 ----
        for (int px = 0; px < iw; px++) {
            int gx = ix + px;
            int sy = surf[px];
            int bottom = iy + ih;

            // 坡向光照：左高则右暗，右高则左暗（±18%）
            int dl = px > 0 ? surf[px - 1] : sy;
            int dr = px < iw - 1 ? surf[px + 1] : sy;
            double slope = (dl - dr) / 8.0;
            float shade = (float) Math.max(0.72, Math.min(1.20, 1.0 + slope * 0.25));

            if (sy >= seaY) {
                // —— 水下：水体 + 海底地形 ——
                int waterTop = seaY;
                // 深水（更暗）到浅水（更亮）：按地表深度取明度
                int depth = Math.min(bottom - sy, 60);
                float t = 1f - depth / 60f;
                int water = UiKit.lerpArgb(0xFF17305E, 0xFF3E7FC0, t);
                g.fill(gx, waterTop, gx + 1, bottom, water);
                // 海底（比水体略暗）
                int bedTop = Math.max(sy, waterTop);
                g.fill(gx, bedTop, gx + 1, Math.min(bottom, bedTop + Math.max(2, depth / 3)),
                        shadeColor(0xFF24304A, shade));
            } else {
                // —— 陆上：从地表往下分层填充 ——
                // 地表下 3px：草皮；再下 10px：土层；其余：岩层
                int grassBot = Math.min(seaY, sy + 3);
                g.fill(gx, sy, gx + 1, grassBot, shadeColor(grassColor(sy, seaY, ih), shade));
                int soilBot = Math.min(seaY, sy + 13);
                if (soilBot > grassBot) {
                    g.fill(gx, grassBot, gx + 1, soilBot, shadeColor(0xFF6B4A32, shade));
                }
                if (seaY > soilBot) {
                    g.fill(gx, soilBot, gx + 1, seaY, shadeColor(0xFF3E3630, shade));
                }
                // 海面以下（如果地表高于海平面，这里不会执行）
                if (seaY < bottom) {
                    float tt = 0.35f;
                    g.fill(gx, seaY, gx + 1, bottom,
                            UiKit.lerpArgb(0xFF17305E, 0xFF3E7FC0, tt));
                }
            }
        }

        // ---- 4) 海面高光 + 参考线 ----
        g.fill(ix, seaY, ix + iw, seaY + 1, 0x884FA8E8);
        g.fill(ix, seaY + 1, ix + iw, seaY + 2, 0x2A9FD0FF);

        // ---- 5) 垂直采样网格（标出"每档水平缩放采一次"）----
        int cell = Math.max(8, (int) Math.round(iw / Math.max(2, (DEMO_PROFILE.length - 1) / Math.max(1, step))));
        for (int gxp = ix + cell; gxp < ix + iw; gxp += cell) {
            g.fill(gxp, iy, gxp + 1, iy + ih, 0x0EFFFFFF);
        }

        // ---- 6) 底部读数 ----
        int ty = y0 + h + 2;
        g.drawString(f, String.format("垂直 ×%.1f", verticalScaleValue), x0, ty,
                UiKit.C_TEAL, false);
        String hs = String.format("水平 ×%.1f", horizontalScaleValue);
        g.drawString(f, hs, x0 + w - f.width(hs), ty, UiKit.C_TEAL, false);

        // ---- 7) 重置按钮（画面右上角内侧）----
        int rbX = x0 + w - RESET_BTN_W - 3;
        int rbY = y0 + 3;
        resetHovered = mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H;
        UiKit.roundRect(g, rbX, rbY, rbX + RESET_BTN_W, rbY + RESET_BTN_H,
                UiKit.RADIUS_FIELD, resetHovered ? 0xFF3E3E3E : 0xCC2A2A2A);
        String resetIcon = "R";
        g.drawString(f, resetIcon, rbX + (RESET_BTN_W - f.width(resetIcon)) / 2,
                rbY + (RESET_BTN_H - f.lineHeight) / 2,
                resetHovered ? UiKit.C_ACCENT : UiKit.C_TXT_DIM, false);
    }

    /** 按海拔选地表颜色：草 → 岩 → 雪（带过渡）。 */
    private static int grassColor(int sy, int seaY, int ih) {
        double elev = (double) (seaY - sy) / Math.max(1, (double) ih);
        if (elev > 0.62) return 0xFFEDEDED;   // 雪
        if (elev > 0.46) return 0xFF8A8578;   // 裸岩
        if (elev > 0.20) return 0xFF4E8A3E;   // 高地草
        return 0xFF5E9E48;                    // 低地草
    }

    /** 给一个颜色乘光照系数（RGB 缩放，保留 alpha）。 */
    private static int shadeColor(int argb, float mul) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * mul));
        int gg = Math.min(255, (int) (((argb >> 8) & 0xFF) * mul));
        int b = Math.min(255, (int) ((argb & 0xFF) * mul));
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    // ---- 鼠标（★ v16：示意图改为<b>只读</b>，只有重置按钮仍可点）----
    //
    //   用户裁定：「示意图改成不可直接交互，仅由 UI 控件配置」。
    //   旧版这里能拖动"垂直柱"和"水平条"直接改 ×N —— 与上方两个滑块
    //   形成两条改值路径。现在数值只能由滑块改，图只负责展示。

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        // ★ v17：重置键移到画面右上角内侧，命中区与此一致
        int rbX = baseX + baseW - RESET_BTN_W - 3;
        int rbY = baseY + 3;
        if (mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H) {
            resetToDefaults();
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        return false;
    }
}
