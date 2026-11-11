package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;

/** Checks the passage itself rather than reusing the hydraulic decision as its oracle. */
public final class WorldChannelGeometryProbe {
    private static int failures;
    public static void main(String[] args) {
        double[][] h = {
                {176.37039979,177.87876650,179.41231234,181.26490767,183.13869415},
                {175.30891478,176.59536029,177.90528529,179.96099968,182.03663777},
                {174.26132258,175.32420328,176.40891557,178.66654283,180.94285886},
                {176.49987226,176.44635363,176.41357759,177.54676537,178.69766076},
                {178.74450271,177.57666543,176.42852171,176.43740651,176.46304038}};
        WindowOutlets.Terrain t=(x,z)->h[(int)z][(int)x];
        var a=new Point(4,4);var b=new Point(0,0);
        var geometry=new WorldChannelGeometry(4,t);var p=geometry.between(a,b);
        check(p.crest()==h[4][4],"real failure cell has a descending subcell passage, not a 177.546765 dam");
        check(p.path().get(0).equals(a) && p.path().get(p.path().size()-1).equals(b),"exact shared endpoints retained");
        double crest=Double.NEGATIVE_INFINITY;boolean connected=true;
        for(int i=0;i<p.path().size();i++) {
            var n=p.path().get(i);crest=Math.max(crest,t.height(n.x(),n.z()));
            if(i>0){var old=p.path().get(i-1);connected&=Math.abs(old.x()-n.x())+Math.abs(old.z()-n.z())==1;}
        }
        check(crest==p.crest() && connected,"independent path audit sees every face-connected column and its true crest");
        var reverse=new ArrayList<>(geometry.between(b,a).path());Collections.reverse(reverse);
        check(reverse.equals(p.path()),"reverse query returns the exact same physical passage");
        var shifted=new WorldChannelGeometry(4,(x,z)->t.height(x+8,z+12)).between(new Point(-4,-8),new Point(-8,-12));
        check(shifted.crest()==p.crest() && shifted.path().size()==p.path().size(),"negative translated passage preserves crest and length");
        var flat=new WorldChannelGeometry(4,(x,z)->0);
        var axis=flat.between(new Point(0,0),new Point(4,0));
        check(axis.crest()==0 && axis.path().size()==5,"axis has its direct shortest face-connected route on a plane");
        var wall=new WorldChannelGeometry(4,(x,z)->x==2?20:0).between(new Point(0,0),new Point(4,4));
        check(wall.crest()==20,"no path crosses an actual full-cell wall below its crest");
        int[] counterexample={2,1,1,4,7,4,7,2,4,4,2,3,3,5,5,1,4,7,2,2,2,2,3,1,7};
        var shortest=new WorldChannelGeometry(4,(x,z)->counterexample[(int)x*5+(int)z])
                .between(new Point(0,0),new Point(4,4));
        check(shortest.crest()==7 && shortest.path().size()==9,
                "later bottleneck preserves the shorter prefix: eight hops, not twelve");
        System.out.println("WorldChannelGeometryProbe failures="+failures);
        System.exit(failures==0?0:1);
    }
    private static void check(boolean ok,String message){System.out.println((ok?"PASS ":"FAIL ")+message);if(!ok)failures++;}
}
