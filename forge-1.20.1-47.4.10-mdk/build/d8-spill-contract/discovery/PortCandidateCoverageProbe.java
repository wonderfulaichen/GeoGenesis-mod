package com.geogenesis.worldgen.hydrology.model;

import java.nio.file.*;
import java.util.*;

/** Geometric candidate coverage, deliberately separate from reachable/admitted rivers. */
public final class PortCandidateCoverageProbe {
    static final int REGION=512,HALO=96,STEP=2,N=353;
    static int checks;
    static long predicates,patches;
    record Owner(int x,int z) { }
    record Bounds(int minX,int maxX,int minZ,int maxZ) { }
    record Candidates(SortedMap<String,Set<Integer>> witnesses,Set<Owner> owners) { }
    interface Terrain { double height(int x,int z); }
    static void check(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); checks++; }
    static int lower(int receiver) {
        long numerator=receiver*(long)REGION-(REGION+HALO+STEP);
        return Math.toIntExact(-Math.floorDiv(-numerator,REGION));
    }
    static int upper(int receiver) {
        long high=(receiver+1L)*REGION-1;
        return Math.toIntExact(Math.floorDiv(high+HALO+STEP,REGION));
    }
    static Bounds bounds(Owner receiver) {
        return new Bounds(lower(receiver.x),upper(receiver.x),lower(receiver.z),upper(receiver.z));
    }
    static boolean possible(Owner sender,Owner receiver) {
        long loX=sender.x*(long)REGION-HALO-STEP,hiX=sender.x*(long)REGION+REGION+HALO+STEP;
        long loZ=sender.z*(long)REGION-HALO-STEP,hiZ=sender.z*(long)REGION+REGION+HALO+STEP;
        long rx=receiver.x*(long)REGION,rz=receiver.z*(long)REGION;
        return loX<=rx+REGION-1 && hiX>=rx && loZ<=rz+REGION-1 && hiZ>=rz;
    }
    static Set<Integer> perimeter(boolean exhaustive) {
        Set<Integer> result=new TreeSet<>();
        if(exhaustive) {
            for(int c=0;c<N*N;c++)result.add(c);
        } else {
            for(int j=0;j<N;j++) { result.add(j); result.add((N-1)*N+j); }
            for(int i=1;i<N-1;i++) { result.add(i*N); result.add(i*N+N-1); }
        }
        return result;
    }
    static Candidates enumerate(Owner receiver,Terrain terrain,boolean exhaustive) {
        Bounds b=bounds(receiver); Set<Owner> owners=new HashSet<>();
        SortedMap<String,Set<Integer>> result=new TreeMap<>(); Set<Integer> cells=perimeter(exhaustive);
        for(int sx=b.minX;sx<=b.maxX;sx++)for(int sz=b.minZ;sz<=b.maxZ;sz++) {
            Owner sender=new Owner(sx,sz); if(sender.equals(receiver))continue;
            owners.add(sender); int ox=sx*REGION-HALO,oz=sz*REGION-HALO;
            double[] bed=new double[N*N];
            for(int x=0;x<N;x++)for(int z=0;z<N;z++)bed[x*N+z]=terrain.height(ox+x*STEP,oz+z*STEP);
            var outside=new DeterministicSkeleton.OutsideContinuation(terrain::height,ox,oz,STEP,2048,-100000);
            for(int c:cells)for(int h=0;h<ReverseOrdinaryProbe.DIR.length;h++) {
                predicates++;
                int[] d=ReverseOrdinaryProbe.DIR[h]; double len=Math.hypot(d[0],d[1]);
                if(c/N==0 || c%N==0 || c/N==N-1 || c%N==N-1)patches++;
                BoundaryPort raw=PortEgress.egress(c,bed,N,N,len==0?0:d[0]/len,len==0?0:d[1]/len,
                        DeterministicSkeleton.GRADIENT_SPAN,outside);
                if(raw==null)continue;
                BoundaryPort p=raw.stamp(sx,sz,REGION);
                if(p.receiverRegionX()!=receiver.x || p.receiverRegionZ()!=receiver.z)continue;
                result.computeIfAbsent(p.key(),unused->new TreeSet<>()).add(h);
            }
        }
        return new Candidates(result,Set.copyOf(owners));
    }
    static String cover(int first) throws Exception {
        var fixture=new MultiRegionDiscoveryProbe(first);
        var a=new MultiRegionDiscoveryProbe.Region(first,0);
        var b=new MultiRegionDiscoveryProbe.Region(first+1,0);
        var c=new MultiRegionDiscoveryProbe.Region(first+2,0);
        var receiver=new Owner(c.x(),c.z());
        Candidates candidates=enumerate(receiver,fixture::height,false);
        Candidates oracle=enumerate(receiver,fixture::height,true);
        check(!candidates.witnesses.isEmpty(),"candidate fixture nonempty");
        check(candidates.equals(oracle),"perimeter candidates equal exhaustive all-cell egress oracle");
        var upstream=fixture.primary(a); var frame=fixture.frame(b); List<WireIn> incoming=new ArrayList<>();
        for(var raw:upstream.ports) {
            var p=raw.stamp(a.x(),a.z(),REGION);
            incoming.add(new WireIn(frame.cell(p.anchorX(),p.anchorZ()),frame.cell(p.nextX(),p.nextZ()),
                    p.dirX(),p.dirZ(),p.anchorX(),p.anchorZ(),p.nextX(),p.nextZ()));
        }
        var relay=fixture.build(b,incoming,fixture.primary(b));
        check(relay.ports.size()==1 && relay.pendingExits.size()==1,"fixture has actual unconsumed second-hop port");
        for(var raw:relay.ports)check(candidates.witnesses.containsKey(raw.stamp(b.x(),b.z(),REGION).key()),
                "independent candidates contain actual second-hop port absent from pass1");
        check(fixture.actualIncoming(c).isEmpty(),"original assembler still misses second-hop source");
        check(fixture.finals.isEmpty(),"candidate enumeration must not consume any final skeleton");
        var noSource=new MultiRegionDiscoveryProbe(first);
        check(noSource.primary(b).ports.isEmpty() && !candidates.witnesses.isEmpty(),
                "possible port is not proof of an admitted river");
        return "first="+first+" senders="+candidates.owners.size()+" candidateEdges="+candidates.witnesses.size()
                +" actualSecondHopIncluded=true pass1Incoming=0 finalReads=0\n";
    }
    static String ownerCoverage() {
        for(int r:new int[]{-58591,-4,-1,0,1,58590}) {
            Owner receiver=new Owner(r,r); Bounds b=bounds(receiver);
            for(int sx=r-3;sx<=r+3;sx++)for(int sz=r-3;sz<=r+3;sz++) {
                Owner sender=new Owner(sx,sz);
                boolean derived=sx>=b.minX && sx<=b.maxX && sz>=b.minZ && sz<=b.maxZ;
                check(derived==possible(sender,receiver),"derived owner range matches interval-intersection oracle");
            }
        }
        return "ownerRangeDerived=true coordinateIntervalsChecked=294\n";
    }
    public static void main(String[] args) throws Exception {
        String result=ownerCoverage()+cover(0)+cover(-4)+"CANDIDATE COVERAGE PASS checks="+checks
                +" predicates="+predicates+" widenedPatches="+patches+"\n"
                +"NOT_DISCOVERY: potential ordinary ports only; history, source reachability, spill, complete pins and production resource policy uncertified.\n";
        Files.writeString(Path.of(args[0]),result); System.out.print(result);
    }
}
