package org.muybaby.shopserver.payment.config;

import com.wechat.pay.java.core.util.PemUtil;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PaymentPemValidator {

    private static final int MAX_PEM_BYTES = 32 * 1024;
    private static final int MIN_RSA_BITS = 2048;
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "\\A\\s*-----BEGIN PRIVATE KEY-----\\s*([A-Za-z0-9+/=\\r\\n\\t ]+)" // gitleaks:allow
                    + "-----END PRIVATE KEY-----\\s*\\z"
    );
    private static final Pattern PUBLIC_KEY_PATTERN = Pattern.compile(
            "\\A\\s*-----BEGIN PUBLIC KEY-----\\s*([A-Za-z0-9+/=\\r\\n\\t ]+)"
                    + "-----END PUBLIC KEY-----\\s*\\z"
    );

    public String validatePrivateKey(String pem) {
        String normalized = normalize(pem, PRIVATE_KEY_PATTERN, "PRIVATE KEY");
        try {
            if (!(PemUtil.loadPrivateKeyFromString(normalized) instanceof RSAPrivateKey rsaKey)
                    || rsaKey.getModulus().bitLength() < MIN_RSA_BITS) {
                throw invalid();
            }
            return normalized;
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    public String validatePublicKey(String pem) {
        String normalized = normalize(pem, PUBLIC_KEY_PATTERN, "PUBLIC KEY");
        try {
            if (!(PemUtil.loadPublicKeyFromString(normalized) instanceof RSAPublicKey rsaKey)
                    || rsaKey.getModulus().bitLength() < MIN_RSA_BITS) {
                throw invalid();
            }
            return normalized;
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private String normalize(String pem, Pattern pattern, String label) {
        if (!StringUtils.hasText(pem)
                || pem.indexOf('\0') >= 0
                || pem.getBytes(StandardCharsets.UTF_8).length > MAX_PEM_BYTES) {
            throw invalid();
        }
        String withoutBom = pem.charAt(0) == '\ufeff' ? pem.substring(1) : pem;
        Matcher matcher = pattern.matcher(withoutBom);
        if (!matcher.matches()) {
            throw invalid();
        }
        String base64 = matcher.group(1).replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            if (der.length == 0) {
                throw invalid();
            }
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        StringBuilder result = new StringBuilder()
                .append("-----BEGIN ").append(label).append("-----\n");
        for (int index = 0; index < base64.length(); index += 64) {
            result.append(base64, index, Math.min(index + 64, base64.length())).append('\n');
        }
        return result.append("-----END ").append(label).append("-----\n").toString();
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
