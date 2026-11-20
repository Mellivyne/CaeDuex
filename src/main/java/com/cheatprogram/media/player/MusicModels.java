package com.cheatprogram.media.player;

import com.cheatprogram.media.LrcLyrics;
import java.util.List;

/** Provider IDs stay attached to every item; no cross-platform matching or substituted songs. */
public final class MusicModels {
    private MusicModels() { }
    public enum Provider {
        NETEASE("netease","网易云音乐","https://music.163.com/"),
        QQ("qq","QQ音乐","https://y.qq.com/"),
        KUGOU("kugou","酷狗音乐","https://www.kugou.com/"),
        QISHUI("qishui","汽水音乐","https://qishui.douyin.com/"),
        KUWO("kuwo","酷我音乐","https://www.kuwo.cn/"),
        SPOTIFY("spotify","Spotify","https://open.spotify.com/");
        public final String id,label,home;
        Provider(String id,String label,String home){this.id=id;this.label=label;this.home=home;}
    }
    public record Account(String id,String name,String avatar) { }
    public record Artist(Provider provider,String id,String name) {
        public String key(){return provider.id+":"+id;}
    }
    public record Track(Provider provider,String id,String title,String artist,String album,String cover,long durationMs,
                        String albumId,List<Artist> artists) {
        public Track {
            artists=artists==null?List.of():List.copyOf(artists);
        }
        public Track(Provider provider,String id,String title,String artist,String album,String cover,long durationMs,String albumId){
            this(provider,id,title,artist,album,cover,durationMs,albumId,List.of());
        }
        public Track(Provider provider,String id,String title,String artist,String album,String cover,long durationMs){
            this(provider,id,title,artist,album,cover,durationMs,"");
        }
        public String key(){return provider.id+":"+id;}
    }
    public enum PlaybackMode {
        ORDERED("ordered"), LIST_LOOP("listLoop"), SINGLE_LOOP("singleLoop"), SHUFFLE("shuffle");
        public final String id;
        PlaybackMode(String id){this.id=id;}
    }
    public record PlaybackQueue(List<Track> tracks,int index,PlaybackMode mode) {
        public PlaybackQueue {tracks=List.copyOf(tracks);}
    }
    public record Playlist(String id,String name,String cover,int count) { }
    public enum RecommendationKind { PLAYLIST, ALBUM }
    public record Recommendation(Provider provider,RecommendationKind kind,String id,String name,String cover,int count) {
        public Playlist playlist(){return new Playlist(id,name,cover,count);}
    }
    public record ArtistAlbums(List<Recommendation> albums,int page,boolean hasMore) {
        public ArtistAlbums {albums=albums==null?List.of():List.copyOf(albums);}
    }
    public enum CommentKind { SONG, ALBUM, PLAYLIST }
    public record CommentTarget(Provider provider,CommentKind kind,String id,String title) {
        public static CommentTarget song(Track track){
            return new CommentTarget(track.provider(),CommentKind.SONG,track.id(),track.title());
        }
        public static CommentTarget album(Track track){
            return track.albumId()==null||track.albumId().isBlank()?null:
                    new CommentTarget(track.provider(),CommentKind.ALBUM,track.albumId(),track.album());
        }
        public static CommentTarget playlist(Provider provider,Playlist playlist){
            return new CommentTarget(provider,CommentKind.PLAYLIST,playlist.id(),playlist.name());
        }
        public static CommentTarget recommendation(Recommendation recommendation){
            return new CommentTarget(recommendation.provider(),recommendation.kind()==RecommendationKind.ALBUM
                    ?CommentKind.ALBUM:CommentKind.PLAYLIST,recommendation.id(),recommendation.name());
        }
    }
    public record Comment(String id,String author,String avatar,String body,long createdAt,int likes,boolean liked,
                          String replyToId,String replyToAuthor,String replyToBody) {
        public Comment(String id,String author,String avatar,String body,long createdAt,int likes,boolean liked){
            this(id,author,avatar,body,createdAt,likes,liked,"","","");
        }
        public Comment(String id,String author,String avatar,String body,long createdAt,int likes){
            this(id,author,avatar,body,createdAt,likes,false);
        }
    }
    public record CommentPage(List<Comment> items,String nextCursor) {
        public CommentPage{
            items=items==null?List.of():List.copyOf(items);
            nextCursor=nextCursor==null?"":nextCursor;
        }
    }
    public record SearchPage(List<Track> tracks,int page,boolean hasMore) {public SearchPage{tracks=tracks==null?List.of():List.copyOf(tracks);}}
    public record Login(String key,String url,byte[] qrPng,String status,boolean complete) {
        public Login {qrPng=qrPng==null?new byte[0]:qrPng.clone();}
        @Override public byte[] qrPng(){return qrPng.clone();}
        @Override public String toString(){return "MusicLogin[status="+status+",complete="+complete+"]";}
    }
    /** IDs and names belong to the source platform; an empty ID means its default choice. */
    public record Quality(String id,String label) { }
    /** quality is the platform-confirmed returned tier, not merely the requested tier. */
    public record Media(String url,String format,String quality) {
        public Media(String url,String format) { this(url,format,""); }
        @Override public String toString(){return "MusicMedia[format="+format+"]";}
    }
    public record Playback(Track track,boolean playing,long positionMs,long sampledNanos,long durationMs,long revision,String status) {
        public long position(){if(track==null)return 0;long extra=playing?Math.max(0,(System.nanoTime()-sampledNanos)/1_000_000):0;
            long value=Math.max(0,positionMs+extra);return durationMs>0?Math.min(durationMs,value):value;}
    }
    public record Snapshot(Provider provider,Account account,List<Track> tracks,List<Playlist> playlists,
                           Playback playback,LrcLyrics lyrics,Login login,String status,boolean busy,long generation) {
        public Snapshot {tracks=List.copyOf(tracks);playlists=List.copyOf(playlists);}
    }
}
