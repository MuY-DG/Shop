package org.muybaby.shopserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlSafetyTest {

    @Test
    void baseApplicationYamlDoesNotActivateDevProfileOrMockWechat() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties).doesNotContainKey("spring.profiles.active");
        assertThat(properties).containsEntry("shop.wechat.mini-program.mock-enabled", false);
    }
}
