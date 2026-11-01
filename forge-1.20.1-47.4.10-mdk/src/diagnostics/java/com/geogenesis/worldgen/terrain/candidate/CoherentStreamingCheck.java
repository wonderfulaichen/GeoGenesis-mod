package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** S4只测现有S3，不改缓存、不绕过seed守卫，失败证据保留并非零退出。 */
public final class CoherentStreamingCheck {
    private final List<String> report = new ArrayList<>();
    private final List<String> rows = new ArrayList<>(List.of("stage,x,z,winnerA,winnerB,maxWeightDiff,eDiff,bitwise,ok"));
    private final List<String> errors = new ArrayList<>();
    private final CoherentMorphWorld world = new CoherentMorphWorld();
    private int failures, comparisons;
    private double maxWeight, maxE;
    private Path out;

    public static void main(String[] args) throws Exception {
        var c = new CoherentStreamingCheck(); c.out = Path.of(args[0]); Files.createDirectories(c.out);
        if (args.length > 1 && args[1].equals("window-only")) c.stage("shifted-window", c::window);
        else {
            c.stage("query-order", c::order);
            c.stage("forced-xy-corners", c::seams);
            c.stage("shifted-window", c::window);
            c.stage("tile-eviction", c::tileEviction);
            c.stage("identity-eviction", c::identityEviction);
            c.stage("patch-eviction", c::patchEviction);
        }
        c.report.add("comparisons=" + c.comparisons + " maxWeight=" + c.maxWeight + " maxE=" + c.maxE);
        c.report.add("failures=" + c.failures + "\n" + c.world.stats()); c.save();
        System.out.println("S4 STREAM " + (c.failures == 0 ? "PASS" : "FAIL") + " failures=" + c.failures);
        if (c.failures > 0) System.exit(1);
    }

