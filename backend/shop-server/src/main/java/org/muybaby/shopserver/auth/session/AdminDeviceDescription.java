package org.muybaby.shopserver.auth.session;

public record AdminDeviceDescription(
        String deviceName,
        String browser,
        String os
) {

    public static AdminDeviceDescription fromUserAgent(String userAgent) {
        String value = userAgent == null ? "" : userAgent;
        String os = operatingSystem(value);
        return new AdminDeviceDescription(deviceName(value, os), browser(value), os);
    }

    private static String operatingSystem(String value) {
        if (value.contains("iPhone")) {
            return "iOS";
        }
        if (value.contains("iPad")) {
            return "iPadOS";
        }
        if (value.contains("Android")) {
            return "Android";
        }
        if (value.contains("Windows")) {
            return "Windows";
        }
        if (value.contains("Mac OS X") || value.contains("Macintosh")) {
            return "macOS";
        }
        if (value.contains("Linux")) {
            return "Linux";
        }
        return "未知系统";
    }

    private static String browser(String value) {
        if (value.contains("MicroMessenger")) {
            return "微信";
        }
        if (value.contains("Edg/")) {
            return "Microsoft Edge";
        }
        if (value.contains("OPR/") || value.contains("Opera/")) {
            return "Opera";
        }
        if (value.contains("Firefox/")) {
            return "Firefox";
        }
        if (value.contains("Chrome/") || value.contains("CriOS/")) {
            return "Chrome";
        }
        if (value.contains("Safari/")) {
            return "Safari";
        }
        return "未知浏览器";
    }

    private static String deviceName(String value, String os) {
        if (value.contains("iPhone")) {
            return "iPhone";
        }
        if (value.contains("iPad")) {
            return "iPad";
        }
        if (value.contains("Android") && value.contains("Mobile")) {
            return "Android 手机";
        }
        if (value.contains("Android")) {
            return "Android 设备";
        }
        return switch (os) {
            case "Windows" -> "Windows 电脑";
            case "macOS" -> "Mac";
            case "Linux" -> "Linux 电脑";
            default -> "未知设备";
        };
    }
}
