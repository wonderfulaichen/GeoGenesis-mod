package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Independent fixtures for subcell lake connectivity. Failures are process failures. */
public final class WorldLakeSurfaceProbe {
    private static int failures;
    public static void main(String[] args) {
        var water = corners(0, 0);
        water.put(new Point(4, 0), lake(new Point(4, 0), 10));
        var shore = WorldLakeSurface.resolve(0, 0, 4, water, (x,z)->12-x);
        check(Double.isNaN(shore.levelAt(2, 2)) && shore.levelAt(3, 2)==10,
                "shore follows integer terrain at x=3, not the old x=4 membership gate");
        var isolated = WorldLakeSurface.resolve(0, 0, 4, water, (x,z)->x==2?11:0);
        check(Double.isNaN(isolated.levelAt(1, 2)) && isolated.levelAt(3, 2)==10,
                "one-block ridge blocks a disconnected low pocket");
        var cornerOnly = WorldLakeSurface.resolve(0, 0, 4, water,
                (x,z)->(x==4 && z==0)||(x==3 && z==1)?0:11);
        check(Double.isNaN(cornerOnly.levelAt(3, 1)), "diagonal water cannot jump two dry face neighbours");
        var empty=WorldLakeSurface.resolve(0, 0, 4, corners(0,0), (x,z)->0);
        check(java.util.Arrays.stream(empty.levels()).allMatch(Double::isNaN), "no basin cannot fabricate a lake");
        var negative=corners(-4,-4);
        negative.put(new Point(0,-4),lake(new Point(4,0),10));
        var translated=WorldLakeSurface.resolve(-4,-4,4,negative,(x,z)->8-x);
        check(java.util.Arrays.equals(shore.levels(),translated.levels()), "negative translation preserves the entire cell");
        var reversed=new java.util.LinkedHashMap<Point,WorldBasinFlow.Water>();
        water.entrySet().stream().sorted(Map.Entry.<Point,WorldBasinFlow.Water>comparingByKey(
                java.util.Comparator.comparingInt(Point::x).reversed().thenComparingInt(Point::z))).forEach(e->reversed.put(e.getKey(),e.getValue()));
        check(java.util.Arrays.equals(shore.levels(),WorldLakeSurface.resolve(0,0,4,reversed,(x,z)->12-x).levels()),
                "map insertion order does not change wetness");
        var conflict=new HashMap<>(water);conflict.put(new Point(0,0),lake(new Point(-4,0),9));
        check(throwsState(()->WorldLakeSurface.resolve(0,0,4,conflict,(x,z)->0)),
                "connected different basins fail explicitly instead of nearest/highest selection");
        var missing=new HashMap<>(water);missing.remove(new Point(0,4));
        check(throwsState(()->WorldLakeSurface.resolve(0,0,4,missing,(x,z)->0)), "missing corner is not silently dry");
        var unknown=new HashMap<>(water);unknown.put(new Point(0,4),new WorldBasinFlow.Water(
                new DemandFlow.Amount(DemandFlow.Kind.UNKNOWN,Double.NaN,"fixture"),Double.NaN,null,"fixture"));
        check(throwsState(()->WorldLakeSurface.resolve(0,0,4,unknown,(x,z)->0)), "UNKNOWN corner is not silently dry");
        var inconsistent=new HashMap<>(water);inconsistent.put(new Point(4,4),lake(new Point(4,0),9));
        check(throwsState(()->WorldLakeSurface.resolve(0,0,4,inconsistent,(x,z)->0)), "same identity with two levels fails");
        System.out.println("WorldLakeSurfaceProbe failures="+failures);
        System.exit(failures==0?0:1);
    }
    private static Map<Point,WorldBasinFlow.Water> corners(int x,int z) {
        var map=new HashMap<Point,WorldBasinFlow.Water>();
        for(int dx:new int[]{0,4})for(int dz:new int[]{0,4})map.put(new Point(x+dx,z+dz),
                new WorldBasinFlow.Water(new DemandFlow.Amount(DemandFlow.Kind.EXACT,0,""),Double.NaN,null,""));
        return map;
    }
    private static WorldBasinFlow.Water lake(Point identity,double level) {
        var basin=new WorldBasinRouting.Basin(WorldBasinRouting.State.PUBLISHED,WorldBasin.State.UNIQUE_EXIT,
                identity,0,11,List.of(identity),null,Map.of(),List.of(),null,false,"");
        return new WorldBasinFlow.Water(new DemandFlow.Amount(DemandFlow.Kind.EXACT,1,""),level,basin,"");
    }
    private static boolean throwsState(Runnable r) {try {r.run();return false;}catch(IllegalStateException e){return true;}}
    private static void check(boolean ok,String message) {System.out.println((ok?"PASS ":"FAIL ")+message);if(!ok)failures++;}
}
