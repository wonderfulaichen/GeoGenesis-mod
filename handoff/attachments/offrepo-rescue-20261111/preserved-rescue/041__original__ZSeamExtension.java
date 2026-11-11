package com.geogenesis.worldgen.terrain.candidate;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.*;
import java.nio.file.*; import java.util.*;
public class ZSeamExtension {
 static final long SEED=9139912035078620160L;
 public static void main(String[] args) throws Exception {
  Locale.setDefault(Locale.ROOT); Path out=Path.of(args[1]); HeightCurve curve; ContinentField c;
  try(var cfg=CommentedFileConfig.of(args[0],TomlFormat.instance())) {cfg.load(); if(!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config incorrect"); GeoGenesisConfig.SPEC.setConfig(cfg); var p=GeoGenesisConfig.INSTANCE.buildParams(); curve=new HeightCurve(p,-64,320); c=new ContinentField(p); c.seed(SEED);}
  var cat=new RecipeMorphCatalog(SEED); var cand=new DoubleGalleryCandidate(cat,c::sample,SEED);
  int[][] pairs={{0,0},{-4,3},{976,976},{-977,-977},{29295,29295},{-29296,-29296},{976,-977},{-977,976},{29295,-29296},{-29296,29295}};
  int failed=0,success=0;
  try(var w=Files.newBufferedWriter(out.resolve("z-seams.csv"))) {
   w.write("tx,tz,x,z,status,dominantA,dominantB,activeA,activeB,maxWeightDiff,landEA,landEB,finalYA,finalYB,error\n");
   for(int[] p:pairs) { double x0=(p[0]+.5)*1024.0,z0=(p[1]+1)*1024.0; DoubleFullMorphLand.Tile t1=null,t2=null; String err="";
    try {t1=cand.land.tileAt(x0,z0-1); t2=cand.land.tileAt(x0,z0+1);} catch(Exception e) {err=e.toString().replace(',',';').replace('\n',' ');}
    for(int j=-4;j<=4;j++) for(int i=-8;i<=8;i++) {double x=x0+i*20.0,z=z0+j*12.0;
     if(Math.hypot(x-x0,z-(z0-512))>728||Math.hypot(x-x0,z-(z0+512))>728) continue;
     try {if(t1==null||t2==null) throw new IllegalStateException("tile construction: "+err);
      double[] a=DoubleFullMorphLand.mergeToEntries(t1.entryOfSite(),t1.weights(x,z)),b=DoubleFullMorphLand.mergeToEntries(t2.entryOfSite(),t2.weights(x,z)); double md=0; for(int k=0;k<a.length;k++) md=Math.max(md,Math.abs(a[k]-b[k]));
      var ta=t1; var tb=t2; double ea=IsolatedProbe.forced(cand.land,ta,x,z),eb=IsolatedProbe.forced(cand.land,tb,x,z);
      var oa=new DoubleOceanAssemblyCandidate((xx,zz)->IsolatedProbe.forced(cand.land,ta,xx,zz),c::sample,SEED+3).enableCoastalDetail(SEED);
      var ob=new DoubleOceanAssemblyCandidate((xx,zz)->IsolatedProbe.forced(cand.land,tb,xx,zz),c::sample,SEED+3).enableCoastalDetail(SEED);
      double ya=curve.heightFromE(oa.e(x,z)),yb=curve.heightFromE(ob.e(x,z));
      if(!Double.isFinite(md)||!Double.isFinite(ea)||!Double.isFinite(eb)||!Double.isFinite(ya)||!Double.isFinite(yb)) throw new ArithmeticException("non-finite");
      w.write(String.format("%d,%d,%.1f,%.1f,ok,%d,%d,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%n",p[0],p[1],x,z,DoubleFullMorphLand.dominant(a),DoubleFullMorphLand.dominant(b),DoubleFullMorphLand.activeCount(a),DoubleFullMorphLand.activeCount(b),md,ea,eb,ya,yb)); success++;
     } catch(Exception ex) {failed++; w.write(String.format("%d,%d,%.1f,%.1f,FAIL,,,,,,,,,,%s%n",p[0],p[1],x,z,ex.toString().replace(',',';').replace('\n',' ')));}
    } w.flush(); System.out.printf("pair %d,%d cumulative ok=%d fail=%d%n",p[0],p[1],success,failed);
   }
  } System.out.printf("total ok=%d fail=%d%n",success,failed); if(failed>0) System.exit(2);
 }
}
