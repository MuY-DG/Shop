package org.muybaby.shopserver.common.api;

public record ApiResponse<T>(int code, String msg, T data) {

    private static final int SUCCESS_CODE = 200;
    private static final String SUCCESS_MESSAGE = "success";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
