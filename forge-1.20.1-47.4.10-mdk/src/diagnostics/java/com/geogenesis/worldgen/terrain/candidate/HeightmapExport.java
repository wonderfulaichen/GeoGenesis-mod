package com.geogenesis.worldgen.terrain.candidate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 高度图导出：把 WU 空间的地形烘焙成<b>地形软件标准产物</b>。
 *
 * <h3>为什么这是核心产物而不是附属图</h3>
 * 本项目的 WU（world unit）是脱离 Minecraft 的内部空间，相当于地形软件的工作坐标；
 * MC 只是高度图的消费者（同一思路见参考 NovoAtlas：{@code MapImage.java:64-83}
 * 用 {@code elevation = round(verticalScale × 灰度 + startingY)} 把图像当作唯一地形权威）。
 *
 * <p>因此验收产物应当是<b>可被任意工具读取的高度图</b>，而不是只在本项目预览器里成立的截图：</p>
 * <ul>
 *   <li>{@code *.png} —— 16-bit 灰度（0..65535 线性映射到 {@code [minY,maxY]}），可直接进
 *       World Machine / Gaea / QGIS 等工具查看与比对；</li>
 *   <li>{@code *.f32.raw} —— 小端 float32 行主序，保留完整精度供数值比对；</li>
 *   <li>{@code *.json} —— 元数据：WU/像素、Y 区间、seed、配置哈希、生成参数。</li>
 * </ul>
 *
 * <p>三种产物必须同源：PNG 由同一数组量化而来，不做任何额外滤波。</p>
 */
public final class HeightmapExport {

    /** 地形采样器：输入 WU 坐标，输出世界 Y。 */
    public interface Sampler {
        double heightAt(double wuX, double wuZ);
    }

    private HeightmapExport() { }

    /**
     * 烘焙并写出高度图。
     *
     * @param dir      输出目录
     * @param name    文件基名（不含扩展名）
     * @param sampler 采样器
     * @param size    像素边长
     * @param wuPerPixel 每个像素代表的 WU 数
     * @param originWu  左上角对应的 WU 坐标
     * @param minY    量化的 Y 下限（对应灰度 0）
     * @param maxY    量化的 Y 上限（对应灰度 65535）
     * @param meta    额外元数据（写进 JSON，可为 null）
     * @return 实际写出的 Y 最小值 / 最大值
     */
    public static double[] bake(Path dir, String name, Sampler sampler, int size, double wuPerPixel,
                                double originWuX, double originWuZ, double minY, double maxY,
                                String meta) throws Exception {
        Files.createDirectories(dir);
        double[] values = new double[size * size];
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int z = 0; z < size; z++) {
            double wz = originWuZ + z * wuPerPixel;
            for (int x = 0; x < size; x++) {
                double wx = originWuX + x * wuPerPixel;
                double y = sampler.heightAt(wx, wz);
                if (!Double.isFinite(y)) {
                    throw new AssertionError("non-finite height at WU " + wx + "," + wz);
                }
                values[z * size + x] = y;
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }

        writeRaw(dir.resolve(name + ".f32.raw"), values);
        writePng16(dir.resolve(name + ".png"), values, size, minY, maxY);
        String json = String.format(Locale.ROOT, """
                {
                  "name": "%s",
                  "format": "16-bit grayscale PNG; 0..65535 maps linearly to [minY,maxY]",
                  "size": %d,
                  "wuPerPixel": %.6f,
                  "originWu": [%.3f, %.3f],
                  "minY": %.3f,
                  "maxY": %.3f,
                  "actualMinY": %.3f,
                  "actualMaxY": %.3f,
                  "rawFormat": "float32 little-endian, row-major (z outer, x inner)",
                  "meta": %s
                }
                """, name, size, wuPerPixel, originWuX, originWuZ, minY, maxY, lo, hi,
                meta == null ? "null" : "\"" + meta.replace("\"", "'") + "\"");
        Files.writeString(dir.resolve(name + ".json"), json);
        return new double[]{lo, hi};
    }

    private static void writeRaw(Path path, double[] values) throws Exception {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            for (double v : values) out.writeFloat((float) v);
        }
    }

    /** 16-bit 灰度 PNG：地形软件通用格式（Java 的 PNG writer 原生支持 TYPE_USHORT_GRAY）。 */
    private static void writePng16(Path path, double[] values, int size,
                                   double minY, double maxY) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_USHORT_GRAY);
        short[] data = ((DataBufferUShort) image.getRaster().getDataBuffer()).getData();
        double range = maxY - minY;
        if (!(range > 0)) throw new IllegalArgumentException("maxY must exceed minY");
        for (int i = 0; i < values.length; i++) {
            double t = (values[i] - minY) / range;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            data[i] = (short) Math.round(t * 65535.0);
        }
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IllegalStateException("PNG writer missing");
        }
    }
}