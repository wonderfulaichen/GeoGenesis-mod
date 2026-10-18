package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.List;

/** Fixed-domain prototype. Candidates come from all drainage edges, not coarse river thresholds. */
final class SharedBoundaryDrainage {
    record Key(int axis, int boundary, int alongTile) {
        Key {
            if (axis != 0 && axis != 1) throw new IllegalArgumentException("axis");
        }
    }
    record ConnectionId(Key key, int innerX, int innerZ, int outerX, int outerZ) { }
    record Connection(ConnectionId id, ProductionDrainageAudit.Point point,
                      ProductionDrainageAudit.Point tangent, double escapeLevel,
                      ProductionDrainageAudit.Region upstream, ProductionDrainageAudit.Region downstream) { }
    enum Status { CONNECTED, NO_DIRECTED_CROSSING, OUTSIDE_DOMAIN, CYCLE }
    record Mapping(Status status, Connection connection, int steps) { }

    static final class Domain {
        private final Key key;
        private final int ox, oz, n, step, tract;
        private final int[] parent;
        private final double[] escape;
        private final List<Connection> connections;

        Domain(Key key, int ox, int oz, int n, int step, int tract, int[] parent, double[] escape) {
            this.key = key;
            this.ox = ox;
            this.oz = oz;
            this.n = n;
            this.step = step;
            this.tract = tract;
            this.parent = parent.clone();
            this.escape = escape.clone();
            this.connections = enumerate();
            assertAcyclic();
        }

        List<Connection> connections() { return connections; }
        Key key() { return key; }
        int cells() { return n * n; }

        double escapeAt(int x, int z) {
            return escape[Math.floorDiv(x - ox, step) * n + Math.floorDiv(z - oz, step)];
        }

        ProductionDrainageAudit.Point downstreamAt(int x, int z) {
            int i = Math.floorDiv(x - ox, step), j = Math.floorDiv(z - oz, step);
            if (i < 0 || j < 0 || i >= n || j >= n || (x - ox) % step != 0 || (z - oz) % step != 0)
                throw new IllegalArgumentException("point outside shared grid");
            int p = parent[i * n + j];
            return p < 0 ? null : point(p);
        }

        Mapping map(ProductionDrainageAudit.Crossing event) {
            double x = event.point().x() - 2 * step * event.tangent().x();
            double z = event.point().z() - 2 * step * event.tangent().z();
            int i = (int) Math.round((x - ox) / step), j = (int) Math.round((z - oz) / step);
            if (i < 0 || j < 0 || i >= n || j >= n)
                return new Mapping(Status.OUTSIDE_DOMAIN, null, 0);
            int k = i * n + j;
            for (int count = 0; count < parent.length; count++) {
                int p = parent[k];
                if (p < 0) return new Mapping(Status.NO_DIRECTED_CROSSING, null, count);
                Connection connection = connection(k, p);
                if (connection != null && connection.upstream().equals(event.upstream())
                        && connection.downstream().equals(event.downstream()))
                    return new Mapping(Status.CONNECTED, connection, count + 1);
                k = p;
            }
            return new Mapping(Status.CYCLE, null, parent.length);
        }

        private List<Connection> enumerate() {
            List<Connection> result = new ArrayList<>();
            for (int k = 0; k < parent.length; k++) {
                if (parent[k] < 0) continue;
                Connection connection = connection(k, parent[k]);
                if (connection != null) result.add(connection);
            }
            return List.copyOf(result);
        }

        private Connection connection(int k, int p) {
            var a = point(k);
            var b = point(p);
            var id = new ProductionDrainageAudit.SegmentId(new ProductionDrainageAudit.Region(0, 0), 0);
            for (var cross : ProductionDrainageAudit.crossings(id, a, b, tract)) {
                double along = key.axis == 0 ? cross.point().z() : cross.point().x();
                if (cross.axis() != key.axis || cross.boundary() != key.boundary
                        || Math.floor(along / tract) != key.alongTile) continue;
                return new Connection(new ConnectionId(key, (int) a.x(), (int) a.z(),
                        (int) b.x(), (int) b.z()), cross.point(), cross.tangent(),
                        Math.max(escape[k], escape[p]), cross.upstream(), cross.downstream());
            }
            return null;
        }

        private ProductionDrainageAudit.Point point(int cell) {
            return new ProductionDrainageAudit.Point(ox + (cell / n) * (double) step,
                    oz + (cell % n) * (double) step);
        }

        private void assertAcyclic() {
            byte[] state = new byte[parent.length];
            int[] path = new int[parent.length];
            for (int start = 0; start < parent.length; start++) {
                if (state[start] == 2) continue;
                int k = start, length = 0;
                while (k >= 0 && state[k] == 0) {
                    state[k] = 1;
                    path[length++] = k;
                    int p = parent[k];
                    if (p >= parent.length || p < -1) throw new IllegalArgumentException("parent outside domain");
                    if (p >= 0 && escape[p] > escape[k] + 1e-9)
                        throw new IllegalArgumentException("uphill escape edge");
                    k = p;
                }
                if (k >= 0 && state[k] == 1) throw new IllegalArgumentException("drainage cycle");
                while (length > 0) state[path[--length]] = 2;
            }
        }
    }

    enum ClaimState { AGREED_EDGE, DIRECTION_CONFLICT, INPUT_PENDING }
    record DirectionClaim(ProductionDrainageAudit.Region owner, WorldCell point, WorldCell next) { }
    record ClaimAgreement(WorldCell point, WorldCell next, ClaimState state, List<DirectionClaim> claims) {
        ClaimAgreement { claims = List.copyOf(claims); }
    }

    /** Diagnostic equality only: agreement between local windows is NOT global direction or a water certificate. */
    static List<ClaimAgreement> agreeClaims(List<ProductionDrainageAudit.Region> owners, List<DirectionClaim> claims) {
        if (owners.size() != 2 || owners.get(0).equals(owners.get(1)) || claims.size() > 131072)
            throw new IllegalArgumentException("two distinct owners and bounded claims required");
        var order = java.util.Comparator.comparingInt(WorldCell::x).thenComparingInt(WorldCell::z);
        var grouped = new java.util.TreeMap<WorldCell, java.util.Map<ProductionDrainageAudit.Region, DirectionClaim>>(order);
        for (var claim : claims) {
            checkClaimCancellation();
            if (!owners.contains(claim.owner)) throw new IllegalArgumentException("foreign claim owner");
            var at = grouped.computeIfAbsent(claim.point, ignored -> new java.util.HashMap<>());
            var old = at.putIfAbsent(claim.owner, claim);
            if (old != null && !old.equals(claim)) throw new IllegalArgumentException("same owner has conflicting declarations");
        }
        var ownerOrder = java.util.Comparator.comparingInt(ProductionDrainageAudit.Region::x)
                .thenComparingInt(ProductionDrainageAudit.Region::z);
        var result = new ArrayList<ClaimAgreement>();
        for (var entry : grouped.entrySet()) {
            checkClaimCancellation();
            var values = entry.getValue().values().stream().sorted(java.util.Comparator.comparing(DirectionClaim::owner, ownerOrder)).toList();
            WorldCell next = values.get(0).next;
            ClaimState state = values.size() != 2 || next == null || values.get(1).next == null ? ClaimState.INPUT_PENDING
                    : next.equals(values.get(1).next) ? ClaimState.AGREED_EDGE : ClaimState.DIRECTION_CONFLICT;
            result.add(new ClaimAgreement(entry.getKey(), state == ClaimState.AGREED_EDGE ? next : null, state, values));
        }
        assertClaimAcyclic(result);
        return List.copyOf(result);
    }

    private static void assertClaimAcyclic(List<ClaimAgreement> agreements) {
        var edges = new java.util.HashMap<WorldCell, WorldCell>();
        for (var a : agreements) if (a.state == ClaimState.AGREED_EDGE) edges.put(a.point, a.next);
        var done = new java.util.HashSet<WorldCell>();
        for (var start : edges.keySet()) {
            checkClaimCancellation(); var seen = new java.util.HashSet<WorldCell>(); WorldCell point = start;
            while (point != null && !done.contains(point)) {
                checkClaimCancellation();
                if (!seen.add(point)) throw new IllegalArgumentException("agreed direction cycle");
                point = edges.get(point);
            }
            done.addAll(seen);
        }
    }

