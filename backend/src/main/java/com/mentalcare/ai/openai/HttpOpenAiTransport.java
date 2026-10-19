package com.mentalcare.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;
import com.mentalcare.config.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JDK HttpClient based transport. The only place in the app that talks to api.openai.com.
 * Credentials come from the active {@link BearerTokenProvider}; redirects are never followed
 * (a redirect could otherwise carry the bearer token to another host).
 *
 * <p>The JDK client's request timeout covers only the response headers, so streamed bodies are
 * additionally guarded by a watchdog: no data for {@code streamIdleTimeoutSeconds}, or a total
 * duration beyond {@code streamMaxSeconds}, closes the stream and surfaces a TIMEOUT.
 */
@Component
public class HttpOpenAiTransport implements OpenAiTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpOpenAiTransport.class);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "openai-stream-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final BearerTokenProvider tokens;
    private final HttpClient client;

    public HttpOpenAiTransport(OpenAiProperties props, ObjectMapper mapper, BearerTokenProvider tokens) {
        this.props = props;
        this.mapper = mapper;
        this.tokens = tokens;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean usesChatGptPlan() {
        return tokens.usesChatGptPlan();
    }

    @Override
    public String post(String path, String jsonBody) {
        return send(request(path).POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json").build());
    }

    @Override
    public String get(String path) {
        return send(request(path).GET().build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw httpError(response.statusCode(), response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw ioError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(Kind.NETWORK, "Request interrupted", e);
        }
    }

    @Override
    public void postStream(String path, String jsonBody, Consumer<String> lineConsumer) {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw ioError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(Kind.NETWORK, "Request interrupted", e);
        }

        long idleNanos = TimeUnit.SECONDS.toNanos(props.streamIdleTimeoutSecondsOrDefault());
        long maxNanos = TimeUnit.SECONDS.toNanos(props.streamMaxSecondsOrDefault());
        long started = System.nanoTime();
        AtomicLong lastRead = new AtomicLong(started);
        AtomicReference<String> timedOut = new AtomicReference<>();
        InputStream body = response.body();
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            String reason = now - lastRead.get() > idleNanos ? "idle" : now - started > maxNanos ? "max-duration" : null;
            if (reason != null && timedOut.compareAndSet(null, reason)) {
                try {
                    body.close(); // unblocks readLine() with an IOException
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        try (body) {
            if (response.statusCode() / 100 != 2) {
                throw httpError(response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                lastRead.set(System.nanoTime());
                lineConsumer.accept(line);
            }
        } catch (IOException e) {
            if (timedOut.get() != null) {
                log.warn("OpenAI stream abandoned ({} timeout)", timedOut.get());
                throw new LlmException(Kind.TIMEOUT, "Stream " + timedOut.get() + " timeout", e);
            }
            throw new LlmException(Kind.STREAM_INTERRUPTED, "Stream interrupted: " + e.getClass().getSimpleName(), e);
        } finally {
            watchdog.cancel(false);
        }
        if (timedOut.get() != null) {
            // the stream ended right as the watchdog fired
            throw new LlmException(Kind.TIMEOUT, "Stream " + timedOut.get() + " timeout");
        }
    }

    private HttpRequest.Builder request(String path) {
        String bearer = tokens.bearer(); // throws NOT_CONNECTED / NO_PLAN_USAGE / AUTH_REQUIRED / NOT_CONFIGURED
        return HttpRequest.newBuilder(URI.create(props.baseUrl() + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()));
    }

    private LlmException httpError(int status, String body) {
        OpenAiErrors.ParsedError parsed = OpenAiErrors.parse(mapper, body);
        LlmException e = OpenAiErrors.exception(status, parsed);
        if (status == 401 || e.kind() == Kind.AUTH_REQUIRED) {
            tokens.onUnauthorized();
        }
        // Log status and code only; the message could echo request content in rare cases.
        log.warn("OpenAI HTTP {} kind={} code={}", status, e.kind(), parsed.code());
        return e;
    }

    private LlmException ioError(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return new LlmException(Kind.TIMEOUT, "OpenAI request timed out", e);
        }
        if (e instanceof ConnectException) {
            return new LlmException(Kind.NETWORK, "Cannot connect to OpenAI", e);
        }
        return new LlmException(Kind.NETWORK, "Network error: " + e.getClass().getSimpleName(), e);
    }
}
