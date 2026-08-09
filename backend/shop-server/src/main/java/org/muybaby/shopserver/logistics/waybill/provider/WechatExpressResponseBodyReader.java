package org.muybaby.shopserver.logistics.waybill.provider;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WechatExpressResponseBodyReader {

    private WechatExpressResponseBodyReader() {
    }

    public static String readJson(ClientHttpResponse response, int maxResponseBytes) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RestClientException("WeChat express API returned a non-success HTTP status");
        }
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maxResponseBytes) {
            throw new ResponseTooLargeException();
        }
        byte[] body = response.getBody().readNBytes(maxResponseBytes + 1);
        if (body.length > maxResponseBytes) {
            throw new ResponseTooLargeException();
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public static final class ResponseTooLargeException extends RestClientException {

        private ResponseTooLargeException() {
            super("WeChat express API response exceeded the configured byte limit");
        }
    }
}
