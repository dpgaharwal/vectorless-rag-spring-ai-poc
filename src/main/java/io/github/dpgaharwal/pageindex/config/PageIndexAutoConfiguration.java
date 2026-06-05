package io.github.dpgaharwal.pageindex.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Spring Boot AutoConfiguration for pageindex-spring-ai.
 * Activated automatically via META-INF/spring/AutoConfiguration.imports.
 * Requires a ChatModel bean in the application context (provided by any Spring AI provider starter).
 */
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@EnableConfigurationProperties(PageIndexProperties.class)
@ComponentScan(basePackages = "io.github.dpgaharwal.pageindex")
public class PageIndexAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper pageIndexObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
