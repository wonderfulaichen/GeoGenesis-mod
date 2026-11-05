package com.geogenesis.worldgen.hydrology.model;

import java.util.TreeMap;

/** One fixed model policy per JVM. No hot System-property switching is supported. */
final class ModelPolicyIdentity {
    private static final String POLICY = capture();
    private ModelPolicyIdentity() { }

    static String namespace(String processIdentity) {
        return namespace(processIdentity, DeterministicSkeleton.Shaping.LEGACY, false);
    }

    /**
     * ★ R2：把<b>塑形规则版本</b>并入策略身份。
     *
     * <p>同一份 B/P 定义在 LEGACY / CANONICAL 下产出<b>不同几何</b> ⇒ 绝不能共用缓存键，
     * 否则先跑的那条规则会静默冒充另一条（与 AGENTS「同一属性名只能有一个默认值」同型）。
     * 规则名用长度框架拼接，与 processIdentity 的分隔安全一致。</p>
     */
    static String namespace(String processIdentity, DeterministicSkeleton.Shaping shaping) {
        return namespace(processIdentity, shaping, false);
    }

    /**
     * ★ R3：再把<b>整数水柱连接规则版本</b>并入策略身份（与 shape 同法，独立一项）。
     *
     * <p>理由同 {@code shape}：连接规则改变<b>列床面</b>（carved/depth）而不改几何/水位，
     * 若与关闭态共用命名空间，先跑的那条会静默冒充另一条 —— 表现为"改了没生效"或
     * "旧图被当成新规则基线"。故规则名单独成项（长度框架），<b>禁止</b>与 shape 合并成一项。</p>
     */
    static String namespace(String processIdentity, DeterministicSkeleton.Shaping shaping,
                            boolean integerWaterConnector) {
        String rule = "shape=" + (shaping == null ? DeterministicSkeleton.Shaping.LEGACY : shaping).name();
        String column = "waterColumn=" + (integerWaterConnector ? "INT-NEIGHBOUR-1" : "OFF");
        // Length framing preserves full equality even if a definition contains separators.
        return "H-model-BP-v1|" + processIdentity.length() + ":" + processIdentity
                + "|" + rule.length() + ":" + rule
                + "|" + column.length() + ":" + column
                + "|" + POLICY.length() + ":" + POLICY;
    }

    private static String capture() {
        String keys = "hierarchicalHydrology hierCoarseHalo hierStrictContract sharedLakeDomains "
                + "modelRiverModel modelRegion modelHalo modelStep modelMargin skeletonTrace "
                + "skeletonCrossPass networkFilter handoffStart selfJoinFix frameEscape skeletonMinNodes "
                + "skeletonSelfRadius skeletonSelfExempt skeletonMaxRivers skeletonMeanderAmp skeletonSourceCells "
                + "channelMinCells sourceKeepFrac sourceSpacing stepCrossGuard hydroBedEroded valleyMin valleyTrough "
                + "bedConcave bedConcaveStep lakeSinkOff localLakeLevel flatBreak inlandResolved intlandResolved "
                + "sewGap particleAmp particleMomentum boundaryPorts widthSmooth widthMonotone bankJitter "
                + "riverWidth riverCrossSection riverBankHeight riverThresholdFrac minTailExtendNodes wDrop wDown "
                + "wKeep continuousInertia wProx vectorTrace contBlend softAnchor softAnchorStiffness softAnchorIters "
                + "handoffMaxRise strictD8 inertialD8 gradientDir gradientSpan continuousAnchor anchorStiffness "
                + "maxSpillRise riverSpill outsideSteps spillFixture";
        TreeMap<String, String> values = new TreeMap<>();
        for (String key : keys.split(" ")) values.put("geogenesis." + key,
                System.getProperty("geogenesis." + key, "<default>"));
        String hydro = "couplingMaxRounds directionRule visitCap floorMul absFloorMul relFloor dropTinyBasins "
                + "minVisibleArea channelUseRawFlux deadOnGeoWet momentum recoverKeepChainInk relaxFloorGate ledgerOff";
        for (String key : hydro.split(" ")) values.put("hydro." + key,
                System.getProperty("hydro." + key, "<default>"));
        StringBuilder out = new StringBuilder("defaults-v=M1;region=512;halo=96;step=2;hier=false;"
                + "sharedLake=false;boundaryPorts=true;crossPass=true;climate=.004,.002,.3;"
                + "samples=50000;worldFlow=6;worldLake=1;");
        values.forEach((key, value) -> out.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value));
        return out.toString();
    }
}
