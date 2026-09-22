package com.jrobertgardzinski.comments.infrastructure;

import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the existence probe asks for, and what it says when it gets no answer. Both used to be
 * wrong in a way no test could see: the probe went to the PICTURE url, whose handler reads the
 * whole image out of object storage before the body is discarded, and every failure — a memes 500,
 * the five-second wait running out on a busy blob store — came back as "no such meme", so a
 * commenter lost their text to a 404 about a meme that was there all along.
 */
@Epic("Infrastructure")
@Feature("Meme directory")
@Story("Existence probe")
class HttpMemeDirectoryTest {

    private HttpServer memes;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private int answer = 200;

    @BeforeEach
    void startStubMemes() throws IOException {
        memes = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        memes.createContext("/memes", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        memes.start();
    }

    @AfterEach
    void stopStubMemes() {
        memes.stop(0);
    }

    @Test
    @DisplayName("the probe asks for the metadata, never for the picture")
    void the_probe_never_pulls_the_image() {
        assertTrue(directory().exists("m1"));

        assertEquals(List.of("/memes/m1/meta"), asked,
                "/memes/{id} serves the bytes from the object store; /meta answers from one row");
    }

    @Test
    @DisplayName("a meme memes does not know is a meme that does not exist")
    void a_404_means_no_such_meme() {
        answer = 404;

        assertFalse(directory().exists("ghost"));
    }

    @Test
    @DisplayName("a memes that fails is not a meme that is missing")
    void a_server_error_is_not_an_answer() {
        answer = 500;

        ResponseStatusException refused =
                assertThrows(ResponseStatusException.class, () -> directory().exists("m1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.valueOf(refused.getStatusCode().value()),
                "nobody asked and nobody knows — saying 'no such meme' here is a lie the commenter pays for");
    }

    private HttpMemeDirectory directory() {
        return new HttpMemeDirectory("http://127.0.0.1:" + memes.getAddress().getPort());
    }
}
