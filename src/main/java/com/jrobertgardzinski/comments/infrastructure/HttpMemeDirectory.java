package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Production {@link MemeDirectory}: a HEAD to the meme service (Spring answers HEAD for every GET
 * mapping, body discarded) — 2xx means the meme exists.
 */
@Component
class HttpMemeDirectory implements MemeDirectory {

    private final RestClient memeService;

    HttpMemeDirectory(@Value("${memes.url}") String memesUrl) {
        this.memeService = RestClient.create(memesUrl);
    }

    @Override
    public boolean exists(String memeId) {
        try {
            return memeService.head().uri("/memes/{id}", memeId)
                    .retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (RestClientException missingOrDown) {
            return false;
        }
    }
}
