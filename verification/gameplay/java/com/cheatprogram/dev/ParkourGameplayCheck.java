package com.cheatprogram.dev;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.module.movement.AutoParkour;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated integrated-server fixture. Teleports only reset tests, never execute a route. */
public final class ParkourGameplayCheck {
    private static int stage,wait,scenario,frame,planTicks,routeCase;private static final long BEGUN=System.nanoTime();
    private static final String[] ROUTES={"flat","gap-2","diagonal","slab","low-ceiling","ice","ordered","narrow","elevated"};
    private static String target(){return routeCase==2?"5.5,101,5.5":routeCase==3?"0.5,101.5,8.5":routeCase==6?"4.5,101,4.5":routeCase==7?"0.5,101,5.0":routeCase==8?"0.5,102,6.5":"0.5,101,8.5";}
    private static final String[] CASES={"walk","sprint","jump","sprint-jump","air-strafe","low-ceiling","slab","ice"};
    private static final AtomicBoolean ready=new AtomicBoolean();
    private static long fpsSum;private static int fpsSamples,fpsBefore;
    private static final String[] ABORTS={"module-disabled","gui-opened","target-changed","unrelated-side-wall"};
    private static int abortCase,abortTicks;private static double pathLength;private static float routeHealth,minimumHealth;private static Vec3 lastPosition;
    private static ParkourWorldSnapshot terrain;private static Parameters parameters;private static State expected;
    private static double maxError,sum;private static int samples;private static final StringBuilder csv=new StringBuilder("scenario,tick,error,actualX,actualY,actualZ,predictedX,predictedY,predictedZ\n");
    public static void tick()throws Exception{
        Minecraft mc=Minecraft.getInstance();
        if(System.nanoTime()-BEGUN>420_000_000_000L){mc.stop();throw new AssertionError("Parkour fixture timeout stage="+stage);}
        if(wait-->0)return;
        switch(stage){
            case 0->{
                if(com.cheatprogram.parkour.ParkourClient.screenOpen() && com.cheatprogram.parkour.ParkourClient.screen()==null)return;
                if(!(com.cheatprogram.parkour.ParkourClient.screen() instanceof TitleScreen)){if(com.cheatprogram.parkour.ParkourClient.screen()!=null&&com.cheatprogram.parkour.ParkourClient.screen().getClass().getSimpleName().contains("Accessibility"))com.cheatprogram.parkour.ParkourClient.open(new TitleScreen());return;}
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(2);mc.options.autoJump().set(false);
                for(var m:ModuleManager.all())m.setEnabled(false);
                var method=GameplayCheck.class.getDeclaredMethod("createWorld",Minecraft.class);method.setAccessible(true);method.invoke(null,mc);stage=1;
            }
            case 1->{if(mc.player==null||com.cheatprogram.parkour.ParkourClient.screen()!=null)return;prepare(mc,false);stage=2;wait=40;}
            case 2->{
                if(!ready.get()||mc.player==null||com.cheatprogram.parkour.ParkourClient.screen()!=null)return;
                var builder=new ParkourWorldSnapshot.Builder(mc.level,mc.player,32,8);while(!builder.advance(100000,100_000_000)){}
                terrain=builder.result();parameters=ParkourWorldSnapshot.parameters(mc.player);expected=null;frame=0;maxError=sum=0;samples=0;stage=3;
            }
            case 3->{
                State actual=ParkourWorldSnapshot.state(mc.player,0,frame);
                if(expected!=null){double e=Math.sqrt(Math.pow(actual.x()-expected.x(),2)+Math.pow(actual.y()-expected.y(),2)+Math.pow(actual.z()-expected.z(),2));maxError=Math.max(maxError,e);sum+=e;samples++;
                    csv.append(String.format(Locale.ROOT,"%s,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",CASES[scenario],frame,e,actual.x(),actual.y(),actual.z(),expected.x(),expected.y(),expected.z()));
                }
                if(frame++>=28){
                    ParkourInput.clear();System.out.printf(Locale.ROOT,"PARKOUR_GAME_PHYSICS %s samples=%d max=%.9f mean=%.9f%n",CASES[scenario],samples,maxError,sum/Math.max(1,samples));
                    if(maxError>.001){Files.writeString(mc.gameDirectory.toPath().resolve("parkour-physics.csv"),csv);throw new AssertionError("Physics mismatch "+CASES[scenario]+" "+maxError);}
                    if(++scenario<CASES.length){prepare(mc,false);stage=2;wait=30;}else{prepare(mc,true);stage=4;wait=40;}return;
                }
                boolean sprint=scenario==1||scenario==3||scenario==4||scenario==7;
                Action a=new Action(0,1,scenario==4&&frame>4?1:0,(scenario==2||scenario==3||scenario==4||scenario==5)&&frame==1,sprint);
                mc.player.setYRot(a.yaw());ParkourInput.set(a);expected=ParkourPhysics.step(terrain,parameters,actual,a);
                if(expected==null)throw new AssertionError("Snapshot rejected test "+CASES[scenario]+" at "+actual);
            }
            case 4->{
                if(!ready.get())return;AutoParkour m=ModuleManager.get(AutoParkour.class);
                configure(m);pathLength=0;lastPosition=mc.player.position();routeHealth=minimumHealth=mc.player.getHealth();
                fpsBefore=mc.getFps();fpsSum=0;fpsSamples=0;mc.player.setXRot(30);m.setEnabled(true);stage=5;planTicks=0;
            }
            case 5->{
                AutoParkour m=ModuleManager.get(AutoParkour.class);planTicks++;
                fpsSum+=mc.getFps();fpsSamples++;
                Vec3 position=mc.player.position();pathLength+=position.distanceTo(lastPosition);lastPosition=position;minimumHealth=Math.min(minimumHealth,mc.player.getHealth());
                if(!mc.player.isAlive()||minimumHealth+.001f<routeHealth)throw new AssertionError("Health lost on "+ROUTES[routeCase]+": start="+routeHealth+" minimum="+minimumHealth);
                if(routeCase==2&&m.metrics().executedTicks()==8){var shot=GameplayCheck.class.getDeclaredMethod("shot",Minecraft.class,String.class);shot.setAccessible(true);shot.invoke(null,mc,"parkour-route");}
                if(m.metrics().status().equals("complete")){
                    if(ParkourInput.active())throw new AssertionError("Input retained after "+ROUTES[routeCase]);
                    System.out.println("PARKOUR_GAME_ROUTE_PASS "+ROUTES[routeCase]+" elapsedClientTicks="+planTicks+" pathLengthBlocks="+pathLength+" averageSpeedBlocksPerSecond="+(20*pathLength/Math.max(1,m.metrics().executedTicks()))+" healthStart="+routeHealth+" healthMinimum="+minimumHealth+" healthEnd="+mc.player.getHealth()+" "+m.metrics()+" "+m.performance()+" fpsBefore="+fpsBefore+" meanReportedFps="+(fpsSum/(double)fpsSamples));m.setEnabled(false);
                    if(++routeCase<ROUTES.length){prepare(mc,true);stage=4;wait=40;}else{routeCase=0;prepare(mc,true);stage=6;wait=40;}
                }else if(planTicks>240){Files.writeString(mc.gameDirectory.toPath().resolve("parkour-physics.csv"),csv);throw new AssertionError("Controller route did not finish: "+ROUTES[routeCase]+" "+m.metrics()+" pos="+mc.player.position());}
            }
            case 6->{
                if(!ready.get())return;AutoParkour m=ModuleManager.get(AutoParkour.class);configure(m);
                setTarget(m,"0.5,101,14.5");mc.options.autoJump().set(true);m.setEnabled(true);abortTicks=0;stage=7;
            }
            case 7->{
                AutoParkour m=ModuleManager.get(AutoParkour.class);
                if(++abortTicks>240)throw new AssertionError("Abort test never started: "+ABORTS[abortCase]+" "+m.metrics());
                // Interrupt actual execution, after native movement input has been applied.
                if(!m.metrics().running()||!m.metrics().status().equals("running")||m.metrics().executedTicks()<2)return;
                if(!ParkourInput.active()||mc.options.autoJump().get())throw new AssertionError("Controller did not own native input before "+ABORTS[abortCase]);
                switch(abortCase){
                    case 0->m.setEnabled(false);
                    case 1->ParkourClient.open(new TitleScreen());
                    case 2->setTarget(m,"0.5,101,12.5");
                    case 3->{
                        ready.set(false);var server=mc.getSingleplayerServer();
                        // A nearby side wall outside the actual swept body must not stop the route.
                        server.execute(()->{server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"fill 1 101 -1 1 102 16 stone");ready.set(true);});
                    }
                    default->throw new AssertionError("Unknown abort case "+abortCase);
                }
                abortTicks=0;stage=8;
            }
            case 8->{
                AutoParkour m=ModuleManager.get(AutoParkour.class);abortTicks++;
                if((abortCase==3&&!ready.get())||m.metrics().running()){
                    if(abortTicks>(abortCase==3?240:5))throw new AssertionError("Controller interruption/continuation failed: "+ABORTS[abortCase]+" "+m.metrics());
                    return;
                }
                String expectedStatus=abortCase==0?"idle":abortCase==3?"complete":"interrupted";
                if(!m.metrics().status().equals(expectedStatus)||ParkourInput.active()||!mc.options.autoJump().get())throw new AssertionError("Abort cleanup failed: "+ABORTS[abortCase]+" "+m.metrics()+" inputActive="+ParkourInput.active()+" nativeAutoJump="+mc.options.autoJump().get());
                System.out.println((abortCase==3?"PARKOUR_GAME_UNRELATED_CHANGE_PASS ":"PARKOUR_GAME_ABORT_PASS ")+ABORTS[abortCase]+" observedClientTicks="+abortTicks+" inputActive="+ParkourInput.active()+" nativeAutoJump="+mc.options.autoJump().get()+" "+m.metrics());
                m.setEnabled(false);if(abortCase==1)ParkourClient.open(null);mc.options.autoJump().set(false);
                if(++abortCase<ABORTS.length){prepare(mc,true);stage=6;wait=40;}
                else{Files.writeString(mc.gameDirectory.toPath().resolve("parkour-physics.csv"),csv);System.out.println("PARKOUR_GAME_CHECK_PASS routes="+ROUTES.length+" aborts="+ABORTS.length);stage=9;disconnect(mc);mc.stop();}
            }
        }
    }
    private static void configure(AutoParkour m){
        setTarget(m,routeCase==6?"0.5,101,4.5,0.4,0.2;4.5,101,4.5,0.4,0.2":target());
        for(var s:m.settings()){
            if(s.id.equals("searchBudget"))((NumberSetting)s).set(1000.0);
        }
    }
    private static void setTarget(AutoParkour m,String target){
        m.clearWaypoints();for(String point:target.split(";")){String[] p=point.split(",");
            m.addWaypoint(new com.cheatprogram.parkour.ParkourPlanner.Goal(Double.parseDouble(p[0]),Double.parseDouble(p[1]),Double.parseDouble(p[2]),p.length>3?Double.parseDouble(p[3]):.3,p.length>4?Double.parseDouble(p[4]):.25));
        }
    }
    private static void disconnect(Minecraft mc)throws Exception{
        // Finish saving the disposable world before Minecraft closes its shared executors.
        try{Minecraft.class.getMethod("disconnect",net.minecraft.client.gui.screens.Screen.class,boolean.class).invoke(mc,new TitleScreen(),false);}
        catch(NoSuchMethodException absent){Minecraft.class.getMethod("disconnect",net.minecraft.client.gui.screens.Screen.class).invoke(mc,new TitleScreen());}
    }
    private static String gamerule(net.minecraft.commands.Commands commands,String legacy,String modern){
        var rules=commands.getDispatcher().getRoot().getChild("gamerule");
        // 26.2 registers both short and namespaced modern literals; older versions use legacy names.
        String name=rules.getChild("minecraft:"+modern)!=null?"minecraft:"+modern:rules.getChild(modern)!=null?modern:legacy;
        if(rules.getChild(name)==null)throw new AssertionError("Missing fixture gamerule "+modern);
        return "gamerule "+name+" false";
    }
    private static void prepare(Minecraft mc,boolean gap){
        ready.set(false);ParkourInput.clear();var server=mc.getSingleplayerServer();String name=mc.player.getScoreboardName();
        server.execute(()->{
            for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=2;cz++)server.overworld().getChunk(cx,cz);
            var source=server.createCommandSourceStack();var commands=server.getCommands();
            List<String> list=new ArrayList<>(List.of(gamerule(commands,"sendCommandFeedback","send_command_feedback"),gamerule(commands,"doMobSpawning","spawn_mobs"),gamerule(commands,"doDaylightCycle","advance_time"),"time set noon","weather clear","fill -8 100 -8 8 107 30 air","fill -8 100 -8 8 100 30 stone","gamemode survival "+name,"tp "+name+" 0.5 101 0.5 0 0","kill @e[type=!minecraft:player]"));
            if(gap){
                if(routeCase==1)list.add("fill -8 100 3 8 100 4 air");
                if(routeCase==3)list.add("fill -3 101 2 3 101 15 stone_slab[type=bottom]");
                if(routeCase==4)list.add("fill -3 103 -2 3 103 20 stone");
                if(routeCase==5)list.add("fill -8 100 -8 8 100 30 ice");
                if(routeCase==7){list.add("fill -8 100 -8 8 100 30 air");list.add("fill 0 100 -2 0 100 1 stone");list.add("fill 0 100 4 0 100 7 stone");}
                if(routeCase==8){list.add("fill -8 100 3 8 100 3 air");list.add("fill -3 101 4 3 101 12 stone");}
            }
            else if(scenario==5)list.add("fill -3 103 -2 3 103 20 stone");
            else if(scenario==6)list.add("fill -3 101 2 3 101 15 stone_slab[type=bottom]");
            else if(scenario==7)list.add("fill -8 100 -8 8 100 30 ice");
            for(String command:list)commands.performPrefixedCommand(source,command);ready.set(true);
        });
    }
}
