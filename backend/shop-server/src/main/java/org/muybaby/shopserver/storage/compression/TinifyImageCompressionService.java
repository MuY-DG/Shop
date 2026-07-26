package org.muybaby.shopserver.storage.compression;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

/**
 * Tinify's binary HTTP protocol, deliberately isolated from runtime configuration and upload fallback policy.
 */
@Component
public final class TinifyImageCompressionService implements ImageCompressionService {

    static final URI PRODUCTION_BASE_URI = URI.create("https://api.tinify.com");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    private static final String WEBP_CONTENT_TYPE = "image/webp";
    private static final byte[] WEBP_CONVERSION_BODY =
            "{\"convert\":{\"type\":\"image/webp\"}}".getBytes(StandardCharsets.UTF_8);
    private static final Pattern SAFE_OUTPUT_PATH =
            Pattern.compile("^/output/[A-Za-z0-9_-]{1,128}$");

    private final HttpClient httpClient;
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    @Autowired
    public TinifyImageCompressionService(ObjectMapper objectMapper) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                PRODUCTION_BASE_URI,
                objectMapper,
                REQUEST_TIMEOUT
        );
    }

    TinifyImageCompressionService(HttpClient httpClient, URI baseUri, ObjectMapper objectMapper) {
        this(httpClient, baseUri, objectMapper, REQUEST_TIMEOUT);
    }

    TinifyImageCompressionService(
            HttpClient httpClient,
            URI baseUri,
            ObjectMapper objectMapper,
            Duration requestTimeout
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.baseUri = validateBaseUri(baseUri);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    }

    @Override
    public ImageCompressionResult compress(String apiKey, ImageCompressionRequest request) {
        Objects.requireNonNull(request, "request");
        String authorization = basicAuthorization(apiKey);

        HttpRequest shrinkRequest = requestBuilder(shrinkUri(), authorization)
                .header("Accept", "application/json")
                .header("Content-Type", request.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.contentForTransport()))
                .build();

        Long shrinkCompressionCount;
        URI outputUri;
        HttpResponse<byte[]> shrinkResponse = sendOnce(
                shrinkRequest,
                boundedBodyHandler(MAX_ERROR_BODY_BYTES)
        );
        int shrinkStatus = shrinkResponse.statusCode();
        shrinkCompressionCount = compressionCount(shrinkResponse.headers());
        if (shrinkStatus != 201) {
            ErrorDetails details = readErrorDetails(shrinkResponse);
            throw providerException(shrinkStatus, details, shrinkCompressionCount);
        }
        try {
            outputUri = validatedOutputUri(shrinkResponse.headers());
        } catch (ImageCompressionException ex) {
            throw withFallbackCompressionCount(ex, shrinkCompressionCount);
        }

        HttpRequest outputRequest;
        if (request.isWebp()) {
            outputRequest = requestBuilder(outputUri, authorization)
                    .header("Accept", WEBP_CONTENT_TYPE)
                    .GET()
                    .build();
        } else {
            outputRequest = requestBuilder(outputUri, authorization)
                    .header("Accept", WEBP_CONTENT_TYPE)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(WEBP_CONVERSION_BODY))
                    .build();
        }

        Long latestCompressionCount = shrinkCompressionCount;
        try {
            HttpResponse<byte[]> outputResponse = sendOnce(
                    outputRequest,
                    responseInfo -> boundedBodySubscriber(
                            responseInfo.statusCode() == 200
                                    ? request.maxOutputBytes()
                                    : MAX_ERROR_BODY_BYTES
                    )
            );
            int outputStatus = outputResponse.statusCode();
            Long outputCompressionCount = compressionCount(outputResponse.headers());
            latestCompressionCount =
                    outputCompressionCount == null ? shrinkCompressionCount : outputCompressionCount;
            if (outputStatus != 200) {
                ErrorDetails details = readErrorDetails(outputResponse);
                throw providerException(outputStatus, details, latestCompressionCount);
            }

            requireWebpContentType(outputResponse.headers());
            int width = positiveIntHeader(outputResponse.headers(), "Image-Width");
            int height = positiveIntHeader(outputResponse.headers(), "Image-Height");
            Long declaredLength = optionalNonNegativeLongHeader(
                    outputResponse.headers(), "Content-Length");
            if (declaredLength != null && declaredLength > request.maxOutputBytes()) {
                throw invalidResponse("Tinify output exceeds the configured size limit");
            }

            byte[] output = outputResponse.body();
            if (output == null) {
                throw invalidResponse("Tinify returned no response body");
            }
            if (declaredLength != null && declaredLength != output.length) {
                throw invalidResponse("Tinify output length does not match Content-Length");
            }
            if (!isWebp(output)) {
                throw invalidResponse("Tinify returned a body that is not a WebP image");
            }

            return new ImageCompressionResult(
                    output,
                    WEBP_CONTENT_TYPE,
                    width,
                    height,
                    optionalLong(latestCompressionCount)
            );
        } catch (ImageCompressionException ex) {
            throw withFallbackCompressionCount(ex, latestCompressionCount);
        }
    }

    @Override
    public ImageCompressionProbeResult probe(String apiKey) {
        String authorization = basicAuthorization(apiKey);
        HttpRequest probeRequest = requestBuilder(shrinkUri(), authorization)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<byte[]> response = sendOnce(
                probeRequest,
                boundedBodyHandler(MAX_ERROR_BODY_BYTES)
        );
        int status = response.statusCode();
        Long count = compressionCount(response.headers());
        ErrorDetails details = readErrorDetails(response);

        if (status == 400) {
            return new ImageCompressionProbeResult(
                    ImageCompressionProbeState.VALID,
                    optionalLong(count)
            );
        }
        if (status == 401 || status == 403) {
            return new ImageCompressionProbeResult(
                    ImageCompressionProbeState.INVALID_CREDENTIALS,
                    optionalLong(count)
            );
        }
        if (status == 429) {
            ImageCompressionFailure failure = classifyProviderFailure(status, details);
            ImageCompressionProbeState state = failure == ImageCompressionFailure.QUOTA_EXHAUSTED
                    ? ImageCompressionProbeState.QUOTA_EXHAUSTED
                    : ImageCompressionProbeState.RATE_LIMITED;
            return new ImageCompressionProbeResult(state, optionalLong(count));
        }
        throw providerException(status, details, count);
    }

    private HttpRequest.Builder requestBuilder(URI uri, String authorization) {
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", authorization);
    }

    private HttpResponse<byte[]> sendOnce(
            HttpRequest request,
            HttpResponse.BodyHandler<byte[]> bodyHandler
    ) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, bodyHandler);
            if (response == null) {
                throw invalidResponse("Tinify returned no HTTP response");
            }
            return response;
        } catch (HttpTimeoutException ex) {
            throw new ImageCompressionException(
                    ImageCompressionFailure.TIMEOUT,
                    "Tinify request timed out",
                    null,
                    null,
                    null,
                    ex
            );
        } catch (IOException ex) {
            if (hasCause(ex, ResponseTooLargeException.class)) {
                throw invalidResponse("Tinify response body exceeds the configured size limit", ex);
            }
            throw new ImageCompressionException(
                    ImageCompressionFailure.NETWORK,
                    "Tinify request failed because of a network error",
                    null,
                    null,
                    null,
                    ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ImageCompressionException(
                    ImageCompressionFailure.NETWORK,
                    "Tinify request was interrupted",
                    null,
                    null,
                    null,
                    ex
            );
        }
    }

    private ErrorDetails readErrorDetails(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        if (body == null) {
            throw invalidResponse("Tinify returned no response body");
        }
        if (body.length == 0) {
            return ErrorDetails.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return ErrorDetails.EMPTY;
            }
            return new ErrorDetails(
                    safeText(root.get("error")),
                    safeText(root.get("message"))
            );
        } catch (JsonProcessingException ex) {
            return ErrorDetails.EMPTY;
        } catch (IOException ex) {
            throw invalidResponse("Tinify returned unreadable error JSON", ex);
        }
    }

    private ImageCompressionException providerException(int status, ErrorDetails details, Long count) {
        ImageCompressionFailure failure = classifyProviderFailure(status, details);
        String message = switch (failure) {
            case QUOTA_EXHAUSTED -> "Tinify compression quota is exhausted";
            case INVALID_CREDENTIALS -> "Tinify credentials are invalid";
            case RATE_LIMITED -> "Tinify rate limit was reached";
            case REJECTED -> "Tinify rejected the image compression request";
            case UNAVAILABLE -> "Tinify is temporarily unavailable";
            case INVALID_RESPONSE -> "Tinify returned an unexpected HTTP response";
            case NETWORK, TIMEOUT -> throw new IllegalStateException("transport failures are created separately");
        };
        return new ImageCompressionException(
                failure,
                message,
                status,
                details.error(),
                count,
                null
        );
    }

    private ImageCompressionFailure classifyProviderFailure(int status, ErrorDetails details) {
        if (status == 401 || status == 403) {
            return ImageCompressionFailure.INVALID_CREDENTIALS;
        }
        if (status == 429) {
            return isQuotaError(details)
                    ? ImageCompressionFailure.QUOTA_EXHAUSTED
                    : ImageCompressionFailure.RATE_LIMITED;
        }
        if (status == 408 || status >= 500) {
            return ImageCompressionFailure.UNAVAILABLE;
        }
        if (status >= 400 && status < 500) {
            return ImageCompressionFailure.REJECTED;
        }
        return ImageCompressionFailure.INVALID_RESPONSE;
    }

    private boolean isQuotaError(ErrorDetails details) {
        String text = (nullToEmpty(details.error()) + " " + nullToEmpty(details.message()))
                .toLowerCase(Locale.ROOT);
        if (text.contains("rate limit") || text.contains("request rate")) {
            return false;
        }
        return text.contains("quota")
                || text.contains("monthly limit")
                || text.contains("compression limit")
                || text.contains("compressions limit")
                || ((text.contains("compression") || text.contains("account"))
                && (text.contains("exceeded") || text.contains("exhausted") || text.contains("reached")));
    }

    private URI validatedOutputUri(HttpHeaders headers) {
        String location = requiredSingleHeader(headers, "Location");
        final URI candidate;
        try {
            candidate = URI.create(location);
        } catch (IllegalArgumentException ex) {
            throw invalidResponse("Tinify returned an invalid output Location", ex);
        }

        boolean sameOrigin = candidate.isAbsolute()
                && candidate.getScheme() != null
                && candidate.getScheme().equalsIgnoreCase(baseUri.getScheme())
                && candidate.getHost() != null
                && candidate.getHost().equalsIgnoreCase(baseUri.getHost())
                && candidate.getPort() == baseUri.getPort();
        boolean safeShape = candidate.getRawUserInfo() == null
                && candidate.getRawQuery() == null
                && candidate.getRawFragment() == null
                && candidate.getRawPath() != null
                && SAFE_OUTPUT_PATH.matcher(candidate.getRawPath()).matches();
        if (!sameOrigin || !safeShape) {
            throw invalidResponse("Tinify returned an untrusted output Location");
        }
        return candidate;
    }

    private void requireWebpContentType(HttpHeaders headers) {
        String contentType = requiredSingleHeader(headers, "Content-Type");
        int parametersStart = contentType.indexOf(';');
        String mediaType = (parametersStart < 0 ? contentType : contentType.substring(0, parametersStart))
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!WEBP_CONTENT_TYPE.equals(mediaType)) {
            throw invalidResponse("Tinify output Content-Type is not image/webp");
        }
    }

    private int positiveIntHeader(HttpHeaders headers, String name) {
        long value = requiredNonNegativeLongHeader(headers, name);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw invalidResponse("Tinify returned an invalid " + name + " header");
        }
        return (int) value;
    }

    private Long compressionCount(HttpHeaders headers) {
        return optionalNonNegativeLongHeader(headers, "Compression-Count");
    }

    private long requiredNonNegativeLongHeader(HttpHeaders headers, String name) {
        Long value = optionalNonNegativeLongHeader(headers, name);
        if (value == null) {
            throw invalidResponse("Tinify response is missing the " + name + " header");
        }
        return value;
    }

    private Long optionalNonNegativeLongHeader(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw invalidResponse("Tinify returned multiple " + name + " headers");
        }
        String raw = values.getFirst().trim();
        try {
            long value = Long.parseLong(raw);
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw invalidResponse("Tinify returned an invalid " + name + " header", ex);
        }
    }

    private String requiredSingleHeader(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw invalidResponse("Tinify response is missing a valid " + name + " header");
        }
        return values.getFirst().trim();
    }

    private ImageCompressionException invalidResponse(String message) {
        return invalidResponse(message, null);
    }

    private ImageCompressionException invalidResponse(String message, Throwable cause) {
        return new ImageCompressionException(
                ImageCompressionFailure.INVALID_RESPONSE,
                message,
                null,
                null,
                null,
                cause
        );
    }

    private ImageCompressionException withFallbackCompressionCount(
            ImageCompressionException exception,
            Long fallbackCount
    ) {
        if (fallbackCount == null || exception.compressionCount().isPresent()) {
            return exception;
        }
        return new ImageCompressionException(
                exception.failure(),
                exception.getMessage(),
                exception.statusCode().isPresent()
                        ? exception.statusCode().getAsInt()
                        : null,
                exception.providerError().orElse(null),
                fallbackCount,
                exception
        );
    }

    private URI shrinkUri() {
        return baseUri.resolve("/shrink");
    }

    private static URI validateBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUri");
        String path = baseUri.getRawPath();
        boolean validScheme = "https".equalsIgnoreCase(baseUri.getScheme())
                || "http".equalsIgnoreCase(baseUri.getScheme());
        if (!baseUri.isAbsolute()
                || !validScheme
                || baseUri.getHost() == null
                || baseUri.getRawUserInfo() != null
                || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null
                || !(path == null || path.isEmpty() || "/".equals(path))) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) origin");
        }
        return baseUri;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static String basicAuthorization(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 1024
                || apiKey.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new ImageCompressionException(
                    ImageCompressionFailure.INVALID_CREDENTIALS,
                    "Tinify API key is missing or invalid",
                    null,
                    null,
                    null,
                    null
            );
        }
        String credentials = "api:" + apiKey;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static String safeText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.textValue().trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler(long maxBytes) {
        return ignored -> boundedBodySubscriber(maxBytes);
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedBodySubscriber(long maxBytes) {
        return new BoundedByteArraySubscriber(maxBytes);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isWebp(byte[] content) {
        return content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P';
    }

    private static final class BoundedByteArraySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final long maxBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long received;

        private BoundedByteArraySubscriber(long maxBytes) {
            if (maxBytes < 0 || maxBytes > Integer.MAX_VALUE - 8L) {
                throw new IllegalArgumentException("maxBytes is outside the supported range");
            }
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int chunkSize = buffer.remaining();
                if (chunkSize > maxBytes - received) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException(maxBytes));
                    return;
                }
                byte[] chunk = new byte[chunkSize];
                buffer.get(chunk);
                output.writeBytes(chunk);
                received += chunkSize;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException(long maxBytes) {
            super("response exceeded " + maxBytes + " bytes");
        }
    }

    private record ErrorDetails(String error, String message) {
        private static final ErrorDetails EMPTY = new ErrorDetails(null, null);
    }
}
