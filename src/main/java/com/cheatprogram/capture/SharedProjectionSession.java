package com.cheatprogram.capture;

public interface SharedProjectionSession {
    boolean publishing(); boolean watching(); String invite(); String publishStatus(); String watchStatus();
    long sentFrames(); long receivedFrames();
    default String encoderName(){return "";}
}
