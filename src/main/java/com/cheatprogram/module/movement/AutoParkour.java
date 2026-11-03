package com.cheatprogram.module.movement;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.ai.AiController;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import com.cheatprogram.render.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.*;

/** Planner and controller exchange immutable trajectories; only native input is applied. */
public final class AutoParkour extends Module {
    // Hidden legacy settings are read only to migrate existing coordinates/courses.
    private final ModeSetting targetMode=add(new ModeSetting("targetMode","parkourWaypoints","parkourWaypoints","parkourBlock","parkourPosition","parkourCourse")).visibleWhen(()->false);
    private final TextSetting coordinates=add(new TextSetting("coordinates","0,64,0",80)).visibleWhen(()->false);
    private final TextSetting course=add(new TextSetting("course","",Integer.MAX_VALUE)).visibleWhen(()->false);
    private final ParkourWaypointSetting waypointList=add(new ParkourWaypointSetting());
    private final NumberSetting markingKey=add(new NumberSetting("waypointKey",org.lwjgl.glfw.GLFW.GLFW_KEY_F5,-1,348,1)).visibleWhen(()->false);
    private final BoolSetting start=add(new BoolSetting("start",false));
    private final BoolSetting restartCourse=add(new BoolSetting("restartCourse",false));
    private final BoolSetting autoSprint=add(new BoolSetting("autoSprint",true)),autoJump=add(new BoolSetting("autoJump",true));
    private final BoolSetting recovery=add(new BoolSetting("recovery",true)),airControl=add(new BoolSetting("airControl",true));
    private final NumberSetting radius=add(new NumberSetting("scanRadius",18,8,40,1,"blocks"));
    private final BoolSetting wideSearch=add(new BoolSetting("wideSearch",true));
    private final NumberSetting vertical=add(new NumberSetting("scanHeight",8,4,16,1,"blocks"));
    private final NumberSetting budget=add(new NumberSetting("searchBudget",350,20,1500,10,"ms"));
    private final NumberSetting horizon=add(new NumberSetting("horizon",100,20,240,10,"ticks"));
    private final NumberSetting nodes=add(new NumberSetting("nodes",100000,10000,500000,10000));
    private final NumberSetting beam=add(new NumberSetting("beam",96,16,256,16));
    private final ModeSetting rotation=add(new ModeSetting("rotation","parkourSmooth","parkourSmooth","parkourFast","parkourKeep"));
    private final NumberSetting turn=add(new NumberSetting("turn",25,5,90,1,"°"));
    private final NumberSetting precision=add(new NumberSetting("precision",.3,.1,.8,.05,"blocks"));
    private final NumberSetting deviation=add(new NumberSetting("deviation",.12,.04,.4,.01,"blocks"));
    private final BoolSetting show=add(new BoolSetting("showPath",true)),glow=add(new BoolSetting("glow",true));
    private final ModeSetting palette=add(new ModeSetting("pathColor","parkourPride","parkourWhite","parkourPride","parkourRainbow"));
    private final BoolSetting bold=add(new BoolSetting("pathBold",false));
    private final NumberSetting ribbonWidth=add(new NumberSetting("ribbonWidth",.18,.04,.8,.01,"blocks")).visibleWhen(bold::on);
    private final NumberSetting linger=add(new NumberSetting("pathLinger",3,0,30,.5,"s"));
    private final NumberSetting width=add(new NumberSetting("pathWidth",2,1,6,.25));
    private final NumberSetting opacity=add(new NumberSetting("pathOpacity",85,10,100,5,"%"));
    private final NumberSetting glowIntensity=add(new NumberSetting("glowIntensity",20,0,60,5,"%"));
    private final NumberSetting gradientSpeed=add(new NumberSetting("gradientSpeed",.08,0,.5,.01));
    private final NumberSetting distance=add(new NumberSetting("pathDistance",64,8,128,4,"blocks"));
    private final BoolSetting animation=add(new BoolSetting("pathAnimation",true)),through=add(new BoolSetting("throughWalls",false));
    private final BoolSetting landings=add(new BoolSetting("landings",true)),markers=add(new BoolSetting("targetMarkers",true));
    private final BoolSetting showInfo=add(new BoolSetting("showInfo",true));
    private final BoolSetting debug=add(new BoolSetting("debug",true)).visibleWhen(showInfo::on);
    private final BoolSetting metrics=add(new BoolSetting("showMetrics",true)).visibleWhen(()->showInfo.on()&&debug.on());
    private final BoolSetting listEntry=add(new BoolSetting("showListEntry",true)).visibleWhen(showInfo::on);
    private final BoolSetting listStatus=add(new BoolSetting("showListStatus",true)).visibleWhen(()->showInfo.on()&&listEntry.on());
    private record Plan(Result result,ParkourWorldSnapshot terrain,Parameters physics,State start,int first,int end,int reached){}
    private ExecutorService worker;private Future<Plan> pending,ahead;
    private ParkourWorldSnapshot.Builder aheadBuilder;private Plan queued;private boolean aheadFresh;
    private State aheadStart,courseOrigin,resumePosition;private int aheadCheckpoint,routeEndCheckpoint,routeGoalEnd,aheadHandoff=-1,forecastInvalidAt=-1;
    private ParkourWorldSnapshot.Builder builder;private ParkourWorldSnapshot terrain;private Parameters parameters;
    private List<Goal> goals=List.of();private Result route;private State expected,lastProgressState;
    private Object world,player;private int routeIndex,ticks,checkpoint,replans,legRetries,stuck,completedTicks,driftReports,searchRadius,planReports;
    private Vec3 progress;private double error,maxError,errorSum;private int samples;
    private boolean armed,running,autoJumpSaved;private Boolean previousAutoJump;
    private String status="idle",targetSignature="";private long lastGeometry;
    private boolean startupBuffer,completedPrefixRecorded;private int forecastCursor,scanStartedTick,aheadStartedTick,observedPredictionTicks;
    private volatile long searchCpuNanos;private long maximumScanNanos,maximumControlNanos;
    private final ParkourNavigationMemory navigationMemory=new ParkourNavigationMemory();
    private List<WorldLines.Segment> mainLines=List.of(),glowLines=List.of();
    private final ParkourLineHistory<LineKey,WorldLines.Segment> mainHistory=new ParkourLineHistory<>(8192),glowHistory=new ParkourLineHistory<>(8192),editorHistory=new ParkourLineHistory<>(8192);
    private record PointKey(long x,long y,long z){static PointKey of(Vec3 v){return new PointKey(Math.round(v.x*10000),Math.round(v.y*10000),Math.round(v.z*10000));}}
    private record LineKey(PointKey from,PointKey to){static LineKey of(WorldLines.Segment s){return new LineKey(PointKey.of(s.from()),PointKey.of(s.to()));}}
    public AutoParkour(){super("autoparkour",Category.MOVEMENT);}
    public record Metrics(boolean running,String status,int executedTicks,int plannedTicks,int replans,int nodes,double searchMillis,double maximumError,double meanError){}
    public Metrics metrics(){return new Metrics(running,status,completedTicks,route==null?0:route.actions().size(),replans,route==null?0:route.expanded(),route==null?0:route.elapsedNanos()/1e6,maxError,samples==0?0:errorSum/samples);}
    public record Performance(double searchCpuMillis,double maximumScanMillis,double maximumControlMillis){}
    public Performance performance(){return new Performance(searchCpuNanos/1e6,maximumScanNanos/1e6,maximumControlNanos/1e6);}
    @Override public String suffix(){return showInfo.on()&&listStatus.on()?Lang.t("parkour.status."+status):"";}
    @Override public boolean isDrawn(){return super.isDrawn()&&showInfo.on()&&listEntry.on();}
    @Override public String summary(){return Lang.t("parkour.waypointCount",waypoints().size());}
    public List<Goal> waypoints(){migrateWaypoints();return waypointList.get();}
    public int waypointRevision(){return waypointList.revision();}
    public double waypointRadius(){return precision.val();}
    public int waypointKey(){return markingKey.intVal();}
    public void waypointKey(int key){markingKey.set((double)key);}
    public void addWaypoint(Goal point){migrateWaypoints();waypointList.append(point);}
    public void removeLastWaypoint(){migrateWaypoints();waypointList.removeLast();}
    public void clearWaypoints(){waypointList.clear();}
    public void stopForEditing(){setEnabled(false);stop("idle");armed=false;}
    private void migrateWaypoints(){
        if(waypointList.explicitlySet())return;
        if(!targetMode.is("parkourCourse")&&!targetMode.is("parkourPosition"))return;
        try{waypointList.set(parseLegacy(targetMode.is("parkourCourse")?course.get():coordinates.get()));}
        catch(IllegalArgumentException invalid){status="invalidTarget";}
    }
    @Override public void onEnable(){clearLineHistory();armed=true;start.set(false);status="armed";}
    @Override public void onDisable(){stop("idle");clearLineHistory();armed=false;}
    public void checkLifecycle(){if(world!=null&&(mc.level!=world||mc.player!=player||!inGame())){stop("worldChanged");world=player=null;}if(!inGame())armed=false;}
    private String signature(){return waypointList.revision()+"|"+precision.get();}
    @Override public void onTick(){
        ticks++;
        if(restartCourse.on()){restartCourse.set(false);stop("idle");checkpoint=0;world=player=null;armed=true;}
        if(start.on()){start.set(false);if(running){stop("idle");return;}armed=true;}
        if(running&&(!signature().equals(targetSignature)||ParkourClient.screenOpen()||!mc.isWindowActive()||AiController.isRunning()||Freecam.isActive())){stop("interrupted");return;}
        if(armed&&!ParkourClient.screenOpen()&&mc.isWindowActive()){armed=false;begin();}
        if(!running)return;
        if(!ParkourWorldSnapshot.supportedPlayer(mc.player)){stop("unsupported");return;}
        if(rewindProgress(ParkourWorldSnapshot.state(mc.player,0,ticks)))return;
        if(builder!=null){
            ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));
            long begun=System.nanoTime();boolean done=builder.advance(ParkourPrediction.SCAN_CELLS,1_500_000);maximumScanNanos=Math.max(maximumScanNanos,System.nanoTime()-begun);
            if(done){observedPredictionTicks=Math.max(observedPredictionTicks,ticks-scanStartedTick+(int)Math.ceil(budget.val()/50));terrain=builder.result();builder=null;submit();}
        }
        if(pending!=null&&pending.isDone())accept();
        if(aheadBuilder!=null){
            long begun=System.nanoTime();boolean done=aheadBuilder.advance(ParkourPrediction.SCAN_CELLS,1_500_000);maximumScanNanos=Math.max(maximumScanNanos,System.nanoTime()-begun);
            if(done){ParkourWorldSnapshot snapshot=aheadBuilder.result();aheadBuilder=null;ahead=search(snapshot,parameters,aheadStart,aheadCheckpoint);}
        }
        collectAhead();
        forecast();
    }
    private void begin(){
        boolean unchanged=world==mc.level&&player==mc.player&&signature().equals(targetSignature);
        int resume=unchanged?checkpoint:0;
        stop("idle");
        if(!inGame()||!ParkourWorldSnapshot.supportedPlayer(mc.player)||AiController.isRunning()||Freecam.isActive()){status="unsupported";return;}
        try{goals=readGoals();}catch(IllegalArgumentException e){status="invalidTarget";return;}
        if(goals.isEmpty()){status="invalidTarget";return;}
        if(!mc.player.onGround()||mc.player.getDeltaMovement().horizontalDistance()>.06){status="standStill";return;}
        State actual=ParkourWorldSnapshot.state(mc.player,0,ticks);
        if(!unchanged||courseOrigin==null)courseOrigin=actual;
        if(unchanged)resume=ParkourCourse.rewind(goals,resume,actual,resumePosition,courseOrigin);
        resume=ParkourCourse.resume(resume,goals.size(),unchanged);
        running=true;world=mc.level;player=mc.player;checkpoint=resume;replans=legRetries=stuck=completedTicks=0;maxError=errorSum=error=0;samples=0;progress=mc.player.position();targetSignature=signature();lastProgressState=actual;
        if(checkpoint==goals.size()){stop("complete");return;}
        searchCpuNanos=maximumScanNanos=maximumControlNanos=0;observedPredictionTicks=0;
        driftReports=planReports=0;
        searchRadius=radius.intVal();
        navigationMemory.clear();
        // Vanilla auto-jump is an additional input source; restore the user's choice on every exit.
        previousAutoJump=mc.options.autoJump().get();mc.options.autoJump().set(false);autoJumpSaved=true;
        if(rotation.is("parkourSmooth"))ParkourCamera.begin(mc.player.getYRot());
        worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"CheatProgram-Parkour");t.setDaemon(true);return t;});
        scan();
    }
    private List<Goal> readGoals(){
        // Old saved points omitted the selected face. Resolve that face from
        // live collision geometry once; thin/partial supports keep their shape.
        return waypoints().stream().map(g->{
            if(g.region()!=null)return g;
            var pos=net.minecraft.core.BlockPos.containing(g.x(),g.y()-.0001,g.z());
            var shape=mc.level.getBlockState(pos).getCollisionShape(mc.level,pos,net.minecraft.world.phys.shapes.CollisionContext.of(mc.player));
            var boxes=shape.toAabbs().stream().map(b->new Box(b.minX+pos.getX(),b.minY+pos.getY(),b.minZ+pos.getZ(),b.maxX+pos.getX(),b.maxY+pos.getY(),b.maxZ+pos.getZ())).toList();
            Goal surface=ParkourWaypointTarget.top(boxes,g.x(),g.y(),g.z());
            return surface!=null&&Math.abs(surface.y()-g.y())<.001?new Goal(g.x(),g.y(),g.z(),g.radius(),g.height(),surface.region()):g;
        }).toList();
    }
    private List<Goal> parseLegacy(String text){
        var out=new ArrayList<Goal>();if(text.isBlank())return out;
        for(String point:text.split(";")){String[] p=point.trim().split("[ ,]+");if(p.length!=3&&p.length!=5)throw new IllegalArgumentException();out.add(new Goal(Double.parseDouble(p[0]),Double.parseDouble(p[1]),Double.parseDouble(p[2]),p.length==5?Double.parseDouble(p[3]):precision.val(),p.length==5?Double.parseDouble(p[4]):.25));}
        return List.copyOf(out);
    }
    private void cancelAhead(){
        if(ahead!=null)ahead.cancel(true);ahead=null;aheadBuilder=null;queued=null;aheadStart=null;aheadHandoff=-1;aheadFresh=false;
    }
    private void scan(){
        scan(null);
    }
    private void scan(double[] expandedCenter){
        cancelAhead();expected=null;route=null;routeIndex=0;mainLines=glowLines=List.of();status="scanning";
        parameters=ParkourWorldSnapshot.parameters(mc.player);
        State origin=ParkourWorldSnapshot.state(mc.player,0,ticks);double[] center=expandedCenter!=null?expandedCenter:ParkourPrediction.scanCenter(origin,ParkourPrediction.scanGoal(goals,checkpoint),searchRadius);
        builder=new ParkourWorldSnapshot.Builder(mc.level,mc.player,searchRadius,vertical.intVal(),center[0],center[1],center[2],null,goals.get(checkpoint));scanStartedTick=ticks;
    }
    private Future<Plan> search(ParkourWorldSnapshot snapshot,Parameters physics,State initial,int first){
        var window=ParkourCourse.window(goals,first,ParkourPrediction.WINDOW);int end=first+window.size();
        int lookahead=ParkourPrediction.planningHorizon(horizon.intVal(),physics,initial,window.getFirst());
        var options=new Options(lookahead,nodes.intVal(),beam.intVal(),(long)(budget.val()*1_000_000),autoSprint.on(),autoJump.on(),airControl.on(),rotation.is("parkourKeep")?0:rotation.is("parkourFast")?180:turn.floatVal());
        return worker.submit(()->{
            var bean=java.lang.management.ManagementFactory.getThreadMXBean();boolean cpu=bean.isCurrentThreadCpuTimeSupported()&&bean.isThreadCpuTimeEnabled();long begun=cpu?bean.getCurrentThreadCpuTime():0;
            Result found=ParkourPlanner.search(snapshot,physics,initial,window,options);searchCpuNanos=cpu?bean.getCurrentThreadCpuTime()-begun:-1;
            return new Plan(found,snapshot,physics,initial,first,end,first+ParkourContinuity.reachedThrough(window,0,found.states()));
        });
    }
    private void submit(){
        State current=ParkourWorldSnapshot.state(mc.player,0,ticks);
        checkpoint=ParkourCourse.advance(goals,checkpoint,current);
        if(checkpoint==goals.size()){stop("complete");return;}
        pending=search(terrain,parameters,current,checkpoint);status="planning";
    }
    private void accept(){
        try{
            Plan plan=pending.get();pending=null;
            if(!running||!plan.terrain().stillValid(mc.level)){stop("worldChanged");return;}
            State actual=ParkourWorldSnapshot.state(mc.player,0,ticks);
            if(!usableResult(plan))return;
            // A worker result may start before neutral input has finished
            // settling momentum. This is an outdated plan, not player drift.
            if(!currentStart(plan,actual)){refreshPlan();return;}
            if(!install(plan,actual)){retry();return;}
        }catch(Exception e){CheatProgram.LOGGER.warn("Parkour planning stopped",e);stop("failed");}
    }
    private boolean usableResult(Plan plan){
        if(plan.result().actions().isEmpty()&&!plan.result().reached()){
            if(debug.on()&&planReports++<4)CheatProgram.LOGGER.info("Parkour route unavailable: reason={} nodes={} searchMs={} scanRadius={}",plan.result().reason(),plan.result().expanded(),plan.result().elapsedNanos()/1_000_000,searchRadius);
            if(!expandSearch(plan.terrain()))stop("noRoute");
            return false;
        }
        return true;
    }
    private boolean expandSearch(ParkourWorldSnapshot snapshot){
        int wider=ParkourEnvironment.widerScan(searchRadius,wideSearch.on());
        if(wider<=searchRadius||!mc.player.onGround())return false;
        double[] center=snapshot.scanCenter();searchRadius=wider;replans++;ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));scan(center);status="expanding";return true;
    }
    private boolean currentStart(Plan plan,State actual){
        return plan.first()==checkpoint&&ParkourContinuity.compatible(plan.start(),actual,deviation.val()*3,.2);
    }
    private void refreshPlan(){
        // Waiting for a late continuation can itself move the player under
        // native friction. Replan from that position without spending the
        // execution-deviation allowance on the scheduling delay.
        if(!mc.player.onGround()){retry();return;}
        replans++;ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));
        if(pending!=null)pending.cancel(true);pending=null;scan();status="recovering";
    }
    private boolean install(Plan plan,State actual){
        if(plan.first()!=checkpoint||!plan.terrain().stillValid(mc.level)
                ||!ParkourContinuity.compatible(plan.start(),actual,deviation.val()*3,.2))return false;
        boolean exact=ParkourPrediction.exactStart(plan.start(),actual);
        Result rebased=exact?plan.result():ParkourContinuity.rebase(plan.result(),plan.terrain(),plan.physics(),actual);
        if(rebased==null)return false;
        if(rebased.actions().isEmpty()&&!rebased.reached())return false;
        int reached=exact?plan.reached():ParkourContinuity.reachedThrough(goals,checkpoint,rebased.states());
        if(rebased.reached()&&(reached<plan.end()||!goals.get(plan.end()-1).reached(rebased.states().getLast())))return false;
        terrain=plan.terrain();parameters=plan.physics();route=rebased;routeIndex=0;completedPrefixRecorded=false;
        routeGoalEnd=plan.end();routeEndCheckpoint=reached;status="running";lastGeometry=0;expected=null;forecastInvalidAt=-1;forecastCursor=0;
        if(route.actions().isEmpty()){
            if(route.reached()&&reached>=goals.size()){checkpoint=goals.size();stop("complete");return true;}
            if(route.reached()&&reached>checkpoint){checkpoint=reached;route=null;submit();return true;}
            stop("noRoute");return true;
        }
        prepareAhead();
        startupBuffer=completedTicks==0&&(ahead!=null||aheadBuilder!=null)&&route.actions().size()<leadTicks()+6;
        return true;
    }
    private int leadTicks(){return ParkourPrediction.leadTicks(searchRadius,vertical.intVal(),budget.val(),observedPredictionTicks);}
    private void prepareAhead(){
        if(route==null)return;
        if(route.reached()&&routeGoalEnd==goals.size()){cancelAhead();return;}
        prepareAheadAt(ParkourPrediction.anchor(route.states(),routeIndex,leadTicks()),false);
    }
    private void prepareAheadAt(int anchor,boolean fresh){
        cancelAhead();
        if(anchor<=routeIndex||route==null)return;
        int first=ParkourContinuity.reachedThrough(goals,checkpoint,route.states().subList(routeIndex,anchor+1));
        if(first>=goals.size())return;
        aheadHandoff=anchor;aheadStart=route.states().get(anchor);aheadCheckpoint=first;aheadStartedTick=ticks;
        // Predict from an overlapping point while the verified prefix is still
        // executing. Keep scanning/search off the critical handoff tick.
        Goal scanGoal=ParkourPrediction.scanGoal(goals,first);
        boolean covered=!fresh&&ParkourPrediction.localCovered(terrain,parameters,aheadStart,scanGoal)
            &&ParkourPrediction.heightCovered(terrain,parameters,aheadStart,goals.get(first));
        aheadFresh=!covered;
        if(covered)ahead=search(terrain,parameters,aheadStart,aheadCheckpoint);
        else{double[] center=ParkourPrediction.scanCenter(aheadStart,scanGoal,searchRadius);aheadBuilder=new ParkourWorldSnapshot.Builder(mc.level,mc.player,searchRadius,vertical.intVal(),center[0],center[1],center[2],fresh?null:terrain,goals.get(first));}
    }
    private void forecast(){
        if(!running||route==null||forecastInvalidAt>=0||routeIndex>=route.actions().size())return;
        // One bounded future corridor per client tick. Live current movement is
        // independently checked immediately before every applied native input.
        int offset=8+(forecastCursor++%3)*6;
        int index=Math.min(route.actions().size()-1,routeIndex+offset);
        State a=route.states().get(index),b=route.states().get(index+1);
        Box corridor=ParkourPhysics.bounds(a,parameters).expand(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()).inflate(.05).expand(0,parameters.stepHeight(),0);
        if(terrain.matches(mc.level,corridor)&&ParkourWorldSnapshot.clearOfEntities(mc.level,mc.player,corridor))return;
        forecastInvalidAt=index;
        int anchor=Math.min(index-2,ParkourPrediction.anchor(routeIndex,route.actions().size(),leadTicks()));
        if(anchor>routeIndex)prepareAheadAt(anchor,true);
    }
    private void collectAhead(){
        if(ahead==null||!ahead.isDone())return;
        Plan ready;
        try{ready=ahead.get();observedPredictionTicks=Math.max(observedPredictionTicks,ticks-aheadStartedTick);}catch(Exception e){ready=null;}finally{ahead=null;}
        if(ready!=null&&!aheadFresh&&ready.result().actions().isEmpty()&&!ready.result().reached()
            &&route!=null&&aheadHandoff>routeIndex){
            // Discover missing capture room while the prefix is still running;
            // do not wait for the contact tick to discover an empty prediction.
            prepareAheadAt(aheadHandoff,true);return;
        }
        queued=ready;
    }
    private void retry(){recover("recovering");}
    private boolean repairRemaining(State actual){
        if(!recovery.on()||route==null||routeIndex>=route.actions().size()
                ||positionError(actual,route.states().get(routeIndex))>Math.max(.75,deviation.val()*6)
                ||velocityError(actual,route.states().get(routeIndex))>.5)return false;
        Result repaired=ParkourRecovery.track(route,routeIndex,terrain,parameters,actual,rotation.is("parkourKeep")?0:rotation.is("parkourFast")?180:turn.floatVal());
        if(repaired==null)repaired=ParkourRecovery.remaining(route,routeIndex,terrain,parameters,actual);
        if(repaired==null)return false;
        int reached=ParkourContinuity.reachedThrough(goals,checkpoint,repaired.states());
        if(reached<routeEndCheckpoint||repaired.reached()&&!goals.get(routeGoalEnd-1).reached(repaired.states().getLast()))return false;
        // Full collision replay and a safe terminal release succeeded, even
        // when the correction began in the air. Apply the corrected suffix
        // rather than releasing control in the middle of a recoverable jump.
        int shifted=aheadHandoff-routeIndex;
        int futurePoint=shifted>0&&shifted<repaired.states().size()
            ?ParkourContinuity.reachedThrough(goals,checkpoint,repaired.states().subList(0,shifted+1)):-1;
        boolean preserve=(ahead!=null||aheadBuilder!=null||queued!=null)&&forecastInvalidAt<0
            &&shifted>0&&shifted<repaired.states().size()
            &&ParkourPrediction.preserve(aheadStart,repaired.states().get(shifted),aheadCheckpoint,futurePoint);
        route=repaired;routeIndex=0;routeEndCheckpoint=reached;expected=null;lastGeometry=0;
        replans++;
        if(preserve)aheadHandoff=shifted;else prepareAhead();
        return true;
    }
    private void recover(String reason){
        replans++;
        if(!recovery.on()||++legRetries>6||!mc.player.onGround()){stop(reason.equals("recovering")?"deviation":"terrainChanged");return;}
        ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));
        if(pending!=null)pending.cancel(true);pending=null;scan();status=reason;
    }
    private void advanceActual(State actual){
        if(checkpoint>=goals.size())return;
        // Intermediate waypoints are passed on contact without stopping or
        // clearing velocity. Final completion still uses the safe route endpoint.
        int next=ParkourCourse.advance(goals,checkpoint,lastProgressState,actual);lastProgressState=actual;
        next=Math.min(next,goals.size()-1);
        if(next>checkpoint){checkpoint=next;legRetries=stuck=0;progress=mc.player.position();}
    }
    private boolean rewindProgress(State actual){
        State reference=expected!=null?expected:lastProgressState;
        if(reference==null||positionError(actual,reference)<1.5)return false;
        int earlier=ParkourCourse.rewind(goals,checkpoint,actual,reference,courseOrigin);
        if(earlier>=checkpoint)return false;
        checkpoint=earlier;legRetries=stuck=0;replans++;navigationMemory.clear();lastProgressState=actual;
        progress=mc.player.position();searchRadius=radius.intVal();
        if(pending!=null)pending.cancel(true);pending=null;
        ParkourInput.set(new Action(actual.yaw(),0,0,false,false));scan();status="recovering";return true;
    }
    public static void beforeInputTick(){
        AutoParkour m=ModuleManager.get(AutoParkour.class);if(m==null||!m.isEnabled())return;
        long begun=System.nanoTime();try{m.drive();if(m.running&&m.rotation.is("parkourSmooth"))ParkourCamera.tick(ParkourCamera.heading(m.route==null?null:m.route.states(),Math.max(0,m.routeIndex-1),m.mc.player.getYRot()));}catch(Throwable e){m.stop("failed");CheatProgram.LOGGER.error("Parkour input stopped",e);}finally{m.maximumControlNanos=Math.max(m.maximumControlNanos,System.nanoTime()-begun);}
    }
    private void drive(){
        if(!running)return;
        if(!inGame()||mc.level!=world||mc.player!=player||!signature().equals(targetSignature)||ParkourClient.screenOpen()||!mc.isWindowActive()||AiController.isRunning()||Freecam.isActive()){stop("interrupted");return;}
        if(!ParkourWorldSnapshot.supportedPlayer(mc.player)){stop("unsupported");return;}
        Parameters live=ParkourWorldSnapshot.parameters(mc.player);
        if(Math.abs(live.walkingSpeed()-parameters.walkingSpeed())>1e-6||Math.abs(live.gravity()-parameters.gravity())>1e-6||Math.abs(live.jumpVelocity()-parameters.jumpVelocity())>1e-6||Math.abs(live.stepHeight()-parameters.stepHeight())>1e-6){stop("unsupported");return;}
        if(route==null){ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));return;}
        State actual=ParkourWorldSnapshot.state(mc.player,0,ticks);
        if(rewindProgress(actual))return;
        if(startupBuffer){
            collectAhead();
            if(queued==null&&(ahead!=null||aheadBuilder!=null)){
                expected=null;status="buffering";ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));return;
            }
            startupBuffer=false;status="running";
            if(!ParkourPrediction.exactStart(route.states().get(routeIndex),actual)){refreshPlan();return;}
        }
        advanceActual(actual);
        collectAhead();
        if(queued!=null&&aheadHandoff>=0&&routeIndex>=aheadHandoff){
            Plan ready=queued;queued=null;
            int elapsed=routeIndex-aheadHandoff;
            if(elapsed>0){
                Result caught=ParkourPrediction.catchUp(ready.result(),elapsed,actual);
                int first=caught==null?-1:ParkourContinuity.reachedThrough(goals,ready.first(),ready.result().states().subList(0,elapsed+1));
                ready=caught==null||first!=checkpoint?null:new Plan(caught,ready.terrain(),ready.physics(),caught.states().getFirst(),first,ready.end(),ready.reached());
            }
            if(ready!=null&&currentStart(ready,actual)&&!ready.result().actions().isEmpty()
                    &&ParkourPrediction.improves(route,routeIndex,routeEndCheckpoint,ready.result(),ready.reached())){
                if(install(ready,actual)){if(!running||route==null)return;}
                else prepareAhead();
            }else if(routeIndex<route.actions().size())prepareAhead();
            else{refreshPlan();return;}
        }
        if(routeIndex>=route.actions().size()){
            if(route.reached()&&routeGoalEnd==goals.size()&&goals.getLast().reached(actual)&&actual.onGround()){
                checkpoint=goals.size();stop("complete");return;
            }
            if(!route.reached()&&!completedPrefixRecorded){
                completedPrefixRecorded=true;
                if(navigationMemory.repeated(checkpoint,actual)){if(!expandSearch(terrain))stop("noRoute");return;}
            }
            collectAhead();
            if(queued!=null){
                Plan ready=queued;queued=null;
                if(!usableResult(ready))return;
                if(!currentStart(ready,actual)){refreshPlan();return;}
                if(!install(ready,actual)){refreshPlan();return;}
                if(!running||route==null){if(running)ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));return;}
                // Deliberately fall through: the next route's first native input
                // is applied in this same tick, preserving endpoint momentum.
            }else if(ahead!=null||aheadBuilder!=null){
                expected=null;ParkourInput.set(new Action(mc.player.getYRot(),0,0,false,false));return;
            }else{retry();return;}
        }
        var predicted=route.states().get(routeIndex);error=positionError(actual,predicted);
        if(!actual.onGround()&&error>.002&&error<.15&&velocityError(actual,predicted)<.06){
            if(repairRemaining(actual))predicted=route.states().get(routeIndex);
            error=positionError(actual,predicted);
        }
        if(error>deviation.val()*3||velocityError(actual,predicted)>.25
                ||actual.sprintBlocked()!=predicted.sprintBlocked()||actual.onGround()!=predicted.onGround()
                ||actual.jumpCooldown()!=predicted.jumpCooldown()){
            if(!repairRemaining(actual)){retry();return;}
            error=0;
        }
        Action chosen=route.actions().get(routeIndex);
        State next=ParkourPhysics.step(terrain,parameters,actual,chosen);
        if(error>deviation.val()&&airControl.on()){
            // One-tick local MPC repair: compare native input combinations against next state.
            State target=route.states().get(routeIndex+1);double best=next==null?Double.POSITIVE_INFINITY:positionError(next,target)+velocityError(next,target);
            for(int f=-1;f<=1;f++)for(int side=-1;side<=1;side++){
                Action option=new Action(chosen.yaw(),f,side,chosen.jump(),chosen.sprint()&&f>0);State candidate=ParkourPhysics.step(terrain,parameters,actual,option);
                if(candidate!=null){double score=positionError(candidate,target)+velocityError(candidate,target);if(score<best){best=score;chosen=option;next=candidate;}}
            }
        }
        if(next==null){retry();return;}
        Box swept=ParkourPhysics.bounds(actual,parameters).expand(next.x()-actual.x(),next.y()-actual.y(),next.z()-actual.z()).inflate(.05);
        if(!terrain.matches(mc.level,swept.expand(0,parameters.stepHeight(),0))||!ParkourWorldSnapshot.clearOfEntities(mc.level,mc.player,swept)){recover("replanningTerrain");return;}
        if(rotation.is("parkourKeep")&&Math.abs(wrap(mc.player.getYRot()-chosen.yaw()))>.5){stop("interrupted");return;}
        mc.player.setYRot(chosen.yaw());ParkourInput.set(chosen);ParkourClient.inputActivity();expected=next;routeIndex++;completedTicks++;
        if(mc.player.position().distanceToSqr(progress)>.04){progress=mc.player.position();stuck=0;}else if(++stuck>60){stop("stuck");}
    }
    @Override public void onPostTick(){
        if(running&&inGame()){
            State actual=ParkourWorldSnapshot.state(mc.player,0,ticks);if(rewindProgress(actual))return;advanceActual(actual);
            if(expected!=null){
                error=positionError(actual,expected);maxError=Math.max(maxError,error);errorSum+=error;samples++;
                if(debug.on()&&error>.01&&driftReports++<4)CheatProgram.LOGGER.warn("Parkour movement drift: tick={} delta=({},{},{}) velocityError={} grounded={}/{} jumpDelay={}/{} sprintBlocked={}/{}",completedTicks,actual.x()-expected.x(),actual.y()-expected.y(),actual.z()-expected.z(),velocityError(actual,expected),actual.onGround(),expected.onGround(),actual.jumpCooldown(),expected.jumpCooldown(),actual.sprintBlocked(),expected.sprintBlocked());
                if(error>deviation.val()*3&&!repairRemaining(actual))retry();
            }
        }
    }
    private static double positionError(State a,State b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    private static double velocityError(State a,State b){return Math.sqrt(Math.pow(a.vx()-b.vx(),2)+Math.pow(a.vy()-b.vy(),2)+Math.pow(a.vz()-b.vz(),2));}
    private static float wrap(float a){a%=360;return a>=180?a-360:a<-180?a+360:a;}
    private void stop(String reason){
        if(running&&inGame()&&mc.level==world&&mc.player==player)resumePosition=ParkourWorldSnapshot.state(mc.player,0,ticks);
        running=false;status=reason;ParkourInput.clear();ParkourCamera.clear();cancelAhead();if(pending!=null)pending.cancel(true);pending=null;if(worker!=null)worker.shutdownNow();worker=null;builder=null;expected=null;startupBuffer=false;forecastInvalidAt=-1;
        if(autoJumpSaved&&previousAutoJump!=null){mc.options.autoJump().set(previousAutoJump);autoJumpSaved=false;previousAutoJump=null;}
        if(reason.equals("worldChanged")||reason.equals("idle")){route=null;terrain=null;mainLines=glowLines=List.of();}
        if(reason.equals("worldChanged"))clearLineHistory();
    }
    @Override public void onRender2D(HudContext hud){
        if(!showInfo.on()||!debug.on())return;
        String line=Lang.t("module.autoparkour")+" · "+Lang.t("parkour.status."+status);
        if(metrics.on()&&!goals.isEmpty())line+=" | "+Math.min(checkpoint+1,goals.size())+" / "+goals.size();
        if(metrics.on()&&route!=null)line+=String.format(Locale.ROOT," | %d/%d t | %.3f m | %d nodes | %.1f ms | R%d",routeIndex,route.actions().size(),error,route.expanded(),route.elapsedNanos()/1e6,replans);
        hud.c.textMid(Fonts.Face.REGULAR,line,hud.width/2,hud.height-62,9,0xffe9f3ff);
    }
    @Override public void onRender3D(Render3D renderer){
        if(!inGame()||!show.on())return;long now=System.nanoTime();
        if(now-lastGeometry>33_000_000){rebuildGeometry(now/1e9);lastGeometry=now;}
        float body=bold.on()?ribbonWidth.floatVal():0;
        if(glow.on())ParkourLines.draw(renderer,glowLines,width.floatVal()+3,body*1.4f,through.on());
        ParkourLines.draw(renderer,mainLines,width.floatVal(),body,through.on());
    }
    private void rebuildGeometry(double time){
        var lines=new ArrayList<WorldLines.Segment>();var halos=new ArrayList<WorldLines.Segment>();
        if(running&&route!=null){var states=route.states();for(int i=Math.max(1,routeIndex);i<states.size();i++){
            State a=states.get(i-1),b=states.get(i);Vec3 from=new Vec3(a.x(),a.y()+.08,a.z()),to=new Vec3(b.x(),b.y()+.08,b.z());
            if(to.distanceTo(mc.player.position())>distance.val())continue;
            int rgb=pathColor(i/(double)Math.max(1,states.size()-1),time);
            lines.add(new WorldLines.Segment(from,to,((int)(opacity.fraction()*255)<<24)|rgb));
            halos.add(new WorldLines.Segment(from,to,((int)(opacity.fraction()*glowIntensity.fraction()*255)<<24)|rgb));
            if(landings.on()&&b.onGround()&&!a.onGround())mark(lines,to,.16,0xff000000|rgb);
            if(landings.on()&&a.onGround()&&!b.onGround())mark(lines,from,.1,0xff000000|rgb);
        }}
        if(running&&markers.on())for(int i=checkpoint;i<Math.min(goals.size(),checkpoint+128);i++){Goal g=goals.get(i);Vec3 p=new Vec3(g.x(),g.y()+.05,g.z());if(p.distanceTo(mc.player.position())<distance.val())mark(lines,p,g.radius(),(i==checkpoint?0xff000000:0x88000000)|pathColor(i/(double)Math.max(1,goals.size()),time));}
        long now=System.nanoTime();mainLines=retained(mainHistory,lines,now);glowLines=retained(glowHistory,halos,now);
    }
    private int pathColor(double phase,double time){phase+=animation.on()?time*gradientSpeed.val():0;return palette.is("parkourWhite")?0xffffff:palette.is("parkourRainbow")?ParkourColors.rainbow(phase):ParkourColors.pride(phase);}
    private List<WorldLines.Segment> retained(ParkourLineHistory<LineKey,WorldLines.Segment> history,List<WorldLines.Segment> active,long now){
        return retained(history,active,now,mc.player.position());
    }
    private List<WorldLines.Segment> retained(ParkourLineHistory<LineKey,WorldLines.Segment> history,List<WorldLines.Segment> active,long now,Vec3 eye){
        var unique=new LinkedHashMap<LineKey,WorldLines.Segment>();for(var line:active)unique.put(LineKey.of(line),line);
        var result=new ArrayList<WorldLines.Segment>();for(var sample:history.update(unique,now,linger.val())){var line=sample.value();if(line.to().distanceToSqr(eye)>distance.val()*distance.val())continue;int alpha=(int)((line.color()>>>24)*sample.opacity());if(alpha>0)result.add(new WorldLines.Segment(line.from(),line.to(),(alpha<<24)|(line.color()&0xffffff)));}return List.copyOf(result);
    }
    private void clearLineHistory(){mainHistory.clear();glowHistory.clear();editorHistory.clear();mainLines=glowLines=List.of();lastGeometry=0;}
    public void renderEditorLines(Render3D renderer,List<WorldLines.Segment> input){
        if(!inGame()||!show.on())return;double time=System.nanoTime()/1e9;var colored=new ArrayList<WorldLines.Segment>();for(int i=0;i<input.size();i++){var line=input.get(i);colored.add(new WorldLines.Segment(line.from(),line.to(),(line.color()&0xff000000)|pathColor(i/(double)Math.max(1,input.size()),time)));}
        ParkourLines.draw(renderer,retained(editorHistory,colored,System.nanoTime(),renderer.camera),width.floatVal(),bold.on()?ribbonWidth.floatVal():0,true);
    }
    public void clearEditorLines(){editorHistory.clear();}
    private static void mark(List<WorldLines.Segment> list,Vec3 p,double r,int color){for(int i=0;i<24;i++){double a=i*Math.PI/12,b=(i+1)*Math.PI/12;list.add(new WorldLines.Segment(p.add(Math.cos(a)*r,0,Math.sin(a)*r),p.add(Math.cos(b)*r,0,Math.sin(b)*r),color));}}
}
