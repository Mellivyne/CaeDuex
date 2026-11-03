package com.cheatprogram.parkour;

import com.cheatprogram.mixin.ParkourLivingAccessor;
import com.cheatprogram.parkour.ParkourPhysics.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;

/** Built incrementally on the client thread; Terrain methods only read immutable arrays. */
public final class ParkourWorldSnapshot implements Terrain {
    private record Cell(BlockState state,List<Box> boxes,float friction,boolean safe,Box fluid) { }
    private final ClientLevel identity;
    private final LocalPlayer actor;
    private final int x,y,z,w,h;
    private final double scanY;
    private final Cell[] cells;
    private final List<Box> entityBoxes;
    private final boolean modern,legacy,fractional;
    private ParkourWorldSnapshot(Builder b){identity=b.world;actor=b.player;x=b.x;y=b.y;z=b.z;w=b.w;h=b.h;scanY=b.anchorY;cells=b.cells;entityBoxes=obstacles(b.world,b.player,new Box(x,y,z,x+w,y+h,z+w));String v=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();legacy=v.startsWith("1.20.");modern=!legacy&&!v.equals("1.21.4");fractional=v.equals("26.2");}
    public boolean modernMovement(){return modern;}
    public boolean legacyStep(){return legacy;}
    public boolean fractionalBounce(){return fractional;}
    /** Preserve retreat space when a failed local scan expands. */
    public double[] scanCenter(){return new double[]{x+(w-1)/2.0,scanY,z+(w-1)/2.0};}
    public boolean slime(double px,double feetY,double pz){Cell c=cell(floor(px),floor(feetY-(double).2f),floor(pz));return c!=null&&c.state.is(Blocks.SLIME_BLOCK);}
    private boolean nearSlime(Box body){
        for(int by=floor(body.minY()-.500001);by<=floor(body.minY());by++)for(int bz=floor(body.minZ());bz<=floor(body.maxZ());bz++)for(int bx=floor(body.minX());bx<=floor(body.maxX());bx++){
            Cell c=cell(bx,by,bz);if(c!=null&&c.state.is(Blocks.SLIME_BLOCK))return true;
        }
        return false;
    }
    private BlockPos support(Box body){
        Box feet=new Box(body.minX(),body.minY()-1e-6,body.minZ(),body.maxX(),body.minY(),body.maxZ());
        double cx=(body.minX()+body.maxX())/2,cz=(body.minZ()+body.maxZ())/2,nearest=Double.POSITIVE_INFINITY;BlockPos chosen=null;
        for(int by=floor(feet.minY())-1;by<=floor(feet.maxY());by++)for(int bz=floor(feet.minZ());bz<=floor(feet.maxZ());bz++)for(int bx=floor(feet.minX());bx<=floor(feet.maxX());bx++){
            Cell c=cell(bx,by,bz);if(c==null)continue;
            boolean touches=false;for(Box shape:c.boxes)if(shape.intersects(feet)){touches=true;break;}if(!touches)continue;
            double dx=bx+.5-cx,dy=by+.5-body.minY(),dz=bz+.5-cz;double distance=dx*dx+dy*dy+dz*dz;
            if(distance>nearest)continue;BlockPos pos=new BlockPos(bx,by,bz);
            if(distance<nearest||distance==nearest&&(chosen==null||chosen.compareTo(pos)<0)){nearest=distance;chosen=pos;}
        }
        return chosen;
    }
    public boolean slimeContact(Box body){
        if(!nearSlime(body))return false;
        BlockPos selected=support(body);
        if(selected==null)return slime((body.minX()+body.maxX())/2,body.minY(),(body.minZ()+body.maxZ())/2);
        Cell c=cell(selected.getX(),floor(body.minY()-(double).2f),selected.getZ());return c!=null&&c.state.is(Blocks.SLIME_BLOCK);
    }
    public Surface groundSurface(Box body){
        double cx=(body.minX()+body.maxX())/2,cz=(body.minZ()+body.maxZ())/2;
        BlockPos selected=support(body);
        return selected==null?surface(cx,body.minY()-.5000001,cz):surface(selected.getX()+.5,body.minY()-.5000001,selected.getZ()+.5);
    }
    public boolean climbable(double px,double py,double pz){
        Cell c=cell(floor(px),floor(py),floor(pz));if(c==null||!c.safe)return false;
        if(c.state.is(BlockTags.CLIMBABLE))return true;
        if(c.state.getBlock() instanceof TrapDoorBlock&&c.state.getValue(TrapDoorBlock.OPEN)){
            Cell below=cell(floor(px),floor(py)-1,floor(pz));
            return below!=null&&below.state.is(Blocks.LADDER)&&below.state.getValue(LadderBlock.FACING)==c.state.getValue(TrapDoorBlock.FACING);
        }
        return false;
    }
    private Cell cell(int bx,int by,int bz){int dx=bx-x,dy=by-y,dz=bz-z;return dx<0||dx>=w||dy<0||dy>=h||dz<0||dz>=w?null:cells[(dy*w+dz)*w+dx];}
    private static int floor(double d){return (int)Math.floor(d);}
    public boolean known(Box b){
        if(b.minX()<x||b.maxX()>=x+w||b.minY()<y||b.maxY()>=y+h||b.minZ()<z||b.maxZ()>=z+w)return false;
        for(int by=floor(b.minY());by<=floor(b.maxY());by++)for(int bz=floor(b.minZ());bz<=floor(b.maxZ());bz++)for(int bx=floor(b.minX());bx<=floor(b.maxX());bx++)if(cell(bx,by,bz)==null)return false;
        return true;
    }
    public boolean safe(Box b){
        for(int by=floor(b.minY()-.001);by<=floor(b.maxY());by++)for(int bz=floor(b.minZ());bz<=floor(b.maxZ());bz++)for(int bx=floor(b.minX());bx<=floor(b.maxX());bx++){Cell c=cell(bx,by,bz);if(c==null||!c.safe||ParkourEnvironment.touchesFluid(b,c.fluid))return false;}
        // Pushable mobs are obstacles, not valid solid support surfaces.
        // Reject contact rather than letting the simulator stand on their boxes.
        Box contact=b.inflate(.00001);for(Box entity:entityBoxes)if(entity.intersects(contact))return false;
        return true;
    }
    public List<Box> collisions(Box b){
        var found=new ArrayList<Box>();
        for(int by=floor(b.minY())-1;by<=floor(b.maxY());by++)for(int bz=floor(b.minZ())-1;bz<=floor(b.maxZ())+1;bz++)for(int bx=floor(b.minX())-1;bx<=floor(b.maxX())+1;bx++){
            Cell c=cell(bx,by,bz);if(c!=null)for(Box a:c.boxes)if(a.intersects(b))found.add(a);
        }
        for(Box obstacle:entityBoxes)if(obstacle.intersects(b))found.add(obstacle);
        return found;
    }
    public Surface surface(double sx,double sy,double sz){
        Cell c=cell(floor(sx),floor(sy),floor(sz));
        if(c==null)return new Surface(.6,false);
        // Fences/walls extend into the air cell above their block position.
        if(c.boxes.isEmpty()){Cell lower=cell(floor(sx),floor(sy)-1,floor(sz));if(lower!=null)for(Box b:lower.boxes)if(b.maxY()>sy){c=lower;break;}}
        return new Surface(c.friction,c.safe);
    }
    public boolean stillValid(ClientLevel level){return identity==level;}
    /** Local bounded revalidation; never called from the planner. */
    public boolean matches(ClientLevel level,Box b){
        if(level!=identity||!known(b))return false;
        long count=(long)(floor(b.maxX())-floor(b.minX())+3)*(floor(b.maxY())-floor(b.minY())+3)*(floor(b.maxZ())-floor(b.minZ())+3);
        if(count>4096)return false;
        for(int by=floor(b.minY())-1;by<=floor(b.maxY())+1;by++)for(int bz=floor(b.minZ())-1;bz<=floor(b.maxZ())+1;bz++)for(int bx=floor(b.minX())-1;bx<=floor(b.maxX())+1;bx++){
            BlockPos pos=new BlockPos(bx,by,bz);Cell c=cell(bx,by,bz);
            Box unit=new Box(bx,by,bz,bx+1,by+1,bz+1);
            boolean relevant=unit.intersects(b);
            if(!level.hasChunkAt(pos)){if(relevant)return false;continue;}
            if(relevant&&!level.getWorldBorder().isWithinBounds(pos))return false;
            BlockState current=level.getBlockState(pos);
            if(c!=null&&current==c.state)continue;
            Cell now=capture(level,actor,pos,current);
            if(c==null){
                // A padding cell outside the snapshot is not itself a terrain
                // change; only a collision protruding into the known sweep is.
                if(relevant||now.boxes.stream().anyMatch(box->box.intersects(b)))return false;
                continue;
            }
            if(!ParkourCollisionChecks.equivalent(unit,b,c.boxes,now.boxes,c.friction,now.friction,c.safe,now.safe,climbSignature(c.state),climbSignature(now.state)))return false;
            if(!ParkourEnvironment.sameFluid(c.fluid,now.fluid,b))return false;
        }
        return true;
    }
    private static int climbSignature(BlockState state){
        if(state.is(Blocks.SLIME_BLOCK))return 256;
        if(state.is(Blocks.LADDER))return 16+state.getValue(LadderBlock.FACING).ordinal();
        if(state.getBlock() instanceof TrapDoorBlock&&state.getValue(TrapDoorBlock.OPEN))return 32+state.getValue(TrapDoorBlock.FACING).ordinal();
        return state.is(BlockTags.CLIMBABLE)?1:0;
    }
    private static boolean relevantEntity(LocalPlayer player,Entity entity){
        return entity.isAlive()&&!entity.isSpectator()&&!entity.isPassengerOfSameVehicle(player)
            &&(player.canCollideWith(entity)||entity.isPushable());
    }
    private static AABB aabb(Box box){return new AABB(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());}
    private static Box entityBox(Entity entity){
        AABB b=entity.getBoundingBox().inflate(entity.isPushable()?.05:0);
        return new Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);
    }
    private static List<Box> obstacles(ClientLevel level,LocalPlayer player,Box bounds){
        return level.getEntities(player,aabb(bounds).inflate(.05),e->relevantEntity(player,e)).stream().map(ParkourWorldSnapshot::entityBox).filter(b->b.intersects(bounds)).toList();
    }
    /** Ignores dropped items, XP and decorative entities; checks the actual path corridor. */
    public static boolean clearOfEntities(ClientLevel level,LocalPlayer player,Box swept){
        for(Entity e:level.getEntities(player,aabb(swept).inflate(.05),candidate->relevantEntity(player,candidate)))
            if(entityBox(e).intersects(swept))return false;
        return true;
    }
    private static Cell capture(ClientLevel world,LocalPlayer player,BlockPos pos,BlockState s){
        String id=BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        boolean safe=s.getBlock().getSpeedFactor()==1&&s.getBlock().getJumpFactor()==1
            &&!id.contains("honey")&&!id.contains("soul_sand")&&!id.contains("web")&&!id.contains("powder_snow")&&!id.contains("scaffolding")&&!id.contains("piston")&&!id.contains("cactus")&&!id.contains("fire")&&!id.contains("magma")&&!id.contains("campfire")&&!id.contains("berry")&&!id.contains("portal")&&!id.contains("dripleaf")&&!id.contains("farmland")&&!id.contains("pointed_dripstone")&&!id.endsWith("_bed");
        safe&=BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace().equals("minecraft")&&world.getWorldBorder().isWithinBounds(pos);
        List<Box> boxes=new ArrayList<>();
        for(AABB a:s.getCollisionShape(world,pos,CollisionContext.of(player)).toAabbs())boxes.add(new Box(a.minX+pos.getX(),a.minY+pos.getY(),a.minZ+pos.getZ(),a.maxX+pos.getX(),a.maxY+pos.getY(),a.maxZ+pos.getZ()));
        var fluid=s.getFluidState();
        Box volume=fluid.isEmpty()?null:ParkourEnvironment.fluid(pos.getX(),pos.getY(),pos.getZ(),fluid.getHeight(world,pos));
        return new Cell(s,List.copyOf(boxes),s.getBlock().getFriction(),safe,volume);
    }
    public static State state(LocalPlayer p,int cooldown,int tick){var v=p.getDeltaMovement();int actual=((ParkourLivingAccessor)p).cheatprogram$jumpDelay();return new State(p.getX(),p.getY(),p.getZ(),v.x,v.y,v.z,p.getYRot(),p.onGround(),p.isSprinting(),actual,tick,p.fallDistance,p.horizontalCollision&&!p.minorHorizontalCollision);}
    public static Parameters parameters(LocalPlayer p){return new Parameters(p.getAttributeValue(Attributes.MOVEMENT_SPEED)/(p.isSprinting()?1.3:1),p.getBbWidth(),p.getBbHeight(),p.maxUpStep(),ParkourPlayerPhysics.gravity(p),((ParkourLivingAccessor)p).cheatprogram$jumpPower(),.02,true);}
    public static boolean supportedPlayer(LocalPlayer p){return p.isAlive()&&!p.isSpectator()&&!p.getAbilities().flying&&!p.isPassenger()&&!p.isInWater()&&!p.isInLava()&&!p.isFallFlying()&&!p.isCrouching()&&!p.isUsingItem()&&p.getActiveEffects().isEmpty()&&(p.isCreative()||p.getFoodData().getFoodLevel()>6)&&Math.abs(p.getBbHeight()-1.8)<.01&&Math.abs(p.getBbWidth()-.6)<.01;}
    public static final class Builder {
        private final ClientLevel world;private final LocalPlayer player;
        private final int x,y,z,w,h;private final Cell[] cells;private int index;
        private final double anchorY;
        private final ParkourWorldSnapshot previous;
        private final Map<BlockState,Cell> air=new IdentityHashMap<>();
        public Builder(ClientLevel world,LocalPlayer player,int radius,int verticalRadius){
            this(world,player,radius,verticalRadius,player.getX(),player.getY(),player.getZ());
        }
        public Builder(ClientLevel world,LocalPlayer player,int radius,int verticalRadius,double centerX,double centerY,double centerZ){
            this(world,player,radius,verticalRadius,centerX,centerY,centerZ,null);
        }
        public Builder(ClientLevel world,LocalPlayer player,int radius,int verticalRadius,double centerX,double centerY,double centerZ,ParkourWorldSnapshot previous){
            this(world,player,radius,verticalRadius,centerX,centerY,centerZ,previous,null);
        }
        public Builder(ClientLevel world,LocalPlayer player,int radius,int verticalRadius,double centerX,double centerY,double centerZ,ParkourWorldSnapshot previous,com.cheatprogram.parkour.ParkourPlanner.Goal target){
            if(radius<4||radius>40||verticalRadius<4||verticalRadius>24)throw new IllegalArgumentException("Snapshot bounds");
            // One cell of collision halo prevents half-block guide samples and
            // the player's full body from losing a passage at the scan edge.
            this.world=world;this.player=player;anchorY=centerY;x=floor(centerX)-radius-1;z=floor(centerZ)-radius-1;w=radius*2+3;
            int[] bounds=ParkourPrediction.verticalBounds(centerY,target==null?centerY:target.y(),verticalRadius,ParkourPlayerPhysics.minimumY(world),world.getHeight());
            y=bounds[0];h=bounds[1]-y;
            cells=new Cell[w*h*w];
            this.previous=previous!=null&&previous.identity==world&&previous.actor==player?previous:null;
        }
        public boolean advance(int blockBudget,long nanos){
            long until=System.nanoTime()+nanos;int n=0;
            while(index<cells.length&&n++<blockBudget&&System.nanoTime()<until){
                int i=index++,bx=x+i%w,bz=z+(i/w)%w,by=y+i/(w*w);BlockPos pos=new BlockPos(bx,by,bz);
                if(!world.hasChunkAt(pos))continue;
                BlockState state=world.getBlockState(pos);
                Cell prior=previous==null?null:previous.cell(bx,by,bz);
                if(prior!=null&&prior.state==state&&prior.safe&&!state.hasBlockEntity()&&state.getFluidState().isEmpty()&&world.getWorldBorder().isWithinBounds(pos))cells[i]=prior;
                else if((state.is(Blocks.AIR)||state.is(Blocks.CAVE_AIR)||state.is(Blocks.VOID_AIR))&&world.getWorldBorder().isWithinBounds(pos)){
                    Cell cached=air.get(state);
                    if(cached==null){cached=capture(world,player,pos,state);air.put(state,cached);}
                    cells[i]=cached;
                }else cells[i]=capture(world,player,pos,state);
            }
            return index==cells.length;
        }
        public ParkourWorldSnapshot result(){if(index!=cells.length)throw new IllegalStateException("Scan incomplete");return new ParkourWorldSnapshot(this);}
    }
}
