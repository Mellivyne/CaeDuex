package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.Action;
import com.cheatprogram.parkour.ParkourPhysics.Box;
import com.cheatprogram.parkour.ParkourPhysics.Parameters;
import com.cheatprogram.parkour.ParkourPhysics.State;
import com.cheatprogram.parkour.ParkourPhysics.Terrain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, tick-layered motion search over immutable collision snapshots.
 * All edges are simulated inputs, and every edge costs exactly one game tick.
 * Beam pruning and quantization sacrifice completeness and global optimality.
 * Results describe the earliest completion retained by this search, never a
 * proof of a globally fastest route. Partial results always end on support.
 */
public final class ParkourPlanner {
    private ParkourPlanner() { }

    public record Goal(double x, double y, double z, double radius, double height,Box region) {
        public Goal(double x,double y,double z,double radius,double height){this(x,y,z,radius,height,null);}
        public Goal {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(radius) || !Double.isFinite(height)
                    || radius <= 0 || height <= 0)
                throw new IllegalArgumentException("Invalid checkpoint region");
            if(region!=null&&(!Double.isFinite(region.minX())||!Double.isFinite(region.minY())||!Double.isFinite(region.minZ())
                    ||!Double.isFinite(region.maxX())||!Double.isFinite(region.maxY())||!Double.isFinite(region.maxZ())
                    ||region.minX()>=region.maxX()||region.minY()>=region.maxY()||region.minZ()>=region.maxZ()
                    ||region.maxX()-region.minX()>4||region.maxZ()-region.minZ()>4
                    ||Math.abs(region.maxY()-y)>.001||x<region.minX()||x>region.maxX()||z<region.minZ()||z>region.maxZ()))
                throw new IllegalArgumentException("Invalid selected support face");
        }
        /** Circular horizontal region and symmetric vertical feet tolerance. */
        public boolean reached(State state) {
            if(region!=null)return Math.abs(state.y()-y)<=height
                &&state.x()+.3>region.minX()+1e-6&&state.x()-.3<region.maxX()-1e-6
                &&state.z()+.3>region.minZ()+1e-6&&state.z()-.3<region.maxZ()-1e-6;
            return Math.hypot(state.x() - x, state.z() - z) <= radius
                    && Math.abs(state.y() - y) <= height;
        }
    }

    public record Options(int horizon, int maxNodes, int beamWidth, long budgetNanos,
                          boolean sprint, boolean jump, boolean airControl, float maxTurnDegrees) {
        public Options {
            if (horizon < 1 || horizon > 1200 || maxNodes < 1 || maxNodes > 2_000_000
                    || beamWidth < 1 || beamWidth > 2048 || budgetNanos < 1
                    || !Float.isFinite(maxTurnDegrees) || maxTurnDegrees < 0 || maxTurnDegrees > 180)
                throw new IllegalArgumentException("Invalid parkour search budget");
        }
    }

    public record Result(List<State> states, List<Action> actions, boolean reached,
                         int expanded, long elapsedNanos, String reason) {
        public Result {
            states = List.copyOf(states);
            actions = List.copyOf(actions);
        }
    }

    private record Node(State state, Node parent, Action action, int checkpoint,
                        int depth, double estimate, long order,boolean guided) { }
    private record Key(long x, long y, long z, long vx, long vy, long vz, int yaw,
                       boolean grounded, boolean sprinting, boolean sprintBlocked,int cooldown, int checkpoint,long fall,boolean guided) { }
    private record Region(long x, long y, long z, int checkpoint, boolean grounded,boolean guided) { }
    private static final Comparator<Node> ORDER = Comparator.comparingDouble(Node::estimate)
            .thenComparingInt(Node::depth).thenComparingLong(Node::order);

    /** Does not read a live Minecraft world and does not mutate the starting state. */
    public static Result search(Terrain terrain, Parameters physics, State start,
                                List<Goal> checkpoints, Options options) {
        long begun = System.nanoTime();
        List<Goal> goals = List.copyOf(checkpoints);
        if (goals.isEmpty()) return new Result(List.of(start), List.of(), false, 0, 0, "NO_TARGET");
        int first = advance(goals, 0, start);
        // Protect the momentum-sensitive compound before running a potentially
        // expensive walking/maze guide. A complete validated jump chain does
        // not need coarse guidance at all.
        ParkourManeuvers.Attempt maneuver=ParkourManeuvers.search(terrain,physics,start,goals,options,
            begun+Math.min(120_000_000L,options.budgetNanos()*2/5),Math.min(30000,options.maxNodes()/3));
        Result prepared=maneuver.route();
        if(prepared!=null&&!prepared.reached()&&prepared.actions().size()>=Math.max(24,options.horizon()/2)
            &&safeRelease(terrain,physics,prepared.states().getLast())){
            // A long fully replayed compound already fills this local window.
            // Publish it now so its continuation can start immediately, instead
            // of spending the remaining budget rediscovering a slower prefix.
            return new Result(prepared.states(),prepared.actions(),false,maneuver.expanded(),
                System.nanoTime()-begun,"PREPARED_COMPOUND_PREFIX");
        }
        ParkourGuide[] guides=new ParkourGuide[goals.size()];
        // Spend only a bounded fraction of the same search budget on coarse
        // obstacle routing; the resulting hints never bypass tick physics.
        long guideDeadline=Math.min(begun+options.budgetNanos()*2/3,
            System.nanoTime()+Math.min(180_000_000L,options.budgetNanos()/3));
        State guideStart=start;
        for(int i=first;i<goals.size()&&(maneuver.route()==null||!maneuver.route().reached())&&System.nanoTime()<guideDeadline;i++){
            guides[i]=ParkourGuide.build(terrain,physics,guideStart,goals.get(i),options.jump(),guideDeadline);
            Goal g=goals.get(i);guideStart=new State(g.x(),g.y(),g.z(),0,-.0784,0,guideStart.yaw(),true,false,0,0);
        }
        Node root = new Node(start, null, null, first, 0, estimate(goals, first, start,guides,true), 0,true);
        Node directRoot=new Node(start,null,null,first,0,estimate(goals,first,start,guides,false),0,false);
        boolean split=java.util.Arrays.stream(guides).anyMatch(java.util.Objects::nonNull);
        if (first == goals.size() && start.onGround() && safeRelease(terrain,physics,start))
            return result(root, true, 0, begun, "ALREADY_REACHED");
        List<Node> frontier = split?List.of(root,directRoot):List.of(root);
        Map<Key, Integer> earliest = new HashMap<>();
        earliest.put(key(root), 0);if(split)earliest.put(key(directRoot),0);
        Node partial = null;
        int expanded = 0;
        long order = 0;
        Node incumbent = null;
        Node maneuverPartial = null;
        expanded+=maneuver.expanded();
        if(maneuver.route()!=null){
            Node node=root;Result candidate=maneuver.route();
            for(int i=0;i<candidate.actions().size();i++){
                State next=candidate.states().get(i+1);int point=ParkourCourse.advance(goals,node.checkpoint(),node.state(),next);
                node=new Node(next,node,candidate.actions().get(i),point,i+1,estimate(goals,point,next,guides,true),++order,true);
            }
            if(candidate.reached())incumbent=node;else partial=maneuverPartial=node;
        }
        // Cheap legal rollouts provide an upper bound before beam pruning. They
        // compete on completion ticks; they never replace collision simulation.
        for(int strategy=0;strategy<(split?12:6);strategy++){
            Node node=strategy>=6?directRoot:root;int control=strategy%6;
            for(int depth=1;depth<=options.horizon();depth++){
                if(Thread.currentThread().isInterrupted())return result(root,false,expanded,begun,"CANCELLED");
                if(expanded>=options.maxNodes()||System.nanoTime()-begun>=options.budgetNanos())break;
                int targetIndex=Math.min(node.checkpoint(),goals.size()-1);
                Goal goal=goals.get(targetIndex);State s=node.state();
                if(node.guided()&&guides[targetIndex]!=null)goal=guides[targetIndex].aim(s);
                float desired=(float)Math.toDegrees(Math.atan2(-(goal.x()-s.x()),goal.z()-s.z()));
                float yaw=!s.onGround()&&!options.airControl()?s.yaw():turn(s.yaw(),desired,options.maxTurnDegrees());
                boolean jumping=control==1&&options.jump()&&(s.onGround()||terrain.climbable(s.x(),s.y(),s.z()));
                if(control>=3&&options.jump()&&s.onGround()){
                    // Distinct legal takeoff timings: probe ahead without jump,
                    // then compare jumping now with the other rollouts. Probe
                    // work shares both the node and wall-clock search budgets.
                    State probe=s;int lead=control==3?1:control==4?2:4;
                    for(int i=0;i<lead;i++){
                        if(expanded>=options.maxNodes()||System.nanoTime()-begun>=options.budgetNanos())break;
                        expanded++;State n=ParkourPhysics.step(terrain,physics,probe,new Action(yaw,1,0,false,options.sprint()));
                        if(n==null||!n.onGround()||Math.hypot(n.x()-probe.x(),n.z()-probe.z())<.01){jumping=true;break;}
                        probe=n;
                    }
                }
                if(expanded>=options.maxNodes()||System.nanoTime()-begun>=options.budgetNanos())break;
                Action action=new Action(yaw,1,0,jumping,control!=2&&options.sprint());
                expanded++;State next=ParkourPhysics.step(terrain,physics,s,action);if(next==null)break;
                int checkpoint=ParkourCourse.advance(goals,node.checkpoint(),s,next);
                node=new Node(next,node,action,checkpoint,depth,estimate(goals,checkpoint,next,guides,node.guided()),++order,node.guided());
                if(checkpoint==goals.size()&&goals.getLast().reached(next)&&next.onGround()&&safeRelease(terrain,physics,next)){
                    if(incumbent==null||depth<incumbent.depth())incumbent=node;break;
                }
            }
        }
        String stop = "HORIZON";
        int transitionAttempts=0;
        Set<String> transitionStarts=new HashSet<>();
        search:
        for (int depth = 1; depth <= options.horizon() && (incumbent==null||depth<incumbent.depth()); depth++) {
            Map<Key, Node> candidates = new HashMap<>();
            for (Node node : frontier) {
                State contact=node.state();
                if(incumbent==null&&maneuverPartial==null&&transitionAttempts<5&&node.parent()!=null&&contact.onGround()
                    &&(!node.parent().state().onGround()||node.checkpoint()>node.parent().checkpoint()||node.depth()%4==0)
                    &&options.horizon()-node.depth()>=24&&node.checkpoint()<goals.size()
                    &&nearWall(terrain,physics,contact,goals.get(node.checkpoint()))){
                    String entry=Math.round(contact.x()*3)+","+Math.round(contact.y()*3)+","+Math.round(contact.z()*3)+","+node.checkpoint();
                    if(transitionStarts.add(entry)&&expanded<options.maxNodes()&&System.nanoTime()-begun<options.budgetNanos()){
                        transitionAttempts++;
                        Options remaining=new Options(options.horizon()-node.depth(),options.maxNodes(),options.beamWidth(),options.budgetNanos(),options.sprint(),options.jump(),options.airControl(),options.maxTurnDegrees());
                        ParkourManeuvers.Attempt continuation=ParkourManeuvers.search(terrain,physics,contact,goals.subList(node.checkpoint(),goals.size()),remaining,
                            begun+options.budgetNanos(),Math.min(18000,options.maxNodes()-expanded));
                        expanded+=continuation.expanded();
                        if(continuation.route()!=null&&!continuation.route().actions().isEmpty()){
                            Node joined=node;Result leg=continuation.route();
                            for(int i=0;i<leg.actions().size();i++){
                                State next=leg.states().get(i+1);int point=ParkourCourse.advance(goals,joined.checkpoint(),joined.state(),next);
                                joined=new Node(next,joined,leg.actions().get(i),point,node.depth()+i+1,estimate(goals,point,next,guides,node.guided()),++order,node.guided());
                            }
                            if(joined.checkpoint()==goals.size()&&goals.getLast().reached(joined.state())&&safeRelease(terrain,physics,joined.state()))
                                return result(joined,true,expanded,begun,"VALIDATED_ENTRY_TRANSITION");
                            if(leg.actions().size()>=24&&safeRelease(terrain,physics,joined.state()))
                                return result(joined,false,expanded,begun,"PREPARED_ENTRY_TRANSITION");
                        }
                    }
                }
                int targetIndex=Math.min(node.checkpoint(),goals.size()-1);
                Goal target = goals.get(targetIndex);
                if(node.guided()&&guides[targetIndex]!=null)target=guides[targetIndex].aim(node.state());
                Box body=ParkourPhysics.bounds(node.state(),physics);
                Box nearby=new Box(body.minX()-.35,body.minY()+.01,body.minZ()-.35,body.maxX()+.35,body.maxY(),body.maxZ()+.35);
                boolean constrained=!terrain.collisions(nearby).isEmpty();
                for (Action action : actions(node.state(), target, options,terrain.climbable(node.state().x(),node.state().y(),node.state().z()),constrained)) {
                    if (Thread.currentThread().isInterrupted()) { stop = "CANCELLED"; break search; }
                    if (expanded >= options.maxNodes()) { stop = "NODE_LIMIT"; break search; }
                    if (System.nanoTime() - begun >= options.budgetNanos()) { stop = "TIME_LIMIT"; break search; }
                    expanded++;
                    State next = ParkourPhysics.step(terrain, physics, node.state(), action);
                    if (next == null) continue;
                    int checkpoint = ParkourCourse.advance(goals, node.checkpoint(), node.state(),next);
                    Node child = new Node(next, node, action, checkpoint, depth,
                            estimate(goals, checkpoint, next,guides,node.guided()), ++order,node.guided());
                    if (checkpoint == goals.size() && goals.getLast().reached(next) && next.onGround() && safeRelease(terrain,physics,next)) {
                        return result(child, true, expanded, begun, "FASTEST_RETAINED");
                    }
                    if (next.onGround() && (checkpoint > first || child.estimate() + .5 < (child.guided()?root:directRoot).estimate())
                            && (partial == null || betterPartial(child, partial))) partial = child;
                    Key key = key(child);
                    Integer earlier = earliest.get(key);
                    if (earlier != null && earlier < depth) continue;
                    Node old = candidates.get(key);
                    if (old == null || ORDER.compare(child, old) < 0) candidates.put(key, child);
                }
            }
            if (candidates.isEmpty()) { stop = "NO_SAFE_ROUTE"; break; }
            List<Node> ranked = new ArrayList<>(candidates.values());
            ranked.sort(ORDER);
            // Prevent almost identical positions from monopolizing the whole beam.
            // The full velocity-aware key still distinguishes their dynamics.
            List<Node> nextFrontier = new ArrayList<>(options.beamWidth());
            Map<Region, Integer> occupancy = new HashMap<>();
            Set<Key> retained = new HashSet<>();
            int perRegion = Math.max(2, options.beamWidth() / 12);
            // Keep independent guided and free-heading lanes: floating hints
            // may be optimistic, while a direct lane can discover a better takeoff.
            for(int lane=0;lane<(split?2:1);lane++){
            int limit=split?(lane==0?Math.max(1,options.beamWidth()*3/4):options.beamWidth()):options.beamWidth();
            for (Node node : ranked) {
                if(split&&node.guided()!=(lane==0))continue;
                Region region = region(node);
                int count = occupancy.getOrDefault(region, 0);
                if (count >= perRegion) continue;
                occupancy.put(region, count + 1);
                nextFrontier.add(node); retained.add(key(node));
                if (nextFrontier.size() == limit) break;
            }
            }
            for (Node node : ranked) {
                if (nextFrontier.size() == options.beamWidth()) break;
                if (retained.add(key(node))) nextFrontier.add(node);
            }
            for (Node node : nextFrontier) earliest.putIfAbsent(key(node), depth);
            frontier = nextFrontier;
        }
        // Cancellation may represent an unloaded world or a replaced target.
        if (stop.equals("CANCELLED")) return result(root, false, expanded, begun, stop);
        if(incumbent!=null)return result(incumbent,true,expanded,begun,"FASTEST_FOUND_"+stop);
        // A geometrically nearer grounded beam endpoint can consume the next
        // neo's runway. Prefer the validated obstacle exit unless another
        // candidate has actually reached more user checkpoints.
        if(maneuverPartial!=null&&(partial==null||partial.checkpoint()<=maneuverPartial.checkpoint()))partial=maneuverPartial;
        while(partial!=null&&!safeRelease(terrain,physics,partial.state()))partial=partial.parent();
        return result(partial == null ? root : partial, false, expanded, begun,
                partial == null ? stop : "PARTIAL_" + stop);
    }

    /** Only probe a newly reached contact near a tall forward obstacle. Ordinary
     * walking/air search stays tick-based; compounds are replayed from the
     * actual predicted landing momentum, not a synthetic standing start. */
    private static boolean nearWall(Terrain terrain,Parameters p,State state,Goal goal){
        double dx=goal.x()-state.x(),dz=goal.z()-state.z(),length=Math.hypot(dx,dz);
        if(length<1)return false;
        Box query=new Box(state.x()-4,state.y()+.01,state.z()-4,state.x()+4,state.y()+p.height()+.1,state.z()+4);
        for(Box b:terrain.collisions(query))if(b.maxY()>=state.y()+p.height()-.01
            &&((b.minX()+b.maxX())/2-state.x())*dx+((b.minZ()+b.maxZ())/2-state.z())*dz>0)return true;
        return false;
    }

    /** A returned endpoint must remain supported while native friction settles it. */
    static boolean safeRelease(Terrain terrain,Parameters p,State state){
        if(!state.onGround())return false;
        State s=state;
        for(int i=0;i<160;i++){
            s=ParkourPhysics.step(terrain,p,s,new Action(s.yaw(),0,0,false,false));
            if(s==null||!s.onGround()||Math.abs(s.y()-state.y())>.05)return false;
            if(Math.hypot(s.vx(),s.vz())<.003)return true;
        }
        return false;
    }

    private static boolean betterPartial(Node a, Node b) {
        return a.checkpoint() > b.checkpoint()
                || a.checkpoint() == b.checkpoint() && a.guided()&&!b.guided()
                || a.checkpoint() == b.checkpoint() && a.guided()==b.guided() && (a.estimate() < b.estimate() - .02
                || Math.abs(a.estimate() - b.estimate()) <= .02 && a.depth() < b.depth());
    }

    private static int advance(List<Goal> goals, int checkpoint, State state) {
        return ParkourCourse.advance(goals,checkpoint,state);
    }

    private static double estimate(List<Goal> goals, int checkpoint, State state,ParkourGuide[] guides,boolean guided) {
        if (checkpoint == goals.size()) return state.onGround() ? 0 : 1 + Math.abs(state.vy()) * 4;
        Goal goal = goals.get(checkpoint);
        double dx = goal.x() - state.x(), dz = goal.z() - state.z();
        double horizontal = Math.hypot(dx, dz);
        double remaining = !guided||guides[checkpoint]==null?Math.max(0,horizontal-goal.radius()):guides[checkpoint].distance(state);
        for (int i = checkpoint + 1; i < goals.size(); i++) {
            Goal a = goals.get(i - 1), b = goals.get(i);
            remaining += Math.hypot(b.x() - a.x(), b.z() - a.z()) + Math.abs(b.y() - a.y()) * .5;
        }
        double toward = horizontal < 1e-6 ? 0 : (state.vx() * dx + state.vz() * dz) / horizontal;
        // A ranking heuristic only, not an admissible remaining-time bound.
        return remaining / .28 + Math.max(0, goal.y() - state.y()) * 3
                + Math.max(0, state.y() - goal.y()) * .15 - toward * 2;
    }

    private static List<Action> actions(State state, Goal goal, Options options,boolean climbing,boolean constrained) {
        float desired = (float) Math.toDegrees(Math.atan2(-(goal.x() - state.x()), goal.z() - state.z()));
        List<Action> result = new ArrayList<>(26);
        boolean airborneLocked = !state.onGround() && !options.airControl();
        float aimed = turn(state.yaw(), desired, options.maxTurnDegrees());
        float[] headings = airborneLocked ? new float[]{state.yaw()}
                : new float[]{aimed, state.yaw(), turn(state.yaw(), desired - 35, options.maxTurnDegrees()),
                turn(state.yaw(), desired + 35, options.maxTurnDegrees()),
                turn(state.yaw(), desired - 75, options.maxTurnDegrees()),
                turn(state.yaw(), desired + 75, options.maxTurnDegrees())};
        Set<Action> unique = new HashSet<>();
        for (float yaw : headings) {
            add(result, unique, new Action(yaw, 1, 0, false, options.sprint()));
            if (options.jump() && (state.onGround()||climbing))
                add(result, unique, new Action(yaw, 1, 0, true, options.sprint()));
        }
        if(constrained&&!airborneLocked)for(float offset:new float[]{-16,-8,8,16}){
            float yaw=turn(state.yaw(),desired+offset,options.maxTurnDegrees());
            add(result,unique,new Action(yaw,1,0,false,options.sprint()));
            if(options.jump()&&state.onGround())add(result,unique,new Action(yaw,1,0,true,options.sprint()));
        }
        if (!airborneLocked) {
            for (int strafe : new int[]{-1, 1}) {
                add(result, unique, new Action(aimed, 1, strafe, false, options.sprint()));
                add(result, unique, new Action(state.yaw(), 0, strafe, false, false));
                if (options.jump() && (state.onGround()||climbing))
                    add(result, unique, new Action(aimed, 1, strafe, true, options.sprint()));
            }
            add(result, unique, new Action(aimed, -1, 0, false, false));
            add(result, unique, new Action(aimed, 0, 0, false, false));
            // Walking is a separate legal way to settle on narrow landing regions.
            if (options.sprint()) add(result, unique, new Action(aimed, 1, 0, false, false));
            if (options.jump() && state.onGround()) add(result, unique, new Action(aimed, 0, 0, true, false));
        }
        return result;
    }

    private static void add(List<Action> output, Set<Action> seen, Action action) {
        if (seen.add(action)) output.add(action);
    }

    private static float turn(float current, float desired, float maximum) {
        float delta = wrap(desired - current);
        return wrap(current + Math.max(-maximum, Math.min(maximum, delta)));
    }
    private static float wrap(float yaw) { return (float) (yaw - Math.floor((yaw + 180) / 360) * 360); }
    private static long quantize(double value, double resolution) { return Math.round(value / resolution); }
    private static Key key(Node node) {
        State s = node.state();
        return new Key(quantize(s.x(), .06), quantize(s.y(), .04), quantize(s.z(), .06),
                quantize(s.vx(), .018), quantize(s.vy(), .018), quantize(s.vz(), .018),
                Math.round(wrap(s.yaw()) / 4), s.onGround(), s.sprinting(),s.sprintBlocked(), s.jumpCooldown(), node.checkpoint(),quantize(s.fallDistance(),.05),node.guided());
    }
    private static Region region(Node node) {
        State s = node.state();
        return new Region(quantize(s.x(), .35), quantize(s.y(), .3), quantize(s.z(), .35), node.checkpoint(), s.onGround(),node.guided());
    }
    private static Result result(Node node, boolean reached, int expanded, long begun, String reason) {
        List<State> states = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent()) {
            states.add(n.state());
            if (n.action() != null) actions.add(n.action());
        }
        java.util.Collections.reverse(states); java.util.Collections.reverse(actions);
        return new Result(states, actions, reached, expanded, System.nanoTime() - begun, reason);
    }
}
