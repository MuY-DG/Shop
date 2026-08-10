package org.muybaby.shopserver.logistics.provider;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class WechatShippingResponseBodyReader {

    private WechatShippingResponseBodyReader() {
    }

    static String readJson(ClientHttpResponse response, int maxResponseBytes) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RestClientException("WeChat shipping API returned a non-success HTTP status");
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

    static final class ResponseTooLargeException extends RestClientException {
        ResponseTooLargeException() {
            super("WeChat shipping API response exceeded the configured byte limit");
        }
    }
}
