package com.geogenesis.worldgen.hydrology;

/** 低分辨率河网 ASCII 输出：高流量河道优先显示，便于快速目测拓扑。 */
public final class HydrologyAscii {
    private HydrologyAscii() { }

    public static String render(HydrologyGrid grid, int stride) {
        StringBuilder out = new StringBuilder();
        int step = Math.max(1, stride);
        for (int z = grid.halo(); z < grid.size() - grid.halo(); z += step) {
            for (int x = grid.halo(); x < grid.size() - grid.halo(); x += step) {
                out.append(symbol(grid, grid.index(x, z))).append(' ');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static char symbol(HydrologyGrid grid, int index) {
        if (grid.ocean(index)) return '~';
        if (grid.lake(index)) return 'O';
        if (!grid.river(index)) return '.';
        double flow = grid.contributingArea(index);
        return flow >= 128 ? '#' : flow >= 32 ? '+' : ':';
    }
}
