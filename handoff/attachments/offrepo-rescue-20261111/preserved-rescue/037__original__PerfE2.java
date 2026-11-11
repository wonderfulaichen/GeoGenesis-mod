package com.geogenesis.worldgen.terrain.candidate;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.*;
import java.util.*;
public class PerfE2 {
 static volatile double sink;
 public static void main(String[] a) throws Exception {
  Locale.setDefault(Locale.ROOT); int zone=Integer.parseInt(a[0]); long seed=9139912035078620160L;
  ContinentField c; try(var cfg=CommentedFileConfig.of(a[1],TomlFormat.instance())) {cfg.load(); if(!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config incorrect"); GeoGenesisConfig.SPEC.setConfig(cfg); var p=GeoGenesisConfig.INSTANCE.buildParams(); c=new ContinentField(p); c.seed(seed);}
  var cand=new DoubleGalleryCandidate(new RecipeMorphCatalog(seed),c::sample,seed);
  for(int r=0;r<25;r++) { double sum=0; long hash=0xcbf29ce484222325L; long st=System.nanoTime();
   for(int i=0;i<10000;i++) { int j=(i*7919)%10000; double x=zone*1024.0+(j%100)*10+.25, z=(j/100)*10+.75; double v=cand.e(x,z); sum+=v; hash=(hash^Double.doubleToRawLongBits(v))*0x100000001b3L; }
   long ns=System.nanoTime()-st; sink=sum; System.out.printf("%d,%d,%d,%016x,%.17g%n",zone,r,ns,hash,sum);
  }
 }
}
