package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
        // JdkClientHttpRequestFactory, not SimpleClientHttpRequestFactory. The two timeouts read
        // like a bounded wait and are not one: SimpleClientHttpRequestFactory hands them to
        // HttpURLConnection, whose connect timeout starts AFTER the name is resolved. A DNS
        // server that accepts the query and never answers therefore blocks the request thread
        // for the resolver's own timeout — minutes, on a default glibc — and no amount of
        // tuning these two numbers changes that. The JDK client applies its request timeout to
        // the WHOLE exchange, resolution included, which is the promise this code was making.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
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
