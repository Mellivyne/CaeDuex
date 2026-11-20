package com.cheatprogram.media.player;

import com.cheatprogram.core.Config;
import com.cheatprogram.media.player.MusicModels.Login;
import com.cheatprogram.media.player.MusicModels.Provider;
import com.cheatprogram.media.player.MusicModels.Track;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared public album artwork; QR images stay local to their owning screen. */
public final class MusicArtworkCache implements AutoCloseable {
    private static final int LIMIT=128,MAX_BYTES=3*1024*1024,DISK_FILES=2048;
    private static final int COVER_WORKERS=4, WARM_WORKERS=2, SHELF_LIMIT=32;
    private static final Set<String> DISK_WARMING=ConcurrentHashMap.newKeySet();
    private static final long DISK_BYTES=128L*1024*1024,MAX_AGE=TimeUnit.DAYS.toMillis(30);
    private static final Object PUBLIC_LOCK=new Object();
    private static final Map<String,Entry> PUBLIC=new LinkedHashMap<>(160,.75f,true);
    private static volatile long epoch;
    private static long sequence,nextDiskPrune;
    private static int diskWrites;
    private final Minecraft mc=Minecraft.getInstance();
    private final Map<String,Entry> qrEntries=new LinkedHashMap<>(4,.75f,true);
    private static final LinkedBlockingDeque<Runnable> REQUESTS=new LinkedBlockingDeque<>(48);
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(COVER_WORKERS,COVER_WORKERS,10,TimeUnit.SECONDS,REQUESTS,task->{
        Thread thread=new Thread(task,"Music artwork");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor QR_WORKER=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),task->{
        Thread thread=new Thread(task,"Music login image");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor PRELOAD_WORKER=new ThreadPoolExecutor(WARM_WORKERS,WARM_WORKERS,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(WARM_WORKERS),task->{
        Thread thread=new Thread(task,"Music artwork disk preload");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private List<Track> idleTracks=List.of();private final ArtworkPreloadCursor idleCursor=new ArtworkPreloadCursor();
    private long idleEpoch=-1,idleGeneration,nextIdle,nextIdleRetry;private int idleRetryPass;
    private record IdleFailure(long generation,int index) { }
    private final ConcurrentLinkedQueue<IdleFailure> idleFailures=new ConcurrentLinkedQueue<>();
    private Set<String> visibleKeys=Set.of();
    private List<String> visibleOrder=List.of();
    private Provider colorProvider;
    private String colorUrl,colorKey;
    private long colorEpoch=-1;
    private static final class Entry {ResourceLocation texture;Runnable task;boolean pending;int color=ArtworkColors.DEFAULT,failures;long retryAt,epoch;}

    private record Cover(Provider provider,URI uri,String key) {
        static Cover of(Provider provider,String url){
            URI uri=publicUri(provider,url);
            return uri==null?null:new Cover(provider,uri,provider.id+":"+uri);
        }
    }

    /** Called only by the render thread; the network policy independently validates provider hosts. */
    public ResourceLocation artwork(Provider provider,String url){
        if(closed)return null;
        Cover cover=Cover.of(provider,url);
        if(cover==null)return null;
        synchronized(PUBLIC_LOCK){
            return queueArtwork(cover);
        }
    }
    /** Reads the decoded cover's theme color without a network request or texture readback. */
    public int dominantColor(Provider provider,String url){
        if(closed||provider==null||url==null)return ArtworkColors.DEFAULT;
        synchronized(PUBLIC_LOCK){
            if(colorProvider!=provider||!url.equals(colorUrl)||colorEpoch!=epoch){
                colorProvider=provider;colorUrl=url;colorEpoch=epoch;
                Cover cover=Cover.of(provider,url);
                colorKey=cover==null?null:cover.key();
            }
            Entry entry=colorKey==null?null:PUBLIC.get(colorKey);
            return entry==null?ArtworkColors.DEFAULT:entry.color;
        }
    }
    private ResourceLocation queueArtwork(Cover cover){
        String key=cover.key();
        Entry entry=PUBLIC.get(key);
        if(entry==null){
            entry=new Entry();
            entry.epoch=epoch;
            PUBLIC.put(key,entry);
            trimPublic();
            if(!PUBLIC.containsKey(key))return null;
        }
        if(entry.texture==null&&!entry.pending&&System.currentTimeMillis()>=entry.retryAt){
            long requestEpoch=entry.epoch;
            request(PUBLIC,key,entry,()->publicImage(cover,requestEpoch),true);
        }
        return entry.texture;
    }
    /** A far-zoom mosaic may display thousands of covers; drawing must not enqueue them all. */
    public ResourceLocation cachedArtwork(Provider provider,String url){
        if(closed)return null;
        Cover cover=Cover.of(provider,url);
        if(cover==null)return null;
        synchronized(PUBLIC_LOCK){Entry entry=PUBLIC.get(cover.key());return entry==null?null:entry.texture;}
    }
    /** Center first, then nearest neighbors; the render-thread work stays bounded. */
    public void preload(List<Track> tracks,int center,int radius){
        if(closed||tracks==null||tracks.isEmpty())return;int selected=Math.max(0,Math.min(tracks.size()-1,center));
        List<Integer> indices=new ArrayList<>(17);indices.add(selected);
        for(int step=1;step<=Math.max(0,Math.min(8,radius));step++){if(selected+step<tracks.size())indices.add(selected+step);if(selected-step>=0)indices.add(selected-step);}
        preloadVisible(tracks,indices);
    }
    /** Protect the visible working set; reserve sixteen slots for playback and list rows. */
    public void preloadVisible(List<Track> tracks,List<Integer> indices){
        if(closed||tracks==null||indices==null)return;
        Map<String,Cover> covers=new LinkedHashMap<>();
        for(Integer index:indices){
            if(covers.size()>=LIMIT-16)break;
            if(index==null||index<0||index>=tracks.size())continue;
            Track track=tracks.get(index);
            if(track!=null)addCover(covers,track.provider(),track.cover());
        }
        queueVisible(covers);
    }
    /** A shelf supplies currently visible cards first, followed by nearby cards. */
    public void preloadCovers(Provider provider,List<String> urls){
        if(closed||urls==null)return;
        Map<String,Cover> covers=new LinkedHashMap<>();
        for(int index=0;index<Math.min(SHELF_LIMIT,urls.size());index++)addCover(covers,provider,urls.get(index));
        queueVisible(covers);
    }
    private static void addCover(Map<String,Cover> covers,Provider provider,String url){
        Cover cover=Cover.of(provider,url);
        if(cover!=null)covers.putIfAbsent(cover.key(),cover);
    }
    private void queueVisible(Map<String,Cover> covers){
        synchronized(PUBLIC_LOCK){
            Set<String> next=covers.keySet();
            List<String> order=List.copyOf(next);
            boolean changed=!visibleOrder.equals(order);
            if(changed){
                for(String key:visibleKeys){
                    if(next.contains(key))continue;
                    Entry entry=PUBLIC.get(key);
                    if(entry!=null&&entry.task!=null&&REQUESTS.remove(entry.task)){
                        entry.task=null;
                        entry.pending=false;
                        entry.retryAt=0;
                    }
                }
                visibleKeys=Set.copyOf(next);
                visibleOrder=order;
            }
            for(Cover cover:covers.values())queueArtwork(cover);
            if(changed){
                // A viewport change moves outstanding visible work in front of queued extras.
                List<Cover> priority=new ArrayList<>(covers.values());
                for(int i=priority.size()-1;i>=0;i--){
                    Entry entry=PUBLIC.get(priority.get(i).key());
                    if(entry!=null&&entry.task!=null&&REQUESTS.remove(entry.task)&&!REQUESTS.offerFirst(entry.task)){
                        entry.pending=false;
                        entry.task=null;
                        entry.retryAt=0;
                    }
                }
            }
        }
    }
    /** Every layout progressively warms its whole library on disk, without uploading far-away textures. */
    public void preloadIdle(List<Track> tracks,int center){
        if(closed||tracks==null||tracks.isEmpty())return;
        int selected=Math.max(0,Math.min(tracks.size()-1,center));
        if(idleTracks!=tracks||idleEpoch!=epoch||idleCursor.size()!=tracks.size()){
            idleTracks=tracks;
            idleEpoch=epoch;
            idleGeneration++;
            idleRetryPass=0;
            idleCursor.reset(tracks.size(),selected);
            nextIdle=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(120);
        }
        long now=System.nanoTime();
        for(int checked=0;checked<4;checked++){
            IdleFailure failure=idleFailures.poll();
            if(failure==null)break;
            if(failure.generation()==idleGeneration){
                idleCursor.failed(failure.index());
                nextIdleRetry=now+TimeUnit.SECONDS.toNanos(5L<<idleRetryPass);
            }
        }
        if(idleCursor.remaining()==0&&idleCursor.hasFailures()&&idleRetryPass<2&&now>=nextIdleRetry){
            idleRetryPass++;
            idleCursor.retryFailures();
        }
        if(now<nextIdle||!REQUESTS.isEmpty()||WORKER.getActiveCount()>1||!Config.persistenceAllowed())return;
        int available=WARM_WORKERS-PRELOAD_WORKER.getActiveCount()-PRELOAD_WORKER.getQueue().size();
        if(available<=0)return;
        nextIdle=now+TimeUnit.MILLISECONDS.toNanos(80);
        for(int checked=0;checked<8&&available>0;checked++){
            int index=idleCursor.next(selected);
            if(index<0)return;
            Track track=tracks.get(index);
            Cover cover=track==null?null:Cover.of(track.provider(),track.cover());
            if(cover==null)continue;
            synchronized(PUBLIC_LOCK){
                Entry entry=PUBLIC.get(cover.key());
                if(entry!=null&&(entry.texture!=null||entry.pending))continue;
            }
            if(!DISK_WARMING.add(cover.key()))continue;
            long requestEpoch=epoch,generation=idleGeneration;
            try{
                PRELOAD_WORKER.execute(()->{
                    try{
                        if(!closed&&requestEpoch==epoch&&Config.persistenceAllowed())warmArtwork(cover,requestEpoch);
                    }catch(Exception|OutOfMemoryError ignored){
                        idleFailures.offer(new IdleFailure(generation,index));
                    }finally{
                        DISK_WARMING.remove(cover.key());
                    }
                });
                available--;
            }catch(RejectedExecutionException full){
                DISK_WARMING.remove(cover.key());
                idleCursor.defer(index);
                return;
            }
        }
    }
    public boolean unavailable(Provider provider,String url){URI uri=publicUri(provider,url);if(uri==null)return true;synchronized(PUBLIC_LOCK){Entry entry=PUBLIC.get(provider.id+":"+uri);return entry!=null&&entry.texture==null&&entry.failures>0;}}
    public ResourceLocation login(Login login){
        if(closed||login==null)return null;byte[] bytes=login.qrPng();if(bytes.length==0||bytes.length>MAX_BYTES)return null;
        String key="qr:"+Arrays.hashCode(bytes);Entry prior=qrEntries.get(key);if(prior!=null)return prior.texture;
        Entry entry=new Entry();qrEntries.put(key,entry);while(qrEntries.size()>2){String eldest=qrEntries.keySet().iterator().next();release(qrEntries.remove(eldest));}
        request(qrEntries,key,entry,()->decode(bytes,false),false);return null;
    }
    private record DecodedArtwork(NativeImage pixels,int color) { }
    private interface Pixels {DecodedArtwork get() throws Exception;}
    private void request(Map<String,Entry> entries,String key,Entry entry,Pixels source,boolean shared){
        entry.pending=true;
        Runnable task=()->{
            NativeImage pixels=null;
            try{DecodedArtwork image=source.get();if(image==null)return;pixels=image.pixels();NativeImage decoded=pixels;mc.execute(()->{synchronized(PUBLIC_LOCK){upload(entries,key,entry,decoded,shared,image.color());}});}
            catch(Exception|OutOfMemoryError failed){if(pixels!=null)pixels.close();mc.execute(()->{synchronized(PUBLIC_LOCK){if(entries.get(key)==entry){entry.pending=false;entry.task=null;entry.failures=Math.min(6,entry.failures+1);entry.retryAt=System.currentTimeMillis()+Math.min(60000,1000L<<entry.failures);}}});}
        };entry.task=task;
        try{(shared?WORKER:QR_WORKER).execute(task);}catch(java.util.concurrent.RejectedExecutionException full){entry.pending=false;entry.task=null;entry.retryAt=System.currentTimeMillis()+250;}
    }
    private static URI publicUri(Provider provider,String url){if(provider==null||url==null||url.isBlank()||url.length()>16384)return null;try{URI uri=URI.create(url.startsWith("//")?"https:"+url:url);if("http".equalsIgnoreCase(uri.getScheme()))uri=URI.create("https"+uri.toString().substring(4));if(!MusicNetwork.allowed(provider,uri))return null;String host=uri.getHost().toLowerCase(Locale.ROOT),query=uri.getRawQuery();
        // NetEase's artwork thumbnail parameter is content sizing, not identity.
        if(provider==Provider.NETEASE&&(host.equals("music.126.net")||host.endsWith(".music.126.net"))){StringBuilder other=new StringBuilder();if(query!=null)for(String part:query.split("&"))if(!part.regionMatches(true,0,"param=",0,6)){if(other.length()>0)other.append('&');other.append(part);}if(other.length()>0)other.append('&');query=other.append("param=512y512").toString();}
        URI result=URI.create("https://"+host+(uri.getRawPath()==null?"":uri.getRawPath())+(query==null?"":"?"+query));return MusicNetwork.allowed(provider,result)?result:null;
    }catch(IllegalArgumentException invalid){return null;}}
    private static Path disk(String key)throws Exception{return Config.directory().resolve("cache/music-artwork").resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))+".img");}
    private static DecodedArtwork publicImage(Cover cover,long requestEpoch)throws Exception{
        if(requestEpoch!=epoch)return null;
        Path path=disk(cover.key());
        long now=System.currentTimeMillis();
        byte[] cached=readCached(path,now);
        // Decode once, outside the settings/credential persistence lock.
        if(cached!=null)try{return decode(cached,true);}catch(IOException|RuntimeException damaged){ /* Refetch the exact artwork. */ }
        byte[] bytes=MusicNetwork.bytes(cover.provider(),cover.uri(),Map.of(),MAX_BYTES);
        DecodedArtwork image=decode(bytes,true);
        remember(path,bytes,now,requestEpoch);
        return image;
    }
    private static byte[] readCached(Path path,long now){
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed())return null;
            try{
                if(!fresh(path,now))return null;
                byte[] bytes=Files.readAllBytes(path);
                try{touch(path,now);}catch(IOException ignored){ }
                return bytes;
            }catch(IOException ignored){return null;}
        }
    }
    private static void warmArtwork(Cover cover,long requestEpoch)throws Exception{
        Path path=disk(cover.key());
        long now=System.currentTimeMillis();
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed())return;
            if(fresh(path,now)){touch(path,now);return;}
        }
        byte[] bytes=MusicNetwork.bytes(cover.provider(),cover.uri(),Map.of(),MAX_BYTES);
        // Warm only the disk. Never allocate a native texture for distant tracks.
        decodeBuffered(bytes).flush();
        remember(path,bytes,now,requestEpoch);
    }
    private static boolean fresh(Path path,long now)throws IOException{
        if(!Files.isRegularFile(path))return false;
        long size=Files.size(path);
        return size>0&&size<=MAX_BYTES&&now-Files.getLastModifiedTime(path).toMillis()<MAX_AGE;
    }
    private static void touch(Path path,long now)throws IOException{
        if(now-Files.getLastModifiedTime(path).toMillis()>=TimeUnit.MINUTES.toMillis(1))
            Files.setLastModifiedTime(path,FileTime.fromMillis(now));
    }
    private static void remember(Path path,byte[] bytes,long now,long requestEpoch){
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed()||requestEpoch!=epoch)return;
            try{
                Files.createDirectories(path.getParent());
                Path part=path.resolveSibling(path.getFileName()+"."+Thread.currentThread().threadId()+".tmp");
                try{
                    Files.write(part,bytes);
                    try{Files.move(part,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                    catch(AtomicMoveNotSupportedException unsupported){Files.move(part,path,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(part);}
                if(++diskWrites>=32||now>=nextDiskPrune){
                    diskWrites=0;
                    nextDiskPrune=now+TimeUnit.SECONDS.toMillis(30);
                    pruneDisk(path.getParent(),now);
                }
            }catch(IOException|SecurityException ignored){ }
        }
    }
    private static void pruneDisk(Path directory,long now)throws java.io.IOException{List<Path> files=new ArrayList<>();try(var stream=Files.list(directory)){stream.filter(path->path.getFileName().toString().matches("[0-9a-f]{64}\\.img")).forEach(files::add);}files.sort(Comparator.comparingLong(MusicArtworkCache::modified).reversed());long total=0;int count=0;for(Path file:files){long size=Files.size(file);if(size<=0||size>MAX_BYTES||now-modified(file)>MAX_AGE||++count>DISK_FILES||total+size>DISK_BYTES)Files.deleteIfExists(file);else total+=size;}}
    private static long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(java.io.IOException missing){return 0;}}
    private void trimPublic(){while(PUBLIC.size()>LIMIT){var iterator=PUBLIC.entrySet().iterator();boolean removed=false;while(iterator.hasNext()){var candidate=iterator.next();if(visibleKeys.contains(candidate.getKey()))continue;release(candidate.getValue());iterator.remove();removed=true;break;}if(!removed)break;}}
    /** Explicit cache controls may call this; leaving a screen retains public covers. */
    public static void clearPublicCache(){Minecraft mc=Minecraft.getInstance();synchronized(PUBLIC_LOCK){epoch++;PUBLIC.values().forEach(entry->{if(entry.texture!=null)mc.getTextureManager().release(entry.texture);});PUBLIC.clear();}try{WORKER.execute(()->{synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())return;Path directory=Config.directory().resolve("cache/music-artwork");if(!Files.isDirectory(directory))return;try(var files=Files.list(directory)){files.filter(path->path.getFileName().toString().matches("[0-9a-f]{64}\\.img")).forEach(path->{try{Files.deleteIfExists(path);}catch(java.io.IOException ignored){ }});}catch(java.io.IOException ignored){ }}});}catch(java.util.concurrent.RejectedExecutionException ignored){ }}
    private static BufferedImage decodeBuffered(byte[] bytes)throws IOException{
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Artwork size");
        // These bounded input bytes are already resident. Do not create ImageIO temp files.
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new IllegalArgumentException("Artwork format");
            var reader=readers.next();
            try{
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>16384||height>16384||(long)width*height>33554432)
                    throw new IllegalArgumentException("Artwork dimensions");
                var parameters=reader.getDefaultReadParam();
                int sample=Math.max(1,(Math.max(width,height)+511)/512);
                parameters.setSourceSubsampling(sample,sample,0,0);
                BufferedImage decoded=reader.read(0,parameters);
                if(decoded==null)throw new IllegalArgumentException("Artwork pixels");
                return decoded;
            }finally{reader.dispose();}
        }
    }
    private static DecodedArtwork decode(byte[] bytes,boolean crop)throws IOException{
        BufferedImage decoded=decodeBuffered(bytes);
        NativeImage pixels=null;
        try{
            int side=Math.min(decoded.getWidth(),decoded.getHeight()),w=crop?side:decoded.getWidth(),h=crop?side:decoded.getHeight();
            int ox=crop?(decoded.getWidth()-side)/2:0,oy=crop?(decoded.getHeight()-side)/2:0;
            pixels=new NativeImage(w,h,false);
            int[] row=new int[w];
            for(int y=0;y<h;y++){
                decoded.getRGB(ox,y+oy,w,1,row,0,w);
                for(int x=0;x<w;x++)pixels.setPixel(x,y,row[x]);
            }
            return new DecodedArtwork(pixels,crop?ArtworkColors.dominant(decoded,ox,oy,w,h):ArtworkColors.DEFAULT);
        }catch(RuntimeException|OutOfMemoryError failed){if(pixels!=null)pixels.close();throw failed;}
        finally{decoded.flush();}
    }
    private void upload(Map<String,Entry> entries,String key,Entry entry,NativeImage pixels,boolean shared,int color){
        if(!shared&&closed||entries.get(key)!=entry||shared&&entry.epoch!=epoch){pixels.close();return;}
        entry.pending=false;entry.task=null;
        DynamicTexture texture=null;
        try{texture=new DynamicTexture(pixels);texture.setFilter(true,false);
            ResourceLocation id=ResourceLocation.fromNamespaceAndPath("cheatprogram","dynamic/music/"+(++sequence));
            mc.getTextureManager().register(id,texture);entry.texture=id;entry.color=color;
        }catch(RuntimeException failed){if(texture!=null)texture.close();else pixels.close();entry.failures++;entry.retryAt=System.currentTimeMillis()+5000;}
    }
    private void release(Entry entry){if(entry==null)return;if(entry.task!=null){REQUESTS.remove(entry.task);QR_WORKER.remove(entry.task);}if(entry.texture!=null)mc.getTextureManager().release(entry.texture);}
    @Override public void close(){closed=true;visibleKeys=Set.of();visibleOrder=List.of();qrEntries.values().forEach(this::release);qrEntries.clear();}
}

