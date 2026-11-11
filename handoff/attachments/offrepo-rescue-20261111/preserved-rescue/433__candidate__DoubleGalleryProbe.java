package com.geogenesis.worldgen.terrain.candidate;
public final class DoubleGalleryProbe {
 interface Test { double run(); }
 static void run(String label, Test f) { try { System.out.println(label+" OK "+f.run()); } catch (UnsupportedOperationException e) { System.out.println(label+" UNSUPPORTED "+e.getMessage()); } }
 public static void main(String[] args) {
  long seed=9139912035078620160L;
  RecipeMorphCatalog c=new RecipeMorphCatalog(seed);
  for(int i=0;i<c.size();i++){final int id=i; var r=new DoubleLandRecipe(c.entry(i)); run("raw-"+id,()->r.raw(130.25,17.75)); run("amp-"+id,()->r.amplitude(130.25,17.75)); run("bias-"+id,()->r.bias(130.25,17.75)); }
  DoubleMorphEngine engine = new DoubleMorphEngine(DoubleLandRecipe.all(c), seed);
  for (int i=0;i<c.size();i++) { final int id=i; double[] w=new double[c.size()]; w[i]=1; run("engine-"+id,()->engine.sampleLand(130.25,17.75,w)); }
  DoubleGalleryCandidate g=new DoubleGalleryCandidate(c,(x,z)->0.5,seed);
  run("gallery",()->g.e(130.25,17.75));
  var geom=g.land.tileAt(130.25,17.75).geometry();
  double[] f=geom.warpForward(130.25,17.75); System.out.println("geometry-forward="+f[0]+","+f[1]);
  run("mask",()->g.land.mask(130.25,17.75));
  run("coverage",()->g.land.oldCoverageHeight(130.25,17.75));
  DoubleOceanAssemblyCandidate sea=new DoubleOceanAssemblyCandidate((x,z)->.2,(x,z)->-1,seed+3).enableCoastalDetail(seed);
  run("ocean-deep",()->sea.e(130.25,17.75));
 }
}
