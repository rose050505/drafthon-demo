package com.v2v.backend;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestHeader;

@Configuration
class OpenApiConfig {
    private static final String SESSION_TOKEN = "BearerAuth";
    private static final String WORKER_TOKEN = "X-V2V-AI-Token";
    private static final String CATALOG_TOKEN = "X-V2V-Catalog-Token";

    @Bean
    OpenAPI v2vApi() {
        var security = new Components()
            .addSecuritySchemes(SESSION_TOKEN, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .description("로그인 응답의 token 값을 입력하세요."))
            .addSecuritySchemes(WORKER_TOKEN, new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                .name(WORKER_TOKEN).description("AI 워커에 발급한 연동 토큰"))
            .addSecuritySchemes(CATALOG_TOKEN, new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                .name(CATALOG_TOKEN).description("공실·건물 등록 담당자에게 발급한 연동 토큰"));
        return new OpenAPI().components(security)
            .info(new Info().title("V2V Backend API").version("v1")
                .description("대구 동성로 공실 조회, 창업 분석, 학생 프로젝트 매칭 API"));
    }

    @Bean
    OperationCustomizer v2vEndpointSecurity() {
        return (operation, handler) -> {
            for (var parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(AuthenticationPrincipal.class)) {
                    if (parameter.getParameter().getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                        operation.addSecurityItem(new SecurityRequirement());
                    }
                    operation.addSecurityItem(new SecurityRequirement().addList(SESSION_TOKEN));
                }
                var header = parameter.getParameterAnnotation(RequestHeader.class);
                if (header != null && (WORKER_TOKEN.equals(header.name()) || CATALOG_TOKEN.equals(header.name()))) {
                    operation.addSecurityItem(new SecurityRequirement().addList(header.name()));
                    if (operation.getParameters() != null) {
                        operation.getParameters().removeIf(entry -> "header".equals(entry.getIn())
                            && header.name().equals(entry.getName()));
                    }
                }
            }
            return operation;
        };
    }
}
