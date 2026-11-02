package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 找定稿链的崩溃边界：先沿射线定断点，再全扫安全区内每个 1024 块瓦片。 */
public final class TileScanProbe {
    private static final long SEED = 9139912035078620160L;
    private static final int TILE = 1024;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int halfTiles = Integer.parseInt(args[0]);
        FullMorphLand morph = new FullMorphLand(new RecipeMorphCatalog(SEED));

        System.out.println("# rays: first failing radius by direction");
        int minFail = Integer.MAX_VALUE, maxOk = 0;
        for (int d = 0; d < 16; d++) {
            double angle = d * Math.PI / 8;
            int firstFail = -1;
            for (int r = 40000; r <= 132000; r += 2048) {
                double x = Math.rint(r * Math.cos(angle)) + 0.5, z = Math.rint(r * Math.sin(angle)) + 0.5;
                try {
                    morph.e(x, z);
                    if (firstFail < 0) maxOk = Math.max(maxOk, r);
                } catch (IllegalStateException e) {
                    if (firstFail < 0) firstFail = r;
                }
            }
            System.out.println("dir=" + d + " firstFail=" + firstFail);
            if (firstFail > 0) minFail = Math.min(minFail, firstFail);
        }
        System.out.println("# rays summary minFirstFail=" + minFail + " maxOkBeforeFail=" + maxOk);
        System.out.flush();

        System.out.println("# grid halfTiles=" + halfTiles + " extent=" + halfTiles * TILE);
        int total = 0, failed = 0, nearestFail = Integer.MAX_VALUE;
        String sample = "-";
        long start = System.nanoTime();
        for (int tz = -halfTiles; tz < halfTiles; tz++) {
            for (int tx = -halfTiles; tx < halfTiles; tx++) {
                double x = (tx + 0.5) * TILE, z = (tz + 0.5) * TILE;
                total++;
                try {
                    morph.e(x, z);
                } catch (IllegalStateException e) {
                    failed++;
                    int radius = (int) Math.hypot(x, z);
                    if (radius < nearestFail) {
                        nearestFail = radius;
                        String message = String.valueOf(e.getMessage());
                        sample = "x=" + x + " z=" + z + " " + message.substring(0, Math.min(90, message.length()));
                    }
                }
            }
            System.out.printf("row tz=%d total=%d failed=%d nearestFail=%d seconds=%.0f%n", tz, total, failed,
                    nearestFail == Integer.MAX_VALUE ? -1 : nearestFail, (System.nanoTime() - start) / 1e9);
            System.out.flush();
        }
        System.out.println("# grid done total=" + total + " failed=" + failed + " nearestFail=" + nearestFail);
        System.out.println("# sample " + sample);
    }
}
