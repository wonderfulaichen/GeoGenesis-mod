package com.geogenesis.worldgen.terrain.candidate;
import java.util.function.DoubleBinaryOperator;
/** Gallery composition; caller provides the original continent sampler, not a new ocean pool. */
final class DoubleGalleryCandidate {
 final DoubleFullMorphLand land;
 final DoubleOceanAssemblyCandidate ocean;
 DoubleGalleryCandidate(RecipeMorphCatalog catalog, DoubleOceanAssemblyCandidate.EdgeSource continent, long seed) {
   land = new DoubleFullMorphLand(catalog);
   ocean = new DoubleOceanAssemblyCandidate(land::e, continent, seed + 3).enableCoastalDetail(seed);
 }
 double e(double x,double z) { return ocean.e(x,z); }
 }
