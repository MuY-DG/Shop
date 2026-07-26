package org.muybaby.shopserver.storage.compression;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TinifyImageCompressionServiceTest {

    private static final URI API_BASE = URI.create("https://api.tinify.com");
    private static final String OUTPUT_LOCATION = "https://api.tinify.com/output/safe_123-ID";
    private static final byte[] WEBP = new byte[]{
            'R', 'I', 'F', 'F', 8, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png"})
    void convertsJpegAndPngToWebpWithoutPreservingMetadata(String inputContentType) throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(201, headers(
                        "Location", OUTPUT_LOCATION,
                        "Compression-Count", "17"
                ), "{}"),
                response(200, headers(
                        "Content-Type", "image/webp",
                        "Content-Length", Integer.toString(WEBP.length),
                        "Image-Width", "640",
                        "Image-Height", "480",
                        "Compression-Count", "18"
                ), WEBP)
        );
        TinifyImageCompressionService service = service(httpClient, API_BASE);
        byte[] source = new byte[]{1, 2, 3, 4};

        ImageCompressionResult result = service.compress(
                "secret-key",
                new ImageCompressionRequest(source, inputContentType, 1024)
        );

        assertThat(result.content()).isEqualTo(WEBP);
        assertThat(result.contentType()).isEqualTo("image/webp");
        assertThat(result.width()).isEqualTo(640);
        assertThat(result.height()).isEqualTo(480);
        assertThat(result.compressionCount()).hasValue(18);
        assertThat(httpClient.requests()).hasSize(2);

        HttpRequest shrink = httpClient.requests().get(0);
        assertThat(shrink.method()).isEqualTo("POST");
        assertThat(shrink.uri()).isEqualTo(URI.create("https://api.tinify.com/shrink"));
        assertThat(shrink.headers().firstValue("Content-Type")).hasValue(inputContentType);
        assertThat(shrink.headers().firstValue("Authorization")).hasValue(
                "Basic " + Base64.getEncoder()
                        .encodeToString("api:secret-key".getBytes(StandardCharsets.UTF_8))
        );
        assertThat(requestBody(shrink)).isEqualTo(source);

        HttpRequest output = httpClient.requests().get(1);
        assertThat(output.method()).isEqualTo("POST");
        assertThat(output.uri()).isEqualTo(URI.create(OUTPUT_LOCATION));
        assertThat(output.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(objectMapper.readTree(requestBody(output))).isEqualTo(objectMapper.readTree("""
                {"convert":{"type":"image/webp"}}
                """));
        assertThat(new String(requestBody(output), StandardCharsets.UTF_8))
                .doesNotContain("preserve");
    }

    @Test
    void retrievesAlreadyWebpInputWithGetToAvoidAnExtraConversionCount() throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(201, headers(
                        "Location", OUTPUT_LOCATION,
                        "Compression-Count", "21"
                ), "{}"),
                response(200, headers(
                        "Content-Type", "image/webp; charset=binary",
                        "Image-Width", "320",
                        "Image-Height", "240",
                        "Compression-Count", "21"
                ), WEBP)
        );
        TinifyImageCompressionService service = service(httpClient, API_BASE);

        ImageCompressionResult result = service.compress(
                "secret-key",
                new ImageCompressionRequest(WEBP, "IMAGE/WEBP", 1024)
        );

        assertThat(result.compressionCount()).hasValue(21);
        assertThat(httpClient.requests()).hasSize(2);
        HttpRequest output = httpClient.requests().get(1);
        assertThat(output.method()).isEqualTo("GET");
        assertThat(output.headers().firstValue("Content-Type")).isEmpty();
        assertThat(output.bodyPublisher()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.tinify.com/output/id",
            "https://evil.example/output/id",
            "https://api.tinify.com.evil.example/output/id",
            "https://api.tinify.com/output/../secret",
            "https://api.tinify.com/output/id?download=true",
            "https://api.tinify.com/output/id#fragment",
            "https://api.tinify.com/output/id%2Fother",
            "https://api.tinify.com/output/",
            "/output/id"
    })
    void rejectsUntrustedOrUnsafeOutputLocations(String location) {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(201, headers(
                        "Location", location,
                        "Compression-Count", "41"
                ), "{}")
        );

        assertThatThrownBy(() -> service(httpClient, API_BASE).compress(
                "secret-key",
                new ImageCompressionRequest(new byte[]{1}, "image/jpeg", 1024)
        )).isInstanceOfSatisfying(ImageCompressionException.class, exception ->
                assertThat(exception.failure()).isEqualTo(ImageCompressionFailure.INVALID_RESPONSE))
                .isInstanceOfSatisfying(ImageCompressionException.class, exception ->
                        assertThat(exception.compressionCount()).hasValue(41));
        assertThat(httpClient.requests()).hasSize(1);
    }

    @Test
    void permitsAValidatedSameOriginLocationForAnInjectedTestOrigin() {
        URI testOrigin = URI.create("http://127.0.0.1:18080");
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(201, headers(
                        "Location", "http://127.0.0.1:18080/output/test-id"
                ), "{}"),
                response(200, headers(
                        "Content-Type", "image/webp",
                        "Image-Width", "10",
                        "Image-Height", "20"
                ), WEBP)
        );

        ImageCompressionResult result = service(httpClient, testOrigin).compress(
                "key",
                new ImageCompressionRequest(new byte[]{1}, "image/png", 1024)
        );

        assertThat(result.contentType()).isEqualTo("image/webp");
        assertThat(httpClient.requests().get(1).uri())
                .isEqualTo(URI.create("http://127.0.0.1:18080/output/test-id"));
    }

    @Test
    void rejectsAResponseThatClaimsAnotherOutputType() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                successfulShrink(),
                response(200, headers(
                        "Content-Type", "image/png",
                        "Image-Width", "10",
                        "Image-Height", "20"
                ), WEBP)
        );

        assertInvalidResponse(httpClient, 1024);
    }

    @Test
    void rejectsAResponseWithoutPositiveDimensions() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                successfulShrink(),
                response(200, headers(
                        "Content-Type", "image/webp",
                        "Image-Width", "0",
                        "Image-Height", "20"
                ), WEBP)
        );

        assertInvalidResponse(httpClient, 1024);
    }

    @Test
    void rejectsAResponseWhoseBodyIsNotWebp() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                successfulShrink(),
                response(200, headers(
                        "Content-Type", "image/webp",
                        "Image-Width", "10",
                        "Image-Height", "20"
                ), "not-webp")
        );

        assertInvalidResponse(httpClient, 1024);
    }

    @Test
    void cancelsAResponseBodyAsSoonAsItExceedsTheOutputLimit() {
        byte[] oversized = new byte[65];
        System.arraycopy(WEBP, 0, oversized, 0, WEBP.length);
        RecordingHttpClient httpClient = new RecordingHttpClient(
                successfulShrink(),
                response(200, headers(
                        "Content-Type", "image/webp",
                        "Image-Width", "10",
                        "Image-Height", "20"
                ), oversized)
        );

        assertInvalidResponse(httpClient, 64);
        assertThat(httpClient.lastSubscriptionCancelled()).isTrue();
        assertThat(httpClient.requests()).hasSize(2);
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void classifiesProviderErrorResponses(
            int status,
            String errorJson,
            ImageCompressionFailure expectedFailure
    ) throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(status, headers("Compression-Count", "42"), errorJson)
        );
        String expectedProviderError = objectMapper.readTree(errorJson).path("error").asText();
        String upstreamMessage = objectMapper.readTree(errorJson).path("message").asText();

        assertThatThrownBy(() -> service(httpClient, API_BASE).compress(
                "secret-key",
                new ImageCompressionRequest(new byte[]{1}, "image/jpeg", 1024)
        )).isInstanceOfSatisfying(ImageCompressionException.class, exception -> {
            assertThat(exception.failure()).isEqualTo(expectedFailure);
            assertThat(exception.statusCode()).hasValue(status);
            assertThat(exception.providerError()).hasValue(expectedProviderError);
            assertThat(exception.compressionCount()).hasValue(42);
            assertThat(exception.getMessage()).doesNotContain(upstreamMessage);
        });
        assertThat(httpClient.requests()).hasSize(1);
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of(
                        401,
                        """
                                {"error":"Unauthorized","message":"Credentials are invalid"}
                                """,
                        ImageCompressionFailure.INVALID_CREDENTIALS
                ),
                Arguments.of(
                        403,
                        """
                                {"error":"Forbidden","message":"This account is disabled"}
                                """,
                        ImageCompressionFailure.INVALID_CREDENTIALS
                ),
                Arguments.of(
                        429,
                        """
                                {"error":"TooManyRequests","message":"Your monthly compression limit has been exceeded"}
                                """,
                        ImageCompressionFailure.QUOTA_EXHAUSTED
                ),
                Arguments.of(
                        429,
                        """
                                {"error":"TooManyRequests","message":"Rate limit exceeded; retry later"}
                                """,
                        ImageCompressionFailure.RATE_LIMITED
                ),
                Arguments.of(
                        422,
                        """
                                {"error":"BadRequest","message":"Unsupported image"}
                                """,
                        ImageCompressionFailure.REJECTED
                ),
                Arguments.of(
                        503,
                        """
                                {"error":"ServiceUnavailable","message":"Please try again later"}
                                """,
                        ImageCompressionFailure.UNAVAILABLE
                )
        );
    }

    @Test
    void treatsAnAmbiguousMalformed429ResponseAsRateLimiting() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(429, headers(), "{not-json")
        );

        assertThatThrownBy(() -> service(httpClient, API_BASE).compress(
                "key",
                new ImageCompressionRequest(new byte[]{1}, "image/jpeg", 1024)
        )).isInstanceOfSatisfying(ImageCompressionException.class, exception -> {
            assertThat(exception.failure()).isEqualTo(ImageCompressionFailure.RATE_LIMITED);
            assertThat(exception.providerError()).isEmpty();
        });
    }

    @Test
    void doesNotRetryABillableShrinkPostAfterATimeout() {
        RecordingHttpClient httpClient = new RecordingHttpClient(request -> {
            throw new HttpTimeoutException("timed out");
        });

        assertThatThrownBy(() -> service(httpClient, API_BASE).compress(
                "key",
                new ImageCompressionRequest(new byte[]{1}, "image/png", 1024)
        )).isInstanceOfSatisfying(ImageCompressionException.class, exception ->
                assertThat(exception.failure()).isEqualTo(ImageCompressionFailure.TIMEOUT));
        assertThat(httpClient.requests()).hasSize(1);
    }

    @Test
    void doesNotRetryABillableConversionPostAfterANetworkFailure() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                request -> successfulShrink(),
                request -> {
                    throw new IOException("connection reset");
                }
        );

        assertThatThrownBy(() -> service(httpClient, API_BASE).compress(
                "key",
                new ImageCompressionRequest(new byte[]{1}, "image/jpeg", 1024)
        )).isInstanceOfSatisfying(ImageCompressionException.class, exception -> {
            assertThat(exception.failure()).isEqualTo(ImageCompressionFailure.NETWORK);
            assertThat(exception.compressionCount()).hasValue(1);
        });
        assertThat(httpClient.requests()).hasSize(2);
    }

    @Test
    void probesCredentialsWithAnEmptyNonBillableShrinkRequest() throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(400, headers("Compression-Count", "123"), """
                        {"error":"BadRequest","message":"Input is empty"}
                        """)
        );

        ImageCompressionProbeResult result = service(httpClient, API_BASE).probe("secret-key");

        assertThat(result.state()).isEqualTo(ImageCompressionProbeState.VALID);
        assertThat(result.compressionCount()).hasValue(123);
        assertThat(httpClient.requests()).hasSize(1);
        HttpRequest request = httpClient.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo(URI.create("https://api.tinify.com/shrink"));
        assertThat(request.headers().firstValue("Content-Type")).isEmpty();
        assertThat(requestBody(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void probeReportsInvalidCredentials(int status) {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(status, headers(), """
                        {"error":"Unauthorized","message":"Credentials are invalid"}
                        """)
        );

        ImageCompressionProbeResult result = service(httpClient, API_BASE).probe("bad-key");

        assertThat(result.state()).isEqualTo(ImageCompressionProbeState.INVALID_CREDENTIALS);
        assertThat(result.compressionCount()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("probeLimitResponses")
    void probeDistinguishesQuotaExhaustionFromOrdinaryRateLimiting(
            String message,
            ImageCompressionProbeState expectedState
    ) {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(429, headers("Compression-Count", "500"), """
                        {"error":"TooManyRequests","message":"%s"}
                        """.formatted(message))
        );

        ImageCompressionProbeResult result = service(httpClient, API_BASE).probe("key");

        assertThat(result.state()).isEqualTo(expectedState);
        assertThat(result.compressionCount()).hasValue(500);
    }

    static Stream<Arguments> probeLimitResponses() {
        return Stream.of(
                Arguments.of(
                        "Your monthly compression limit has been exceeded",
                        ImageCompressionProbeState.QUOTA_EXHAUSTED
                ),
                Arguments.of(
                        "Rate limit exceeded; slow down",
                        ImageCompressionProbeState.RATE_LIMITED
                )
        );
    }

    @Test
    void probePropagatesProviderAvailabilityFailures() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(503, headers(), """
                        {"error":"ServiceUnavailable","message":"Temporary outage"}
                        """)
        );

        assertThatThrownBy(() -> service(httpClient, API_BASE).probe("key"))
                .isInstanceOfSatisfying(ImageCompressionException.class, exception ->
                        assertThat(exception.failure()).isEqualTo(ImageCompressionFailure.UNAVAILABLE));
    }

    @Test
    void rejectsMissingOrControlCharacterCredentialsBeforeSending() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        TinifyImageCompressionService service = service(httpClient, API_BASE);

        assertThatThrownBy(() -> service.probe(" \nsecret"))
                .isInstanceOfSatisfying(ImageCompressionException.class, exception ->
                        assertThat(exception.failure())
                                .isEqualTo(ImageCompressionFailure.INVALID_CREDENTIALS));
        assertThat(httpClient.requests()).isEmpty();
    }

    private void assertInvalidResponse(RecordingHttpClient httpClient, long maxOutputBytes) {
        assertThatThrownBy(() -> service(httpClient, API_BASE).compress(
                "key",
                new ImageCompressionRequest(new byte[]{1}, "image/jpeg", maxOutputBytes)
        )).isInstanceOfSatisfying(ImageCompressionException.class, exception ->
                assertThat(exception.failure()).isEqualTo(ImageCompressionFailure.INVALID_RESPONSE));
    }

    private TinifyImageCompressionService service(RecordingHttpClient client, URI baseUri) {
        return new TinifyImageCompressionService(
                client,
                baseUri,
                objectMapper,
                Duration.ofSeconds(2)
        );
    }

    private static RawResponse successfulShrink() {
        return response(201, headers(
                "Location", OUTPUT_LOCATION,
                "Compression-Count", "1"
        ), "{}");
    }

    private static RawResponse response(int status, HttpHeaders headers, String body) {
        return response(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static RawResponse response(int status, HttpHeaders headers, byte[] body) {
        return new RawResponse(status, headers, body.clone());
    }

    private static HttpHeaders headers(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("header names and values must be paired");
        }
        Map<String, List<String>> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            values.put(namesAndValues[index], List.of(namesAndValues[index + 1]));
        }
        return HttpHeaders.of(values, (name, value) -> true);
    }

    private static byte[] requestBody(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(output.toByteArray());
            }
        });
        return result.get();
    }

    private record RawResponse(int status, HttpHeaders headers, byte[] body) {
    }

    @FunctionalInterface
    private interface Responder {
        RawResponse respond(HttpRequest request) throws IOException, InterruptedException;
    }

    private static final class RecordingHttpClient extends HttpClient {

        private final ArrayDeque<Responder> responders = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private boolean lastSubscriptionCancelled;

        private RecordingHttpClient() {
        }

        private RecordingHttpClient(RawResponse... responses) {
            for (RawResponse response : responses) {
                responders.add(request -> response);
            }
        }

        private RecordingHttpClient(Responder... responders) {
            this.responders.addAll(List.of(responders));
        }

        private List<HttpRequest> requests() {
            return List.copyOf(requests);
        }

        private boolean lastSubscriptionCancelled() {
            return lastSubscriptionCancelled;
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            requests.add(request);
            Responder responder = responders.poll();
            if (responder == null) {
                throw new AssertionError("unexpected HTTP request: " + request);
            }
            RawResponse raw = responder.respond(request);
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(
                    new StubResponseInfo(raw.status(), raw.headers())
            );
            TestSubscription subscription = new TestSubscription();
            subscriber.onSubscribe(subscription);
            if (!subscription.cancelled() && raw.body().length > 0) {
                subscriber.onNext(List.of(ByteBuffer.wrap(raw.body().clone())));
            }
            if (!subscription.cancelled()) {
                subscriber.onComplete();
            }
            lastSubscriptionCancelled = subscription.cancelled();

            final T body;
            try {
                body = subscriber.getBody().toCompletableFuture().join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof IOException ioException) {
                    throw new IOException("response body failed", ioException);
                }
                throw ex;
            }
            return new StubHttpResponse<>(request, raw.status(), raw.headers(), body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private static final class TestSubscription implements Flow.Subscription {

        private boolean cancelled;

        @Override
        public void request(long n) {
            // The fake transport emits one response body item synchronously.
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private boolean cancelled() {
            return cancelled;
        }
    }

    private record StubResponseInfo(int statusCode, HttpHeaders headers)
            implements HttpResponse.ResponseInfo {

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }

    private record StubHttpResponse<T>(
            HttpRequest request,
            int statusCode,
            HttpHeaders headers,
            T body
    ) implements HttpResponse<T> {

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
