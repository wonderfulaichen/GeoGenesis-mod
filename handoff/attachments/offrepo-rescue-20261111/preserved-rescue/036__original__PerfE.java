package com.geogenesis.worldgen.terrain.candidate;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.*;
import java.util.*;
public class PerfE {
 static final long SEED=9139912035078620160L; static volatile double sink;
 public static void main(String[] a) throws Exception {
  int zone=Integer.parseInt(a[0]); Locale.setDefault(Locale.ROOT);
  HeightCurve curve; ContinentField c;
  try(var cfg=CommentedFileConfig.of(a[1],TomlFormat.instance())) {cfg.load(); if(!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config incorrect"); GeoGenesisConfig.SPEC.setConfig(cfg); var p=GeoGenesisConfig.INSTANCE.buildParams(); curve=new HeightCurve(p,-64,320); c=new ContinentField(p); c.seed(SEED);}
  RecipeMorphCatalog cat=new RecipeMorphCatalog(SEED);
  DoubleGalleryCandidate cand=new DoubleGalleryCandidate(cat,c::sample,SEED);
  double x0=zone*1024+512, z0=512;
  long t=System.nanoTime(); double first=cand.e(x0,z0); long firstNs=System.nanoTime()-t;
  double sum=0; long st=System.nanoTime();
  for(int i=0;i<10000;i++){double xx=zone*1024+(i%100)*10+.25,zz=(i/100)*10+.75; sum+=cand.e(xx,zz);}
  long full=System.nanoTime()-st;
  double lsum=0; long lt=System.nanoTime();
  for(int i=0;i<10000;i++){double xx=zone*1024+(i%100)*10+.25,zz=(i/100)*10+.75; lsum+=cand.land.e(xx,zz);}
  long land=System.nanoTime()-lt;
  sink=first+sum+lsum;
  System.out.printf(Locale.ROOT,"%d,%.6f,%.6f,%.6f,%.17g,%.17g%n",zone,firstNs/1e6,full/1e6,land/1e6,sum,lsum);
 }
}
