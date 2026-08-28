package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Priority-flood 洼地解析：最低溢出口优先，结果只依赖网格数据和固定比较器。 */
public final class DrainageResolver {
    private DrainageResolver() { }

    public static void resolve(HydrologyGrid grid) {
        int n = grid.size(), total = n * n;
        boolean[] visited = new boolean[total];
        PriorityQueue<Integer> queue = new PriorityQueue<>(Comparator
                .comparingDouble((Integer i) -> grid.filledElevation(i)).thenComparingInt(Integer::intValue));
        for (int z = 0; z < n; z++) {
            addBoundary(grid, z * n, visited, queue);
            addBoundary(grid, z * n + n - 1, visited, queue);
        }
        for (int x = 1; x < n - 1; x++) {
            addBoundary(grid, x, visited, queue);
            addBoundary(grid, (n - 1) * n + x, visited, queue);
        }
        while (!queue.isEmpty()) {
            int current = queue.poll();
            int x = grid.x(current), z = grid.z(current);
            for (int d = 0; d < 8; d++) {
                int nx = x + FlowDirectionSolver.dx(d), nz = z + FlowDirectionSolver.dz(d);
                if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                int next = grid.index(nx, nz);
                if (visited[next]) continue;
                visited[next] = true;
                double filled = Math.max(grid.elevation(next), grid.filledElevation(current));
                grid.filledElevations()[next] = filled;
                grid.lakes()[next] = filled > grid.elevation(next) + 1e-9 && !grid.ocean(next);
                queue.add(next);
            }
        }
        FlowDirectionSolver.solve(grid);
        Arrays.fill(grid.lakes(), false);
        for (int i = 0; i < total; i++) grid.lakes()[i] = grid.filledElevation(i) > grid.elevation(i) + 1e-9;
    }

    private static void addBoundary(HydrologyGrid grid, int index, boolean[] visited, PriorityQueue<Integer> queue) {
        if (!visited[index]) { visited[index] = true; queue.add(index); }
    }
}
