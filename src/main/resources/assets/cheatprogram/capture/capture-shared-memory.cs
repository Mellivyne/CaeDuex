using System;
using System.Diagnostics;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Threading;
using Microsoft.Win32.SafeHandles;

// Only the output thread owns this mapping. A slow reader can lose an old slot,
// but it cannot block capture, input, or release of a compositor frame.
public sealed class CaptureSharedMemory : IDisposable {
 public const int Slots=3,MaxFrameBytes=134217728;
 readonly MemoryMappedFile mapping;readonly MemoryMappedViewAccessor view;
 readonly SafeMemoryMappedViewHandle memory;readonly IntPtr address;bool retained;
 readonly int stride;int nextSlot;bool disposed;
 public int FrameBytes {get;private set;}
 public string Descriptor {get;private set;}
 public CaptureSharedMemory(long owner,int frameBytes){
  if(owner<=0)throw new ArgumentOutOfRangeException("owner");
  if(frameBytes<=0||frameBytes>MaxFrameBytes)throw new ArgumentOutOfRangeException("frameBytes");
  FrameBytes=frameBytes;stride=checked((frameBytes+8+63)&~63);long capacity=checked((long)Slots*stride);
  string name="Local\\CaeDuexCapture-"+owner+"-"+Process.GetCurrentProcess().Id+"-"+Guid.NewGuid().ToString("N");
  mapping=MemoryMappedFile.CreateNew(name,capacity,MemoryMappedFileAccess.ReadWrite);
  try{
   view=mapping.CreateViewAccessor(0,capacity,MemoryMappedFileAccess.ReadWrite);
   memory=view.SafeMemoryMappedViewHandle;memory.DangerousAddRef(ref retained);
   address=new IntPtr(checked(memory.DangerousGetHandle().ToInt64()+view.PointerOffset));
  }catch{if(retained){memory.DangerousRelease();retained=false;}if(view!=null)view.Dispose();mapping.Dispose();throw;}
  Descriptor=name+"\t"+capacity+"\t"+stride;
 }
 public int Write(byte[] pixels,long number){
  if(disposed)throw new ObjectDisposedException("CaptureSharedMemory");
  if(pixels==null||pixels.Length!=FrameBytes)throw new ArgumentException("Invalid capture pixel length","pixels");
  if(number<=0)throw new ArgumentOutOfRangeException("number");
  int slot=nextSlot;nextSlot=(nextSlot+1)%Slots;long offset=(long)slot*stride;
  // Aligned 64-bit sequence stores are atomic in the x64 helper. Marking the
  // slot busy first lets the reader detect both stale and torn frame copies.
  view.Write(offset,-number);Thread.MemoryBarrier();
  // Framework WriteArray<byte> marshals each element. One native bulk copy is
  // essential at full-resolution frame rates. The view's safe handle stays
  // retained until Dispose; this object has one output-thread owner.
  Marshal.Copy(pixels,0,IntPtr.Add(address,checked((int)(offset+8))),pixels.Length);Thread.MemoryBarrier();
  view.Write(offset,number);return slot;
 }
 public void Dispose(){if(disposed)return;disposed=true;try{if(retained){memory.DangerousRelease();retained=false;}view.Dispose();}finally{mapping.Dispose();}}
}
