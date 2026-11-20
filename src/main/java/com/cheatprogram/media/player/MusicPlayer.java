package com.cheatprogram.media.player;

import com.cheatprogram.core.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** In-game player. Only confirmed provider accounts and original provider lyrics enter snapshots. */
public final class MusicPlayer {
    private static final LrcLyrics EMPTY_LYRICS = LrcLyrics.parse("");
    private static final ThreadPoolExecutor PREFERENCES=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),MusicAudioPlayer.daemon("music-preferences"),new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final String DEFAULT_LAYOUT = "coverflow";
    /** Stable stored ids shared by the layout picker and preference validation. */
    public static final List<String> LAYOUTS = List.of(DEFAULT_LAYOUT, "wall", "stars", "sphere");
    private static final List<PlaybackMode> PLAYBACK_MODES = List.of(PlaybackMode.values());
    public static final MusicPlayer INSTANCE = new MusicPlayer();
    private static String status(String name) { return "ui.musicPlayer." + name; }
    private final Object lock = new Object();
    private final Function<Provider, MusicProvider> providers;
    private final Map<Provider,MusicProvider> providerInstances=new ConcurrentHashMap<>();
    private final MusicAudioPlayer audio;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(48), MusicAudioPlayer.daemon("music-provider"));
    private final ThreadPoolExecutor playbackWorker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),MusicAudioPlayer.daemon("music-playback-provider"));
    private final ReentrantReadWriteLock providerAccess=new ReentrantReadWriteLock(true);
    private volatile Job runningSong;
    private record CachedTracks(long time,List<Track> tracks){}
    private record CachedPlaylists(long time,List<Playlist> lists){}
    private final Map<String,CachedTracks> libraryCache=new LinkedHashMap<>();
    private final Map<String,CachedPlaylists> playlistCache=new LinkedHashMap<>();
    private record CachedRecommendations(long time,List<Recommendation> items){}
    public record Recommendations(Provider provider,List<Recommendation> items,String status,boolean loading){
        public Recommendations{items=List.copyOf(items);}
    }
    private final Map<String,CachedRecommendations> recommendationCache=new LinkedHashMap<>();
    private Recommendations recommendations=new Recommendations(Provider.NETEASE,List.of(),status("recommendationsIdle"),false);
    public record Comments(CommentTarget target,List<Comment> items,String status,boolean loading,boolean hasMore,
                           boolean likesEnabled,boolean postingEnabled,boolean writing){
        public Comments{items=List.copyOf(items);}
    }
    private Comments comments=new Comments(null,List.of(),status("commentsEmpty"),false,false,false,false,false);
    private String commentCursor="";
    private final Set<String> seenCommentCursors=new HashSet<>();
    private long commentRequest;
    private CompletableFuture<Boolean> pendingCommentWrite;
    private boolean commentWriteStarted;
    private CompletableFuture<Boolean> recommendationDetail;
    private final Set<DetailRead<?>> detailReads=new HashSet<>();
    private final Map<String,Recommendation> artistAlbumCards=new LinkedHashMap<>();

    public record Discovery(String kind,String query,int page,boolean hasMore) { }
    public record Capabilities(boolean search,boolean radio,boolean translation) { }
    public record TrackLyrics(LrcLyrics original,LrcLyrics translated) {
        public TrackLyrics {
            if(original==null)original=EMPTY_LYRICS;
            if(translated==null)translated=EMPTY_LYRICS;
        }
    }
    private static final TrackLyrics EMPTY_TRACK_LYRICS=new TrackLyrics(EMPTY_LYRICS,EMPTY_LYRICS);
    private static final class DetailRead<T> {
        final CompletableFuture<T> result=new CompletableFuture<>();
        final T unavailable;
        DetailRead(T unavailable){this.unavailable=unavailable;}
        void discard(){result.complete(unavailable);}
    }
    /** Opaque browsing-only bookmark; account and playback state are never restored from it. */
    public static final class LibraryPage {
        private final MusicPlayer owner;
        private final long epoch;
        private final Provider provider;
        private final String accountId,library;
        private final List<Track> tracks;
        private final List<Playlist> playlists;
        private final Discovery discovery;
        private LibraryPage(MusicPlayer owner,long epoch,Provider provider,String accountId,List<Track> tracks,List<Playlist> playlists,String library,Discovery discovery){
            this.owner=owner;this.epoch=epoch;this.provider=provider;this.accountId=accountId;
            this.tracks=List.copyOf(tracks);this.playlists=List.copyOf(playlists);this.library=library;this.discovery=discovery;
        }
    }
    private record CachedSession(Account account,List<Track> tracks,List<Playlist> playlists,String library,List<Quality> qualities,Discovery discovery){}
    private final Map<Provider,CachedSession> sessionCache=new EnumMap<>(Provider.class);
    private CompletableFuture<Boolean> accountVerification=new CompletableFuture<>();
    private boolean accountVerified;
    public record QualitySettings(List<Quality> offered,String requested,String applied){public QualitySettings{offered=List.copyOf(offered);}}
    private final Map<Provider,String> requestedQualities=new EnumMap<>(Provider.class);
    private final Map<Provider,PlaybackMode> requestedModes=new EnumMap<>(Provider.class);
    private List<Quality> offeredQualities=List.of();
    private String layout = DEFAULT_LAYOUT;
    private boolean moduleEnabled=true,translationEnabled;
    private LrcLyrics translated=EMPTY_LYRICS;private String translatedTrack="",translationState=status("translatedLyricsDisabled");private long translationRequest;
    private Discovery discovery=new Discovery("library","",0,false);
    private String currentLibrary="";
    private int streamFailures;
    private long lastAudioProgress,recoveryPosition;
    private ScheduledFuture<?> recovery;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, MusicAudioPlayer.daemon("music-login"));
    private final Set<Long> operations = new HashSet<>();
    private volatile Snapshot snapshot;
    private volatile String requirement = "";
    private Provider provider = Provider.NETEASE;
    private MusicProvider service;
    private Account account;
    private List<Track> tracks = List.of();
    private List<Playlist> playlists = List.of();
    private Playback playback = emptyPlayback(0);
    private LrcLyrics lyrics = EMPTY_LYRICS;
    private Login login;
    private Media media;
    private String state = status("idle");
    private long epoch, revision, generation, operationSequence, libraryRequest, playlistsRequest, cancelledOperationsThrough;
    private List<Track> queue = List.of();
    private int queueIndex = -1;
    private List<Integer> shuffleOrder = List.of();
    private boolean queueRadio,radioPending;
    private String spotifyContext="";
    private boolean loginPending, desiredPlaying, closed;
    private ScheduledFuture<?> loginPoll;
    private ScheduledFuture<?> remotePoll,volumeUpdate;

    private MusicPlayer() {
        this(p -> switch(p) {
            case NETEASE -> new NeteaseMusicProvider();
            case QQ -> new QQMusicProvider();
            case KUGOU -> new KugouMusicProvider();
            case QISHUI -> new QishuiMusicProvider();
            case KUWO -> new KuwoMusicProvider();
            case SPOTIFY -> new SpotifyMusicProvider();
            default -> new UnavailableMusicProvider(p);
        }, null);
    }
    /** Tests inject provider operations and a device-free audio factory. */
    MusicPlayer(Function<Provider, MusicProvider> providers, Function<MusicAudioPlayer.Listener, MusicAudioPlayer> audioFactory) {
        this.providers = Objects.requireNonNull(providers);
        readQualityPreferences();
        this.audio = audioFactory == null ? new MusicAudioPlayer(this::audioSample) : audioFactory.apply(this::audioSample);
        timer.setRemoveOnCancelPolicy(true);
        synchronized (lock) { publish(); }
        selectProvider(Provider.NETEASE);
    }
    public Snapshot snapshot() { return snapshot; }
    public String requirement() { return requirement; }
    public Discovery discovery(){synchronized(lock){return discovery;}}
    /** Captures the current account's browsing page for a later return from discovery. */
    public LibraryPage captureLibraryPage(){
        synchronized(lock){
            if(closed||account==null||verificationRejected())return null;
            return new LibraryPage(this,epoch,provider,account.id(),tracks,playlists,currentLibrary,discovery);
        }
    }
    /** Returns false when the bookmark belongs to another player, provider or account session. */
    public boolean restoreLibraryPage(LibraryPage page){
        synchronized(lock){
            if(page==null||closed||page.owner!=this||page.epoch!=epoch||page.provider!=provider||account==null||!Objects.equals(page.accountId,account.id())||verificationRejected())return false;
            nextLibraryRequest();playlistsRequest++;
            // Obsolete loads may finish upstream, but must not keep this page busy or report failures on it.
            cancelledOperationsThrough=operationSequence;operations.clear();
            worker.getQueue().removeIf(task->task instanceof Job job&&job.operation!=0&&job.operation<=cancelledOperationsThrough);
            tracks=page.tracks;playlists=page.playlists;currentLibrary=page.library;discovery=page.discovery;
            state=status("ready");publish();return true;
        }
    }
    public Capabilities capabilities(){synchronized(lock){return new Capabilities(service!=null&&service.supportsSearch(),service!=null&&service.supportsRadio(),service!=null&&service.supportsTranslatedLyrics());}}
    public CompletableFuture<TrackLyrics> previewLyrics(Track track,boolean includeTranslation){
        synchronized(lock){
            if(!canReadDetails(track))return CompletableFuture.completedFuture(EMPTY_TRACK_LYRICS);
            boolean sameTrack=playback.track()!=null&&playback.track().key().equals(track.key());
            LrcLyrics original=sameTrack?lyrics:EMPTY_LYRICS;
            LrcLyrics translation=includeTranslation&&track.key().equals(translatedTrack)?translated:EMPTY_LYRICS;
            boolean loadTranslation=includeTranslation&&service.supportsTranslatedLyrics()&&translation.isEmpty();
            if(!original.isEmpty()&&!loadTranslation)
                return CompletableFuture.completedFuture(new TrackLyrics(original,translation));
            long token=epoch;
            return readDetails(EMPTY_TRACK_LYRICS,active->{
                LrcLyrics resolvedOriginal=original,resolvedTranslation=translation;
                try{if(original.isEmpty())resolvedOriginal=active.lyrics(track);}catch(Exception unavailable){/* One lyric source can be absent. */}
                try{if(loadTranslation&&validSafe(token))resolvedTranslation=active.translatedLyrics(track);}catch(Exception unavailable){/* Preserve any original lyrics. */}
                return new TrackLyrics(resolvedOriginal,resolvedTranslation);
            });
        }
    }

    public boolean supportsSimilarTracks(Track track){
        synchronized(lock){return canReadDetails(track)&&service.supportsSimilarTracks(track);}
    }

    public CompletableFuture<List<Track>> similarTracks(Track track){
        synchronized(lock){
            if(!supportsSimilarTracks(track))return CompletableFuture.completedFuture(List.of());
            return readDetails(List.of(),active->safeTracks(active,active.similarTracks(track),20).stream()
                    .filter(song->!song.key().equals(track.key())).distinct().toList());
        }
    }

    public List<Artist> artists(Track track) {
        synchronized (lock) {
            if (!canReadDetails(track)) return List.of();
            return track.artists().stream().filter(this::canReadArtist).distinct().limit(16).toList();
        }
    }

    public CompletableFuture<SearchPage> artistSongs(Artist artist,int page) {
        SearchPage empty = new SearchPage(List.of(),page,false);
        synchronized (lock) {
            if (!canReadArtist(artist) || page<0 || page>=100) return CompletableFuture.completedFuture(empty);
            return readDetails(empty,active->{
                SearchPage result=active.artistSongs(artist,page);
                if (result==null || result.page()!=page) return empty;
                return new SearchPage(safeTracks(active,result.tracks(),30),page,result.hasMore()&&page<99);
            });
        }
    }

    public CompletableFuture<ArtistAlbums> artistAlbums(Artist artist,int page) {
        ArtistAlbums empty = new ArtistAlbums(List.of(),page,false);
        synchronized (lock) {
            if (!canReadArtist(artist) || page<0 || page>=100) return CompletableFuture.completedFuture(empty);
            long token=epoch;
            return readDetails(empty,active->{
                ArtistAlbums result=active.artistAlbums(artist,page);
                if (result==null || result.page()!=page) return empty;
                List<Recommendation> albums=safeRecommendations(active.provider(),result.albums()).stream()
                        .filter(item->item.kind()==RecommendationKind.ALBUM).toList();
                synchronized (lock) {
                    if (!valid(token)) return empty;
                    for (Recommendation album:albums) artistAlbumCards.put(album.id(),album);
                    trim(artistAlbumCards,600);
                }
                return new ArtistAlbums(albums,page,result.hasMore()&&page<99);
            });
        }
    }

    public CompletableFuture<List<Track>> artistAlbumTracks(Recommendation album) {
        synchronized (lock) {
            if (closed || album==null || album.provider()!=provider || !album.equals(artistAlbumCards.get(album.id())))
                return CompletableFuture.completedFuture(List.of());
            return readDetails(List.of(),active->safeTracks(active,active.recommendationTracks(album),1000));
        }
    }

    private boolean canReadArtist(Artist artist) {
        return !closed&&service!=null&&artist!=null&&artist.provider()==provider&&service.supportsArtist(artist);
    }

    private boolean canReadDetails(Track track){
        return !closed&&service!=null&&track!=null&&track.provider()==provider&&track.id()!=null&&!track.id().isBlank();
    }

    @FunctionalInterface private interface ReadDetail<T> { T read(MusicProvider active) throws Exception; }

    /** Use the bounded provider worker while keeping browsing and playback state independent of detail reads. */
    private <T> CompletableFuture<T> readDetails(T unavailable,ReadDetail<T> read){
        long token=epoch;
        DetailRead<T> detail=new DetailRead<>(unavailable);
        detailReads.add(detail);
        try{
            worker.execute(new Job(token,()->{
                T loaded=unavailable;
                try{
                    MusicProvider active=activeProvider(token);
                    if(active!=null&&!detail.result.isDone())loaded=read.read(active);
                }catch(Exception failed){/* Missing detail data is local to this request. */}
                synchronized(lock){
                    detailReads.remove(detail);
                    detail.result.complete(valid(token)&&loaded!=null?loaded:unavailable);
                }
            },0,false,0,false,false));
        }catch(RejectedExecutionException full){
            detailReads.remove(detail);
            detail.discard();
        }
        return detail.result;
    }
    public LrcLyrics translatedLyrics(){synchronized(lock){return translationEnabled&&playback.track()!=null&&playback.track().key().equals(translatedTrack)?translated:EMPTY_LYRICS;}}
    public String translationStatus(){synchronized(lock){return translationState;}}
    public void translation(boolean enabled){synchronized(lock){if(closed||translationEnabled==enabled)return;translationEnabled=enabled;translationRequest++;translated=EMPTY_LYRICS;translatedTrack="";translationState=status(enabled?"loading":"translatedLyricsDisabled");}if(enabled)requestTranslation();}
    public void setModuleEnabled(boolean enabled){
        if (!enabled) { pauseForModule(); return; }
        synchronized (lock) {
            if (closed || moduleEnabled) return;
            moduleEnabled=true;
            if (accountVerified && service instanceof SpotifyMusicProvider remote) scheduleRemote(epoch,remote,1000);
        }
        requestTranslation();
    }
    /** Stops the current decoder and invalidates pending stream/retry work without touching accounts. */
    public void pauseForModule(){
        final long token;final SpotifyMusicProvider remote;
        synchronized(lock){if(closed||!moduleEnabled)return;moduleEnabled=false;desiredPlaying=false;token=epoch;remote=service instanceof SpotifyMusicProvider spotify?spotify:null;
            long position=playback.position();revision++;generation++;media=null;if(recovery!=null){recovery.cancel(false);recovery=null;}if(remotePoll!=null){remotePoll.cancel(false);remotePoll=null;}
            playbackWorker.getQueue().clear();Job running=runningSong;if(running!=null&&running.runner!=null)running.runner.interrupt();audio.stop();
            state=status(playback.track()==null?"idle":"paused");playback=new Playback(playback.track(),false,position,System.nanoTime(),playback.durationMs(),revision,state);publish();
        }
        if(remote!=null)submit(token,remote::pauseRemote);
    }
    public String layout(){synchronized(lock){return layout;}}
    public void layout(String selected){synchronized(lock){if(closed||selected==null||!LAYOUTS.contains(selected)||layout.equals(selected))return;layout=selected;writeQualityPreferences();}}
    public List<PlaybackMode> playbackModes() { return PLAYBACK_MODES; }
    public PlaybackMode playbackMode() {
        synchronized (lock) { return requestedModes.getOrDefault(provider,PlaybackMode.ORDERED); }
    }
    public PlaybackQueue playbackQueue() {
        synchronized (lock) { return new PlaybackQueue(queue,queueIndex,playbackMode()); }
    }
    public List<Track> worldTracks() {
        synchronized (lock) { return queue.isEmpty()?tracks:queue; }
    }
    public void playWorldTrack(Track track) { playTrack(track); }
    public void playbackMode(PlaybackMode mode) {
        synchronized (lock) {
            if (closed || mode==null || mode==playbackMode()) return;
            requestedModes.put(provider,mode);
            updateShuffleOrder();
            writeQualityPreferences();
            if (service instanceof SpotifyMusicProvider remote && playback.track()!=null) {
                long token=epoch,song=revision;
                submitSong(token,song,()->remote.playbackModeRemote(mode));
            }
        }
    }
    public QualitySettings qualitySettings(){synchronized(lock){return new QualitySettings(offeredQualities,requestedQualities.getOrDefault(provider,""),media==null||media.quality()==null?"":media.quality());}}
    /** Only provider-offered IDs can be selected; the actual stream label comes from Media. */
    public boolean quality(String id){
        final Track track;final long token,song,position;final boolean fetchLyrics;
        synchronized(lock){
            if(closed||verificationRejected()||id==null||!id.isEmpty()&&offeredQualities.stream().noneMatch(q->q.id().equals(id)))return false;
            if(id.equals(requestedQualities.getOrDefault(provider,"")))return true;
            if(id.isEmpty())requestedQualities.remove(provider);else requestedQualities.put(provider,id);writeQualityPreferences();
            track=playback.track();if(track==null){publish();return true;}
            position=playback.position();token=epoch;song=++revision;generation++;media=null;fetchLyrics=lyrics.isEmpty();
            streamFailures=0;lastAudioProgress=System.nanoTime();if(recovery!=null){recovery.cancel(false);recovery=null;}
            state=status(desiredPlaying?"buffering":"paused");playback=new Playback(track,false,position,System.nanoTime(),playback.durationMs(),song,state);audio.stop();publish();
            if(!moduleEnabled)return true;
        }
        queueSong(token,song,track,position,null,fetchLyrics);return true;
    }
    public void selectProvider(Provider selected) {
        if (selected == null) return;
        final long token;
        synchronized (lock) {
            if (closed || provider == selected && service != null) return;
            if(service instanceof SpotifyMusicProvider spotify)spotify.cancelLogin();
            provider = selected; service = providerInstances.get(selected); resetSession(); token = epoch;
            CachedSession cached=sessionCache.get(selected);
            if(cached!=null){account=cached.account();tracks=cached.tracks();playlists=cached.playlists();currentLibrary=cached.library();offeredQualities=cached.qualities();discovery=cached.discovery();}
            state = status(cached==null?"loading":"ready"); audio.stop(); publish();
        }
        submitExclusive(token, () -> {
            try{
                MusicProvider active = activeProvider(token);
                if (active == null) return;
                if (unavailable(active)) { setState(token, "unsupported"); return; }
                validateSelectedAccount(token,active);
            }finally{synchronized(lock){if(valid(token)&&!accountVerification.isDone())accountVerification.complete(false);}}
        });
    }
    public void login() {
        final long token;
        synchronized (lock) {
            if (closed) return;
            clearAccountCache();resetSession(); token = epoch; loginPending = true; state = status("loginPending"); audio.stop(); publish();
        }
        submitExclusive(token, () -> {
            MusicProvider active = activeProvider(token);
            if (active == null) return;
            if (unavailable(active)) { finishLoginFailure(token, "unsupported"); return; }
            Login started = active.beginLogin();
            if (started == null) { finishLoginFailure(token, "failed"); return; }
            synchronized (lock) { if (!valid(token)) return; login = started; state = safeStatus(started.status()); publish(); }
            pollLogin(token, started, System.nanoTime());
        });
    }
    public void logout() {
        final long token; final MusicProvider old; final Provider selected;
        synchronized (lock) {
            if (closed) return;
            clearAccountCache();
            old = service; selected = provider; resetSession(); token = epoch; state = status("loginRequired"); audio.stop(); publish();
        }
        // Clearing saved credentials remains required even if the user immediately changes providers.
        enqueue(new Job(token, () -> { MusicProvider target = old == null ? providers.apply(selected) : old; if (target != null) target.logout(); }, 0, true));
    }
    public void refreshLiked() { refreshLiked(false); }
    public void refreshLiked(boolean force) { loadTracks(null,force); }
    public String accountConfiguration(){synchronized(lock){return service instanceof SpotifyMusicProvider spotify?spotify.clientId():"";}}
    public void configureAccount(Provider expected,String input){
        final long token;
        synchronized(lock){if(closed||provider!=expected)return;clearAccountCache();resetSession();token=epoch;state=status("loading");audio.stop();publish();}
        submitExclusive(token,()->{
            MusicProvider active=activeProvider(token);Account confirmed=null;
            if(active instanceof SpotifyMusicProvider spotify)spotify.clientId(input);
            else if(active instanceof KuwoMusicProvider kuwo)confirmed=kuwo.importCookie(input);
            else throw new IllegalArgumentException(status("unsupported"));
            if(confirmed!=null){if(acceptAccount(token,confirmed))refreshLiked();}
            else setState(token,"loginRequired");
        });
    }
    public Comments comments(){synchronized(lock){return comments;}}

    public boolean supportsComments(CommentTarget target){
        synchronized(lock){return !closed&&target!=null&&target.provider()==provider&&service!=null&&service.supportsComments(target);}
    }

    public void openComments(Track track){openComments(track==null?null:CommentTarget.song(track));}

    public void openComments(CommentTarget target){
        synchronized(lock){
            closeComments();
            if(closed||target==null||target.provider()!=provider)return;
            boolean supported=supportsComments(target);
            boolean canWrite=supported&&accountVerified&&account!=null;
            comments=new Comments(target,List.of(),status(supported?"commentsEmpty":"commentsUnsupported"),false,false,
                    canWrite&&service.supportsCommentLikes(target),canWrite&&service.supportsCommentPosting(target),false);
            if(supported)requestComments("");
        }
    }

    public void moreComments(){
        synchronized(lock){
            if(closed||comments.target()==null||comments.loading()||comments.writing()||!comments.hasMore())return;
            requestComments(commentCursor);
        }
    }

    public void retryComments(){moreComments();}

    public void closeComments(){
        synchronized(lock){
            commentRequest++;
            commentCursor="";
            seenCommentCursors.clear();
            comments=new Comments(null,List.of(),status("commentsEmpty"),false,false,false,false,false);
            if(pendingCommentWrite!=null&&!commentWriteStarted){pendingCommentWrite.complete(false);pendingCommentWrite=null;}
        }
    }

    private void requestComments(String cursor){
        long token=epoch,request=++commentRequest;
        CommentTarget target=comments.target();
        updateComments(comments.items(),"commentsLoading",true,false,false);
        if(!enqueue(new Job(token,()->{
            try{
                synchronized(lock){if(!valid(token)||request!=commentRequest)return;}
                MusicProvider active=activeProvider(token);
                if(active==null)return;
                CommentPage page=active.comments(target,cursor);
                synchronized(lock){
                    if(!valid(token)||request!=commentRequest)return;
                    applyCommentPage(cursor,page);
                }
            }catch(Exception failed){
                synchronized(lock){
                    if(!valid(token)||request!=commentRequest)return;
                    String key=failureStatus(failed);
                    if(key.equals(status("loginRequired")))updateComments(comments.items(),"commentsLogin",false,false,false);
                    else if(key.equals(status("commentsUnsupported")))updateComments(comments.items(),"commentsUnsupported",false,false,false);
                    else updateComments(comments.items(),"commentsFailed",false,true,false);
                }
            }
        },0,false,0,false,false)))updateComments(comments.items(),"commentsFailed",false,true,false);
    }

    private void applyCommentPage(String requested,CommentPage page){
        LinkedHashMap<String,Comment> combined=new LinkedHashMap<>();
        for(Comment comment:comments.items())combined.put(comment.id(),comment);
        if(page!=null)for(Comment comment:page.items()){
            Comment clean=cleanComment(comment);
            if(clean!=null)combined.putIfAbsent(clean.id(),clean);
        }
        seenCommentCursors.add(requested);
        String next=page==null?"":page.nextCursor();
        boolean more=!next.isBlank()&&next.length()<=1024&&!seenCommentCursors.contains(next);
        commentCursor=more?next:"";
        updateComments(List.copyOf(combined.values()),combined.isEmpty()?"commentsEmpty":"commentsReady",false,more,false);
    }

    public boolean canReplyToComments(){
        synchronized(lock){
            return canWriteComment()&&service.supportsCommentReplies(comments.target());
        }
    }

    public boolean canReplyToComment(Comment selected){
        synchronized(lock){
            return selected!=null&&canWriteComment()&&service.supportsCommentReplies(comments.target())
                    &&comments.items().stream().anyMatch(comment->comment.id().equals(selected.id()));
        }
    }

    public CompletableFuture<Boolean> postComment(String body){return sendComment(null,body);}

    public CompletableFuture<Boolean> replyToComment(Comment parent,String body){
        if(parent==null)return CompletableFuture.completedFuture(false);
        return sendComment(parent,body);
    }

    private CompletableFuture<Boolean> sendComment(Comment parent,String body){
        synchronized(lock){
            if(!canWriteComment())return CompletableFuture.completedFuture(false);
            final Comment replyTo;
            if(parent==null){
                if(!comments.postingEnabled())return CompletableFuture.completedFuture(false);
                replyTo=null;
            }else{
                if(!service.supportsCommentReplies(comments.target()))return CompletableFuture.completedFuture(false);
                replyTo=comments.items().stream().filter(comment->comment.id().equals(parent.id())).findFirst().orElse(null);
                if(replyTo==null)return CompletableFuture.completedFuture(false);
            }
            final String content;
            try{
                content=MusicProvider.commentBody(body);
                if(content.codePointCount(0,content.length())>500)throw new java.io.IOException();
            }catch(java.io.IOException invalid){
                updateComments(comments.items(),"commentInvalid",false,comments.hasMore(),false);
                return CompletableFuture.completedFuture(false);
            }
            CommentTarget target=comments.target();
            return writeComment("commentsPosting","commentsPosted","commentsPostFailed",active->replyTo==null
                    ?active.postComment(target,content):active.replyComment(target,replyTo,content),posted->{
                Comment clean=cleanComment(posted);
                if(clean==null)return comments.items();
                List<Comment> result=new ArrayList<>(comments.items().size()+1);
                result.add(clean);
                comments.items().stream().filter(comment->!clean.id().equals(comment.id())).forEach(result::add);
                return result;
            });
        }
    }

    public boolean toggleCommentLike(Comment selected){
        synchronized(lock){
            if(selected==null||!comments.likesEnabled()||!canWriteComment())return false;
            Comment current=comments.items().stream().filter(comment->comment.id().equals(selected.id())).findFirst().orElse(null);
            if(current==null)return false;
            CommentTarget target=comments.target();
            boolean liked=!current.liked();
            writeComment("commentsLiking","commentsLikeUpdated","commentsLikeFailed",active->{
                active.likeComment(target,current,liked);
                return new Comment(current.id(),current.author(),current.avatar(),current.body(),current.createdAt(),
                        (int)Math.clamp((long)current.likes()+(liked?1:-1),0,Integer.MAX_VALUE),liked,
                        current.replyToId(),current.replyToAuthor(),current.replyToBody());
            },updated->comments.items().stream().map(comment->comment.id().equals(updated.id())?updated:comment).toList());
            return true;
        }
    }

    private boolean canWriteComment(){
        return !closed&&accountVerified&&account!=null&&comments.target()!=null&&!comments.loading()&&!comments.writing()&&pendingCommentWrite==null;
    }

    @FunctionalInterface private interface CommentWrite {Comment send(MusicProvider provider)throws Exception;}

    private CompletableFuture<Boolean> writeComment(String working,String success,String failure,CommentWrite write,
                                                    Function<Comment,List<Comment>> apply){
        long token=epoch,request=commentRequest;
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        pendingCommentWrite=result;
        commentWriteStarted=false;
        updateComments(comments.items(),working,false,comments.hasMore(),true);
        if(!enqueue(new Job(token,()->{
            synchronized(lock){
                if(!valid(token)||request!=commentRequest||pendingCommentWrite!=result)return;
                commentWriteStarted=true;
            }
            boolean acknowledged=false;
            Comment response=null;
            try{
                MusicProvider active=activeProvider(token);
                if(active!=null){response=write.send(active);acknowledged=true;}
            }catch(Exception ignored){/* The user may retry explicitly after checking the platform. */}
            synchronized(lock){
                if(pendingCommentWrite==result)pendingCommentWrite=null;
                if(valid(token)&&request==commentRequest){
                    updateComments(acknowledged?apply.apply(response):comments.items(),acknowledged?success:failure,
                            false,comments.hasMore(),false);
                }
            }
            result.complete(acknowledged);
        },0,false,0,false,false))){
            pendingCommentWrite=null;
            updateComments(comments.items(),failure,false,comments.hasMore(),false);
            result.complete(false);
        }
        return result;
    }

    private static Comment cleanComment(Comment comment){
        if(comment==null||comment.id()==null||comment.id().isBlank()||comment.id().length()>160)return null;
        String body=commentText(comment.body(),16_384);
        if(body.isBlank())return null;
        return new Comment(comment.id(),commentText(comment.author(),160),comment.avatar()==null?"":comment.avatar(),body,
                Math.max(0,comment.createdAt()),Math.max(0,comment.likes()),comment.liked(),commentText(comment.replyToId(),160),
                commentText(comment.replyToAuthor(),160),commentText(comment.replyToBody(),16_384));
    }

    private static String commentText(String input,int max){
        if(input==null)return "";
        int end=input.offsetByCodePoints(0,Math.min(max,input.codePointCount(0,input.length())));
        return input.substring(0,end).replace("\r\n","\n").replace('\r','\n').replaceAll("[\\p{Cc}&&[^\\n\\t]]","").strip();
    }

    private void updateComments(List<Comment> items,String name,boolean loading,boolean more,boolean writing){
        comments=new Comments(comments.target(),items,status(name),loading,more,comments.likesEnabled(),comments.postingEnabled(),writing);
    }

    public Recommendations recommendations(){synchronized(lock){return recommendations;}}
    public boolean recommendationAccountReady(){synchronized(lock){return accountVerified&&account!=null;}}
    public void loadRecommendations(boolean refresh){
        final long token;
        final String key;
        synchronized(lock){
            if(closed||recommendations.loading())return;
            if(service!=null&&!service.supportsRecommendations()){catalog(List.of(),"recommendationsUnsupported",false);return;}
            if(!accountVerified||account==null){catalog(List.of(),"recommendationsLogin",false);return;}
            token=epoch;
            key=accountKey();
            CachedRecommendations cached=recommendationCache.get(key);
            if(!refresh&&cached!=null&&System.nanoTime()-cached.time()<TimeUnit.MINUTES.toNanos(5)){
                showRecommendations(cached.items());
                return;
            }
            catalog(recommendations.items(),"recommendationsLoading",true);
            if(!enqueue(new Job(token,()->{
                try{
                    MusicProvider active=activeProvider(token);
                    if(active==null)return;
                    List<Recommendation> items=safeRecommendations(active.provider(),active.recommendations());
                    synchronized(lock){
                        if(!valid(token))return;
                        recommendationCache.put(key,new CachedRecommendations(System.nanoTime(),items));
                        trim(recommendationCache,8);
                        showRecommendations(items);
                    }
                }catch(Exception failed){
                    synchronized(lock){
                        if(valid(token))catalog(recommendations.items(),recommendationFailure(failed),false);
                    }
                }
            },0,false)))catalog(recommendations.items(),"recommendationsFailed",false);
        }
    }
    private static String recommendationFailure(Exception failure){
        String key=failureStatus(failure);
        if(key.equals(status("recommendationsUnsupported")))return "recommendationsUnsupported";
        if(key.equals(status("loginRequired")))return "recommendationsLogin";
        return "recommendationsFailed";
    }
    private void showRecommendations(List<Recommendation> items){
        catalog(items,items.isEmpty()?"recommendationsEmpty":"recommendationsReady",false);
    }
    private void catalog(List<Recommendation> items,String state,boolean loading){
        recommendations=new Recommendations(provider,items,status(state),loading);
    }
    private static List<Recommendation> safeRecommendations(Provider provider,List<Recommendation> items){
        if(items==null)return List.of();
        EnumMap<RecommendationKind,Integer> counts=new EnumMap<>(RecommendationKind.class);
        LinkedHashMap<String,Recommendation> result=new LinkedHashMap<>();
        for(Recommendation item:items){
            if(item==null||item.provider()!=provider||item.kind()==null||item.id()==null||!item.id().matches("[A-Za-z0-9_-]{1,128}")
                    ||item.name()==null||item.name().isBlank()||item.name().length()>300||counts.getOrDefault(item.kind(),0)>=30)continue;
            if(result.putIfAbsent(item.kind()+":"+item.id(),item)==null)counts.merge(item.kind(),1,Integer::sum);
        }
        return List.copyOf(result.values());
    }
    public CompletableFuture<Boolean> openRecommendation(Recommendation item){
        synchronized(lock){
            if (closed || !accountVerified || item==null || item.provider()!=provider
                    || !recommendations.items().contains(item) && !item.equals(artistAlbumCards.get(item.id())))
                return CompletableFuture.completedFuture(false);
            long token=epoch,request=nextLibraryRequest();
            String key=accountKey()+":recommendation:"+item.kind()+":"+item.id();
            CachedTracks cached=libraryCache.get(key);
            if(cached!=null&&System.nanoTime()-cached.time()<TimeUnit.MINUTES.toNanos(2)){
                showRecommendation(item,key,cached.tracks());
                return CompletableFuture.completedFuture(true);
            }
            var result=new CompletableFuture<Boolean>();
            recommendationDetail=result;
            state=status("loading");
            publish();
            if(!enqueue(new Job(token,()->{
                boolean applied=false;
                try{
                    MusicProvider active=activeProvider(token);
                    if(active==null)return;
                    List<Track> loaded=safeTracks(active,active.recommendationTracks(item),1000);
                    synchronized(lock){
                        if(!valid(token)||request!=libraryRequest)return;
                        if(loaded.isEmpty()){
                            state=status("noTracks");
                            publish();
                        }else{
                            libraryCache.put(key,new CachedTracks(System.nanoTime(),loaded));
                            trim(libraryCache,16);
                            showRecommendation(item,key,loaded);
                            applied=true;
                        }
                    }
                }catch(Exception failed){
                    synchronized(lock){if(valid(token)&&request==libraryRequest){state=failureStatus(failed);publish();}}
                }finally{result.complete(applied);}
            },0,false)))result.complete(false);
            return result;
        }
    }
    private void showRecommendation(Recommendation item,String key,List<Track> loaded){
        tracks=loaded;
        currentLibrary=key;
        discovery=new Discovery("recommendation",item.name(),0,false);
        state=status("ready");
        publish();
    }
    public void cancelRecommendationDetail(){
        synchronized(lock){if(recommendationDetail!=null&&!recommendationDetail.isDone())nextLibraryRequest();}
    }
    private long nextLibraryRequest(){
        if(recommendationDetail!=null){recommendationDetail.complete(false);recommendationDetail=null;}
        return ++libraryRequest;
    }

    public void openPlaylist(Playlist playlist) { if (playlist != null) loadTracks(playlist,false); }
    public void search(String query){search(query,0,false);}
    public void moreSearch(){Discovery shown=discovery();if(shown.kind().equals("search")&&shown.hasMore())search(shown.query(),shown.page()+1,true);}
    private void search(String query,int page,boolean append){
        String text=query==null?"":query.strip();if(text.isEmpty()||text.length()>120||page<0||page>=100){synchronized(lock){state=status("invalidSearch");publish();}return;}
        discoveryRequest("search",text,page,append,false);
    }
    public void radio(){discoveryRequest("radio","",0,false,true);}
    private void discoveryRequest(String kind,String query,int page,boolean append,boolean autoplay){
        final long token,request,operation;
        synchronized(lock){if(closed||verificationRejected())return;if(account==null){state=status("loginRequired");publish();return;}token=epoch;request=nextLibraryRequest();operation=beginOperation();state=status("loading");publish();}
        submit(token,()->{try{MusicProvider active=activeProvider(token);if(active==null)return;
            SearchPage result;if(kind.equals("radio")){if(!active.supportsRadio())throw new IllegalArgumentException(status("radioUnsupported"));result=new SearchPage(active.radio(),0,false);}
            else {if(!active.supportsSearch())throw new IllegalArgumentException(status("searchUnsupported"));result=active.search(query,page);}
            List<Track> safe=result==null?List.of():safeTracks(active,result.tracks(),30);List<Quality> offers=safe.isEmpty()?List.of():qualityOffers(active,safe.getFirst());boolean start=false;
            synchronized(lock){if(!valid(token)||request!=libraryRequest)return;LinkedHashMap<String,Track> combined=new LinkedHashMap<>();if(append&&discovery.kind().equals(kind)&&discovery.query().equals(query))for(Track track:tracks)combined.put(track.key(),track);for(Track track:safe)if(combined.size()<300)combined.putIfAbsent(track.key(),track);
                tracks=List.copyOf(combined.values());discovery=new Discovery(kind,query,page,result!=null&&result.hasMore()&&tracks.size()<300&&page<99);currentLibrary=kind+":"+query;if(playback.track()==null)offeredQualities=offers;
                state=status(tracks.isEmpty()?"noTracks":"ready");publish();start=autoplay&&moduleEnabled&&!tracks.isEmpty();}
            if(start)synchronized(lock){if(valid(token)&&request==libraryRequest)playIndex(0);}
        }catch(Exception failed){synchronized(lock){if(valid(token)&&request==libraryRequest){state=failureStatus(failed);publish();}}}finally{endOperation(operation);}},operation);
    }
    private static List<Track> safeTracks(MusicProvider active,List<Track> values,int limit){return values==null?List.of():values.stream().filter(t->t!=null&&t.provider()==active.provider()&&t.id()!=null&&!t.id().isBlank()).limit(limit).toList();}
    private void loadTracks(Playlist playlist,boolean force) {
        final long token, request, operation;
        final String key;
        synchronized (lock) {
            if (closed) return;
            if(verificationRejected())return;
            if (account == null) { state = status("loginRequired"); publish(); return; }
            token = epoch; request = nextLibraryRequest();key=accountKey()+":"+(playlist==null?"liked":playlist.id());
            CachedTracks cached=libraryCache.get(key);
            if(!force&&cached!=null&&System.nanoTime()-cached.time()<TimeUnit.MINUTES.toNanos(2)){
                discovery=new Discovery("library","",0,false);currentLibrary=key;tracks=cached.tracks();state=playback.track()==null?status("ready"):playback.status();publish();return;
            }
            operation = beginOperation();
            state = status("loading"); publish();
        }
        submit(token, () -> {
            try {
                MusicProvider active = activeProvider(token); if (active == null) return;
                List<Track> loaded = playlist == null ? active.liked() : active.tracks(playlist);
                List<Track> safe = loaded == null ? List.of() : loaded.stream()
                        .filter(t -> t != null && t.provider() == active.provider() && t.id() != null && !t.id().isBlank()).toList();
                List<Quality> offers=safe.isEmpty()?List.of():qualityOffers(active,safe.getFirst());
                synchronized (lock) {
                    if (!valid(token) || request != libraryRequest) return;
                    discovery=new Discovery("library","",0,false);tracks = List.copyOf(safe);currentLibrary=key;libraryCache.put(key,new CachedTracks(System.nanoTime(),tracks));trim(libraryCache,8);
                    if(playback.track()==null)offeredQualities=offers;
                    state = playback.track()==null?status("ready"):playback.status(); publish();
                }
            } finally { endOperation(operation); }
        }, operation);
    }
    public void loadPlaylists() {loadPlaylists(false);}
    public void loadPlaylists(boolean force) {
        final long token, request, operation;
        final String key;
        synchronized (lock) {
            if (closed) return;
            if(verificationRejected())return;
            if (account == null) { state = status("loginRequired"); publish(); return; }
            token = epoch; request = ++playlistsRequest;key=accountKey();CachedPlaylists cached=playlistCache.get(key);
            if(!force&&cached!=null&&System.nanoTime()-cached.time()<TimeUnit.MINUTES.toNanos(2)){playlists=cached.lists();publish();return;}
            operation = beginOperation(); state = status("loading"); publish();
        }
        submit(token, () -> {
            try {
                MusicProvider active = activeProvider(token); if (active == null) return;
                List<Playlist> loaded = active.playlists();
                synchronized (lock) {
                    if (!valid(token) || request != playlistsRequest) return;
                    playlists = loaded == null ? List.of() : loaded.stream().filter(Objects::nonNull).toList();
                    playlistCache.put(key,new CachedPlaylists(System.nanoTime(),playlists));trim(playlistCache,8);
                    state = playback.track()==null?status("ready"):playback.status(); publish();
                }
            } finally { endOperation(operation); }
        }, operation);
    }
    public void playIndex(int index) {
        synchronized (lock) {
            if (closed || !moduleEnabled || verificationRejected() || index < 0 || index >= tracks.size()) return;
            setQueue(tracks,index,discovery.kind().equals("radio"),browsedSpotifyContext());
            startPlayback(queue.get(index),index);
        }
    }
    /** Plays a selected row from an independent provider catalog without changing the browser page. */
    public void playCollection(List<Track> collection,int index) {
        synchronized (lock) {
            if (collection==null || index<0 || index>=collection.size()) return;
            Track selected=collection.get(index);
            if (!canReadDetails(selected) || !moduleEnabled || verificationRejected()) return;
            List<Track> verified=new ArrayList<>();
            int position=-1;
            for (int i=0; i<collection.size() && verified.size()<20_000; i++) {
                Track track=collection.get(i);
                if (!canReadDetails(track)) continue;
                if (i==index) position=verified.size();
                verified.add(track);
            }
            if (position<0) return;
            setQueue(verified,position,false,"");
            startPlayback(selected,position);
        }
    }
    /** Play a provider track without replacing the library currently open in the player. */
    public void playTrack(Track track){
        synchronized(lock){
            if(!canReadDetails(track)||!moduleEnabled||verificationRejected())return;
            int index=indexOfTrack(queue,track);
            if (index>=0) {
                startPlayback(queue.get(index),index);
                return;
            }
            index=indexOfTrack(tracks,track);
            if (index>=0) playIndex(index);
            else startPlayback(track,-1);
        }
    }
    private static int indexOfTrack(List<Track> collection,Track track) {
        if (track!=null) for (int index=0;index<collection.size();index++)
            if (collection.get(index).key().equals(track.key())) return index;
        return -1;
    }
    private void setQueue(List<Track> collection,int index,boolean radio,String context) {
        queue=List.copyOf(collection);
        queueIndex=index;
        queueRadio=radio;
        radioPending=false;
        spotifyContext=context;
        updateShuffleOrder();
    }
    private void updateShuffleOrder() {
        if (playbackMode()!=PlaybackMode.SHUFFLE || queue.isEmpty()) {
            shuffleOrder=List.of();
            return;
        }
        List<Integer> order=new ArrayList<>(queue.size());
        for (int i=0;i<queue.size();i++) if (i!=queueIndex) order.add(i);
        Collections.shuffle(order);
        if (queueIndex>=0) order.addFirst(queueIndex);
        shuffleOrder=List.copyOf(order);
    }
    private String browsedSpotifyContext() {
        if (provider!=Provider.SPOTIFY || !currentLibrary.startsWith(accountKey()+":")) return "";
        String source=currentLibrary.substring(accountKey().length()+1);
        String type="playlist";
        if (source.startsWith("recommendation:ALBUM:")) {
            type="album";
            source=source.substring("recommendation:ALBUM:".length());
        } else if (source.startsWith("recommendation:PLAYLIST:")) {
            source=source.substring("recommendation:PLAYLIST:".length());
        }
        return source.matches("[A-Za-z0-9]{22}")?"spotify:"+type+":"+source:"";
    }
    private void startPlayback(Track track,int index){
        clearPlayback();
        queueIndex=index;
        radioPending=false;
        desiredPlaying=true;
        state=status("loading");
        playback=new Playback(track,false,0,System.nanoTime(),Math.max(0,track.durationMs()),revision,state);
        audio.stop();
        publish();
        queueSong(epoch,revision,track,0,null,true);
    }
    public void togglePlaying() {
        boolean pause,resolve;long resumePosition;
        synchronized (lock) {
            if (closed || !moduleEnabled || verificationRejected()) return;
            if (playback.track() == null) {
                if(!tracks.isEmpty())playIndex(0);
                return;
            }
            if (Set.of(status("ended"), status("failed"), status("decoderRequired"), status("audioUnavailable")).contains(playback.status())){
                startPlayback(playback.track(),queueIndex);
                return;
            }
            desiredPlaying = !desiredPlaying; pause = !desiredPlaying;
            if (pause) radioPending=false;
            if(desiredPlaying)lastAudioProgress=System.nanoTime();
            String next = status(pause ? "paused" : "buffering"); state = next;
            playback = new Playback(playback.track(), false, playback.position(), System.nanoTime(), playback.durationMs(), revision, next); publish();
            resolve=desiredPlaying&&media==null&&!(service instanceof SpotifyMusicProvider);resumePosition=playback.positionMs();
        }
        if(resolve)seek(resumePosition);
        else {
            final long token,song;final SpotifyMusicProvider remote;final boolean pauseRemote=pause;
            synchronized(lock){token=epoch;song=revision;remote=service instanceof SpotifyMusicProvider spotify?spotify:null;}
            if(remote==null)audio.paused(pause);
            else submitSong(token,song,()->{try{if(pauseRemote)remote.pauseRemote();else remote.resumeRemote();applyRemote(token,song,remote.remotePlayback());}catch(Exception failure){failSong(token,song,failure);}});
        }
    }
    public void seek(long positionMs) {
        final Track track; final Media cached; final long token, song, position; final boolean requestLyrics;
        synchronized (lock) {
            if (closed || verificationRejected() || playback.track() == null) return;
            track = playback.track(); cached = media; requestLyrics = lyrics.isEmpty(); position = Math.max(0, playback.durationMs() > 0 ? Math.min(playback.durationMs(), positionMs) : positionMs);
            token = epoch; song = ++revision; generation++;
            lastAudioProgress=System.nanoTime();streamFailures=0;if(recovery!=null){recovery.cancel(false);recovery=null;}
            state = status(desiredPlaying ? "buffering" : "paused");
            playback = new Playback(track, false, position, System.nanoTime(), playback.durationMs(), song, state); audio.stop(); publish();
        }
        synchronized(lock){if(!moduleEnabled)return;}queueSong(token, song, track, position, cached, requestLyrics);
    }
    public void next(int delta) {
        synchronized (lock) {
            if (closed || !moduleEnabled || verificationRejected() || delta==0) return;
            if (queue.isEmpty()) {
                if (service instanceof SpotifyMusicProvider remote && playback.track()!=null) {
                    long token=epoch,song=revision;
                    submitSong(token,song,()->{remote.nextRemote(delta);applyRemote(token,song,remote.remotePlayback());});
                } else if (!tracks.isEmpty()) playIndex(delta<0?tracks.size()-1:0);
                return;
            }
            advanceQueue(delta,false);
        }
    }

    private void advanceQueue(int delta,boolean naturalEnd) {
        PlaybackMode mode=playbackMode();
        if (naturalEnd && mode==PlaybackMode.SINGLE_LOOP) {
            startPlayback(playback.track(),queueIndex);
            return;
        }
        int index;
        if (mode==PlaybackMode.SHUFFLE && !queueRadio) {
            if (shuffleOrder.isEmpty()) updateShuffleOrder();
            int cursor=shuffleOrder.indexOf(queueIndex);
            index=shuffleOrder.get(Math.floorMod((long)cursor+delta,shuffleOrder.size()));
        } else {
            long candidate=queueIndex<0?(delta<0?queue.size()-1:0):(long)queueIndex+delta;
            if (queueRadio && candidate>=queue.size()) {
                requestRadioBatch();
                return;
            }
            boolean wrap=mode==PlaybackMode.LIST_LOOP || mode==PlaybackMode.SINGLE_LOOP;
            if (!wrap && (candidate<0 || candidate>=queue.size())) {
                if (naturalEnd) desiredPlaying=false;
                return;
            }
            index=Math.floorMod(candidate,queue.size());
        }
        startPlayback(queue.get(index),index);
    }

    private void requestRadioBatch() {
        if (radioPending) return;
        long token=epoch,song=revision;
        radioPending=true;
        if (!enqueue(new Job(token,()->{
            try {
                MusicProvider active=activeProvider(token);
                if (active==null) return;
                List<Track> batch=safeTracks(active,active.radio(),30);
                synchronized (lock) {
                    if (!songValidLocked(token,song) || !moduleEnabled || !queueRadio || !radioPending) return;
                    if (batch.isEmpty()) { desiredPlaying=false; return; }
                    if (discovery.kind().equals("radio") && tracks.equals(queue)) tracks=batch;
                    setQueue(batch,0,true,"");
                    startPlayback(batch.getFirst(),0);
                }
            } finally {
                synchronized (lock) { if (songValidLocked(token,song)) radioPending=false; }
            }
        },0,false))) radioPending=false;
    }
    public void volume(double value) {
        if(!Double.isFinite(value))return;
        synchronized(lock){
            if(closed)return;
            if(!(service instanceof SpotifyMusicProvider remote)){audio.volume(value);return;}
            long token=epoch;if(volumeUpdate!=null)volumeUpdate.cancel(false);
            volumeUpdate=timer.schedule(()->submit(token,()->remote.volumeRemote(value)),180,TimeUnit.MILLISECONDS);
        }
    }
    public void shutdown() {
        synchronized (lock) {
            if (closed) return;
            if(service instanceof SpotifyMusicProvider spotify)spotify.cancelLogin();
            resetSession(); closed = true; state = status("idle"); publish();
        }
        timer.shutdownNow(); worker.shutdown();playbackWorker.shutdownNow(); audio.close();
    }
    private void queueSong(long token, long song, Track track, long position, Media cached, boolean fetchLyrics) {
        submitSong(token, song, () -> {
            MusicProvider active = activeProvider(token); synchronized(lock){if(active==null||!moduleEnabled||!songValidLocked(token,song))return;}
            if(active instanceof SpotifyMusicProvider remote){
                try{
                    if (cached==null) {
                        final List<Track> collection;
                        final int index;
                        final String context;
                        final PlaybackMode mode;
                        synchronized (lock) {
                            if (!songValidLocked(token,song)) return;
                            collection=queue;
                            index=queueIndex;
                            context=spotifyContext;
                            mode=playbackMode();
                        }
                        remote.playRemote(track,position,collection,index,context,mode);
                        if (songValid(token,song)) remote.playbackModeRemote(mode);
                    } else remote.seekRemote(position);
                    boolean disabled;synchronized(lock){disabled=!moduleEnabled;}if(disabled){remote.pauseRemote();return;}
                    synchronized(lock){if(!songValidLocked(token,song))return;media=new Media("spotify-connect","");}
                    applyRemote(token,song,remote.remotePlayback());
                }catch(Exception failure){failSong(token,song,failure);}return;
            }
            try {
                List<Quality> offers=qualityOffers(active,track);String requested;
                synchronized(lock){if(!songValidLocked(token,song))return;offeredQualities=offers;requested=requestedQualities.getOrDefault(provider,"");publish();}
                if(!requested.isEmpty()&&!offers.isEmpty()&&offers.stream().noneMatch(q->q.id().equals(requested)))throw new IllegalArgumentException(status("qualityUnavailable"));
                Media resolved = cached == null ? active.media(track,requested) : cached;
                if (resolved == null || resolved.url() == null || resolved.url().isBlank()) throw new IllegalStateException("No playable media");
                List<Quality> resolvedOffers=qualityOffers(active,track);
                synchronized (lock) {
                    if (!moduleEnabled||!songValidLocked(token, song)) return;
                    media = resolved;offeredQualities=resolvedOffers;
                    audio.play(track.provider(), resolved, position, song, !desiredPlaying);
                }
            } catch (Exception failure) {
                try{List<Quality> available=qualityOffers(active,track);synchronized(lock){if(songValidLocked(token,song))offeredQualities=available;}}catch(RuntimeException ignored){ }
                failSong(token, song, failure);
            }
            if (fetchLyrics && songValid(token, song)) {
                LrcLyrics original;
                try { original = active.lyrics(track); } catch (Exception absent) { original = EMPTY_LYRICS; }
                synchronized (lock) {
                    if (!songValidLocked(token, song)) return;
                    lyrics = original == null ? EMPTY_LYRICS : original; publish();
                }
            }
            requestTranslation();
        });
    }
    private void requestTranslation(){
        final long token,song,request;final Track track;
        synchronized(lock){if(closed||!translationEnabled||playback.track()==null)return;if(playback.track().key().equals(translatedTrack)&&!translated.isEmpty())return;token=epoch;song=revision;track=playback.track();request=++translationRequest;translationState=status("loading");}
        submit(token,()->{try{MusicProvider active=activeProvider(token);if(active==null)return;if(!active.supportsTranslatedLyrics())throw new IllegalArgumentException(status("translatedLyricsUnsupported"));LrcLyrics result=active.translatedLyrics(track);
            synchronized(lock){if(!songValidLocked(token,song)||request!=translationRequest||!translationEnabled)return;translated=result==null?EMPTY_LYRICS:result;translatedTrack=track.key();translationState=status(translated.isEmpty()?"translatedLyricsUnavailable":!translated.synced()?"noTimedLyrics":"translatedLyricsReady");}
        }catch(Exception unavailable){synchronized(lock){if(songValidLocked(token,song)&&request==translationRequest){translated=EMPTY_LYRICS;translatedTrack="";translationState=failureStatus(unavailable);}}}});
    }
    private void audioSample(MusicAudioPlayer.Sample sample) {
        synchronized (lock) {
            if (closed || !moduleEnabled || sample.token() != revision || playback.track() == null) return;
            long position = Math.max(0, sample.positionMs());
            if (playback.durationMs() > 0) position = Math.min(playback.durationMs(), position);
            boolean playing = sample.playing() && desiredPlaying;
            String next = !desiredPlaying && sample.status().equals(status("playing")) ? status("paused") : sample.status();
            if(playing){lastAudioProgress=System.nanoTime();if(position>recoveryPosition+1000)streamFailures=0;}
            boolean premature=sample.status().equals(status("ended"))&&playback.durationMs()>0&&position<playback.durationMs()-2000;
            boolean stalled=sample.status().equals(status("buffering"))&&System.nanoTime()-lastAudioProgress>TimeUnit.SECONDS.toNanos(12);
            boolean recoverable=sample.status().equals(status("failed"))||premature||stalled;
            if(desiredPlaying&&recoverable&&streamFailures<3){
                next=status("buffering");scheduleRecovery(epoch,revision,playback.track(),position);
            }else if(premature)next=status("failed");
            playback = new Playback(playback.track(), playing, position, System.nanoTime(), playback.durationMs(), revision, next);
            state = next; publish();
            if (desiredPlaying && sample.status().equals(status("ended")) && !premature && !queue.isEmpty()) {
                long token=epoch,song=revision;
                timer.schedule(()->{
                    synchronized (lock) {
                        if (songValidLocked(token,song) && moduleEnabled && desiredPlaying) advanceQueue(1,true);
                    }
                },150,TimeUnit.MILLISECONDS);
            }
        }
    }
    private void scheduleRecovery(long token,long song,Track track,long position){
        if(recovery!=null&&!recovery.isDone())return;
        long delay=500L*(1L<<streamFailures);streamFailures++;recoveryPosition=position;
        recovery=timer.schedule(()->{
            long retry;
            synchronized(lock){
                recovery=null;
                if(!moduleEnabled||!songValidLocked(token,song)||!desiredPlaying)return;
                retry=++revision;generation++;media=null;audio.stop();lastAudioProgress=System.nanoTime();
                playback=new Playback(track,false,position,System.nanoTime(),playback.durationMs(),retry,status("buffering"));publish();
            }
            queueSong(token,retry,track,position,null,lyrics.isEmpty());
        },delay,TimeUnit.MILLISECONDS);
    }
    private void pollLogin(long token, Login pending, long startedNanos) {
        if (!validSafe(token)) return;
        if (Set.of(status("qrExpired"),status("loginDeclined"),status("captchaRequired"),status("platformVerification"),status("loginAuthorizationRequired")).contains(pending.status())) {
            synchronized(lock){if(!valid(token))return;loginPending=false;state=safeStatus(pending.status());publish();}return;
        }
        if (System.nanoTime() - startedNanos > TimeUnit.MINUTES.toNanos(3)) { finishLoginFailure(token, "failed"); return; }
        if (pending.complete()) {
            MusicProvider active;
            synchronized (lock) { active = service; }
            try {
                Account confirmed = active == null ? null : active.account();
                if (confirmed != null && confirmed.id() != null && !confirmed.id().isBlank()) {
                    if (acceptAccount(token, confirmed)) refreshLiked();
                    return;
                }
            } catch (Exception unconfirmed) { }
            finishLoginFailure(token, "failed"); return;
        }
        synchronized (lock) {
            if (!valid(token)) return;
            loginPoll = timer.schedule(() -> submitExclusive(token, () -> {
                MusicProvider active = activeProvider(token); if (active == null) return;
                Login result = active.pollLogin(pending);
                if (result == null) { finishLoginFailure(token, "failed"); return; }
                synchronized (lock) { if (!valid(token)) return; login = result; state = safeStatus(result.status()); publish(); }
                pollLogin(token, result, startedNanos);
            }), 1800, TimeUnit.MILLISECONDS);
        }
    }
    private boolean acceptAccount(long token, Account confirmed) {
        synchronized (lock) {
            if (!valid(token)) return false;
            resetSession(); account = confirmed;accountVerified=true;accountVerification.complete(true);state = status("ready"); publish();
            if(service instanceof SpotifyMusicProvider remote)scheduleRemote(epoch,remote,1000);
            return true;
        }
    }
    /** Cached rows are display-only until this epoch confirms the same account upstream. */
    private void validateSelectedAccount(long token,MusicProvider active)throws Exception{
        boolean refresh=false;
        try{
            Account confirmed=active.account();
            synchronized(lock){
                if(!valid(token))return;
                boolean validAccount=confirmed!=null&&confirmed.id()!=null&&!confirmed.id().isBlank();
                if(validAccount&&account!=null&&confirmed.id().equals(account.id())){
                    account=confirmed;accountVerified=true;accountVerification.complete(true);
                    state=playback.track()==null?status("ready"):playback.status();publish();
                    if(active instanceof SpotifyMusicProvider remote)scheduleRemote(epoch,remote,1000);
                    refresh=tracks.isEmpty();
                }else{
                    clearAccountCache();resetSession();audio.stop();
                    if(validAccount){account=confirmed;accountVerified=true;accountVerification.complete(true);state=status("ready");refresh=true;}
                    else {accountVerification.complete(false);state=status("loginRequired");}
                    publish();
                    if(validAccount&&active instanceof SpotifyMusicProvider remote)scheduleRemote(epoch,remote,1000);
                }
            }
            if(refresh)refreshLiked();
        }catch(Exception failure){synchronized(lock){if(valid(token)){if(failureStatus(failure).equals(status("loginRequired"))){clearAccountCache();resetSession();audio.stop();}accountVerification.complete(false);state=failureStatus(failure);publish();}}throw failure;}
    }
    private void scheduleRemote(long token,SpotifyMusicProvider remote,long delay){
        synchronized(lock){if(!moduleEnabled||!valid(token))return;remotePoll=timer.schedule(()->submit(token,()->{
            long song;synchronized(lock){if(!valid(token))return;song=revision;}
            long next=1000;
            try{applyRemote(token,song,remote.remotePlayback());}
            catch(Exception failure){next=5000;synchronized(lock){if(valid(token)){state=failureStatus(failure);publish();}}}
            finally{scheduleRemote(token,remote,next);}
        }),delay,TimeUnit.MILLISECONDS);}
    }
    private void applyRemote(long token,long expectedSong,SpotifyMusicProvider.RemotePlayback actual){
        synchronized(lock){
            if(!moduleEnabled||!valid(token)||revision!=expectedSong||actual==null)return;
            Track old=playback.track(),next=actual.track();
            if(!Objects.equals(old==null?null:old.key(),next==null?null:next.key())){
                revision++;generation++;lyrics=EMPTY_LYRICS;
                int adjacent=queueIndex+1;
                if (next!=null && adjacent>=0 && adjacent<queue.size() && queue.get(adjacent).key().equals(next.key())) queueIndex=adjacent;
                else queueIndex=indexOfTrack(queue,next);
            }
            desiredPlaying=actual.playing();state=safeStatus(actual.status());
            playback=new Playback(next,actual.playing(),actual.positionMs(),actual.sampledNanos(),actual.durationMs(),revision,state);publish();
        }
    }
    private void finishLoginFailure(long token, String name) {
        synchronized (lock) { if (!valid(token)) return; if(service instanceof SpotifyMusicProvider spotify)spotify.cancelLogin();loginPending = false; state = status(name); publish(); }
    }
    private MusicProvider activeProvider(long token) {
        Provider selected;
        synchronized (lock) { if (!valid(token)) return null; if (service != null) return service; selected = provider; }
        MusicProvider created = providerInstances.computeIfAbsent(selected,providers);
        if (created == null || created.provider() != selected) throw new IllegalStateException("Invalid provider");
        synchronized (lock) { if (!valid(token)) return null; service = created; return created; }
    }
    private long beginOperation() { long id = ++operationSequence; operations.add(id); return id; }
    private void endOperation(long operation) { synchronized (lock) { if (operations.remove(operation)) publish(); } }
    private void clearPlayback() {
        revision++; generation++; media = null; desiredPlaying = false;
        streamFailures=0;lastAudioProgress=System.nanoTime();if(recovery!=null){recovery.cancel(false);recovery=null;}
        playback = emptyPlayback(revision); lyrics = EMPTY_LYRICS;
        translated=EMPTY_LYRICS;translatedTrack="";translationRequest++;translationState=status(translationEnabled?"loading":"translatedLyricsDisabled");
    }
    private void resetSession() {
        accountVerification.complete(false);accountVerification=new CompletableFuture<>();accountVerified=false;
        epoch++; generation++; account = null; tracks = List.of(); playlists = List.of(); login = null;
        currentLibrary="";
        discovery=new Discovery("library","",0,false);
        offeredQualities=List.of();
        closeComments();
        var staleDetails=List.copyOf(detailReads);
        detailReads.clear();
        staleDetails.forEach(DetailRead::discard);
        artistAlbumCards.clear();
        setQueue(List.of(),-1,false,"");
        catalog(List.of(),"recommendationsIdle",false);
        operations.clear(); loginPending = false; nextLibraryRequest(); playlistsRequest++;
        if (loginPoll != null) { loginPoll.cancel(false); loginPoll = null; }
        if (remotePoll != null) { remotePoll.cancel(false); remotePoll = null; }
        if (volumeUpdate != null) { volumeUpdate.cancel(false); volumeUpdate = null; }
        worker.getQueue().removeIf(task -> task instanceof Job job && !job.mandatory);
        playbackWorker.getQueue().clear();Job playing=runningSong;if(playing!=null&&playing.runner!=null)playing.runner.interrupt();
        clearPlayback();
    }
    private String accountKey(){return provider.id+":"+(account==null?"":account.id());}
    private static List<Quality> qualityOffers(MusicProvider active,Track track){List<Quality> offers=active.qualities(track);if(offers==null)return List.of();LinkedHashMap<String,Quality> safe=new LinkedHashMap<>();for(Quality quality:offers){if(quality!=null&&quality.id()!=null&&quality.id().matches("[A-Za-z0-9_-]{1,64}")&&quality.label()!=null&&!quality.label().isBlank()&&quality.label().length()<=100&&quality.label().chars().noneMatch(Character::isISOControl))safe.putIfAbsent(quality.id(),quality);if(safe.size()>=16)break;}return List.copyOf(safe.values());}
    private void readQualityPreferences() {
        try {
            synchronized (Config.persistenceLock()) {
                if (!Config.persistenceAllowed()) return;
                Path path=Config.directory().resolve("music-preferences.json");
                if (!Files.isRegularFile(path) || Files.size(path)>8192) return;
                JsonObject root=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
                String storedLayout=preference(root,"layout");
                layout = LAYOUTS.contains(storedLayout) ? storedLayout : DEFAULT_LAYOUT;
                for (Provider selected:Provider.values()) {
                    String quality=root.has("quality")&&root.get("quality").isJsonObject()
                            ?preference(root.getAsJsonObject("quality"),selected.id):"";
                    if (quality.matches("[A-Za-z0-9_-]{1,64}")) requestedQualities.put(selected,quality);
                    String mode=root.has("playbackMode")&&root.get("playbackMode").isJsonObject()
                            ?preference(root.getAsJsonObject("playbackMode"),selected.id):"";
                    for (PlaybackMode candidate:PLAYBACK_MODES)
                        if (candidate.id.equals(mode)) requestedModes.put(selected,candidate);
                }
            }
        } catch (Exception|LinkageError invalid) { }
    }
    private static String preference(JsonObject root,String key) {
        return root.has(key)&&root.get(key).isJsonPrimitive()&&root.getAsJsonPrimitive(key).isString()
                ?root.get(key).getAsString():"";
    }
    private void writeQualityPreferences() {
        Map<Provider,String> choices=Map.copyOf(requestedQualities);
        Map<Provider,PlaybackMode> modes=Map.copyOf(requestedModes);
        String selectedLayout=layout;
        PREFERENCES.execute(()->{
            try {
                synchronized (Config.persistenceLock()) {
                    if (!Config.persistenceAllowed()) return;
                    JsonObject root=new JsonObject(),quality=new JsonObject(),playbackMode=new JsonObject();
                    root.addProperty("version",1);
                    root.addProperty("layout",selectedLayout);
                    choices.forEach((selected,id)->quality.addProperty(selected.id,id));
                    modes.forEach((selected,mode)->playbackMode.addProperty(selected.id,mode.id));
                    root.add("quality",quality);
                    root.add("playbackMode",playbackMode);
                    Path path=Config.directory().resolve("music-preferences.json");
                    Path temporary=path.resolveSibling("music-preferences.json.tmp");
                    Files.createDirectories(path.getParent());
                    try {
                        Files.writeString(temporary,root.toString(),StandardCharsets.UTF_8);
                        try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
                    } finally { Files.deleteIfExists(temporary); }
                }
            } catch (Exception|LinkageError ignored) { }
        });
    }
    private boolean verificationRejected(){return !accountVerified&&accountVerification.isDone();}
    private void clearAccountCache(){String prefix=provider.id+":";sessionCache.remove(provider);libraryCache.keySet().removeIf(k->k.startsWith(prefix));playlistCache.keySet().removeIf(k->k.startsWith(prefix));recommendationCache.keySet().removeIf(k->k.startsWith(prefix));}
    private static void trim(Map<?,?> map,int max){while(map.size()>max)map.remove(map.keySet().iterator().next());}
    private void publish() {
        if(accountVerified&&account!=null)sessionCache.put(provider,new CachedSession(account,tracks,playlists,currentLibrary,offeredQualities,discovery));
        requirement = service == null ? "" : service.requirement();
        snapshot = new Snapshot(provider, account, tracks, playlists, playback, lyrics, login, state, loginPending || !operations.isEmpty(), generation);
    }
    private static Playback emptyPlayback(long revision) { return new Playback(null, false, 0, System.nanoTime(), 0, revision, status("idle")); }
    private boolean valid(long token) { return !closed && token == epoch; }
    private boolean operationCurrent(long operation){return operation==0||operation>cancelledOperationsThrough;}
    private boolean jobValidSafe(long token,long operation){synchronized(lock){return valid(token)&&operationCurrent(operation);}}
    private boolean validSafe(long token) { synchronized (lock) { return valid(token); } }
    private boolean songValid(long token, long song) { synchronized (lock) { return songValidLocked(token, song); } }
    private boolean songValidLocked(long token, long song) { return valid(token) && revision == song && playback.track() != null; }
    private void setState(long token, String name) { synchronized (lock) { if (valid(token)) { state = status(name); publish(); } } }
    private void failSong(long token, long song, Exception failure) {
        synchronized (lock) {
            if (!songValidLocked(token, song)) return;
            String next = failureStatus(failure);
            if(desiredPlaying&&streamFailures<3&&Set.of(status("failed"),status("networkFailed")).contains(next)){scheduleRecovery(token,song,playback.track(),playback.positionMs());next=status("buffering");}
            state = next;
            playback = new Playback(playback.track(), false, playback.positionMs(), System.nanoTime(), playback.durationMs(), revision, next); publish();
        }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void submit(long token, Work action) { submit(token, action, 0); }
    private void submit(long token, Work action, long operation) {
        enqueue(new Job(token, action, operation, false));
    }
    private void submitExclusive(long token,Work action){enqueue(new Job(token,action,0,false,0,true));}
    private void submitSong(long token, long song, Work action) { enqueue(new Job(token, action, 0, false, song)); }
    private final class Job implements Runnable {
        final long token, operation, song; final Work action; final boolean mandatory,exclusive,verifyAccount;volatile Thread runner;
        Job(long token, Work action, long operation, boolean mandatory) { this(token, action, operation, mandatory, 0); }
        Job(long token, Work action, long operation, boolean mandatory, long song) {this(token,action,operation,mandatory,song,mandatory);}
        Job(long token, Work action, long operation, boolean mandatory, long song,boolean exclusive) {
            this(token,action,operation,mandatory,song,exclusive,!mandatory&&!exclusive);
        }
        Job(long token,Work action,long operation,boolean mandatory,long song,boolean exclusive,boolean verifyAccount){
            this.token=token;this.action=action;this.operation=operation;this.mandatory=mandatory;
            this.song=song;this.exclusive=exclusive;this.verifyAccount=verifyAccount;
        }
        @Override public void run() {
            if (!mandatory && (!jobValidSafe(token,operation)||song!=0&&!songValid(token,song))) { if (operation != 0) endOperation(operation); return; }
            var gate=exclusive?providerAccess.writeLock():providerAccess.readLock();boolean acquired=false;
            runner=Thread.currentThread();if(song!=0)runningSong=this;
            try {
                if(verifyAccount){CompletableFuture<Boolean> verification;synchronized(lock){if(!valid(token)||!operationCurrent(operation))return;verification=accountVerification;}
                    if(!verification.get(30,TimeUnit.SECONDS)){if(operation!=0)endOperation(operation);return;}}
                gate.lockInterruptibly();acquired=true;if(mandatory||jobValidSafe(token,operation)&& (song==0||songValid(token,song)))action.run();
            }
            catch (Exception failure) {
                synchronized (lock) {
                    if (valid(token)&&operationCurrent(operation)&&(song==0||songValidLocked(token,song))) { loginPending = false; state = failureStatus(failure); publish(); }
                }
                if (operation != 0) endOperation(operation);
            }finally{if(acquired)gate.unlock();runner=null;if(runningSong==this)runningSong=null;}
        }
    }
    private boolean enqueue(Job job) {
        if (job.song != 0) synchronized (lock) {
            if (!songValidLocked(job.token, job.song)) return false;
            worker.getQueue().removeIf(task -> task instanceof Job old && old.song != 0 && old.song != job.song);
            playbackWorker.getQueue().removeIf(task -> task instanceof Job old && old.song != job.song);
            Job active=runningSong;if(active!=null&&active.song!=job.song&&active.runner!=null)active.runner.interrupt();
        }
        try {
            (job.song!=0?playbackWorker:worker).execute(job);
            return true;
        } catch (RejectedExecutionException full) {
            synchronized (lock) {
                if (valid(job.token)&&operationCurrent(job.operation)) {
                    operations.remove(job.operation); loginPending = false; state = status("failed");
                    if (job.song == revision && playback.track() != null)
                        playback = new Playback(playback.track(), false, playback.positionMs(), System.nanoTime(), playback.durationMs(), revision, state);
                    publish();
                }
            }
            return false;
        }
    }
    private static String failureStatus(Exception failure) {
        return safeStatus(failure.getMessage());
    }
    private static String safeStatus(String key) {return key!=null&&key.matches("ui\\.musicPlayer\\.[A-Za-z]{1,50}")?key:status("failed");}
    private static boolean unavailable(MusicProvider provider) {
        return !provider.supported();
    }
}
