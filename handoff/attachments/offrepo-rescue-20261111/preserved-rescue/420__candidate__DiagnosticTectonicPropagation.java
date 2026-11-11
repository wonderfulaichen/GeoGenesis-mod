package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.TectonicDeformation;
import com.geogenesis.worldgen.terrain.TectonicField;

/** R3诊断适配：新S/D仅喂原消费者，不接任何世界高度链。每实例固定seed，无缓存。 */
final class DiagnosticTectonicPropagation {
    private final TectonicField original;
    private final TectonicDeformation deformation;
    private final DiagnosticTectonicDriver driver;

    DiagnosticTectonicPropagation(long seed) {
        original = new TectonicField(seed);
        deformation = new TectonicDeformation(seed);
        driver = new DiagnosticTectonicDriver(seed);
    }

    record Values(double stress, double distance, double envelope110,
                  double convergent110, double divergent130, double offset) {
        double[] array() {
            return new double[]{stress, distance, envelope110, convergent110, divergent130, offset};
        }
    }
    record Pair(Values before, Values after) { }

    Pair sample(double x, double z) {
        var old = original.sample(x, z);
        var repaired = driver.sample(x, z);
        var candidate = adapt(old, repaired.stress(), repaired.distance());
        return new Pair(consume(old, deformation, x, z), consume(candidate, deformation, x, z));
    }

    /** 这是候选适配Sample，不是原sample输出；离散配对字段只保留作元数据。 */
    static TectonicField.Sample adapt(TectonicField.Sample metadata, double stress, double distance) {
        return new TectonicField.Sample(distance, metadata.btype(), metadata.rate(),
                metadata.tangentX(), metadata.tangentZ(), stress, metadata.alongCoord());
    }

    static Values consume(TectonicField.Sample sample, TectonicDeformation deformation, double x, double z) {
        double envelope = TectonicField.boundaryStrength(sample);
        double convergence = OceanMorphConcepts.discreteStrength(-sample.stress()) * envelope;
        double divergence = OceanMorphConcepts.tectonicGate(sample.stress(), sample.dist(),
                OceanMorphConcepts.RIDGE_BAND_SIGMA);
        return new Values(sample.stress(), sample.dist(), envelope, convergence, divergence,
                deformation.offset(sample, x, z));
    }
}
