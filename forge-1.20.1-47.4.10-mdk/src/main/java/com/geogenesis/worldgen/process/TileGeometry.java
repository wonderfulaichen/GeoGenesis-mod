package com.geogenesis.worldgen.process;

import java.util.List;
import java.util.ArrayList;

/** Fixed compatibility geometry. All coordinates are wu, not block or render pixels. */
public final class TileGeometry {
    private TileGeometry() {}
    public record Owner(int x, int z) {
        public Owner {
            if (x % 48 != 0 || z % 48 != 0) throw new IllegalArgumentException("owner alignment");
        }
        public int originX() { return x - 40; }
        public int originZ() { return z - 40; }
        public int inputX() { return x - 49; }
        public int inputZ() { return z - 49; }
        public int hardnessLastX() { return inputX() + 160; }
        public int hardnessLastZ() { return inputZ() + 160; }
    }
    public record Footprint(Owner main, Owner sideX, Owner sideZ, Owner corner,
                            double fx, double fz) {
        public List<Owner> required() {
            List<Owner> owners = new ArrayList<>();
            owners.add(main);
            if (fx > 0) owners.add(sideX);
            if (fz > 0) owners.add(sideZ);
            if (fx > 0 && fz > 0) owners.add(corner);
            return List.copyOf(owners);
        }
    }
    public static void checkPoint(double x, double z) {
        // Kernel uses int world hashing; reserve halo and hash arithmetic compatibility.
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 1_000_000_000
                || Math.abs(z) > 1_000_000_000) throw new IllegalArgumentException("wu point");
    }
    public static Footprint footprint(double x, double z) {
        checkPoint(x, z);
        int ax = Math.floorDiv((int) Math.floor(x), 48) * 48;
        int az = Math.floorDiv((int) Math.floor(z), 48) * 48;
        int nx = ax, nz = az;
        double fx = 0, fz = 0;
        if (ax + 48.0 - x <= 10) {
            double b = 1 - (ax + 48.0 - x) / 10;
            fx = .5 * b * b * (3 - 2 * b); nx += 48;
        } else if (x - ax < 10) {
            double b = 1 - (x - ax) / 10;
            fx = .5 * b * b * (3 - 2 * b); nx -= 48;
        }
        if (az + 48.0 - z <= 10) {
            double b = 1 - (az + 48.0 - z) / 10;
            fz = .5 * b * b * (3 - 2 * b); nz += 48;
        } else if (z - az < 10) {
            double b = 1 - (z - az) / 10;
            fz = .5 * b * b * (3 - 2 * b); nz -= 48;
        }
        return new Footprint(new Owner(ax, az), new Owner(nx, az),
                new Owner(ax, nz), new Owner(nx, nz), fx, fz);
    }
}
