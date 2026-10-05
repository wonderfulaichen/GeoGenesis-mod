package com.geogenesis.worldgen.hydrology.model;

/** Exchange-law checks independent of global basin detection. */
public final class LakeSaddleExchangeProbe {
    public static void main(String[] args) {
        var model = new LakeSaddleExchange(new double[]{0}, new double[]{0}, 10, 3, 2);
        var dry = model.step(20, 0, 1);
        require(Math.abs(dry.transfer()) < 1e-12, "below crest: no artificial leakage");
        var equal = model.step(40, 40, 1);
        require(Math.abs(equal.transfer()) < 1e-12, "equal heads: zero exchange");
        require(model.rate(4, 0) == 2 && model.rate(4, 3.5) < model.rate(4, 0),
                "receiver backwater suppresses outgoing flow");
        var forward = model.step(40, 0, 1);
        var reverse = model.step(0, 40, 1);
        require(forward.transfer() > 0 && Math.abs(forward.transfer() + reverse.transfer()) < 1e-12,
                "role reversal gives opposite transfer");
        require(Math.abs(forward.volumeA() + forward.volumeB() - 40) < 1e-12,
                "external two-storage ledger closes");
        require(Math.abs(forward.transfer() - model.rate(forward.levelA(), forward.levelB())) < 1e-12,
                "implicit end-state rate residual");
        var huge = model.step(40, 0, 1e6);
        require(huge.volumeA() >= 0 && huge.volumeB() >= 0 && huge.levelA() >= 3 - 1e-10,
                "large dt cannot empty donor below crest");
        double exactAtOne = 30 + 10 / Math.pow(1 + 0.1, 2);
        double coarse = run(model, 0.1), fine = run(model, 0.01);
        require(Math.abs(fine - exactAtOne) < Math.abs(coarse - exactAtOne),
                "time refinement approaches analytic flat-storage recession");
        System.out.printf("analyticVA=%.12f coarse=%.12f fine=%.12f forwardTransfer=%.12f%n",
                exactAtOne, coarse, fine, forward.transfer());
        System.out.println("ALL PASS: crest exchange approximation; global routing and footprint merging not implemented here.");
    }

    private static double run(LakeSaddleExchange model, double dt) {
        double a = 40, b = 0;
        for (int i = 0; i < Math.round(1 / dt); i++) {
            var result = model.step(a, b, dt); a = result.volumeA(); b = result.volumeB();
        }
        if (Math.abs(a + b - 40) > 1e-10) throw new AssertionError("accumulated mass residual");
        return a;
    }

    private static void require(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("[PASS] " + name);
    }
}
