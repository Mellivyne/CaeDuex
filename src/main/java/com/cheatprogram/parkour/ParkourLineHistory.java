package com.cheatprogram.parkour;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, geometry-keyed display history. It never participates in movement planning. */
public final class ParkourLineHistory<K,V> {
    public record Sample<V>(V value, double opacity) { }
    private record Entry<V>(V value, long lastSeen) { }
    private final LinkedHashMap<K,Entry<V>> entries=new LinkedHashMap<>(128,.75f,true);
    private final int limit;
    public ParkourLineHistory(int limit) {
        if(limit<1)throw new IllegalArgumentException("Positive history limit required");
        this.limit=limit;
    }
    public void clear(){entries.clear();}
    public int size(){return entries.size();}
    public List<Sample<V>> update(Map<K,V> active,long now,double seconds) {
        long lifetime=Double.isFinite(seconds)?(long)(Math.max(0,Math.min(60,seconds))*1_000_000_000L):0;
        for(var item:active.entrySet()) {
            entries.put(item.getKey(),new Entry<>(item.getValue(),now));
            while(entries.size()>limit)entries.remove(entries.keySet().iterator().next());
        }
        var result=new ArrayList<Sample<V>>(entries.size());
        for(Iterator<Map.Entry<K,Entry<V>>> it=entries.entrySet().iterator();it.hasNext();) {
            var item=it.next();var entry=item.getValue();
            long age=Math.max(0,now-entry.lastSeen());
            boolean present=active.containsKey(item.getKey());
            if(!present&&(lifetime==0||age>=lifetime)){it.remove();continue;}
            double opacity=present?1:Math.min(1,(lifetime-age)/(double)Math.max(1,lifetime)*4);
            result.add(new Sample<>(entry.value(),opacity));
        }
        return result;
    }
}
