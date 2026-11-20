package com.cheatprogram.media.player;

import com.cheatprogram.media.player.MusicModels.CommentTarget;
import java.net.URI;

/** The selected item's public platform page. No credentials or user data enter the URL. */
public final class MusicPlatformLinks {
    private MusicPlatformLinks() { }

    public static URI page(CommentTarget target){
        if(target==null||target.provider()==null||target.kind()==null||target.id()==null)return null;
        String id=target.id();
        String path=switch(target.provider()){
            case NETEASE -> id.matches("[0-9]{1,20}")?"https://music.163.com/#/"+switch(target.kind()){
                case SONG->"song";case ALBUM->"album";case PLAYLIST->"playlist";
            }+"?id="+id:null;
            case SPOTIFY -> id.matches("[A-Za-z0-9]{22}")?"https://open.spotify.com/"+switch(target.kind()){
                case SONG->"track";case ALBUM->"album";case PLAYLIST->"playlist";
            }+"/"+id:null;
            case QQ -> switch(target.kind()){
                case SONG -> id.matches("[A-Za-z0-9]{8,24}")?"https://y.qq.com/n/ryqq/songDetail/"+id:null;
                case ALBUM -> id.matches("[A-Za-z0-9]{8,24}")?"https://y.qq.com/n/ryqq/albumDetail/"+id:null;
                case PLAYLIST -> id.matches("[0-9]{1,20}")?"https://y.qq.com/n/ryqq/playlist/"+id:null;
            };
            default -> null;
        };
        return path==null?null:URI.create(path);
    }
}
