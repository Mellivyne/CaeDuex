package com.cheatprogram.capture;

/** UI contract shared by native captions and encoded application screens. */
public interface SharedProjectionSource {
    SharedProjectionSession sharing();
    String shareRelay(); void shareRelay(String address);
    boolean startSharing(); void revokeSharing(); void watchSharing(String invite); void leaveSharing();
    default boolean videoSharing(){return false;}
    default boolean shareSourceQuality(){return true;}
    default int shareFrameRate(){return shareSourceQuality()?0:60;}
    default void cycleShareQuality(){ }
}
