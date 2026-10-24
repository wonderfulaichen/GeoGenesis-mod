package com.geogenesis.worldgen.hydrology.model;
import java.util.*;

/** Only the nearest same-basin shore suffix on the existing drainage tree. */
final class OutletLakePrefix {
    static List<DeterministicSkeleton.Shaped> attach(List<DeterministicSkeleton.Trace> traces,
            List<DeterministicSkeleton.Shaped> shaped, HydrologyPipeline.Result p,
            int ox, int oz, int step) {
        var lakes=p.lakeResult(); if(lakes==null)return shaped;
        var out=new ArrayList<>(shaped);
        for(int i=0;i<traces.size();i++) {
            var t=traces.get(i);var poly=shaped.get(i);
            if(!t.fromOutlet || poly.size()<2)continue;
            int b=DeterministicSkeleton.sourceBasinOf(lakes,t.sourceCell());
            if(b<0 || !lakes.basins().get(b).overflow())continue;
            int[] prefix=path(lakes,p.parent(),p.bed(),b,t.sourceCell(),p.nx(),p.nz());
            if(prefix.length<2)continue;
            double[] x=new double[poly.size()+prefix.length-1],z=new double[x.length];
            for(int j=0;j<prefix.length-1;j++) {
                x[j]=ox+prefix[j]/p.nz()*(double)step;z[j]=oz+prefix[j]%p.nz()*(double)step;
            }
            System.arraycopy(poly.x,0,x,prefix.length-1,poly.size());
            System.arraycopy(poly.z,0,z,prefix.length-1,poly.size());
            out.set(i,new DeterministicSkeleton.Shaped(x,z));
        }
        return out;
    }

    static int[] path(LakeWaterBalance.Result lakes,int[] parent,double[] bed,
                      int basin,int outlet,int nx,int nz) {
        var queue=new ArrayDeque<Integer>();var seen=new HashSet<Integer>();
        queue.add(outlet);seen.add(outlet);
        double wetLevel=lakes.basins().get(basin).level()-0.5;
        while(!queue.isEmpty()) {
            int target=queue.removeFirst();int best=-1;
            for(int di=-1;di<=1;di++)for(int dj=-1;dj<=1;dj++) {
                int x=target/nz+di,z=target%nz+dj;
                if(x<0 || z<0 || x>=nx || z>=nz)continue;
                int c=x*nz+z;
                if(parent[c]!=target || LakeWaterBalance.basinOf(lakes,c)!=basin || !seen.add(c))continue;
                if(lakes.lake()[c] && bed[c]<wetLevel) {if(best<0 || c<best)best=c;}
                queue.addLast(c);
            }
            if(best>=0)return forward(parent,best,outlet);
        }
        return new int[0];
    }

    private static int[] forward(int[] parent,int start,int outlet) {
        var cells=new ArrayList<Integer>();
        for(int c=start;;c=parent[c]) {
            cells.add(c);if(c==outlet)break;
        }
        return cells.stream().mapToInt(Integer::intValue).toArray();
    }
}
