package com.cheatprogram.dev;
import com.cheatprogram.capture.*;
import java.util.*;
public final class CaptureSchedulingCheck {
    static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    static final class Fences implements CaptureUploadQueue.Fences {
        long serial;int polls,inserts,deletes;Set<Long> live=new HashSet<>(),ready=new HashSet<>();
        public long insert(){long fence=++serial;inserts++;live.add(fence);return fence;}
        public boolean ready(long fence){polls++;require(live.contains(fence),"Only poll live fences");return ready.contains(fence);}
        public void delete(long fence){require(live.remove(fence),"Delete each fence exactly once");deletes++;}
    }
    public static void main(String[] args){int cases=0;
        var f=new Fences();var queue=new CaptureUploadQueue(f);require(queue.acquire(-1)==0,"First available upload surface");cases++;
        queue.used(0);require(queue.acquire(0)==1&&f.polls==0,"Never overwrite the displayed surface in the same frame");cases++;
        queue.used(1);queue.used(2);require(queue.acquire(2)==-1,"No overwrite of any surface consumed by GUI/world this frame");cases++;
        queue.boundary();require(f.inserts==3&&queue.acquire(2)==-1&&f.polls==2,"GPU-busy surfaces return immediately without a wait");cases++;
        f.ready.add(1L);require(queue.acquire(2)==0&&f.deletes==1,"Reuse only a signaled retired surface");cases++;
        queue.used(0);queue.boundary();require(f.inserts==4,"New display use receives a new frame fence");cases++;
        queue.clear();queue.clear();require(f.live.isEmpty()&&f.deletes==4&&queue.acquire(-1)==0,"Resize/stop release every fence exactly once");cases++;
        queue.boundary();require(f.inserts==4,"No draw means no fence churn");cases++;
        System.out.println("CAPTURE_SCHEDULING_PASS "+cases+" nonblocking surface cases");
    }
}
