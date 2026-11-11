package com.geogenesis.worldgen.terrain.candidate;
import com.electronwill.nightconfig.core.file.CommentedFileConfig; import com.electronwill.nightconfig.toml.TomlFormat; import com.geogenesis.config.GeoGenesisConfig; import com.geogenesis.worldgen.terrain.*; import java.nio.file.*; import java.util.*;
public class SeamForensics {
 static final long SEED=9139912035078620160L; static final double X=1875992,Z=1875476;
 static String key(DoubleBoundaryGeometry.Cell c){return c.cellX+":"+c.cellZ;}
 static void inspect(String label, DoubleFullMorphLand.Tile t, RecipeMorphCatalog cat) {
  var g=t.geometry(); var q=g.query(X,Z); double[] fast=t.weights(X,Z); var win=g.exactWinner(X,Z);
  System.out.printf(Locale.ROOT,"%s center=(%.1f,%.1f) radius=%.1f distance=%.9f margin=%.9f sites=%d edges=%d winner=%s winnerSlot=%d queryActiveSites=%d indexedActiveSites=%d%n",label,g.centerX(),g.centerZ(),g.queryRadius(),Math.hypot(X-g.centerX(),Z-g.centerZ()),g.queryRadius()-Math.hypot(X-g.centerX(),Z-g.centerZ()),g.sites().size(),g.edges().size(),key(win),q.slot,q.activeCount,Arrays.stream(fast).filter(v->v>0).count());
  double diff=0; for(int i=0;i<fast.length;i++) diff=Math.max(diff,Math.abs(fast[i]-q.weight[i])); System.out.println("indexed_vs_query_max="+diff);
  for(int i=0;i<fast.length;i++) if(fast[i]>0 || q.weight[i]>0) {var s=g.sites().get(i); int slot=g.slotOfCell(s.cellX,s.cellZ); System.out.printf(Locale.ROOT,"active cell=%s slot=%d entry=%d weight=%.17g signedDistance=%.17g%n",key(s),slot,cat.slotEntry(slot),fast[i],q.signedDistance[i]);}
  for(var e:g.edges()) {double best=Double.POSITIVE_INFINITY; for(int j=0;j+1<e.wx.length;j++) best=Math.min(best,DoubleBoundaryGeometry.segmentDistance(X,Z,e.wx[j],e.wz[j],e.wx[j+1],e.wz[j+1])); if(best<160) System.out.printf(Locale.ROOT,"edge %s|%s d=%.12f frameTruncated=%s vertices=%d length=%.3f endpoints=(%.8f,%.8f)-(%.8f,%.8f)%n",key(e.a),key(e.b),best,e.frameTruncated,e.wx.length,e.worldLength,e.wx[0],e.wz[0],e.wx[e.wx.length-1],e.wz[e.wz.length-1]);}
 }
 public static void main(String[] args)throws Exception {
  Locale.setDefault(Locale.ROOT); HeightCurve curve; ContinentField c; try(var cfg=CommentedFileConfig.of(args[0],TomlFormat.instance())){cfg.load(); GeoGenesisConfig.SPEC.setConfig(cfg); var p=GeoGenesisConfig.INSTANCE.buildParams();curve=new HeightCurve(p,-64,320);c=new ContinentField(p);c.seed(SEED);}
  var cat=new RecipeMorphCatalog(SEED); var cand=new DoubleGalleryCandidate(cat,c::sample,SEED); var a=cand.land.tileAt(1875967,Z); var b=cand.land.tileAt(1875969,Z);
  var g=DoubleBoundaryGeometry.of(cat.original(),X,Z,728,4,.02);g.setMaxSeg(2); var extra=DoubleFullMorphLand.wrap(cat,g);
  inspect("A",a,cat); inspect("B",b,cat); inspect("POINT_CENTERED",extra,cat);
  var ga=a.geometry();var gb=b.geometry(); var ma=new HashMap<Long,DoubleBoundaryGeometry.Site>();for(var s:ga.sites())ma.put(s.key(),s); int common=0,mismatch=0,coords=0;for(var s:gb.sites()){var t=ma.get(s.key());if(t!=null){common++;if(ga.slotOfCell(t.cellX,t.cellZ)!=gb.slotOfCell(s.cellX,s.cellZ))mismatch++;if(t.vx!=s.vx||t.vz!=s.vz)coords++;}}System.out.printf("site alignment common=%d slotMismatch=%d coordMismatch=%d onlyA=%d onlyB=%d%n",common,mismatch,coords,ga.sites().size()-common,gb.sites().size()-common);
  try(var w=Files.newBufferedWriter(Path.of(args[1],"forensics-neighborhood.csv"))){w.write("x,z,weightDiff,activeA,activeB,landEA,landEB,worldEA,worldEB,yA,yB,mask,alpha\n"); var oa=new DoubleOceanAssemblyCandidate((x,z)->IsolatedProbe.forced(cand.land,a,x,z),c::sample,SEED+3).enableCoastalDetail(SEED);var ob=new DoubleOceanAssemblyCandidate((x,z)->IsolatedProbe.forced(cand.land,b,x,z),c::sample,SEED+3).enableCoastalDetail(SEED);
   for(int dz=-64;dz<=64;dz+=2)for(int dx=-64;dx<=64;dx+=2){double x=X+dx,z=Z+dz;if(Math.hypot(x-ga.centerX(),z-ga.centerZ())>728||Math.hypot(x-gb.centerX(),z-gb.centerZ())>728)continue;double[] wa=DoubleFullMorphLand.mergeToEntries(a.entryOfSite(),a.weights(x,z)),wb=DoubleFullMorphLand.mergeToEntries(b.entryOfSite(),b.weights(x,z));double md=0;for(int i=0;i<wa.length;i++)md=Math.max(md,Math.abs(wa[i]-wb[i]));double ea=IsolatedProbe.forced(cand.land,a,x,z),eb=IsolatedProbe.forced(cand.land,b,x,z),wea=oa.e(x,z),web=ob.e(x,z);double mask=cand.land.mask(x,z);w.write(String.format("%.1f,%.1f,%.17g,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",x,z,md,DoubleFullMorphLand.activeCount(wa),DoubleFullMorphLand.activeCount(wb),ea,eb,wea,web,curve.heightFromE(wea),curve.heightFromE(web),mask,cand.land.alphaOf(mask)));}
  }
 }
}