    private void order() {
        List<Point> points = new ArrayList<>();
        for (int[] t : new int[][]{{-1,-1},{0,0},{-3,-2},{-2,-3}})
            for (int i = 0; i < 5; i++) points.add(new Point(t[0]*1024+96+i*181, t[1]*1024+144+i*151));
        List<CoherentMorphWorld.Pair> baseline = new ArrayList<>();
        for (Point p : points) baseline.add(world.sample(p.x(), p.z()));
        for (int i = points.size()-1; i >= 0; i--) {
            Point p = points.get(i); compare("warm-reverse", p, baseline.get(i), world.sample(p.x(),p.z()), true);
        }
        var cold = new CoherentMorphWorld(); List<Integer> order = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) order.add(i);
        Collections.shuffle(order, new Random(403120240));
        for (int i : order) {
            Point p = points.get(i); compare("cold-shuffled", p, baseline.get(i), cold.sample(p.x(),p.z()), true);
        }
        report.add("order points=" + points.size() + " reverse+independent cold shuffled exact");
    }

    private void seams() {
        int mixed = 0;
        for (int axis = 0; axis < 2; axis++) for (double line : new double[]{-1024,0,1024})
            for (double along : new double[]{-1024,-511,-.01,0,.01,511,1024}) {
                Point p = axis == 0 ? new Point(line,along) : new Point(along,line);
                var a = world.tileAt(p.x()-(axis==0?.5:0),p.z()-(axis==1?.5:0));
                var b = world.tileAt(p.x()+(axis==0?.5:0),p.z()+(axis==1?.5:0));
                var pa = world.sample(a,p.x(),p.z()); var pb = world.sample(b,p.x(),p.z());
                compare("forced-axis-"+axis,p,pa,pb,false);
                if (FullMorphLand.activeCount(pa.after())>1) mixed++;
            }
        require(mixed>0,"seam samples all pure"); report.add("seams mixed="+mixed);
    }

    private void window() {
        for (int[] t : new int[][]{{0,0},{-3,-2}}) {
            var tile = world.tileAt((t[0]+.5)*1024,(t[1]+.5)*1024);
            var g = LocalBoundaryGeometry.of(new RecipeMorphCatalog(RegionalMorphGallery.SEED).original(),
                    tile.original().centerX()+128,tile.original().centerZ()-96,900,4,.02,600); g.setMaxSeg(2);
            int[] recipes = new int[g.sites().size()];
            for (var s : g.sites()) recipes[s.index()] = world.recipe(s);
            var alternate = new CoherentMorphWorld.Tile(FullMorphLand.wrap(
                    new RecipeMorphCatalog(RegionalMorphGallery.SEED),g),recipes,new RecipeBoundaryWeights(g,recipes));
            for (int i=0;i<7;i++) {
                Point p = new Point(tile.original().centerX()-250+i*75,tile.original().centerZ()-150+i*40);
                compare("shifted-radius900",p,world.sample(tile,p.x(),p.z()),world.sample(alternate,p.x(),p.z()),false);
            }
            int mixed=0;
            for(var edge:tile.original().geometry().edges()) {
                if(tile.recipes()[edge.a.index()]==tile.recipes()[edge.b.index()])continue;
                int k=edge.wx.length/2;Point p=new Point(edge.wx[k],edge.wz[k]);
                if(Math.hypot(p.x()-tile.original().centerX(),p.z()-tile.original().centerZ())>650
                        ||Math.hypot(p.x()-g.centerX(),p.z()-g.centerZ())>800)continue;
                var a=world.sample(tile,p.x(),p.z());var b=world.sample(alternate,p.x(),p.z());
                if(FullMorphLand.activeCount(a.after())<=1)continue;
                compare("shifted-mixed-boundary",p,a,b,false);if(++mixed>=4)break;
            }
            require(mixed>0,"shifted window has no mixed boundary sample");
            report.add("shifted window mixed boundary samples="+mixed);
        }
    }

    private void tileEviction() throws Exception {
        Point p = new Point(128,256); var tile = world.tileAt(p.x(),p.z()); var before = world.sample(p.x(),p.z());
        for (int i=0;i<10;i++) world.tileAt((i-5+.5)*1024,2.5*1024);
        Map<?,?> cache = map(world,"tiles"); require(cache.size()<=8,"S3 tile cache exceeded8");
        require(!cache.containsValue(tile),"target tile not actually evicted");
        var afterTile = world.tileAt(p.x(),p.z()); require(tile!=afterTile,"same tile object after eviction");
        compare("tile-rebuilt",p,before,world.sample(p.x(),p.z()),true);
        report.add("tile cache target evicted and rebuilt; size="+cache.size()+" originalLand="+world.evaluator().originalLand().stats());
    }

    private void identityEviction() throws Exception {
        var cell = cell(-3,-4); int before = world.recipe(cell);
        Map<?,?> ids = map(world,"identities"); var base = new CoherentRegionDistribution(RegionalMorphGallery.SEED);
        int inserted=0;
        for (int z=100;z<200 && inserted<2200;z++) for (int x=100;x<200 && inserted<2200;x++) {
            var q=cell(x,z); if (base.choose(q).recipe()>=18) continue;
            world.recipe(q);inserted++;
        }
        require(inserted==2200,"insufficient real land identities"); require(ids.size()<=2048,"identity cache exceeded2048");
        require(!ids.containsKey(cell.key()),"identity target not evicted");
        require(world.recipe(cell)==before,"identity reconstruction changed final recipe");
        report.add("identity eviction real base-land fills="+inserted+" target="+cell.key()+" recipe="+before+" size="+ids.size());
    }

    private void patchEviction() throws Exception {
        var matcher = (ShallowRegionMatcher) field(world,"matcher"); var patches = map(matcher,"patches");
        var target=cell(-3,-4); var before=matcher.match(target);
        require(before.before()>=18 && before.before()!=22,"patch target must exercise sea geometry");
        var initial=new ArrayList<>(patches.values()); long builds=matcher.builds();
        var base=new CoherentRegionDistribution(RegionalMorphGallery.SEED); int probed=0;
        for (int z=-20;z<=20 && matcher.builds()-builds<150;z+=4)
            for(int x=-20;x<=20 && matcher.builds()-builds<150;x+=4) {
                var q=cell(x,z); int recipe=base.choose(q).recipe();
                if(recipe<18 || recipe==22)continue;
                matcher.match(q);probed++;
            }
        require(matcher.builds()-builds>=150,"patch test did not exceed capacity");
        require(patches.size()<=128,"patch cache exceeded128");
        boolean removed=initial.stream().anyMatch(p->!patches.containsValue(p)); require(removed,"no patch object evicted");
        long pre=matcher.builds(); var after=matcher.match(target);
        require(matcher.builds()>pre,"target query did not rebuild patches");
        require(before.equals(after),"patch reconstruction changed match: "+before+" -> "+after);
        report.add("patch eviction real sea queries="+probed+" buildsSinceStart="+(matcher.builds()-builds)
                +" rebuiltTarget="+(matcher.builds()-pre)+" capacity="+patches.size()+" match="+after);
    }

    private static LocalBoundaryGeometry.Cell cell(int x,int z) {
        double[] p=CoherentRegionDistribution.site(CoherentRegionDistribution.base(RegionalMorphGallery.SEED)+23,x,z);
        return new LocalBoundaryGeometry.Cell(x,z,p[0],p[1],0);
    }
    private void compare(String stage,Point p,CoherentMorphWorld.Pair a,CoherentMorphWorld.Pair b,boolean exact) {
        double dw=0;for(int i=0;i<a.after().length;i++)dw=Math.max(dw,Math.abs(a.after()[i]-b.after()[i]));
        double de=Math.abs(a.afterE()-b.afterE()); boolean bits=Arrays.equals(a.after(),b.after())
                && Double.doubleToLongBits(a.afterE())==Double.doubleToLongBits(b.afterE());
        boolean ok=a.winner()==b.winner() && (exact?bits:dw<=2e-3&&de<=2e-3);
        rows.add(stage+","+p.x()+","+p.z()+","+a.winner()+","+b.winner()+","+dw+","+de+","+bits+","+ok);
        comparisons++;maxWeight=Math.max(maxWeight,dw);maxE=Math.max(maxE,de);
        if(!ok){failures++;errors.add("MISMATCH "+rows.get(rows.size()-1)+"\nA="+Arrays.toString(a.after())+"\nB="+Arrays.toString(b.after()));}
    }
    private void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    private interface Stage{void run()throws Exception;}
    private void stage(String name,Stage body)throws Exception {
        int before=failures;
        try{body.run();String status=failures==before?" PASS":" FAIL comparisons";report.add(name+status);System.out.println(name+status);}
        catch(Throwable e){failures++;errors.add(name+" "+e);report.add(name+" FAIL "+e);System.out.println(name+" FAIL "+e);}
        save();
    }
    private void save()throws Exception{Files.write(out.resolve("stream.txt"),report);Files.write(out.resolve("samples.csv"),rows);Files.write(out.resolve("failures.txt"),errors);}
    private static Object field(Object obj,String name)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private static Map<?,?> map(Object obj,String name)throws Exception{return(Map<?,?>)field(obj,name);}
    private record Point(double x,double z){}
}
