package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.MessageResponse;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerServiceImageThumbnailMySqlTest {

    private static final long APP_USER_ID = 71_001L;
    private static final AuthenticatedPrincipal APP_USER = new AuthenticatedPrincipal(
            TokenKind.APP,
            APP_USER_ID,
            "thumbnail-mysql-app",
            List.of(),
            List.of()
    );

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("customer_service_thumbnail")
            .withUsername("shop_test")
            .withPassword("shop_test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CustomerServiceService customerServiceService;

    @Test
    void appImageIsClaimedAndThumbnailBecomesReadyOnMySql() throws Exception {
        jdbcClient.sql("""
                        insert into app_user (id, openid, nickname, status)
                        values (:id, 'thumbnail-mysql-openid', '缩略图测试用户', 'ACTIVE')
                        """)
                .param("id", APP_USER_ID)
                .update();

        MessageResponse message = customerServiceService.sendImageFromApp(
                APP_USER,
                new MockMultipartFile(
                        "file",
                        "mysql-thumbnail.png",
                        "image/png",
                        png(1440, 900)
                )
        );

        assertThat(message.messageType()).isEqualTo("IMAGE");
        assertThat(jdbcClient.sql("""
                        select thumbnail_status
                        from storage_asset
                        where id = (
                            select resource_id
                            from customer_service_message
                            where id = :messageId
                        )
                        """)
                .param("messageId", message.messageId())
                .query(String.class)
                .single()).isEqualTo("READY");
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(43, 111, 201));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }
}
