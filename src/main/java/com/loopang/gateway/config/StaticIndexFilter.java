package com.loopang.gateway.config;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway(WebFlux)에서 {@code /} 와 {@code /index.html} 요청을 가장 앞단에서
 * intercept 하여 {@code classpath:/static/index.html}을 직접 서빙하는 WebFilter.
 *
 * <p>기본 정적 리소스 핸들러는 {@code RoutePredicateHandlerMapping}보다 우선순위가 낮아서
 * Spring Cloud Gateway 환경에서는 정적 파일이 서빙되지 않는다. {@link RouterFunction}으로
 * 등록해도 같은 -1 order이라 매칭이 보장되지 않는다.</p>
 *
 * <p>{@link WebFilter}는 모든 요청에 가장 먼저 적용되며 {@link Ordered#HIGHEST_PRECEDENCE}로
 * 우선순위를 명시하면 어떤 핸들러보다도 먼저 응답을 작성할 수 있다. 이 필터는 {@code /} 또는
 * {@code /index.html} 요청만 직접 처리하고, 그 외 요청은 그대로 다음 핸들러로 위임한다.</p>
 */
@Component
public class StaticIndexFilter implements WebFilter, Ordered {

    private volatile byte[] cachedIndexHtml;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if ("/".equals(path) || "/index.html".equals(path)) {
            return serveIndex(exchange);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> serveIndex(ServerWebExchange exchange) {
        try {
            byte[] body = loadIndexHtml();
            ServerHttpResponse response = exchange.getResponse();
            response.getHeaders().setContentType(MediaType.TEXT_HTML);
            response.getHeaders().setContentLength(body.length);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        } catch (IOException e) {
            return Mono.error(e);
        }
    }

    private byte[] loadIndexHtml() throws IOException {
        if (cachedIndexHtml == null) {
            synchronized (this) {
                if (cachedIndexHtml == null) {
                    try (InputStream is = new ClassPathResource("static/index.html").getInputStream()) {
                        cachedIndexHtml = StreamUtils.copyToByteArray(is);
                    }
                }
            }
        }
        return cachedIndexHtml;
    }
}
