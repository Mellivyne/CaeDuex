package com.cheatprogram.capture;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.regex.Pattern;

/** Read-only, bounded access to the native helper's three latest frame slots. */
public final class SharedCaptureMemory implements AutoCloseable {
    private static final int FILE_MAP_READ=4,SLOTS=3,MAX_FRAME_BYTES=134_217_728;
    private interface Windows extends StdCallLibrary {
        Windows API=Native.load("kernel32",Windows.class);
        Pointer OpenFileMappingW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(Pointer mapping,int access,int offsetHigh,int offsetLow,long bytes);
        boolean UnmapViewOfFile(Pointer address);
        boolean CloseHandle(Pointer handle);
    }
    private Pointer mapping,view;
    private final long stride;
    private SharedCaptureMemory(Pointer mapping,Pointer view,long stride){this.mapping=mapping;this.view=view;this.stride=stride;}

    public static SharedCaptureMemory open(String descriptor,long owner,long helperPid)throws IOException {
        if(descriptor==null||descriptor.length()>512||owner<=0||helperPid<=0)throw new IOException("Invalid capture memory descriptor");
        String[] fields=descriptor.split("\t",-1);
        String prefix="Local\\CaeDuexCapture-"+owner+"-"+helperPid+"-";
        if(fields.length!=3||!fields[0].matches(Pattern.quote(prefix)+"[0-9a-f]{32}"))throw new IOException("Invalid capture memory identity");
        long capacity,stride;
        try{
            if(!fields[1].matches("[0-9]{1,10}")||!fields[2].matches("[0-9]{1,10}"))throw new NumberFormatException();
            capacity=Long.parseLong(fields[1]);stride=Long.parseLong(fields[2]);
        }catch(NumberFormatException invalid){throw new IOException("Invalid capture memory bounds",invalid);}
        if(stride<64||stride>MAX_FRAME_BYTES+64L||(stride&63)!=0||capacity!=SLOTS*stride)throw new IOException("Invalid capture memory bounds");
        if(!Platform.isWindows()||!Platform.is64Bit())throw new IOException("Capture memory requires 64-bit Windows");
        Pointer mapping=null,view=null;
        try{
            mapping=Windows.API.OpenFileMappingW(FILE_MAP_READ,false,new WString(fields[0]));
            if(mapping==null)throw new IOException("Cannot open capture memory ("+Native.getLastError()+")");
            view=Windows.API.MapViewOfFile(mapping,FILE_MAP_READ,0,0,capacity);
            if(view==null)throw new IOException("Cannot map capture memory ("+Native.getLastError()+")");
            return new SharedCaptureMemory(mapping,view,stride);
        }catch(LinkageError|RuntimeException failure){throw new IOException("Capture memory unavailable",failure);}
        finally{
            if(view==null&&mapping!=null)Windows.API.CloseHandle(mapping);
        }
    }

    /** Copies into the caller's existing pixels; a raced or stale slot is discarded.
     * Position advances on success only. Bytes may have changed after a failed copy. */
    public synchronized boolean copy(int slot,long number,ByteBuffer dest){
        if(view==null||slot<0||slot>=SLOTS||number<=0||dest==null||dest.isReadOnly())return false;
        int bytes=dest.remaining(),position=dest.position();
        if(bytes<=0||bytes>stride-8)return false;
        long offset=slot*stride;
        if(view.getLong(offset)!=number)return false;
        VarHandle.acquireFence();
        dest.put(view.getByteBuffer(offset+8,bytes));
        VarHandle.acquireFence();
        if(view.getLong(offset)==number)return true;
        dest.position(position);return false;
    }

    @Override public synchronized void close(){
        Pointer oldView=view,oldMapping=mapping;view=null;mapping=null;
        if(oldView!=null)try{Windows.API.UnmapViewOfFile(oldView);}finally{if(oldMapping!=null)Windows.API.CloseHandle(oldMapping);}
        else if(oldMapping!=null)Windows.API.CloseHandle(oldMapping);
    }
}
