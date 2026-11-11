package com.geogenesis.worldgen.terrain.candidate;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.*;
import java.nio.file.*; import java.io.*; import java.util.*;
public class IsolatedProbe {
 static final long SEED=9139912035078620160L; static volatile double sink;
 static double forced(DoubleFullMorphLand l, DoubleFullMorphLand.Tile t,double x,double z) { double[] w=DoubleFullMorphLand.mergeToEntries(t.entryOfSite(),t.weights(x,z)); double r=l.heightForMerged(x,z,w); return Math.max(CombineCandidate.GROUND_E+l.replayOverlay(r,x,z)*CombineCandidate.E_SPAN,0); }
 public static void main(String[] a) throws Exception {
  Locale.setDefault(Locale.ROOT); Path out=Path.of(a[1]); HeightCurve curve; ContinentField c;
  try(var cfg=CommentedFileConfig.of(a[0],TomlFormat.instance())) {cfg.load(); if(!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config incorrect"); GeoGenesisConfig.SPEC.setConfig(cfg); var p=GeoGenesisConfig.INSTANCE.buildParams(); curve=new HeightCurve(p,-64,320); c=new ContinentField(p); c.seed(SEED);}
  RecipeMorphCatalog cat=new RecipeMorphCatalog(SEED); DoubleGalleryCandidate cand=new DoubleGalleryCandidate(cat,c::sample,SEED);
  try(var w=Files.newBufferedWriter(out.resolve("seams.csv"))) { w.write("pair,x,z,dominantA,dominantB,activeA,activeB,maxWeightDiff,landEA,landEB,finalYA,finalYB\n");
   int[][] pairs={{0,0},{-4,3},{1831,1831},{-1832,-1832},{5,-8},{-12,14}};
   for(int[] p:pairs) {double x0=(p[0]+1)*1024.0,z0=(p[1]+.5)*1024.0;
    var t1=cand.land.tileAt(x0-1,z0); var t2=cand.land.tileAt(x0+1,z0);
    for(int j=-8;j<=8;j++) for(int i=-4;i<=4;i++) {double x=x0+i*12.0,z=z0+j*20.0;
     if(Math.hypot(x-t1.centerX(),z-t1.centerZ())>728||Math.hypot(x-t2.centerX(),z-t2.centerZ())>728) continue;
     double[] a1=DoubleFullMorphLand.mergeToEntries(t1.entryOfSite(),t1.weights(x,z)),a2=DoubleFullMorphLand.mergeToEntries(t2.entryOfSite(),t2.weights(x,z)); double md=0; for(int k=0;k<a1.length;k++) md=Math.max(md,Math.abs(a1[k]-a2[k]));
     double e1=forced(cand.land,t1,x,z),e2=forced(cand.land,t2,x,z);
     var o1=new DoubleOceanAssemblyCandidate((xx,zz)->forced(cand.land,t1,xx,zz),c::sample,SEED+3).enableCoastalDetail(SEED);
     var o2=new DoubleOceanAssemblyCandidate((xx,zz)->forced(cand.land,t2,xx,zz),c::sample,SEED+3).enableCoastalDetail(SEED);
     double y1=curve.heightFromE(o1.e(x,z)),y2=curve.heightFromE(o2.e(x,z));
     w.write(String.format("%d:%d,%.1f,%.1f,%d,%d,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g%n",p[0],p[1],x,z,DoubleFullMorphLand.dominant(a1),DoubleFullMorphLand.dominant(a2),DoubleFullMorphLand.activeCount(a1),DoubleFullMorphLand.activeCount(a2),md,e1,e2,y1,y2));
    } System.out.println("seam pair "+Arrays.toString(p));
   }
  }
  try(var w=Files.newBufferedWriter(out.resolve("far-grid.csv"))) { w.write("x,z,worldE,finalY,landE\n"); for(int j=0;j<64;j++) for(int i=0;i<64;i++){double x=1875000.5+i,z=1875000.5+j; double e=cand.e(x,z); w.write(String.format("%.1f,%.1f,%.17g,%.17g,%.17g%n",x,z,e,curve.heightFromE(e),cand.land.e(x,z)));} }
  System.out.println("far done");
  FullMorphLand ref=new FullMorphLand(cat); OceanAssemblyCandidate ro=new OceanAssemblyCandidate(ref::e,c::sample,SEED+3).enableCoastalDetail(SEED);
  for(int k=0;k<4;k++) for(int i=0;i<1200;i++){double x=100+i%40,z=100+i/40; sink+=cand.e(x,z)+ro.e(x,z);}
  try(var w=Files.newBufferedWriter(out.resolve("perf.csv"))) {w.write("iteration,order,arm,mode,points,elapsed_ns,checksum\n");
   for(int r=0;r<8;r++) for(int order=0;order<2;order++){boolean dbl=((r+order)%2==0); for(int mode=0;mode<2;mode++) {DoubleGalleryCandidate dc=mode==0?new DoubleGalleryCandidate(cat,c::sample,SEED):cand; FullMorphLand fr=mode==0?new FullMorphLand(cat):ref; OceanAssemblyCandidate fo=mode==0?new OceanAssemblyCandidate(fr::e,c::sample,SEED+3).enableCoastalDetail(SEED):ro;
    double sum=0; long start=System.nanoTime(); for(int i=0;i<1024;i++){double x=100+i%32,z=100+i/32; sum+=dbl?dc.e(x,z):fo.e(x,z);} long elapsed=System.nanoTime()-start; sink=sum; w.write(String.format("%d,%d,%s,%s,1024,%d,%.17g%n",r,order,dbl?"double":"float",mode==0?"cold-new-instance":"warm-instance",elapsed,sum)); w.flush(); }
   } }
  System.out.println("performance done");
 }
}
