package com.mentalcare.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.ai.LlmException;
import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.config.OpenAiProperties.AuthMode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Streaming timeouts against a local server that sends headers and then stalls. */
class HttpOpenAiTransportTest {

    private HttpServer server;

    static final BearerTokenProvider TOKENS = new BearerTokenProvider() {
        @Override public AuthMode mode() { return AuthMode.API_KEY; }
        @Override public boolean available() { return true; }
        @Override public String unavailableReason() { return null; }
        @Override public String bearer() { return "test-token"; }
    };

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked: stays open
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                Thread.sleep(8000); // stall far longer than the configured idle timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiProperties props(int idleSeconds) {
        return new OpenAiProperties("api-key", "k", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "m", "u", "mod", "low", "low", 5, 10, 100, 100, null, idleSeconds, 600);
    }

    @Test
    void stalledStreamIsAbandonedAfterIdleTimeout() {
        HttpOpenAiTransport transport = new HttpOpenAiTransport(props(1), new ObjectMapper(), TOKENS);
        List<String> lines = new ArrayList<>();
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> transport.postStream("/responses", "{}", lines::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TIMEOUT));

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).as("gave up long before the 8 s stall ended").isLessThan(6000);
        assertThat(lines).contains("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}");
    }
}
