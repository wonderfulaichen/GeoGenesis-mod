package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.SharedDownstreamSearch.Graph;
import com.geogenesis.standalone.SharedDownstreamSearch.Node;
import com.geogenesis.standalone.SharedDownstreamSearch.Settings;
import java.util.List;
import static com.geogenesis.standalone.SharedDescentGraph.require;

/** Synthetic checks only; never constructs or searches the frozen real graph. */
public final class SharedDownstreamSearchCheck {
    private SharedDownstreamSearchCheck() {}
    private static Node node(HeightField f, int id, double x, double z, int next, String state) {
        return new Node(id,x,z,f.height(x,z),next,state);
    }
    private static void curved() {
        HeightField f = (x,z)->-x+.6*Math.pow(z-2*Math.sin(Math.PI*x/4),2);
        Graph g = new Graph(List.of(node(f,0,0,0,-1,"BOUND_NO_DESCENT"),
                node(f,1,4,0,2,"DESCENT"),node(f,2,8,0,-1,"BOUND_NO_DESCENT")));
        require(!SharedDescentGraph.validate(f,0,0,4,0,.03125,0).accepted(),"straight shortcut fails");
        var a = SharedDownstreamSearch.search(g,0,f,f,new Settings());
        var b = SharedDownstreamSearch.search(g,0,f,f,new Settings());
        require(a.success() && a.equals(b) && a.points().size() > 2,"curved alternate and deterministic");
        require(a.target() == 1 && a.points().get(a.points().size()-1).equals(new Point(4,0)),"exact target");
        Node canonical = g.node(1); g.connect(0,a.target());
        require(g.node(g.next(0)) == canonical && g.next(1) == 2 && g.root(0) == 2,"same stored suffix");
        require(g.component(0) == g.component(2) && !g.eligible(0,1),"updated DSU, unresolved suffix valid");
        require(g.node(g.root(0)).state().equals("BOUND_NO_DESCENT"),"join is not boundary escape");
    }
    private static void refusal() {
        HeightField ridge = (x,z)->-x+20*Math.exp(-Math.pow((x-2)/.3,2));
        for (HeightField f : List.<HeightField>of(ridge,(x,z)->x*x+z*z,(x,z)->1)) {
            Graph g = new Graph(List.of(node(f,0,0,0,-1,"BOUND_NO_DESCENT"),
                    node(f,1,4,0,-1,"DOMAIN_BOUNDARY")));
            var r = SharedDownstreamSearch.search(g,0,f,f,new Settings());
            require(!r.success() && r.points().isEmpty() && r.reason().endsWith("UNKNOWN"),"no fake basin/outlet");
            require(g.next(0) == -1 && g.node(1).state().equals("DOMAIN_BOUNDARY"),"no graph mutation");
        }
    }
    private static void finerAndBudgets() {
        HeightField coarse = (x,z)->-x;
        HeightField fine = (x,z)->-x+2*Math.exp(-Math.pow((x-.22)/.015,2))*Math.exp(-z*z/.01);
        Graph g = new Graph(List.of(node(coarse,0,0,0,-1,"BOUND_NO_DESCENT"),
                node(coarse,1,4,0,-1,"DOMAIN_BOUNDARY")));
        var r = SharedDownstreamSearch.search(g,0,coarse,fine,new Settings());
        require(r.success() && r.finerRejects() > 0 && r.restarts() > 0,"finer edge rejection and alternate route");
        require(r.points().get(r.points().size()-1).equals(new Point(4,0)),"never truncated after fine rejection");
        var budget = SharedDownstreamSearch.search(g,0,coarse,coarse,new Settings(.5,12,32,1));
        require(budget.budget() && budget.explored() == 1 && budget.points().isEmpty(),"bounded states");
        var shortPath = SharedDownstreamSearch.search(g,0,coarse,coarse,new Settings(.5,12,1,4096));
        require(!shortPath.success() && shortPath.lengthRejects() > 0,"path length bound");
        var radius = SharedDownstreamSearch.search(g,0,coarse,coarse,new Settings(.5,2,32,4096));
        require(!radius.success() && radius.eligibleNodesInDisk() == 0 && radius.callbacks() == 0
                && radius.reason().equals("NO_ELIGIBLE_UNKNOWN"),"disk inventory without H0 callbacks");
        require(shortPath.eligibleNodesInDisk() == 1
                && shortPath.reason().equals("NO_MONOTONE_ROUTE_UNKNOWN"),"eligible but no bounded route");
        require(budget.reason().equals("STATE_BUDGET_UNKNOWN"),"state budget distinct from restart budget");
    }
    private static void changedRoots() {
        HeightField f = (x,z)->-x;
        Graph g = new Graph(List.of(node(f,0,0,0,-1,"BOUND_NO_DESCENT"),
                node(f,1,4,0,-1,"BOUND_NO_DESCENT"),node(f,2,8,0,-1,"DOMAIN_BOUNDARY")));
        g.connect(0,1); g.connect(1,2);
        require(g.root(0) == 2 && g.component(0) == g.component(2),"changed root shared suffix");
        boolean rejected = false;
        try { g.connect(2,0); } catch (AssertionError expected) { rejected = true; }
        require(rejected && g.next(2) == -1,"cycle/boundary refused");
    }
    public static void main(String[] args) {
        curved(); refusal(); finerAndBudgets(); changedRoots();
        System.out.println("PASS: curved descent, ridge refusal, bowl/flat UNKNOWN, deterministic, exact suffix, finer reroute, bounds, DSU/DAG");
    }
}
