package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.concurrent.CancellationException;

/** A storage-rooted tree with wet-first attachment and one fixed outlet trunk. */
final class BasinStorageRouting {
    static Map<Point,Point> build(List<Point> members, Point lip, Point outer, double sill,
                                  ToDoubleFunction<Point> height, Function<Point,List<Point>> neighbours,
                                  BiFunction<Point,Point,Double> crest) {
        var inside=new HashSet<>(members);
        Point bottom=members.stream().min(Comparator.comparingDouble(height)
                .thenComparingInt(Point::x).thenComparingInt(Point::z)).orElseThrow();
        var parents=new HashMap<Point,Point>();var queue=new ArrayDeque<Point>();
        parents.put(lip,outer);queue.add(lip);
        while(!queue.isEmpty() && !parents.containsKey(bottom)) {
            cancelled();Point from=queue.remove();
            for(var p:neighbours.apply(from)) if(inside.contains(p) && !parents.containsKey(p)
                    && crest.apply(from,p)<sill) {parents.put(p,from);queue.add(p);}
        }
        if(!parents.containsKey(bottom))throw new IllegalStateException("storage bottom disconnected from lip");
        var next=new LinkedHashMap<Point,Point>();
        for(Point p=bottom;inside.contains(p);p=parents.get(p))next.put(p,parents.get(p));
        // Prefer submerged trunk targets before completing pockets behind the dry trunk.
        queue.clear();
        for(Point p=bottom;inside.contains(p);p=parents.get(p))
            if(height.applyAsDouble(p)<sill-0.5)queue.add(p);
        if(queue.isEmpty())return completeFromLip(inside,lip,outer,sill,neighbours,crest);
        attach(inside,next,queue,sill,neighbours,crest);
        // Only components cut off by the fixed dry trunk may attach after the wet-first flood.
        if(next.size()!=inside.size()) {
            for(Point p=bottom;inside.contains(p);p=parents.get(p))queue.add(p);
            attach(inside,next,queue,sill,neighbours,crest);
        }
        if(next.size()!=inside.size())throw new IllegalStateException("storage tree does not cover basin");
        return Map.copyOf(next);
    }

    private static Map<Point,Point> completeFromLip(Set<Point> inside,Point lip,Point outer,double sill,
                                                    Function<Point,List<Point>> neighbours,
                                                    BiFunction<Point,Point,Double> crest) {
        var next=new LinkedHashMap<Point,Point>();var queue=new ArrayDeque<Point>();
        next.put(lip,outer);queue.add(lip);
        attach(inside,next,queue,sill,neighbours,crest);
        if(next.size()!=inside.size())throw new IllegalStateException("unrepresented shallow storage basin");
        return Map.copyOf(next);
    }

    private static void attach(Set<Point> inside,Map<Point,Point> next,ArrayDeque<Point> queue,double sill,
                               Function<Point,List<Point>> neighbours,BiFunction<Point,Point,Double> crest) {
        while(!queue.isEmpty()) {
            cancelled();Point from=queue.remove();
            for(var p:neighbours.apply(from))if(inside.contains(p) && !next.containsKey(p)
                    && crest.apply(from,p)<sill) {next.put(p,from);queue.add(p);}
        }
    }

    private static void cancelled() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("storage route interrupted");
    }
}
