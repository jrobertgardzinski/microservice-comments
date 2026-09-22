package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Production {@link MemeDirectory}: a HEAD to the meme service's METADATA endpoint (Spring answers
 * HEAD for every GET mapping, body discarded) — 2xx means the meme exists.
 *
 * <p>The metadata URL, not {@code /memes/{id}}, and the difference is not cosmetic: the picture URL
 * is served by {@code ServeMeme}, which pulls the whole image out of object storage before Tomcat
 * throws the bytes away, so every comment posted cost memes one full-size read from MinIO and an
 * inter-service transfer of it. {@code /memes/{id}/meta} answers the same question — is there a row
 * with this id — from one indexed read, and never touches the object store. A blob store having a
 * slow minute is therefore no longer something this probe can even notice.
 */
@Component
class HttpMemeDirectory implements MemeDirectory {

    private final RestClient memeService;

    HttpMemeDirectory(@Value("${memes.url}") String memesUrl) {
        // bounded waits: the default factory has none, so a hung meme service would pin every
        // request thread here; a timeout falls into the catch below and reads as "could not ask"
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
            memeService.head().uri("/memes/{id}/meta", memeId).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound noSuchMeme) {
            return false;
        } catch (RestClientException couldNotAsk) {
            // "memes did not answer" is not the same statement as "there is no such meme", and
            // returning false made this service say the second when it only knew the first: a 500
            // from memes, or the wait above running out, told the commenter their meme does not
            // exist and lost them the text they had just written. Nobody asked, so nobody knows —
            // which is a 503, the same answer microservice-memes gives when it cannot reach
            // security rather than declaring the caller signed out.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "the meme service could not be reached; your comment was not saved", couldNotAsk);
        }
    }
}
