package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Production {@link MemeDirectory}: a HEAD to the meme service (Spring answers HEAD for every GET
 * mapping, body discarded) — 2xx means the meme exists.
 */
@Component
class HttpMemeDirectory implements MemeDirectory {

    private final RestClient memeService;

    HttpMemeDirectory(@Value("${memes.url}") String memesUrl) {
        // bounded waits: the default factory has none, so a hung meme service would pin every
        // request thread here; a timeout falls into the catch below and reads as "no such meme"
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.memeService = RestClient.builder().baseUrl(memesUrl)
                .requestFactory(requestFactory).build();
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
