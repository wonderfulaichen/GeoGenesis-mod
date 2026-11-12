package com.geogenesis.worldgen.terrain.tectreplica;

/**
 * SplitMix64 顺序随机流 —— SimpleTectonics 复刻的确定性核心。
 *
 * <p>原版随机源有三处、且互不可复现：{@code srand(SEED)+rand()}（板块质心
 * tectonics.h:401、间隙填充角 tectonics.h:464）与 {@code mt19937(random_device())}
 * （poisson.h:6-8，固定种子也不可复现）。复刻用单一 SplitMix64 按固定顺序接管全部
 * 随机点：泊松采样最先（cluster 在 World 构造前初始化）→ 板块质心 → 间隙填充角。</p>
 *
 * <p>"确定性"指同种子同输出；不追求与 C++ 逐位一致（PRNG 不同，且量纲/编译器
 * 差异本就无法逐位）。</p>
 */
final class TectRng {

    private long state;

    TectRng(long seed) {
        this.state = seed;
    }

    long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * 参考项目的 {@code uniform(a,b) = dis(gen)*(a-b)+b}（poisson.h:14-16）——
     * 注意参数序：返回值落在 {@code [a,b]} 端点闭区间一侧（b + (1-d)(a-b)）。
     * 照抄该实现，不做 [0,1) 语义的"修正"。
     */
    double uniform(double a, double b) {
        return nextDouble() * (a - b) + b;
    }

    /** {@code rand() % SIZE} 的等价（rand()%SIZE 有轻微模偏，量级无关紧要）。 */
    int nextInt(int bound) {
        return (int) Long.remainderUnsigned(nextLong(), bound);
    }
}
