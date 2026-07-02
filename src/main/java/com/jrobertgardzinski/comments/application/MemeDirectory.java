package com.jrobertgardzinski.comments.application;

/**
 * Port to microservice-memes: does this meme exist? Comments never attach to ghosts. The HTTP
 * adapter asks the meme service; tests stub it.
 */
public interface MemeDirectory {

    boolean exists(String memeId);
}
