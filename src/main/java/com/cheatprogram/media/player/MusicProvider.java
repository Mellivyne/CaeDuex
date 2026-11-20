package com.cheatprogram.media.player;

import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import java.io.IOException;
import java.util.List;

/** Blocking provider operations are called only on the player's bounded worker. */
public interface MusicProvider {
    Provider provider();
    default boolean supported(){return true;}
    Login beginLogin() throws Exception;
    Login pollLogin(Login login) throws Exception;
    Account account() throws Exception;
    List<Track> liked() throws Exception;
    List<Playlist> playlists() throws Exception;
    List<Track> tracks(Playlist playlist) throws Exception;
    int COMMENT_PAGE_SIZE=20;
    default boolean supportsComments(){return false;}
    /** One page of public song comments; an empty request cursor starts from the first page. */
    default CommentPage comments(Track track,String cursor) throws Exception {
        throw new IOException("ui.musicPlayer.commentsUnsupported");
    }
    default boolean supportsComments(CommentTarget target){
        return target!=null&&target.provider()==provider()&&target.kind()==CommentKind.SONG&&supportsComments();
    }
    default CommentPage comments(CommentTarget target,String cursor)throws Exception{
        if(!supportsComments(target)||target.kind()!=CommentKind.SONG)throw new IOException("ui.musicPlayer.commentsUnsupported");
        return comments(new Track(provider(),target.id(),target.title(),"","","",0),cursor);
    }
    default boolean supportsCommentPosting(CommentTarget target){return false;}
    default boolean supportsCommentLikes(CommentTarget target){return false;}
    default boolean supportsCommentReplies(CommentTarget target){return false;}
    default Comment postComment(CommentTarget target,String body)throws Exception{
        throw new IOException("ui.musicPlayer.commentsUnsupported");
    }
    default Comment replyComment(CommentTarget target,Comment parent,String body)throws Exception{
        throw new IOException("ui.musicPlayer.commentsUnsupported");
    }
    default void likeComment(CommentTarget target,Comment comment,boolean liked)throws Exception{
        throw new IOException("ui.musicPlayer.commentsUnsupported");
    }
    static String commentBody(String body)throws IOException{
        if(body==null)throw new IOException("ui.musicPlayer.commentInvalid");
        String content=body.strip();
        if(content.isBlank()||content.codePointCount(0,content.length())>1000
                ||content.codePoints().anyMatch(point->Character.isISOControl(point)&&point!='\n'&&point!='\t'))
            throw new IOException("ui.musicPlayer.commentInvalid");
        return content;
    }
    static int commentOffset(String cursor) throws IOException {
        if(cursor==null||cursor.isEmpty())return 0;
        if(!cursor.matches("[0-9]{1,10}"))throw new IOException("ui.musicPlayer.protocolChanged");
        try{return Integer.parseInt(cursor);}
        catch(NumberFormatException invalid){throw new IOException("ui.musicPlayer.protocolChanged");}
    }
    LrcLyrics lyrics(Track track) throws Exception;
    default boolean supportsRecommendations(){return false;}
    /** Bounded first-party catalog, in the order returned by the platform. */
    default List<Recommendation> recommendations() throws Exception {throw new IOException("ui.musicPlayer.recommendationsUnsupported");}
    default List<Track> recommendationTracks(Recommendation item) throws Exception {
        if(item.provider()!=provider()||item.kind()!=RecommendationKind.PLAYLIST)throw new IOException("ui.musicPlayer.invalidTrack");
        return tracks(item.playlist());
    }
    /** Local capability metadata; unsupported providers never synthesize catalog or lyric results. */
    default boolean supportsSearch() { return false; }
    default boolean supportsRadio() { return false; }
    default boolean supportsTranslatedLyrics() { return false; }
    default boolean supportsSimilarTracks(Track track) { return false; }
    default List<Track> similarTracks(Track track) throws Exception { return List.of(); }
    default boolean supportsArtist(Artist artist) { return false; }
    default SearchPage artistSongs(Artist artist,int page) throws Exception { return new SearchPage(List.of(),page,false); }
    default ArtistAlbums artistAlbums(Artist artist,int page) throws Exception { return new ArtistAlbums(List.of(),page,false); }
    /** One zero-based page of at most 30 exact provider tracks; implementations cap page numbers. */
    default SearchPage search(String query,int page) throws Exception { throw new IOException("ui.musicPlayer.searchUnsupported"); }
    /** One bounded batch from this provider's own personalized radio service. */
    default List<Track> radio() throws Exception { throw new IOException("ui.musicPlayer.radioUnsupported"); }
    /** Only the supplied track ID's platform translation; no inferred translations or song matching. */
    default LrcLyrics translatedLyrics(Track track) throws Exception { throw new IOException("ui.musicPlayer.translatedLyricsUnsupported"); }
    Media media(Track track) throws Exception;
    /** Local capability metadata only; render-thread callers must never trigger a network request. */
    default List<Quality> qualities(Track track) { return List.of(); }
    default Media media(Track track,String quality) throws Exception { return media(track); }
    default void logout() throws Exception { }
    default String requirement(){return "";}
}
