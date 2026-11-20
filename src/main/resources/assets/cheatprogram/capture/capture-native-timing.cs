using System;
using System.Runtime.InteropServices;

// Precision is requested only by the local capture helper while WGC is alive.
// Windows 11 can otherwise ignore its timer request because it has no visible UI.
public sealed class CaptureNativeTiming : IDisposable {
 [StructLayout(LayoutKind.Sequential)] struct PowerState { public uint Version,ControlMask,StateMask; }
 [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetProcessInformation(IntPtr process,int kind,ref PowerState state,uint size);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetProcessInformation(IntPtr process,int kind,ref PowerState state,uint size);
 [DllImport("winmm.dll")] static extern uint timeBeginPeriod(uint milliseconds);
 [DllImport("winmm.dll")] static extern uint timeEndPeriod(uint milliseconds);
 bool period,restore,closed;PowerState previous;
 CaptureNativeTiming(){
  try{
   previous=new PowerState{Version=1};
   bool known=GetProcessInformation(GetCurrentProcess(),4,ref previous,12);
   PowerState active=new PowerState{Version=1,ControlMask=4,StateMask=0};
   restore=SetProcessInformation(GetCurrentProcess(),4,ref active,12)&&known;
  }catch(EntryPointNotFoundException){}catch(DllNotFoundException){}
  try{period=timeBeginPeriod(1)==0;}catch(DllNotFoundException){}
 }
 public static CaptureNativeTiming Begin(){return new CaptureNativeTiming();}
 public void Dispose(){
  if(closed)return;closed=true;
  if(period){timeEndPeriod(1);period=false;}
  if(restore){PowerState state=new PowerState{Version=1,ControlMask=4,StateMask=previous.StateMask&4};SetProcessInformation(GetCurrentProcess(),4,ref state,12);restore=false;}
 }
}
