package com.loopang.gateway.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Spring Cloud Gateway(WebFlux)에서 {@code classpath:/static/} 경로의 정적 리소스를
 * 명시적으로 라우팅하기 위한 설정.
 *
 * <p>WebFlux의 기본 정적 리소스 핸들러는 {@code RoutePredicateHandlerMapping}(order=1)
 * 보다 우선순위가 낮아서 Gateway 환경에서는 정적 파일이 서빙되지 않는다. {@link RouterFunction}
 * 빈을 직접 등록하면 {@code RouterFunctionMapping}(order=-1)의 높은 우선순위로 처리되어
 * 정적 파일이 정상 노출된다.</p>
 *
 * <p>도메인 서비스 라우팅({@code /api/**}, {@code /internal/**} 등)은 이 RouterFunction이
 * 매칭하지 않으므로 기존 Gateway routes가 그대로 동작한다.</p>
 */
@Configuration
public class StaticResourceConfig {

    @Bean
    public RouterFunction<ServerResponse> staticResourceRouter() {
        Resource indexHtml = new ClassPathResource("static/index.html");

        return RouterFunctions
                // "/" 루트는 index.html을 반환
                .route(GET("/"), req -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(BodyInserters.fromResource(indexHtml)))
                // 그 외 정적 자원(/index.html, /favicon.ico, /static/**)은 classpath:/static/ 매핑
                .andOther(RouterFunctions.resources("/**", new ClassPathResource("static/")));
    }
}
