package com.cheatprogram.capture;

/** Three bounded display surfaces. No CPU wait is permitted on a busy GPU fence. */
public final class CaptureUploadQueue {
    public interface Fences {long insert();boolean ready(long fence);void delete(long fence);}
    private final Fences host;
    private final long[] fences=new long[3];
    private final boolean[] used=new boolean[3];
    public CaptureUploadQueue(Fences host){this.host=host;}
    public int acquire(int displayed){
        for(int i=0;i<fences.length;i++){
            if(i==displayed||used[i])continue;
            long fence=fences[i];if(fence!=0&&!host.ready(fence))continue;
            if(fence!=0){host.delete(fence);fences[i]=0;}return i;
        }
        return -1;
    }
    public void used(int index){if(index>=0)used[index]=true;}
    /** Called before a new frame, after all previous world/GUI consumers submitted draws. */
    public void boundary(){
        for(int i=0;i<used.length;i++)if(used[i]){
            long next=host.insert();if(next==0)continue;
            if(fences[i]!=0)host.delete(fences[i]);fences[i]=next;used[i]=false;
        }
    }
    public void clear(){for(int i=0;i<fences.length;i++){if(fences[i]!=0)host.delete(fences[i]);fences[i]=0;used[i]=false;}}
}
