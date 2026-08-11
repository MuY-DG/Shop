package org.muybaby.shopserver.wechat.servicecard.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "shop.wechat.mini-program",
        name = "mock-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class RealWechatServiceCardProvider implements WechatServiceCardProvider {

    private static final Logger log = LoggerFactory.getLogger(RealWechatServiceCardProvider.class);
    private static final String SET_URL =
            "https://api.weixin.qq.com/wxa/set_user_notify?access_token={accessToken}";
    private static final String GET_URL =
            "https://api.weixin.qq.com/wxa/get_user_notify?access_token={accessToken}";
    private static final Set<Integer> AMBIGUOUS_SET_CODES = Set.of(85431, 85448, 85449);
    private static final long UINT32_MAX = 4_294_967_295L;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider accessTokenProvider;
    private final int maxResponseBytes;

    public RealWechatServiceCardProvider(
            @Qualifier(WechatServiceCardHttpConfiguration.REST_CLIENT_BEAN_NAME) RestClient restClient,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider accessTokenProvider,
            WechatServiceCardProperties properties
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
        this.maxResponseBytes = properties.maxResponseBytes();
    }

    @Override
    public WechatServiceCardSetResult setUserNotify(WechatServiceCardSetRequest request) {
        ObjectNode payload;
        try {
            payload = objectMapper.createObjectNode();
            payload.put("openid", required(request == null ? null : request.openid(), "openid"));
            payload.put("notify_type", 2001);
            payload.put("notify_code", required(request.notifyCode(), "notifyCode"));
            String contentJson = required(request.contentJson(), "contentJson");
            payload.put("content_json", validatedContentJson(contentJson));
            if (StringUtils.hasText(request.checkJson())) {
                payload.put("check_json", validatedCheckJson(request.checkJson()));
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            return WechatServiceCardSetResult.rejected(null, "Local 2001 payload validation failed");
        }

        String accessToken;
        try {
            accessToken = required(accessTokenProvider.getAccessToken(), "accessToken");
        } catch (RuntimeException ex) {
            log.warn("WeChat 2001 access token unavailable: exception={}", ex.getClass().getSimpleName());
            return WechatServiceCardSetResult.retryable(null, "WeChat access token is unavailable");
        }
        ProviderHttpResponse response;
        try {
            response = post(SET_URL, accessToken, payload);
        } catch (RuntimeException ex) {
            String category = transportCategory(ex);
            log.warn(
                    "WeChat 2001 set outcome unknown: category={}, exception={}",
                    category, exceptionChain(ex)
            );
            return WechatServiceCardSetResult.unknown(
                    null, "WeChat set_user_notify transport failed: " + category
            );
        }
        JsonNode body = parseResponse(response.body());
        if (body == null || !body.isObject() || !intNode(body.path("errcode"))) {
            if (!response.successful()) {
                log.warn(
                        "WeChat 2001 set returned an unreadable HTTP response: status={}",
                        response.statusCode()
                );
                return WechatServiceCardSetResult.unknown(
                        null, "WeChat set_user_notify HTTP response is unavailable"
                );
            }
            return WechatServiceCardSetResult.unknown(null, "WeChat set_user_notify response is invalid");
        }
        int code = body.path("errcode").intValue();
        String message = safeMessage(code);
        if (code == 0) {
            if (!response.successful()) {
                log.warn(
                        "WeChat 2001 set returned errcode 0 with a non-success HTTP status: status={}",
                        response.statusCode()
                );
                return WechatServiceCardSetResult.unknown(
                        null, "WeChat set_user_notify HTTP response is unavailable"
                );
            }
            return WechatServiceCardSetResult.applied();
        }
        if (AMBIGUOUS_SET_CODES.contains(code)) {
            return WechatServiceCardSetResult.unknown(code, message);
        }
        if (code == 85437) {
            return WechatServiceCardSetResult.unknown(code, message);
        }
        if (Set.of(
                40003, 85433, 85434, 85435, 85436, 85438, 85439,
                85440, 85441, 85442, 85443, 85461, 85462
        ).contains(code)) {
            return WechatServiceCardSetResult.rejected(code, message);
        }
        return WechatServiceCardSetResult.unknown(code, "WeChat set_user_notify outcome requires reconciliation");
    }

    @Override
    public WechatServiceCardQueryResult getUserNotify(WechatServiceCardQueryRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        try {
            payload.put("openid", required(request == null ? null : request.openid(), "openid"));
            payload.put("notify_type", 2001);
            payload.put("notify_code", required(request.notifyCode(), "notifyCode"));
        } catch (RuntimeException ex) {
            return WechatServiceCardQueryResult.rejected(null, "Local 2001 query validation failed");
        }
        String accessToken;
        try {
            accessToken = required(accessTokenProvider.getAccessToken(), "accessToken");
        } catch (RuntimeException ex) {
            return WechatServiceCardQueryResult.retryable(null, "WeChat access token is unavailable");
        }
        ProviderHttpResponse response;
        try {
            response = post(GET_URL, accessToken, payload);
        } catch (RuntimeException ex) {
            String category = transportCategory(ex);
            log.warn(
                    "WeChat 2001 query unavailable: category={}, exception={}",
                    category, exceptionChain(ex)
            );
            return WechatServiceCardQueryResult.retryable(
                    null, "WeChat get_user_notify transport failed: " + category
            );
        }
        JsonNode body = parseResponse(response.body());
        if (body == null || !body.isObject() || !intNode(body.path("errcode"))) {
            if (!response.successful()) {
                log.warn(
                        "WeChat 2001 query returned an unreadable HTTP response: status={}",
                        response.statusCode()
                );
                return WechatServiceCardQueryResult.retryable(
                        null, "WeChat get_user_notify HTTP response is unavailable"
                );
            }
            return WechatServiceCardQueryResult.retryable(null, "WeChat get_user_notify response is invalid");
        }
        int code = body.path("errcode").intValue();
        String message = safeMessage(code);
        if (code == 0 && !response.successful()) {
            log.warn(
                    "WeChat 2001 query returned errcode 0 with a non-success HTTP status: status={}",
                    response.statusCode()
            );
            return WechatServiceCardQueryResult.retryable(
                    null, "WeChat get_user_notify HTTP response is unavailable"
            );
        }
        if (code != 0) {
            if (code == 85437) {
                return WechatServiceCardQueryResult.notFound(code, message);
            }
            if (Set.of(40003, 85434, 85438).contains(code)) {
                return WechatServiceCardQueryResult.rejected(code, message);
            }
            return WechatServiceCardQueryResult.retryable(code, message);
        }
        JsonNode info = body.path("notify_info");
        if (!info.isObject() || !intNode(info.path("notify_type"))
                || info.path("notify_type").intValue() != 2001
                || !intNode(info.path("code_state"))) {
            return WechatServiceCardQueryResult.retryable(null, "WeChat notify_info is invalid");
        }
        int stateValue = info.path("code_state").intValue();
        if (!Set.of(0, 1, 2, 10).contains(stateValue)) {
            return WechatServiceCardQueryResult.retryable(null, "WeChat code_state is invalid");
        }
        JsonNode contentValue = info.path("content_json");
        if (!contentValue.isTextual()) {
            return WechatServiceCardQueryResult.retryable(null, "WeChat content_json is invalid");
        }
        JsonNode content;
        if (!StringUtils.hasText(contentValue.asText())) {
            content = null;
        } else {
            try {
                content = objectMapper.readTree(contentValue.asText());
            } catch (JsonProcessingException ex) {
                return WechatServiceCardQueryResult.retryable(null, "WeChat content_json is invalid");
            }
        }
        Integer status = null;
        if (content != null) {
            JsonNode statusNode = content.path("cur_status");
            if (!content.isObject() || !intNode(statusNode)
                    || statusNode.intValue() < 1 || statusNode.intValue() > 11) {
                return WechatServiceCardQueryResult.retryable(null, "WeChat content_json status is invalid");
            }
            status = statusNode.intValue();
        }
        JsonNode expiryNode = info.path("code_expire_time");
        if (!expiryNode.isIntegralNumber() || !expiryNode.canConvertToLong()
                || expiryNode.longValue() <= 0) {
            return WechatServiceCardQueryResult.retryable(null, "WeChat code_expire_time is invalid");
        }
        long expireSeconds = expiryNode.longValue();
        Instant expiresAt;
        try {
            expiresAt = Instant.ofEpochSecond(expireSeconds);
            LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            return WechatServiceCardQueryResult.retryable(null, "WeChat code_expire_time is invalid");
        }
        return WechatServiceCardQueryResult.found(
                status, stateValue, expiresAt
        );
    }

    private ProviderHttpResponse post(String url, String accessToken, JsonNode body) {
        return restClient.post()
                .uri(url, required(accessToken, "accessToken"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new ProviderHttpResponse(
                        response.getStatusCode().value(), readBounded(response.getBody())
                ));
    }

    private String readBounded(InputStream input) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new IOException("WeChat response exceeds configured limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private JsonNode parseResponse(String response) {
        if (!StringUtils.hasText(response)) {
            return null;
        }
        try {
            return objectMapper.readTree(response);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String validatedObjectJson(String value) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(value);
        if (node == null || !node.isObject()) {
            throw new JsonProcessingException("WeChat 2001 JSON must be an object") { };
        }
        return objectMapper.writeValueAsString(node);
    }

    private String validatedContentJson(String value) throws JsonProcessingException {
        String normalized = validatedObjectJson(value);
        JsonNode node = objectMapper.readTree(normalized);
        JsonNode status = node.path("cur_status");
        JsonNode path = node.path("wxa_path_query");
        if (!intNode(status) || status.intValue() < 1 || status.intValue() > 11
                || !path.isTextual() || !StringUtils.hasText(path.asText())
                || path.asText().startsWith("/")) {
            throw invalidJson("WeChat 2001 content fields are invalid");
        }
        if (status.intValue() == 1 || status.intValue() == 2) {
            JsonNode products = node.path("product_list").path("info_list");
            if (!uint32(node.path("product_count"), false)
                    || !products.isArray() || products.isEmpty() || products.size() > 10) {
                throw invalidJson("WeChat 2001 product fields are invalid");
            }
            for (JsonNode product : products) {
                if (!product.isObject()
                        || !nonBlankText(product.path("product_img"))
                        || !nonBlankText(product.path("product_name"))
                        || !safePath(product.path("product_path_query"))) {
                    throw invalidJson("WeChat 2001 product fields are invalid");
                }
                JsonNode count = product.path("count");
                JsonNode price = product.path("single_price");
                if ((!count.isMissingNode() && !uint32(count, false))
                        || (!price.isMissingNode() && !uint32(price, true))) {
                    throw invalidJson("WeChat 2001 optional product amount fields are invalid");
                }
            }
        }
        return normalized;
    }

    private String validatedCheckJson(String value) throws JsonProcessingException {
        String normalized = validatedObjectJson(value);
        JsonNode node = objectMapper.readTree(normalized);
        if (!uint32(node.path("pay_amount"), true)
                || !uint32(node.path("pay_time"), false)) {
            throw invalidJson("WeChat 2001 payment check fields are invalid");
        }
        JsonNode channel = node.path("pay_channel");
        if (!channel.isMissingNode()
                && (!intNode(channel) || (channel.intValue() != 0 && channel.intValue() != 1001))) {
            throw invalidJson("WeChat 2001 payment channel is invalid");
        }
        return normalized;
    }

    private static boolean intNode(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }

    private static boolean uint32(JsonNode node, boolean allowZero) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return false;
        }
        long value = node.longValue();
        return value >= (allowZero ? 0L : 1L) && value <= UINT32_MAX;
    }

    private static boolean nonBlankText(JsonNode node) {
        return node != null && node.isTextual() && StringUtils.hasText(node.asText());
    }

    private static boolean safePath(JsonNode node) {
        return nonBlankText(node) && !node.asText().startsWith("/");
    }

    private static JsonProcessingException invalidJson(String message) {
        return new JsonProcessingException(message) { };
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String exceptionChain(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == exception) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + "/" + root.getClass().getSimpleName();
    }

    private static String transportCategory(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof HttpConnectTimeoutException) {
                return "CONNECT_TIMEOUT";
            }
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return "READ_TIMEOUT";
            }
            if (current instanceof UnknownHostException) {
                return "DNS_FAILURE";
            }
            if (current instanceof ConnectException) {
                return "CONNECT_FAILURE";
            }
            if (current instanceof SSLException) {
                return "TLS_FAILURE";
            }
            if (current instanceof EOFException) {
                return "EARLY_EOF";
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("rst_stream")) {
                    return "HTTP2_STREAM_RESET";
                }
                if (normalized.contains("goaway")) {
                    return "HTTP2_GOAWAY";
                }
                if (normalized.contains("connection reset")) {
                    return "CONNECTION_RESET";
                }
                if (normalized.contains("header parser received no bytes")) {
                    return "EARLY_EOF";
                }
                if (normalized.contains("response exceeds configured limit")) {
                    return "RESPONSE_TOO_LARGE";
                }
            }
            if (current instanceof SocketException) {
                return "SOCKET_FAILURE";
            }
        }
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root instanceof IOException ? "IO_FAILURE" : "CLIENT_FAILURE";
    }

    private static String safeMessage(int code) {
        return switch (code) {
            case 0 -> "";
            case 40003 -> "WeChat OpenID is invalid";
            case 85431 -> "WeChat service is temporarily unavailable";
            case 85433 -> "WeChat payment check data is invalid";
            case 85434 -> "WeChat service-card type is invalid";
            case 85435 -> "WeChat service-card content is invalid";
            case 85436 -> "WeChat notification code is unavailable";
            case 85437 -> "WeChat payment is not visible yet";
            case 85438 -> "WeChat notification code has expired";
            case 85439 -> "WeChat service-card transition is invalid";
            case 85440 -> "WeChat service-card field is missing";
            case 85441 -> "WeChat service-card field format is invalid";
            case 85442 -> "WeChat content security rejected the service card";
            case 85443 -> "WeChat service-card text encoding is invalid";
            case 85448 -> "WeChat notification code was already used";
            case 85449 -> "WeChat notification code is temporarily locked";
            case 85461 -> "WeChat service-card type is not authorized";
            case 85462 -> "Mini Program service-card capability is not authorized";
            default -> "WeChat service-card request was rejected";
        };
    }

    private record ProviderHttpResponse(int statusCode, String body) {
        private boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
