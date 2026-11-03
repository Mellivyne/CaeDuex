package com.cheatprogram.parkour;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.setting.Setting;
import com.cheatprogram.parkour.ParkourPlanner.Goal;
import com.google.gson.*;
import java.util.*;

/** A growable, persisted list, without a waypoint-count or text-field limit. */
public final class ParkourWaypointSetting extends Setting<List<Goal>> {
    private final ArrayList<Goal> points=new ArrayList<>();
    private final List<Goal> view=Collections.unmodifiableList(points);
    private int revision;
    private boolean explicit;
    public ParkourWaypointSetting(){super("waypoints",List.of());value=view;visibleWhen(()->false);}
    public int revision(){return revision;}
    public boolean explicitlySet(){return explicit;}
    @Override public void set(List<Goal> next){List<Goal> copy=List.copyOf(next);points.clear();points.addAll(copy);explicit=true;changed();}
    public void append(Goal point){points.add(Objects.requireNonNull(point));explicit=true;changed();}
    public void removeLast(){if(!points.isEmpty()){points.removeLast();explicit=true;changed();}}
    public void clear(){points.clear();explicit=true;changed();}
    private void changed(){revision++;Config.markDirty();}
    @Override public void reset(){points.clear();explicit=false;changed();}
    @Override public String summary(){return Integer.toString(points.size());}
    @Override public JsonElement save(){JsonArray list=new JsonArray();for(Goal p:points){JsonArray row=new JsonArray();row.add(p.x());row.add(p.y());row.add(p.z());row.add(p.radius());row.add(p.height());if(p.region()!=null){var b=p.region();for(double n:new double[]{b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()})row.add(n);}list.add(row);}return list;}
    private static ArrayList<Goal> decode(JsonElement json){
        if(json==null||!json.isJsonArray())throw new IllegalArgumentException("Invalid waypoint list");
        ArrayList<Goal> loaded=new ArrayList<>();
        for(JsonElement item:json.getAsJsonArray()){
            if(!item.isJsonArray())throw new IllegalArgumentException("Invalid waypoint");
            JsonArray row=item.getAsJsonArray();if(row.size()!=5&&row.size()!=11)throw new IllegalArgumentException("Invalid waypoint");
            for(JsonElement number:row)if(!number.isJsonPrimitive()||!number.getAsJsonPrimitive().isNumber()||!Double.isFinite(number.getAsDouble()))throw new IllegalArgumentException("Invalid waypoint coordinate");
            com.cheatprogram.parkour.ParkourPhysics.Box region=row.size()==11?new com.cheatprogram.parkour.ParkourPhysics.Box(row.get(5).getAsDouble(),row.get(6).getAsDouble(),row.get(7).getAsDouble(),row.get(8).getAsDouble(),row.get(9).getAsDouble(),row.get(10).getAsDouble()):null;
            loaded.add(new Goal(row.get(0).getAsDouble(),row.get(1).getAsDouble(),row.get(2).getAsDouble(),row.get(3).getAsDouble(),row.get(4).getAsDouble(),region));
        }
        return loaded;
    }
    @Override public void validateSerialized(JsonElement json){decode(json);}
    @Override public void load(JsonElement json){
        if(json==null||json.isJsonNull())return;
        ArrayList<Goal> loaded=decode(json);
        points.clear();points.addAll(loaded);explicit=true;revision++;
    }
}
