package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/** 独立三点地形反例：出窗后再入窗，必须保持真实行进顺序。 */
public final class OutsidePathOrderProbe {
    public static void main(String[] args) throws Exception {
        int n=5, start=4*n+1, mouth=4*n+3;
        boolean[] lake=new boolean[n*n],sea=new boolean[n*n]; sea[mouth]=true;
        var ctx=new DeterministicSkeleton.OutsideContinuation((x,z)->
                x==4&&z==1 ? 10 : x==5&&z==2 ? 9 : x==4&&z==3 ? 8 : 100,
                0,0,1,8,0);
        var method=DeterministicSkeleton.class.getDeclaredMethod("continueOutside",int.class,double[].class,
                boolean[].class,boolean[].class,int.class,int.class,DeterministicSkeleton.OutsideContinuation.class);
        method.setAccessible(true);
        Object result=method.invoke(null,start,new double[n*n],lake,sea,n,n,ctx);
        if(result==null)throw new AssertionError("three-point terrain did not reach sea");
        var p=result.getClass().getDeclaredMethod("path");p.setAccessible(true);
        var t=result.getClass().getDeclaredMethod("outsideTail");t.setAccessible(true);
        int[] cells=(int[])p.invoke(result);double[] tail=(double[])t.invoke(result);
        System.out.println("cells="+Arrays.toString(cells)+" tail="+Arrays.toString(tail));
        if(!Arrays.equals(cells,new int[]{mouth}) || !Arrays.equals(tail,new double[]{5,2,4,3}))
            throw new AssertionError("outside reentry reordered real path");
        var prefix=result.getClass().getDeclaredMethod("prefixNodes");prefix.setAccessible(true);
        if((int)prefix.invoke(result)!=0)throw new AssertionError("outside prefix includes reentry");
        var trace=new DeterministicSkeleton.Trace(new int[]{start,mouth},DeterministicSkeleton.End.MOUTH_SEA,
                false,tail,new double[0],1);
        var segments=new java.util.ArrayList<int[]>();
        DeterministicSkeleton.registerSegments(trace,n,ctx,segments);
        if(segments.size()!=2 || !Arrays.equals(segments.get(0),new int[]{4,1,5,2})
                || !Arrays.equals(segments.get(1),new int[]{5,2,4,3}))
            throw new AssertionError("crossing guard uses reordered ghost chord");
        var params=new DeterministicSkeleton.Params(0,0,0,0,24,0,2,0,0);
        var shape=DeterministicSkeleton.shape(java.util.List.of(trace),new double[n*n],0,0,1,n,n,params,null).get(0);
        int outside=-1,reentry=-1;
        for(int i=0;i<shape.size();i++) {
            if(shape.x[i]==5&&shape.z[i]==2&&outside<0)outside=i;
            if(shape.x[i]==4&&shape.z[i]==3&&reentry<0)reentry=i;
        }
        if(outside<0||reentry<=outside)throw new AssertionError("final shape reorders outside/reentry anchors");
        var single=new DeterministicSkeleton.Trace(new int[]{start},DeterministicSkeleton.End.MOUTH_SEA,false,new double[]{5,2});
        if(DeterministicSkeleton.shape(java.util.List.of(single),new double[n*n],0,0,1,n,n,params,null).get(0).size()<2)
            throw new AssertionError("single outside point discarded");
        System.out.println("PASS ordered exit/reentry with real sea terminal and final shape");
    }
}
