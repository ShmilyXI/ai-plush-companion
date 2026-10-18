package zixuan.common.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger配置
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi deviceApi() {
        return GroupedOpenApi.builder()
                .group("device")
                .pathsToMatch("/device/**")
                .build();
    }

    @Bean
    public GroupedOpenApi agentApi() {
        return GroupedOpenApi.builder()
                .group("agent")
                .pathsToMatch("/agent/**")
                .build();
    }

    @Bean
    public GroupedOpenApi modelApi() {
        return GroupedOpenApi.builder()
                .group("models")
                .pathsToMatch("/models/**")
                .build();
    }

    @Bean
    public GroupedOpenApi oatApi() {
        return GroupedOpenApi.builder()
                .group("ota")
                .pathsToMatch("/ota/**", "/otaMag/**")
                .build();
    }

    @Bean
    public GroupedOpenApi timbreApi() {
        return GroupedOpenApi.builder()
                .group("timbre")
                .pathsToMatch("/ttsVoice/**")
                .build();
    }

    @Bean
    public GroupedOpenApi sysApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("user")
                .pathsToMatch("/user/**")
                .build();
    }

    @Bean
    public GroupedOpenApi configApi() {
        return GroupedOpenApi.builder()
                .group("config")
                .pathsToMatch("/config/**")
                .build();
    }

    @Bean
    public GroupedOpenApi knowledgeApi() {
        return GroupedOpenApi.builder()
                .group("knowledge")
                .pathsToMatch("/datasets/**")
                .build();
    }

    @Bean
    public GroupedOpenApi botApi() {
        return GroupedOpenApi.builder()
                .group("bot")
                .pathsToMatch("/api/v1/**")
                .build();
    }

    @Bean
    public GroupedOpenApi correctWordApi() {
        return GroupedOpenApi.builder()
                .group("correct-word")
                .pathsToMatch("/correct-word/**")
                .build();
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI().components(new Components()
                .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                        .scheme("bearer").bearerFormat("access-token").description("第一方用户 Bearer token"))
                .addSecuritySchemes("publicApiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name("Authorization")
                        .description("第三方调用使用 ApiKey <createdSecret>；原始 Key 只在创建时返回一次")))
                .info(new Info()
                .title("zixuan-esp32-manager-api")
                .description("zixuan-esp32-manager-api文档")
                .version("3.0")
                .termsOfService("https://127.0.0.1"));
    }
}