    private static void checkClaimCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("direction claims");
    }

    record WorldPolicy(long seed, long config, int version, int tract, int step) {
        WorldPolicy {
            if (version < 1 || tract < 1 || step < 1 || tract % step != 0)
                throw new IllegalArgumentException("aligned world policy");
        }
    }
    enum WorldState { NEXT, OCEAN_CANDIDATE, BASIN_PENDING, INPUT_PENDING, OUTSIDE_INPUT, CYCLE, NONDESCENDING, LIMIT }
    record WorldNode(WorldCell point, WorldCell next, double bed, double level, WorldState state) { }
    record WorldPiece(WorldPolicy policy, ProductionDrainageAudit.Region owner, java.util.Map<WorldCell, WorldNode> nodes) {
        WorldPiece {
            nodes = java.util.Map.copyOf(nodes);
            if (nodes.size() > 131072) throw new IllegalArgumentException("bounded world piece");
            for (var e : nodes.entrySet()) {
                if (!e.getKey().equals(e.getValue().point) || !worldOwner(policy, e.getKey()).equals(owner)
                        || Math.floorMod(e.getKey().x, policy.step) != 0 || Math.floorMod(e.getKey().z, policy.step) != 0
                        || !Double.isFinite(e.getValue().bed) || !Double.isFinite(e.getValue().level)
                        || (e.getValue().state != WorldState.NEXT && e.getValue().state != WorldState.OCEAN_CANDIDATE
                            && e.getValue().state != WorldState.BASIN_PENDING && e.getValue().state != WorldState.INPUT_PENDING)
                        || (e.getValue().state == WorldState.NEXT) != (e.getValue().next != null))
                    throw new IllegalArgumentException("invalid owned world node");
            }
        }
    }
    record WorldSource(WorldCell identity, WorldCell entry) { }
    record WorldOrigin(WorldCell identity, double localDischarge, double threshold) { }
    record OriginPolicy(int radius, double threshold, boolean valleyTrough) {
        OriginPolicy {
            if (radius < 1 || !Double.isFinite(threshold) || threshold < 0)
                throw new IllegalArgumentException("fixed origin policy");
        }
    }
    record WorldProduct(WorldPiece piece, OriginPolicy admission, List<WorldOrigin> origins) {
        WorldProduct {
            origins = List.copyOf(origins);
            if (origins.size() > 4096) throw new IllegalArgumentException("bounded world origins");
            var seen = new java.util.HashSet<WorldCell>();
            for (var origin : origins) {
                var node = piece.nodes.get(origin.identity);
                if (!seen.add(origin.identity) || node == null || node.state != WorldState.NEXT && node.state != WorldState.BASIN_PENDING
                        || !Double.isFinite(origin.localDischarge) || !Double.isFinite(origin.threshold)
                        || origin.threshold != admission.threshold || origin.localDischarge < origin.threshold)
                    throw new IllegalArgumentException("invalid owned world origin");
            }
            origins = origins.stream().sorted(java.util.Comparator.comparingInt((WorldOrigin o) -> o.identity.x)
                    .thenComparingInt(o -> o.identity.z)).toList();
        }
    }
    record WorldEdge(WorldCell from, WorldCell to) { }
    record WorldRoute(java.util.List<WorldCell> path, WorldState state) {
        WorldRoute { path = java.util.List.copyOf(path); }
    }
    record WorldProjection(java.util.Map<WorldSource, WorldRoute> routes,
                           java.util.Map<WorldEdge, java.util.Set<WorldCell>> support) {
        WorldProjection {
            routes = java.util.Map.copyOf(routes);
            var copy = new java.util.HashMap<WorldEdge, java.util.Set<WorldCell>>();
            support.forEach((edge, sources) -> copy.put(edge, java.util.Set.copyOf(sources)));
            support = java.util.Map.copyOf(copy);
        }
    }

    private static ProductionDrainageAudit.Region worldOwner(WorldPolicy policy, WorldCell point) {
        return new ProductionDrainageAudit.Region(Math.floorDiv(point.x, policy.tract), Math.floorDiv(point.z, policy.tract));
    }

    /** Source-free frozen owner result. Raw parent is a candidate, not a certified physical river. */
    static WorldPiece worldPiece(WorldPolicy policy, ProductionDrainageAudit.Region owner,
                                 int ox, int oz, HydrologyPipeline.Result p) {
        checkClaimCancellation();
        if (p.cellBlocks() != policy.step || p.bed().length > 131072)
            throw new IllegalArgumentException("world piece sampling policy");
        var nodes = new java.util.HashMap<WorldCell, WorldNode>();
        for (int k = 0; k < p.bed().length; k++) {
            checkClaimCancellation();
            WorldCell point = planePoint(ox, oz, policy.step, p.nz(), k);
            if (!worldOwner(policy, point).equals(owner)) continue;
            int parent = p.parent()[k]; WorldCell next = null; WorldState state;
            if (!p.resolved()[k]) state = WorldState.INPUT_PENDING;
            else if (p.sea()[k]) state = WorldState.OCEAN_CANDIDATE;
            else if (p.lake()[k] || p.bed()[k] < p.level()[k]) state = WorldState.BASIN_PENDING;
            else if (parent < 0 || parent >= p.bed().length || parent == k) state = WorldState.INPUT_PENDING;
            else { state = WorldState.NEXT; next = planePoint(ox, oz, policy.step, p.nz(), parent); }
            nodes.put(point, new WorldNode(point, next, p.bed()[k], p.level()[k], state));
        }
        return new WorldPiece(policy, owner, nodes);
    }

    /** Fixed-owner local admission, not a global discharge certificate; no selected traces are consumed. */
    static WorldProduct worldProduct(WorldPolicy policy, ProductionDrainageAudit.Region owner,
                                     int ox, int oz, HydrologyPipeline.Result p, DeterministicSkeleton.Params params) {
        checkClaimCancellation();
        long spanX = (long) (p.nx() - 1) * policy.step, spanZ = (long) (p.nz() - 1) * policy.step;
        if (spanX != spanZ || spanX < policy.tract || 2L * ox + spanX != 2L * owner.x() * policy.tract + policy.tract
                || 2L * oz + spanZ != 2L * owner.z() * policy.tract + policy.tract)
            throw new IllegalArgumentException("fixed owner-centered origin context required");
        var piece = worldPiece(policy, owner, ox, oz, p);
        if (!Double.isFinite(params.minSourceAccum()) || params.minSourceAccum() < 0)
            throw new IllegalArgumentException("finite nonnegative origin threshold");
        var origins = new ArrayList<WorldOrigin>();
        for (int k : DeterministicSkeleton.collectSources(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.nx(), p.nz(), params)) {
            checkClaimCancellation();
            var at = planePoint(ox, oz, policy.step, p.nz(), k);
            if (worldOwner(policy, at).equals(owner)) origins.add(new WorldOrigin(at, p.discharge()[k], params.minSourceAccum()));
        }
        return new WorldProduct(piece, new OriginPolicy((int) (spanX / 2), params.minSourceAccum(), DeterministicSkeleton.VALLEY_TROUGH), origins);
    }

    static WorldProjection projectProducts(WorldPolicy policy, List<WorldProduct> products, int maxNodes) {
        checkClaimCancellation();
        if (products.size() > 4) throw new IllegalArgumentException("bounded world products");
        var owners = new java.util.HashMap<ProductionDrainageAudit.Region, WorldProduct>();
        for (var product : products) {
            checkClaimCancellation();
            if (!products.get(0).admission.equals(product.admission)) throw new IllegalArgumentException("different origin policy");
            var old = owners.putIfAbsent(product.piece.owner, product);
            if (old != null && !old.equals(product)) throw new IllegalArgumentException("conflicting owner source product");
        }
        var sources = new ArrayList<WorldSource>();
        for (var product : owners.values()) for (var origin : product.origins) {
            if (sources.size() == 4096) throw new IllegalArgumentException("world origin budget");
            sources.add(new WorldSource(origin.identity, origin.identity));
        }
        return projectWorld(policy, products.stream().map(WorldProduct::piece).toList(), sources, maxNodes);
    }

    /** Explicit finite pieces only: never fetch a missing owner or reinterpret it as dry/sea. */
    static WorldProjection projectWorld(WorldPolicy policy, List<WorldPiece> pieces, List<WorldSource> sources, int maxNodes) {
        checkClaimCancellation();
        if (sources.size() > 4096 || maxNodes < 1 || maxNodes > 4096)
            throw new IllegalArgumentException("bounded world projection");
        var owners = worldOwners(policy, pieces);
        return projectNodes(policy, at -> {
            var piece = owners.get(worldOwner(policy, at)); return piece == null ? null : piece.nodes.get(at);
        }, sources, maxNodes);
    }

    static WorldProjection projectConditional(SharedBoundaryTransfer.ConditionalRouting routing, List<WorldSource> sources, int maxNodes) {
        return projectNodes(routing.policy(), routing.nodes()::get, sources, maxNodes);
    }

    private static WorldProjection projectNodes(WorldPolicy policy, java.util.function.Function<WorldCell, WorldNode> nodes,
                                                List<WorldSource> sources, int maxNodes) {
        checkClaimCancellation();
        if (sources.size() > 4096 || maxNodes < 1 || maxNodes > 4096) throw new IllegalArgumentException("bounded world projection");
        var routes = new java.util.HashMap<WorldSource, WorldRoute>();
        var entries = new java.util.HashMap<WorldCell, WorldCell>();
        var support = new java.util.HashMap<WorldEdge, java.util.Set<WorldCell>>(); int pairs = 0;
        for (var source : sources) {
            checkClaimCancellation();
            var entry = entries.putIfAbsent(source.identity, source.entry);
            if (entry != null && !entry.equals(source.entry)) throw new IllegalArgumentException("conflicting source entry");
            if (routes.containsKey(source)) continue;
            var route = followWorld(policy, nodes, source.entry, maxNodes); routes.put(source, route);
            if (route.state == WorldState.CYCLE || route.state == WorldState.NONDESCENDING) continue;
            for (int i = 1; i < route.path.size(); i++) {
                var edge = new WorldEdge(route.path.get(i - 1), route.path.get(i));
                if (support.computeIfAbsent(edge, ignored -> new java.util.HashSet<>()).add(source.identity) && ++pairs > 1048576)
                    throw new IllegalArgumentException("world source-edge budget");
            }
        }
        return new WorldProjection(routes, support);
    }

    private static WorldRoute followWorld(WorldPolicy policy, java.util.function.Function<WorldCell, WorldNode> nodes,
                                          WorldCell start, int maxNodes) {
        var path = new ArrayList<WorldCell>(); var seen = new java.util.HashSet<WorldCell>(); WorldCell at = start;
        WorldNode previous = null;
        while (true) {
            checkClaimCancellation();
            if (!seen.add(at)) return new WorldRoute(path, WorldState.CYCLE);
            var node = nodes.apply(at);
            if (node == null) return new WorldRoute(path, WorldState.OUTSIDE_INPUT);
            if (path.size() == maxNodes) return new WorldRoute(path, WorldState.LIMIT);
            path.add(at);
            if (previous != null && node.level > previous.level + 1e-9) return new WorldRoute(path, WorldState.NONDESCENDING);
            if (node.state != WorldState.NEXT) return new WorldRoute(path, node.state);
            long dx = (long) node.next.x - at.x, dz = (long) node.next.z - at.z;
            if (Math.abs(dx) > policy.step || Math.abs(dz) > policy.step || dx % policy.step != 0 || dz % policy.step != 0
                    || (dx == 0 && dz == 0)) throw new IllegalArgumentException("nonlocal world successor");
            previous = node; at = node.next;
        }
    }

    private static java.util.Map<ProductionDrainageAudit.Region, WorldPiece> worldOwners(WorldPolicy policy, List<WorldPiece> pieces) {
        checkClaimCancellation();
        if (pieces.size() > 4) throw new IllegalArgumentException("bounded world pieces");
        var owners = new java.util.HashMap<ProductionDrainageAudit.Region, WorldPiece>(); int nodes = 0;
        for (var piece : pieces) {
            checkClaimCancellation();
            if (!policy.equals(piece.policy)) throw new IllegalArgumentException("different world policy");
            var old = owners.putIfAbsent(piece.owner, piece);
            if (old != null && !old.equals(piece)) throw new IllegalArgumentException("conflicting owner product");
            if (old == null) nodes += piece.nodes.size();
        }
        if (nodes > 131072) throw new IllegalArgumentException("world projection node budget");
        return owners;
    }

    enum WorldRimState { HIGHER, LOWER_CANDIDATE, OCEAN_CANDIDATE, INPUT_PENDING, OUTSIDE_INPUT }
    record WorldRim(WorldEdge edge, WorldRimState state) { }
    record WorldPlane(WorldPolicy policy, WorldCell anchor, double level, PlaneState state,
                      List<WorldCell> members, List<WorldRim> rim, int filledCells) {
        WorldPlane { members = List.copyOf(members); rim = List.copyOf(rim); }
    }

    /** Finite equal-level grid component and ALL rim edges; neither a physical basin nor an outflow certificate. */
    static WorldPlane worldPlane(WorldPolicy policy, List<WorldPiece> pieces, WorldCell start) {
        var owners = worldOwners(policy, pieces);
        var piece = owners.get(worldOwner(policy, start)); var first = piece == null ? null : piece.nodes.get(start);
        if (first == null || first.state != WorldState.BASIN_PENDING)
            throw new IllegalArgumentException("known pending basin entry required");
        double level = first.level; var seen = new java.util.HashSet<WorldCell>();
        var queue = new java.util.ArrayDeque<WorldCell>(); var rim = new ArrayList<WorldRim>();
        seen.add(start); queue.add(start); int filled = 0; boolean pending = false;
        while (!queue.isEmpty()) {
            checkClaimCancellation(); var at = queue.remove(); var node = owners.get(worldOwner(policy, at)).nodes.get(at);
            if (node.bed > level) throw new IllegalArgumentException("invalid equal-level bed");
            if (node.bed < level) filled++;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var next = new WorldCell(Math.addExact(at.x, Math.multiplyExact(dx, policy.step)),
                        Math.addExact(at.z, Math.multiplyExact(dz, policy.step)));
                var otherPiece = owners.get(worldOwner(policy, next)); var other = otherPiece == null ? null : otherPiece.nodes.get(next);
                WorldRimState state;
                if (other == null) state = WorldRimState.OUTSIDE_INPUT;
                else if (other.state == WorldState.INPUT_PENDING) state = WorldRimState.INPUT_PENDING;
                else if (other.state == WorldState.OCEAN_CANDIDATE) state = WorldRimState.OCEAN_CANDIDATE;
                else if (other.level == level) {
                    if (seen.add(next)) queue.add(next);
                    continue;
                } else state = other.level < level ? WorldRimState.LOWER_CANDIDATE : WorldRimState.HIGHER;
                pending |= state == WorldRimState.OUTSIDE_INPUT || state == WorldRimState.INPUT_PENDING;
                rim.add(new WorldRim(new WorldEdge(at, next), state));
            }
        }
        var order = java.util.Comparator.comparingInt(WorldCell::x).thenComparingInt(WorldCell::z);
        var members = seen.stream().sorted(order).toList();
        rim.sort(java.util.Comparator.comparing((WorldRim r) -> r.edge.from, order).thenComparing(r -> r.edge.to, order));
        return new WorldPlane(policy, members.get(0), level, pending ? PlaneState.INPUT_PENDING : PlaneState.LOCAL_COMPLETE,
                members, rim, filled);
    }

    enum SlopeState { DESCENT_CANDIDATE, BASIN_PENDING, INPUT_PENDING }
    record BedSample(WorldCell point, double bed) { }
    record SlopeStep(WorldCell point, WorldCell next, double bed, double nextBed, SlopeState state) { }

    /** Complete nine-point bed stencil only; not a filled-basin, ocean, subcell or river-selection certificate. */
    static SlopeStep drySlope(WorldCell point, int step, List<BedSample> samples) {
        checkClaimCancellation();
        if (step <= 0 || samples.size() > 9) throw new IllegalArgumentException("bounded slope stencil");
        var beds = new java.util.HashMap<WorldCell, Double>();
        for (var sample : samples) {
            checkClaimCancellation();
            long dx = (long) sample.point.x - point.x, dz = (long) sample.point.z - point.z;
            if (!Double.isFinite(sample.bed) || Math.abs(dx) > step || Math.abs(dz) > step
                    || dx % step != 0 || dz % step != 0) throw new IllegalArgumentException("invalid bed sample");
            var old = beds.putIfAbsent(sample.point, sample.bed);
            if (old != null && Double.doubleToLongBits(old) != Double.doubleToLongBits(sample.bed))
                throw new IllegalArgumentException("contradictory bed samples");
        }
        double bed = beds.getOrDefault(point, Double.NaN);
        if (beds.size() != 9) return new SlopeStep(point, null, bed, Double.NaN, SlopeState.INPUT_PENDING);
        WorldCell next = null; double best = 0, nextBed = bed;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            var candidate = new WorldCell(Math.addExact(point.x, Math.multiplyExact(dx, step)),
                    Math.addExact(point.z, Math.multiplyExact(dz, step)));
            double height = beds.get(candidate), slope = (bed - height) / (step * Math.hypot(dx, dz));
            if (slope > best) { best = slope; next = candidate; nextBed = height; }
        }
        return new SlopeStep(point, next, bed, nextBed, next == null ? SlopeState.BASIN_PENDING : SlopeState.DESCENT_CANDIDATE);
    }

    enum PlaneState { LOCAL_COMPLETE, INPUT_PENDING }
    record PlaneExit(WorldCell inner, WorldCell outer, double innerBed, double outerBed, double outerLevel) { }
    record EscapePlane(WorldCell identity, double level, PlaneState state, List<WorldCell> members,
                       List<PlaneExit> exits, List<WorldCell> framePorts, int filledCells, int unresolvedCells, boolean touchesFrame) {
        EscapePlane { members = List.copyOf(members); exits = List.copyOf(exits); framePorts = List.copyOf(framePorts); }
    }

    /** Local equal-escape-level component, including dry rim cells; never an actual lake or global outlet certificate. */
    static EscapePlane escapePlane(int ox, int oz, int step, int nx, int nz, double[] bed, double[] level,
                                   boolean[] resolved, int start) {
        checkClaimCancellation();
        if (step <= 0 || nx < 1 || nz < 1 || (long) nx * nz > 131072 || start < 0 || start >= nx * nz
                || bed.length != nx * nz || level.length != bed.length || resolved.length != bed.length)
            throw new IllegalArgumentException("bounded plane arrays");
        double plane = level[start];
        if (!Double.isFinite(plane)) throw new IllegalArgumentException("nonfinite escape plane");
        var seen = new java.util.BitSet(bed.length); var queue = new java.util.ArrayDeque<Integer>();
        var indices = new ArrayList<Integer>(); var exits = new ArrayList<PlaneExit>();
        seen.set(start); queue.add(start); int filled = 0, unresolved = 0; boolean frame = false;
        while (!queue.isEmpty()) {
            checkClaimCancellation(); int k = queue.remove(); indices.add(k);
            if (!Double.isFinite(bed[k]) || bed[k] > plane) throw new IllegalArgumentException("invalid plane bed");
            if (bed[k] < plane) filled++; if (!resolved[k]) unresolved++;
            int x = k / nz, z = k % nz;
            frame |= x == 0 || z == 0 || x == nx - 1 || z == nz - 1;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || x + dx < 0 || z + dz < 0 || x + dx >= nx || z + dz >= nz) continue;
                int next = (x + dx) * nz + z + dz;
                if (!Double.isFinite(level[next]) || !Double.isFinite(bed[next]) || bed[next] > level[next])
                    throw new IllegalArgumentException("invalid neighbor plane");
                if (level[next] == plane) { if (!seen.get(next)) { seen.set(next); queue.add(next); } }
                else if (level[next] < plane) exits.add(new PlaneExit(planePoint(ox, oz, step, nz, k),
                        planePoint(ox, oz, step, nz, next), bed[k], bed[next], level[next]));
            }
        }
        indices.sort(Integer::compareTo);
        var members = indices.stream().map(k -> planePoint(ox, oz, step, nz, k)).toList();
        var ports = indices.stream().filter(k -> k / nz == 0 || k % nz == 0 || k / nz == nx - 1 || k % nz == nz - 1)
                .map(k -> planePoint(ox, oz, step, nz, k)).toList();
        exits.sort(java.util.Comparator.comparingInt((PlaneExit e) -> e.inner.x).thenComparingInt(e -> e.inner.z)
                .thenComparingInt(e -> e.outer.x).thenComparingInt(e -> e.outer.z));
        return new EscapePlane(members.get(0), plane, frame || unresolved > 0 ? PlaneState.INPUT_PENDING : PlaneState.LOCAL_COMPLETE,
                members, exits, ports, filled, unresolved, frame);
    }

    private static WorldCell planePoint(int ox, int oz, int step, int nz, int k) {
        return new WorldCell(Math.addExact(ox, Math.multiplyExact(k / nz, step)), Math.addExact(oz, Math.multiplyExact(k % nz, step)));
    }

    enum LocalStatus { DOWNHILL, SPILL_CANDIDATE, FLAT_CANDIDATE, SEA, BASIN_PENDING, DOMAIN_LIMIT, SELF_RETURN, MERGE_PENDING }
    record WorldCell(int x, int z) { }
    record LocalStep(WorldCell cell, WorldCell next, double bed, double downstreamBed, LocalStatus status) { }
    record WorldConnectionId(int axis, int boundary, WorldCell upstream, WorldCell downstream) { }
    record LocalMapping(LocalStatus status, WorldConnectionId connection,
                        ProductionDrainageAudit.Point point, int steps, WorldCell terminal) { }
    record SpillPair(WorldCell inner, WorldCell outer, double innerBed, double outerBed) { }
    record BasinEscape(LocalStatus status, WorldCell sink, WorldCell downstream,
                       double sill, SpillPair spill, List<WorldCell> path, int visited) { }
    private record SearchNode(WorldCell cell, double barrier) { }
    record Descent(LocalStatus status, List<WorldCell> path) {
        Descent { path = List.copyOf(path); }
        WorldCell terminal() { return path.get(path.size() - 1); }
    }
    record CandidateChain(LocalStatus status, List<WorldCell> path, List<BasinEscape> transitions) {
        CandidateChain { path = List.copyOf(path); transitions = List.copyOf(transitions); }
    }
    record BasinFootprint(LocalStatus status, WorldCell owner, double sill,
                          List<WorldCell> members, double capacity) {
        BasinFootprint { members = List.copyOf(members); }
    }

    /** Strict bed descent is a common progress measure across every window, not a water level. */
    static final class TerrainRouting {
        private final int step, tract;
        private final double sea;
        private final WindowOutlets.Terrain terrain;
        private final java.util.concurrent.ConcurrentMap<WorldCell, Double> heights =
                new java.util.concurrent.ConcurrentHashMap<>();

        TerrainRouting(int step, int tract, double sea, WindowOutlets.Terrain terrain) {
            if (step <= 0 || tract <= 0 || tract % step != 0)
                throw new IllegalArgumentException("world grid alignment");
            this.step = step;
            this.tract = tract;
            this.sea = sea;
            this.terrain = terrain;
        }

        LocalStep at(int x, int z) {
            if (Math.floorMod(x, step) != 0 || Math.floorMod(z, step) != 0)
                throw new IllegalArgumentException("world grid point required");
            WorldCell cell = new WorldCell(x, z);
            double bed = height(cell);
            if (bed < sea) return new LocalStep(cell, null, bed, bed, LocalStatus.SEA);
            WorldCell next = null;
            double bestSlope = 0, nextBed = bed;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                WorldCell candidate = new WorldCell(x + dx * step, z + dz * step);
                double h = height(candidate);
                double slope = (bed - h) / (step * Math.hypot(dx, dz));
                // Scan order breaks ties only between equally steep, genuinely downhill edges.
                if (h < bed && slope > bestSlope) {
                    bestSlope = slope;
                    next = candidate;
                    nextBed = h;
                }
            }
            return new LocalStep(cell, next, bed, nextBed,
                    next == null ? LocalStatus.BASIN_PENDING : LocalStatus.DOWNHILL);
        }

        private double height(WorldCell cell) {
            return heights.computeIfAbsent(cell, k -> {
                double half = step * 0.5;
                double h = WindowOutlets.VALLEY_MIN
                        ? Math.min(Math.min(terrain.height(k.x - half, k.z - half),
                        terrain.height(k.x + half, k.z - half)),
                        Math.min(terrain.height(k.x - half, k.z + half),
                        terrain.height(k.x + half, k.z + half))) : terrain.height(k.x, k.z);
                if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite terrain at " + k);
                return h;
            });
        }

        LocalMapping map(ProductionDrainageAudit.Crossing event, int half, int maxSteps) {
            if (half <= 0 || half > 512 || maxSteps <= 0 || maxSteps > 257 * 257)
                throw new IllegalArgumentException("fixed routing budget");
            double along = event.axis() == 0 ? event.point().z() : event.point().x();
            int center = (int) Math.floor(along / tract) * tract + tract / 2;
            double cx = event.axis() == 0 ? event.boundary() : center;
            double cz = event.axis() == 0 ? center : event.boundary();
            int x = (int) Math.round((event.point().x() - 2 * step * event.tangent().x()) / step) * step;
            int z = (int) Math.round((event.point().z() - 2 * step * event.tangent().z()) / step) * step;
            for (int i = 0; i < maxSteps; i++) {
                if (Math.abs(x - cx) > half || Math.abs(z - cz) > half)
                    return new LocalMapping(LocalStatus.DOMAIN_LIMIT, null, null, i, new WorldCell(x, z));
                LocalStep route = at(x, z);
                if (route.status != LocalStatus.DOWNHILL)
                    return new LocalMapping(route.status, null, null, i, route.cell);
                var a = new ProductionDrainageAudit.Point(x, z);
                var b = new ProductionDrainageAudit.Point(route.next.x, route.next.z);
                for (var crossing : ProductionDrainageAudit.crossings(event.segment(), a, b, tract)) {
                    if (crossing.axis() == event.axis() && crossing.boundary() == event.boundary()
                            && crossing.upstream().equals(event.upstream())
                            && crossing.downstream().equals(event.downstream())) {
                        return new LocalMapping(LocalStatus.DOWNHILL,
                                new WorldConnectionId(crossing.axis(), crossing.boundary(), route.cell, route.next),
                                crossing.point(), i + 1, route.next);
                    }
                }
                x = route.next.x;
                z = route.next.z;
            }
            return new LocalMapping(LocalStatus.DOMAIN_LIMIT, null, null, maxSteps, new WorldCell(x, z));
        }

        LocalMapping firstCrossing(ProductionDrainageAudit.Crossing event, int half) {
            if (half <= 0 || half > 512) throw new IllegalArgumentException("fixed domain");
            double along = event.axis() == 0 ? event.point().z() : event.point().x();
            int center = (int) Math.floor(along / tract) * tract + tract / 2;
            double cx = event.axis() == 0 ? event.boundary() : center;
            double cz = event.axis() == 0 ? center : event.boundary();
            int x = (int) Math.round((event.point().x() - 2 * step * event.tangent().x()) / step) * step;
            int z = (int) Math.round((event.point().z() - 2 * step * event.tangent().z()) / step) * step;
            for (int i = 0; i < 257 * 257; i++) {
                if (Math.abs(x - cx) > half || Math.abs(z - cz) > half)
                    return new LocalMapping(LocalStatus.DOMAIN_LIMIT, null, null, i, new WorldCell(x, z));
                LocalStep route = at(x, z);
                if (route.status != LocalStatus.DOWNHILL)
                    return new LocalMapping(route.status, null, null, i, route.cell);
                var crosses = ProductionDrainageAudit.crossings(event.segment(),
                        new ProductionDrainageAudit.Point(x, z),
                        new ProductionDrainageAudit.Point(route.next.x, route.next.z), tract);
                if (!crosses.isEmpty()) {
                    var cross = crosses.get(0);
                    return new LocalMapping(LocalStatus.DOWNHILL,
                            new WorldConnectionId(cross.axis(), cross.boundary(), route.cell, route.next),
                            cross.point(), i + 1, route.next);
                }
                x = route.next.x;
                z = route.next.z;
            }
            return new LocalMapping(LocalStatus.DOMAIN_LIMIT, null, null, 257 * 257, new WorldCell(x, z));
        }

        /** Search from the sink, never from a caller's window; truncated searches publish no spill. */
        BasinEscape escape(WorldCell sink, int half) {
            if (half <= 0 || half > 512 || half % step != 0)
                throw new IllegalArgumentException("fixed basin budget");
            double bottom = height(sink);
            if (at(sink.x, sink.z).status != LocalStatus.BASIN_PENDING)
                throw new IllegalArgumentException("sink required");
            var queue = new java.util.PriorityQueue<SearchNode>(java.util.Comparator
                    .comparingDouble(SearchNode::barrier).thenComparingInt(v -> v.cell.x)
                    .thenComparingInt(v -> v.cell.z));
            var best = new java.util.HashMap<WorldCell, Double>();
            var previous = new java.util.HashMap<WorldCell, WorldCell>();
            best.put(sink, bottom);
            queue.add(new SearchNode(sink, bottom));
            int visited = 0;
            while (!queue.isEmpty()) {
                SearchNode node = queue.remove();
                if (node.barrier != best.get(node.cell)) continue;
                visited++;
                if (Math.abs(node.cell.x - sink.x) >= half || Math.abs(node.cell.z - sink.z) >= half)
                    return new BasinEscape(LocalStatus.DOMAIN_LIMIT, sink, null, Double.NaN, null, List.of(), visited);
                if (height(node.cell) < bottom) {
                    var path = new java.util.ArrayList<WorldCell>();
                    WorldCell c = node.cell;
                    while (c != null) { path.add(c); c = previous.get(c); }
                    java.util.Collections.reverse(path);
                    int rim = 0;
                    for (int i = 0; i < path.size() - 1; i++)
                        if (height(path.get(i)) == node.barrier) rim = i;
                    WorldCell inner = path.get(rim), outer = path.get(rim + 1);
                    var spill = new SpillPair(inner, outer, height(inner), height(outer));
                    LocalStatus status = node.barrier == bottom ? LocalStatus.FLAT_CANDIDATE : LocalStatus.SPILL_CANDIDATE;
                    return new BasinEscape(status, sink, node.cell,
                            node.barrier, spill, List.copyOf(path), visited);
                }
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    WorldCell c = new WorldCell(node.cell.x + dx * step, node.cell.z + dz * step);
                    double barrier = Math.max(node.barrier, height(c));
                    if (barrier < best.getOrDefault(c, Double.POSITIVE_INFINITY)) {
                        best.put(c, barrier);
                        previous.put(c, node.cell);
                        queue.add(new SearchNode(c, barrier));
                    }
                }
            }
            return new BasinEscape(LocalStatus.BASIN_PENDING, sink, null, Double.NaN, null, List.of(), visited);
        }

        Descent descend(WorldCell start, int half) {
            if (half <= 0 || half > 512) throw new IllegalArgumentException("fixed descent domain");
            var path = new ArrayList<WorldCell>();
            WorldCell cell = start;
            for (int i = 0; i < 257 * 257; i++) {
                path.add(cell);
                if (Math.abs(cell.x - start.x) > half || Math.abs(cell.z - start.z) > half)
                    return new Descent(LocalStatus.DOMAIN_LIMIT, path);
                LocalStep route = at(cell.x, cell.z);
                if (route.status != LocalStatus.DOWNHILL) return new Descent(route.status, path);
                cell = route.next;
            }
            return new Descent(LocalStatus.DOMAIN_LIMIT, path);
        }

        BasinFootprint footprint(BasinEscape escape, int half) {
            if (half <= 0 || half > 512 || half % step != 0) throw new IllegalArgumentException("fixed basin domain");
            if (escape.status == LocalStatus.FLAT_CANDIDATE)
                return new BasinFootprint(LocalStatus.FLAT_CANDIDATE, null, escape.sill, List.of(), 0);
            if (escape.status != LocalStatus.SPILL_CANDIDATE)
                return new BasinFootprint(escape.status, null, Double.NaN, List.of(), Double.NaN);
            var seen = new java.util.HashSet<WorldCell>();
            var queue = new java.util.ArrayDeque<WorldCell>();
            var members = new ArrayList<WorldCell>();
            seen.add(escape.sink);
            queue.add(escape.sink);
            double capacity = 0;
            while (!queue.isEmpty()) {
                WorldCell c = queue.remove();
                if (Math.abs(c.x - escape.sink.x) >= half || Math.abs(c.z - escape.sink.z) >= half)
                    return new BasinFootprint(LocalStatus.DOMAIN_LIMIT, null, escape.sill, List.of(), Double.NaN);
                if (height(c) >= escape.sill) continue;
                members.add(c);
                capacity += (escape.sill - height(c)) * step * step;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    WorldCell next = new WorldCell(c.x + dx * step, c.z + dz * step);
                    if (seen.add(next) && height(next) < escape.sill) queue.add(next);
                }
            }
            members.sort(java.util.Comparator.comparingInt(WorldCell::x).thenComparingInt(WorldCell::z));
            return new BasinFootprint(LocalStatus.SPILL_CANDIDATE,
                    members.isEmpty() ? null : members.get(0), escape.sill, members, capacity);
        }

        BasinFootprint enclosingAt(WorldCell start, double level, int half) {
            if (!Double.isFinite(level) || height(start) >= level)
                throw new IllegalArgumentException("submerged start required");
            return footprint(new BasinEscape(LocalStatus.SPILL_CANDIDATE, start, null,
                    level, null, List.of(), 0), half);
        }

        /** Lowest escape to terrain below the whole basin minimum, with no return through members. */
        BasinEscape enclosingOutlet(BasinFootprint basin, int half) {
            if (half <= 0 || half > 512 || half % step != 0) throw new IllegalArgumentException("fixed outlet domain");
            if (basin.owner == null || basin.status != LocalStatus.SPILL_CANDIDATE)
                return new BasinEscape(basin.status, basin.owner, null, Double.NaN, null, List.of(), 0);
            var members = new java.util.HashSet<>(basin.members);
            WorldCell seed = basin.members.stream().min(java.util.Comparator
                    .comparingDouble(this::height).thenComparingInt(WorldCell::x).thenComparingInt(WorldCell::z)).orElseThrow();
            double bottom = height(seed);
            for (WorldCell member : basin.members)
                if (Math.abs(member.x - seed.x) >= half || Math.abs(member.z - seed.z) >= half)
                    return new BasinEscape(LocalStatus.DOMAIN_LIMIT, seed, null, Double.NaN, null, List.of(), 0);
            var queue = new java.util.PriorityQueue<SearchNode>(java.util.Comparator
                    .comparingDouble(SearchNode::barrier).thenComparingInt(v -> v.cell.x).thenComparingInt(v -> v.cell.z));
            var best = new java.util.HashMap<WorldCell, Double>();
            var previous = new java.util.HashMap<WorldCell, WorldCell>();
            for (WorldCell member : basin.members) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    WorldCell next = new WorldCell(member.x + dx * step, member.z + dz * step);
                    if (members.contains(next)) continue;
                    double cost = Math.max(height(member), height(next));
                    if (cost < best.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        best.put(next, cost); previous.put(next, member); queue.add(new SearchNode(next, cost));
                    }
                }
            }
            int visited = 0;
            while (!queue.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("outlet search");
                SearchNode node = queue.remove();
                if (node.barrier != best.get(node.cell)) continue;
                visited++;
                if (Math.abs(node.cell.x - seed.x) >= half || Math.abs(node.cell.z - seed.z) >= half)
                    return new BasinEscape(LocalStatus.DOMAIN_LIMIT, seed, null, Double.NaN, null, List.of(), visited);
                if (height(node.cell) < bottom) {
                    var path = new ArrayList<WorldCell>();
                    WorldCell c = node.cell;
                    while (c != null) {
                        path.add(c);
                        if (members.contains(c)) break;
                        c = previous.get(c);
                    }
                    java.util.Collections.reverse(path);
                    WorldCell inner = path.get(0), outer = path.get(1);
                    return new BasinEscape(LocalStatus.SPILL_CANDIDATE, seed, node.cell, node.barrier,
                            new SpillPair(inner, outer, height(inner), height(outer)), List.copyOf(path), visited);
                }
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    WorldCell next = new WorldCell(node.cell.x + dx * step, node.cell.z + dz * step);
                    if (members.contains(next)) continue;
                    double cost = Math.max(node.barrier, height(next));
                    if (cost < best.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        best.put(next, cost); previous.put(next, node.cell); queue.add(new SearchNode(next, cost));
                    }
                }
            }
            return new BasinEscape(LocalStatus.BASIN_PENDING, seed, null, Double.NaN, null, List.of(), visited);
        }

        Descent downstream(BasinEscape escape, int half) {
            if (escape.downstream == null) return new Descent(escape.status, List.of(escape.sink));
            Descent route = descend(escape.downstream, half);
            if (route.path.contains(escape.sink)) return new Descent(LocalStatus.SELF_RETURN, route.path);
            return route;
        }

        Descent downstream(BasinFootprint basin, BasinEscape escape, int half) {
            if (escape.downstream == null) return new Descent(escape.status, List.of(basin.owner));
            Descent route = descend(escape.downstream, half);
            var members = new java.util.HashSet<>(basin.members);
            if (route.path.stream().anyMatch(members::contains))
                return new Descent(LocalStatus.SELF_RETURN, route.path);
            return route;
        }

        /** Geometry-only chain; a spill is not permission to publish actual water. */
        CandidateChain candidateChain(WorldCell start, int half, int maxTransitions) {
            if (half <= 0 || half > 512 || maxTransitions < 0 || maxTransitions > 16)
                throw new IllegalArgumentException("fixed chain budget");
            var path = new ArrayList<WorldCell>();
            var transitions = new ArrayList<BasinEscape>();
            var seen = new java.util.HashSet<WorldCell>();
            WorldCell cell = start;
            for (int i = 0; i < 257 * 257; i++) {
                if (!seen.add(cell)) return new CandidateChain(LocalStatus.SELF_RETURN, path, transitions);
                path.add(cell);
                if (Math.abs(cell.x - start.x) > half || Math.abs(cell.z - start.z) > half)
                    return new CandidateChain(LocalStatus.DOMAIN_LIMIT, path, transitions);
                LocalStep route = at(cell.x, cell.z);
                if (route.status == LocalStatus.SEA) return new CandidateChain(LocalStatus.SEA, path, transitions);
                if (route.next != null) { cell = route.next; continue; }
                if (transitions.size() >= maxTransitions)
                    return new CandidateChain(LocalStatus.BASIN_PENDING, path, transitions);
                BasinEscape escape = escape(cell, half);
                transitions.add(escape);
                if (escape.downstream == null) return new CandidateChain(escape.status, path, transitions);
                BasinFootprint basin = footprint(escape, half);
                if (basin.status == LocalStatus.DOMAIN_LIMIT)
                    return new CandidateChain(LocalStatus.DOMAIN_LIMIT, path, transitions);
                for (WorldCell member : basin.members) {
                    if (!member.equals(cell) && seen.contains(member))
                        return new CandidateChain(LocalStatus.MERGE_PENDING, path, transitions);
                }
                for (int j = 1; j < escape.path.size() - 1; j++) {
                    WorldCell middle = escape.path.get(j);
                    if (!seen.add(middle)) return new CandidateChain(LocalStatus.SELF_RETURN, path, transitions);
                    if (Math.abs(middle.x - start.x) > half || Math.abs(middle.z - start.z) > half)
                        return new CandidateChain(LocalStatus.DOMAIN_LIMIT, path, transitions);
                    path.add(middle);
                }
                cell = escape.downstream;
            }
            return new CandidateChain(LocalStatus.DOMAIN_LIMIT, path, transitions);
        }

        int sampledCells() { return heights.size(); }
    }

    enum BasinEnd { SEA, NEXT_LAKE, SELF_RETURN, UNKNOWN_FRAME, UNRESOLVED, CYCLE, NO_PARENT }
    record ShoreEdge(WorldCell inner, WorldCell outer, double barrier, boolean parentEdge,
                     BasinEnd terminal, int downstreamBasin, double pathBarrier, List<WorldCell> path) {
        ShoreEdge { path = List.copyOf(path); }
    }
    record BasinAudit(int basinId, int members, int wetMembers, double minEscape, double maxEscape,
                      List<ShoreEdge> shores) {
        BasinAudit { shores = List.copyOf(shores); }
    }

    /** Read-only topology candidates from the actual pipeline, not permission to publish water. */
    static BasinAudit auditBasin(HydrologyPipeline.Result p, int basinId, int ox, int oz) {
        var shores = new ArrayList<ShoreEdge>();
        int members = 0, wet = 0;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        int[][] dirs = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
        for (int k = 0; k < p.bed().length; k++) {
            if (LakeWaterBalance.basinOf(p.lakeResult(), k) != basinId) continue;
            members++;
            if (p.lake()[k]) wet++;
            lo = Math.min(lo, p.level()[k]); hi = Math.max(hi, p.level()[k]);
            int x = k / p.nz(), z = k % p.nz();
            var neighbors = new java.util.TreeSet<Integer>();
            for (int[] d : dirs) {
                int i = x + d[0], j = z + d[1];
                if (i >= 0 && j >= 0 && i < p.nx() && j < p.nz()) neighbors.add(i * p.nz() + j);
            }
            if (p.parent()[k] >= 0) neighbors.add(p.parent()[k]);
            for (int outer : neighbors) {
                if (LakeWaterBalance.basinOf(p.lakeResult(), outer) == basinId) continue;
                shores.add(followShore(p, basinId, k, outer, ox, oz));
            }
        }
        shores.sort(java.util.Comparator.comparingDouble(ShoreEdge::pathBarrier)
                .thenComparingDouble(ShoreEdge::barrier)
                .thenComparingInt(e -> e.inner.x).thenComparingInt(e -> e.inner.z)
                .thenComparingInt(e -> e.outer.x).thenComparingInt(e -> e.outer.z));
        return new BasinAudit(basinId, members, wet, lo, hi, shores);
    }

    private static ShoreEdge followShore(HydrologyPipeline.Result p, int basin, int inner,
                                         int outer, int ox, int oz) {
        var path = new ArrayList<WorldCell>();
        var seen = new java.util.HashSet<Integer>();
        double barrier = Math.max(p.bed()[inner], p.bed()[outer]), maxBed = barrier;
        BasinEnd end = BasinEnd.NO_PARENT;
        int downstream = -1, k = outer;
        path.add(worldCell(p, inner, ox, oz));
        while (k >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("shore audit");
            if (!seen.add(k)) { end = BasinEnd.CYCLE; break; }
            path.add(worldCell(p, k, ox, oz)); maxBed = Math.max(maxBed, p.bed()[k]);
            int id = LakeWaterBalance.basinOf(p.lakeResult(), k);
            if (id == basin) { end = BasinEnd.SELF_RETURN; break; }
            if (p.sea()[k]) { end = BasinEnd.SEA; break; }
            if (k / p.nz() == 0 || k % p.nz() == 0 || k / p.nz() == p.nx() - 1 || k % p.nz() == p.nz() - 1) {
                end = BasinEnd.UNKNOWN_FRAME; break;
            }
            if (!p.resolved()[k]) { end = BasinEnd.UNRESOLVED; break; }
            if (p.lake()[k] && id >= 0) { end = BasinEnd.NEXT_LAKE; downstream = id; break; }
            k = p.parent()[k];
        }
        return new ShoreEdge(worldCell(p, inner, ox, oz), worldCell(p, outer, ox, oz), barrier,
                p.parent()[inner] == outer, end, downstream, maxBed, path);
    }

    enum OutletState { CANDIDATE, WATER_PENDING, CLOSED_LAKE, NO_FEASIBLE_OUTLET, UNKNOWN, CYCLE, MERGE_PENDING }
    record BasinSelection(OutletState state, ShoreEdge edge) { }
    record BasinChain(OutletState state, List<Integer> basins, List<ShoreEdge> edges) {
        BasinChain { basins = List.copyOf(basins); edges = List.copyOf(edges); }
    }

    static BasinSelection selectOutlet(HydrologyPipeline.Result p, BasinAudit audit) {
        var balance = p.lakeResult().basins().get(audit.basinId);
        if (balance.catchmentTruncated()) return new BasinSelection(OutletState.WATER_PENDING, null);
        if (balance.outflow() <= 0) return new BasinSelection(OutletState.CLOSED_LAKE, null);
        if (!balance.overflow()) return new BasinSelection(OutletState.WATER_PENDING, null);
        for (ShoreEdge edge : audit.shores) {
            if (edge.pathBarrier > balance.level() + 1e-8 || !edge.parentEdge) continue;
            if (edge.terminal == BasinEnd.SEA) return new BasinSelection(OutletState.CANDIDATE, edge);
            if (edge.terminal == BasinEnd.NEXT_LAKE) {
                var next = p.lakeResult().basins().get(edge.downstreamBasin);
                if (Math.abs(next.level() - balance.level()) <= 1e-8)
                    return new BasinSelection(OutletState.MERGE_PENDING, edge);
                if (next.level() < balance.level())
                    return new BasinSelection(OutletState.CANDIDATE, edge);
            }
            if (edge.terminal == BasinEnd.UNKNOWN_FRAME || edge.terminal == BasinEnd.UNRESOLVED
                    || edge.terminal == BasinEnd.NO_PARENT)
                return new BasinSelection(OutletState.UNKNOWN, null);
        }
        return new BasinSelection(OutletState.NO_FEASIBLE_OUTLET, null);
    }

    /** A window-local basin chain certificate, never a published production lifecycle. */
    static BasinChain basinChain(HydrologyPipeline.Result p, int start, int ox, int oz) {
        var ids = new ArrayList<Integer>();
        var edges = new ArrayList<ShoreEdge>();
        var seen = new java.util.HashSet<Integer>();
        int id = start;
        while (seen.add(id)) {
            ids.add(id);
            var selection = selectOutlet(p, auditBasin(p, id, ox, oz));
            if (selection.state != OutletState.CANDIDATE) return new BasinChain(selection.state, ids, edges);
            edges.add(selection.edge);
            if (selection.edge.terminal == BasinEnd.SEA) return new BasinChain(OutletState.CANDIDATE, ids, edges);
            id = selection.edge.downstreamBasin;
        }
        return new BasinChain(OutletState.CYCLE, ids, edges);
    }

    record ShoreWitness(boolean passable, double barrier, List<WorldCell> path, int sampled) {
        ShoreWitness { path = List.copyOf(path); }
    }

    /** Four-neighbour block-scale witness inside two cell patches; failure is only corridor-pending. */
    static ShoreWitness refineShore(ShoreEdge edge, int step, double water, WindowOutlets.Terrain terrain) {
        if (step < 2 || step > 8 || step % 2 != 0
                || Math.abs(edge.inner.x - edge.outer.x) > step || Math.abs(edge.inner.z - edge.outer.z) > step)
            throw new IllegalArgumentException("fixed adjacent-cell corridor");
        int half = step / 2;
        int ox = Math.min(edge.inner.x, edge.outer.x) - half, oz = Math.min(edge.inner.z, edge.outer.z) - half;
        int nx = Math.abs(edge.inner.x - edge.outer.x) + step + 1;
        int nz = Math.abs(edge.inner.z - edge.outer.z) + step + 1, cells = nx * nz;
        double[] bed = new double[cells], cost = new double[cells];
        int[] previous = new int[cells];
        java.util.Arrays.fill(cost, Double.POSITIVE_INFINITY); java.util.Arrays.fill(previous, -1);
        for (int k = 0; k < cells; k++) {
            bed[k] = terrain.height(ox + k / nz, oz + k % nz);
            if (!Double.isFinite(bed[k])) throw new IllegalArgumentException("nonfinite corridor bed");
        }
        int start = cornerMinimum(edge.inner, half, ox, oz, nz, bed);
        int target = cornerMinimum(edge.outer, half, ox, oz, nz, bed);
        record Pixel(int cell, double cost) { }
        var queue = new java.util.PriorityQueue<Pixel>(java.util.Comparator.comparingDouble(Pixel::cost)
                .thenComparingInt(Pixel::cell));
        cost[start] = bed[start]; queue.add(new Pixel(start, cost[start]));
        int[][] dirs = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("shore refinement");
            Pixel node = queue.remove();
            if (node.cost != cost[node.cell]) continue;
            if (node.cell == target) {
                var path = new ArrayList<WorldCell>();
                for (int k = target; k >= 0; k = previous[k]) path.add(new WorldCell(ox + k / nz, oz + k % nz));
                java.util.Collections.reverse(path);
                return new ShoreWitness(node.cost <= water + 1e-8, node.cost, path, cells);
            }
            int x = node.cell / nz, z = node.cell % nz;
            for (int[] d : dirs) {
                int i = x + d[0], j = z + d[1];
                if (i < 0 || j < 0 || i >= nx || j >= nz) continue;
                int k = i * nz + j;
                double next = Math.max(node.cost, bed[k]);
                if (next < cost[k]) { cost[k] = next; previous[k] = node.cell; queue.add(new Pixel(k, next)); }
            }
        }
        throw new IllegalStateException("connected corridor exhausted");
    }

    private static int cornerMinimum(WorldCell center, int half, int ox, int oz, int nz, double[] bed) {
        int best = -1;
        for (int dx : new int[]{-half, half}) for (int dz : new int[]{-half, half}) {
            int k = (center.x + dx - ox) * nz + center.z + dz - oz;
            if (best < 0 || bed[k] < bed[best]) best = k;
        }
        return best;
    }

    record CornerRoute(WorldCell from, WorldCell to, double barrier, List<WorldCell> path) {
        CornerRoute { path = List.copyOf(path); }
        List<WorldCell> oriented(WorldCell start) {
            if (from.equals(start)) return path;
            if (!to.equals(start)) throw new IllegalArgumentException("route endpoint");
            var reverse = new ArrayList<>(path); java.util.Collections.reverse(reverse); return List.copyOf(reverse);
        }
    }
    record CellConnectivity(WorldCell center, int step, List<WorldCell> corners,
                            List<Double> cornerBeds, List<WorldCell> ports, List<Double> portBeds,
                            List<CornerRoute> routes) {
        CellConnectivity {
            corners = List.copyOf(corners); cornerBeds = List.copyOf(cornerBeds);
            ports = List.copyOf(ports); portBeds = List.copyOf(portBeds); routes = List.copyOf(routes);
        }
    }
    private record CornerSearch(WorldCell cell, double barrier, int length) { }

    /** Symmetric hydraulic capacity, not a downstream direction or an actual water surface. */
    static CellConnectivity cellConnectivity(WorldCell center, int step, WindowOutlets.Terrain terrain) {
        if (step < 2 || step > 8 || step % 2 != 0
                || Math.floorMod(center.x, step) != 0 || Math.floorMod(center.z, step) != 0)
            throw new IllegalArgumentException("fixed world-aligned cell");
        int half = step / 2;
        var bed = new java.util.TreeMap<WorldCell, Double>(java.util.Comparator
                .comparingInt(WorldCell::x).thenComparingInt(WorldCell::z));
        for (int x = center.x - half; x <= center.x + half; x++)
            for (int z = center.z - half; z <= center.z + half; z++) {
                double h = terrain.height(x, z);
                if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite cell bed");
                bed.put(new WorldCell(x, z), h);
            }
        var corners = new ArrayList<WorldCell>();
        for (int dx : new int[]{-half, half}) for (int dz : new int[]{-half, half})
            corners.add(new WorldCell(center.x + dx, center.z + dz));
        var ports = new ArrayList<WorldCell>();
        for (var point : bed.keySet())
            if (Math.abs(point.x - center.x) == half || Math.abs(point.z - center.z) == half) ports.add(point);
        return new CellConnectivity(center, step, corners, corners.stream().map(bed::get).toList(),
                ports, ports.stream().map(bed::get).toList(), capacityTree(bed));
    }

    /** A minimum spanning tree preserves every threshold-connected component, not shortest routes. */
    private static List<CornerRoute> capacityTree(java.util.Map<WorldCell, Double> bed) {
        var edges = new ArrayList<CornerRoute>();
        var points = new ArrayList<>(bed.keySet());
        var indices = new java.util.HashMap<WorldCell, Integer>();
        int[] components = new int[points.size()];
        for (int i = 0; i < points.size(); i++) { indices.put(points.get(i), i); components[i] = i; }
        for (WorldCell from : points) for (int[] d : new int[][]{{0, 1}, {1, 0}}) {
            var to = new WorldCell(from.x + d[0], from.z + d[1]);
            if (bed.containsKey(to)) edges.add(new CornerRoute(from, to,
                    Math.max(bed.get(from), bed.get(to)), List.of(from, to)));
        }
        edges.sort(java.util.Comparator.comparingDouble(CornerRoute::barrier)
                .thenComparingInt(r -> r.from.x).thenComparingInt(r -> r.from.z)
                .thenComparingInt(r -> r.to.x).thenComparingInt(r -> r.to.z));
        var tree = new ArrayList<CornerRoute>();
        for (var edge : edges) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("capacity tree");
            int a = component(components, indices.get(edge.from)), b = component(components, indices.get(edge.to));
            if (a == b) continue;
            components[b] = a; tree.add(edge);
            if (tree.size() == points.size() - 1) break;
        }
        return List.copyOf(tree);
    }

    private static int component(int[] components, int k) {
        while (components[k] != k) { components[k] = components[components[k]]; k = components[k]; }
        return k;
    }

    /** Compose explicit world-boundary nodes; preserve four-neighbour minimax capacity, not shortest geometry. */
    static ShoreWitness composeCells(List<CellConnectivity> cells, WorldCell from, WorldCell to, double water) {
        if (cells.isEmpty() || cells.size() > 16) throw new IllegalArgumentException("fixed cell graph bound");
        var graph = new java.util.HashMap<WorldCell, List<CornerRoute>>();
        var unique = new java.util.HashSet<WorldCell>();
        int sampled = 0;
        for (var cell : cells) {
            if (!unique.add(cell.center)) throw new IllegalArgumentException("duplicate cell");
            sampled += (cell.step + 1) * (cell.step + 1);
            for (var route : cell.routes) {
                graph.computeIfAbsent(route.from, k -> new ArrayList<>()).add(route);
                graph.computeIfAbsent(route.to, k -> new ArrayList<>()).add(route);
            }
        }
        if (!graph.containsKey(from) || !graph.containsKey(to)) throw new IllegalArgumentException("corner endpoints required");
        if (compareCells(from, to) > 0) {
            var result = composeCells(cells, to, from, water);
            var reverse = new ArrayList<>(result.path); java.util.Collections.reverse(reverse);
            return new ShoreWitness(result.passable, result.barrier, reverse, result.sampled);
        }
        var firstCell = cells.stream().filter(c -> c.ports.contains(from)).findFirst().orElseThrow();
        double startBed = firstCell.portBeds.get(firstCell.ports.indexOf(from));
        var queue = new java.util.PriorityQueue<CornerSearch>(java.util.Comparator
                .comparingDouble(CornerSearch::barrier).thenComparingInt(CornerSearch::length)
                .thenComparingInt(s -> s.cell.x).thenComparingInt(s -> s.cell.z));
        var best = new java.util.HashMap<WorldCell, CornerSearch>();
        var previous = new java.util.HashMap<WorldCell, CornerRoute>();
        var start = new CornerSearch(from, startBed, 0); best.put(from, start); queue.add(start);
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("corner composition");
            var node = queue.remove();
            if (!node.equals(best.get(node.cell))) continue;
            if (node.cell.equals(to)) return composedWitness(from, to, previous, node.barrier, water, sampled);
            var routes = new ArrayList<>(graph.get(node.cell));
            routes.sort(java.util.Comparator.comparingDouble(CornerRoute::barrier)
                    .thenComparingInt(r -> r.from.x).thenComparingInt(r -> r.from.z)
                    .thenComparingInt(r -> r.to.x).thenComparingInt(r -> r.to.z)
                    .thenComparing(r -> r.path.toString()));
            for (var route : routes) {
                var next = route.from.equals(node.cell) ? route.to : route.from;
                double barrier = Math.max(node.barrier, route.barrier);
                int length = node.length + route.path.size() - 1;
                var old = best.get(next);
                if (old == null || barrier < old.barrier || (barrier == old.barrier && length < old.length)) {
                    var value = new CornerSearch(next, barrier, length);
                    best.put(next, value); previous.put(next, route); queue.add(value);
                }
            }
        }
        return new ShoreWitness(false, Double.POSITIVE_INFINITY, List.of(), sampled);
    }

    private static ShoreWitness composedWitness(WorldCell from, WorldCell to,
            java.util.Map<WorldCell, CornerRoute> previous, double barrier, double water, int sampled) {
        var pieces = new ArrayList<List<WorldCell>>();
        for (WorldCell k = to; !k.equals(from); ) {
            var route = previous.get(k);
            WorldCell start = route.from.equals(k) ? route.to : route.from;
            pieces.add(route.oriented(start)); k = start;
        }
        java.util.Collections.reverse(pieces);
        var path = new ArrayList<WorldCell>(); path.add(from);
        for (var piece : pieces) path.addAll(piece.subList(1, piece.size()));
        return new ShoreWitness(barrier <= water + 1e-8, barrier, path, sampled);
    }

    private static int compareCells(WorldCell a, WorldCell b) {
        int x = Integer.compare(a.x, b.x);
        return x == 0 ? Integer.compare(a.z, b.z) : x;
    }

    private static WorldCell worldCell(HydrologyPipeline.Result p, int k, int ox, int oz) {
        return new WorldCell(ox + k / p.nz() * p.cellBlocks(), oz + k % p.nz() * p.cellBlocks());
    }

    private SharedBoundaryDrainage() { }

    static Domain build(Key key, int tract, int halo, int step, double sea,
                        WindowOutlets.Terrain terrain, WindowOutlets.Terrain outside) {
        int along = key.alongTile * tract + tract / 2;
        int cx = key.axis == 0 ? key.boundary : along;
        int cz = key.axis == 0 ? along : key.boundary;
        return buildAt(key, cx, cz, tract / 2 + halo, tract, step, sea, terrain, outside);
    }

    static Domain buildAt(Key key, int cx, int cz, int half, int tract, int step, double sea,
                          WindowOutlets.Terrain terrain, WindowOutlets.Terrain outside) {
        if (half > 512 || half % step != 0) throw new IllegalArgumentException("fixed domain bound/alignment");
        int n = 2 * half / step + 1, ox = cx - half, oz = cz - half;
        var built = WindowOutlets.build(n, n, cx, cz, half, step, sea, terrain);
        double[] exterior = built.exterior().clone();
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i != 0 && j != 0 && i != n - 1 && j != n - 1) continue;
            int oi = i == 0 ? -1 : i == n - 1 ? n : i;
            int oj = j == 0 ? -1 : j == n - 1 ? n : j;
            exterior[i * n + j] = Math.max(sea, outside.height(ox + oi * step, oz + oj * step));
        }
        var surface = EquilibriumWater.compute(built.bed(), n, n, built.drains(), exterior);
        return new Domain(key, ox, oz, n, step, tract, surface.parent(), surface.level());
    }
}
