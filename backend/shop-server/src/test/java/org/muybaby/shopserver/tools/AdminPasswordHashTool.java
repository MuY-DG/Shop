package org.muybaby.shopserver.tools;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

/**
 * Interactive helper for generating a BCrypt hash for an administrator password.
 *
 * <p>Run this class directly from the IDE. The generated hash can be used in a
 * controlled first-time Flyway seed or in a manual database password update.
 * The plaintext password is never written to application configuration.</p>
 */
public final class AdminPasswordHashTool {

    private static final int MINIMUM_PASSWORD_LENGTH = 10;
    private static final int BCRYPT_MAXIMUM_BYTES = 72;
    private static final Scanner STDIN = new Scanner(System.in);
    private static boolean visibleInputWarningPrinted;

    private AdminPasswordHashTool() {
    }

    public static void main(String[] args) {
        char[] password = null;
        char[] confirmation = null;

        try {
            password = readPassword("请输入新的管理员密码：");
            confirmation = readPassword("请再次输入密码：");

            validate(password, confirmation);

            String passwordHash = new BCryptPasswordEncoder().encode(new String(password));
            System.out.println();
            System.out.println("BCrypt password hash:");
            System.out.println(passwordHash);
        } finally {
            clear(password);
            clear(confirmation);
        }
    }

    private static char[] readPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] value = console.readPassword("%s", prompt);
            return value == null ? new char[0] : value;
        }

        // Some IDE run consoles do not provide System.console(); keep a fallback
        // so the tool remains usable, while making the visible-input risk explicit.
        if (!visibleInputWarningPrinted) {
            System.err.println("警告：当前控制台不支持隐藏输入，密码会显示在屏幕上。");
            visibleInputWarningPrinted = true;
        }
        System.err.print(prompt);
        return STDIN.hasNextLine() ? STDIN.nextLine().toCharArray() : new char[0];
    }

    private static void validate(char[] password, char[] confirmation) {
        if (password.length < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "管理员密码不能少于 " + MINIMUM_PASSWORD_LENGTH + " 个字符"
            );
        }
        if (new String(password).getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAXIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "管理员密码的 UTF-8 编码不能超过 " + BCRYPT_MAXIMUM_BYTES + " 字节"
            );
        }
        if (!Arrays.equals(password, confirmation)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
