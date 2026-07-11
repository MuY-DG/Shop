package org.muybaby.shopserver.logistics.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class WechatShippingErrorSanitizer {

    static final String GENERIC_CODE = "WECHAT_SHIPPING_ERROR";
    static final String GENERIC_MESSAGE = "WeChat shipping operation failed";
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 255;
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+"
    );
    private static final Pattern TOKEN_VALUE = Pattern.compile(
            "(?i)((?:access[_-]?token|token|openid)\\s*[:=]\\s*)[^\\s,;]+"
    );
    private static final Pattern TOKEN_LIKE_FRAGMENT = Pattern.compile(
            "(?i)[A-Za-z0-9._-]*(?:access[_-]?token|token)[A-Za-z0-9._-]*"
    );
    private static final Pattern LONG_DIGIT_SEQUENCE = Pattern.compile("(?<!\\d)\\d{7,}(?!\\d)");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z0-9_.-]+");
    private static final Pattern SAFE_TOKEN_SEMANTIC_CODE = Pattern.compile(
            "(?:ACCESS_)?TOKEN_(?:UNAVAILABLE|EXPIRED|INVALID|MISSING|FAILED)"
    );
    private static final Pattern SERIALIZED_PAYLOAD = Pattern.compile(
            "(?i)(?:[{}\\[\\]]|\\bpayload\\s*[:=]|"
                    + "(?:shipping[_-]?list|order[_-]?key|transaction[_-]?id|payer|openid|"
                    + "consignor[_-]?contact|receiver[_-]?contact|tracking[_-]?(?:no|number)|"
                    + "item[_-]?desc)\\s*[:=])"
    );

    public SanitizedError sanitize(String errorCode, String errorMessage, List<String> knownSecrets) {
        String code = CONTROL_CHARACTERS.matcher(defaultString(errorCode)).replaceAll("").trim();
        code = redactKnownSecrets(code, knownSecrets);
        code = AUTHORIZATION_VALUE.matcher(code).replaceAll("$1" + REDACTED);
        code = TOKEN_VALUE.matcher(code).replaceAll("$1" + REDACTED);
        code = LONG_DIGIT_SEQUENCE.matcher(code).replaceAll(REDACTED);
        boolean suspiciousTokenFragment = TOKEN_LIKE_FRAGMENT.matcher(code).find()
                && !SAFE_TOKEN_SEMANTIC_CODE.matcher(code).matches();
        if (!StringUtils.hasText(code)
                || !SAFE_ERROR_CODE.matcher(code).matches()
                || code.codePoints().noneMatch(Character::isLetterOrDigit)
                || suspiciousTokenFragment) {
            code = GENERIC_CODE;
        }
        code = truncate(code, MAX_CODE_LENGTH);

        String message = CONTROL_CHARACTERS.matcher(defaultString(errorMessage)).replaceAll(" ").trim();
        if (SERIALIZED_PAYLOAD.matcher(message).find()) {
            return new SanitizedError(code, GENERIC_MESSAGE);
        }
        message = redact(message, knownSecrets);
        message = message.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(message)) {
            message = GENERIC_MESSAGE;
        }
        return new SanitizedError(code, truncate(message, MAX_MESSAGE_LENGTH));
    }

    private String redact(String value, List<String> knownSecrets) {
        String redacted = redactKnownSecrets(value, knownSecrets);
        redacted = AUTHORIZATION_VALUE.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = TOKEN_VALUE.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = TOKEN_LIKE_FRAGMENT.matcher(redacted).replaceAll(REDACTED);
        return LONG_DIGIT_SEQUENCE.matcher(redacted).replaceAll(REDACTED);
    }

    private String redactKnownSecrets(String value, List<String> knownSecrets) {
        String redacted = value;
        if (knownSecrets != null) {
            for (String secret : knownSecrets.stream()
                    .filter(StringUtils::hasText)
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        return redacted;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    public record SanitizedError(String code, String message) {
    }
}
