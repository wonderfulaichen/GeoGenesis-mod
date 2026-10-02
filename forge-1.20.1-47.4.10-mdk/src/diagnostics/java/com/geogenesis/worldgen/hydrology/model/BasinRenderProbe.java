package com.geogenesis.worldgen.hydrology.model;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Computed synthetic basin diagnostic; not a Minecraft preview or infinite-world proof. */
public final class BasinRenderProbe {
    private static final int NX = 48, NZ = 32, SCALE = 10;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("build", "hydro-reassessment", "basin-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        double[] bed = new double[NX * NZ];
        for (int i = 0; i < NX; i++) for (int j = 0; j < NZ; j++) {
            double left = 0.015 * ((i - 12.0) * (i - 12.0) + (j - 16.0) * (j - 16.0));
            double right = 0.015 * ((i - 35.0) * (i - 35.0) + (j - 16.0) * (j - 16.0));
            bed[i * NZ + j] = Math.min(left, right);
        }
        WaterGrid grid = WaterGrid.of(NX, NZ, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel model = new DiffusiveWaveModel(grid, ModelParams.still().withSteadyTol(1e-6));
        for (int i = 0; i < 24; i++) for (int j = 0; j < NZ; j++) model.fillTo(i, j, 3.4);
        double[] initial = model.depth();
        double volume = model.storage();
        model.steadyState();
        if (!model.converged()) throw new IllegalStateException("basin did not converge");
        double residual = model.storage() - volume;
        if (Math.abs(residual) > 1e-8) throw new AssertionError("mass conservation: " + residual);
        double[] result = model.depth();
        BufferedImage image = render(grid, initial, result);
        ImageIO.write(image, "png", dir.resolve("basin.png").toFile());
        String stats = String.format("synthetic closed basin; no rain or loss%n"
                + "steps=%d time=%.6f initialVolume=%.12f finalVolume=%.12f residual=%.3e%n"
                + "data=bed/depth fields, no decorative river curves; not production%n",
                model.steps(), model.time(), volume, model.storage(), residual);
        Files.writeString(dir.resolve("metrics.txt"), stats);
        System.out.print(stats);
        System.out.println("IMAGE=" + dir.resolve("basin.png").toAbsolutePath());
    }

    private static BufferedImage render(WaterGrid g, double[] initial, double[] result) {
        int width = NX * SCALE;
        BufferedImage im = new BufferedImage(width * 3, NZ * SCALE + 270, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = im.createGraphics();
        graphics.setColor(new Color(25, 28, 33)); graphics.fillRect(0, 0, im.getWidth(), im.getHeight());
        String[] labels = {"BED + DIAGNOSTIC TILE GRID", "INITIAL WATER (LEFT BASIN)", "COMPUTED EQUILIBRIUM"};
        for (int panel = 0; panel < 3; panel++) {
            graphics.setColor(Color.WHITE); graphics.drawString(labels[panel], panel * width + 12, 20);
            for (int i = 0; i < NX; i++) for (int j = 0; j < NZ; j++) {
                int k = g.idx(i, j);
                int gray = (int) Math.max(35, Math.min(210, 55 + g.bed[k] * 22));
                double depth = panel == 1 ? initial[k] : result[k];
                Color color = panel == 0 || depth < 0.001 ? new Color(gray, gray, gray)
                        : new Color(20, (int) Math.max(65, 185 - depth * 22), 220);
                graphics.setColor(color);
                graphics.fillRect(panel * width + i * SCALE, 30 + j * SCALE, SCALE, SCALE);
            }
        }
        graphics.setColor(new Color(220, 125, 60));
        for (int i = 8; i < NX; i += 8) graphics.drawLine(i * SCALE, 30, i * SCALE, 30 + NZ * SCALE);
        for (int j = 8; j < NZ; j += 8) graphics.drawLine(0, 30 + j * SCALE, width, 30 + j * SCALE);
        drawSection(graphics, g, initial, result, 30 + NZ * SCALE);
        graphics.dispose();
        return im;
    }

    private static void drawSection(Graphics2D gr, WaterGrid g, double[] initial, double[] result, int y0) {
        gr.setColor(Color.WHITE);
        gr.drawString("CENTRAL SECTION: gray=bed, orange=initial surface, cyan=computed wet surface", 12, y0 + 24);
        gr.drawString("DIAGNOSTIC ONLY: synthetic terrain, CLOSED exterior, not a Minecraft river-network preview", 12, y0 + 45);
        for (int i = 0; i + 1 < NX; i++) {
            int a = g.idx(i, 16), b = g.idx(i + 1, 16);
            int x1 = 20 + i * 29, x2 = 20 + (i + 1) * 29;
            int bottom = y0 + 225;
            gr.setColor(Color.LIGHT_GRAY);
            gr.drawLine(x1, bottom - (int) (g.bed[a] * 40), x2, bottom - (int) (g.bed[b] * 40));
            if (initial[a] > 0.001 && initial[b] > 0.001) {
                gr.setColor(Color.ORANGE);
                gr.drawLine(x1, bottom - (int) ((g.bed[a] + initial[a]) * 40),
                        x2, bottom - (int) ((g.bed[b] + initial[b]) * 40));
            }
            if (result[a] > 0.001 && result[b] > 0.001) {
                gr.setColor(Color.CYAN);
                gr.drawLine(x1, bottom - (int) ((g.bed[a] + result[a]) * 40),
                        x2, bottom - (int) ((g.bed[b] + result[b]) * 40));
            }
        }
    }
}
