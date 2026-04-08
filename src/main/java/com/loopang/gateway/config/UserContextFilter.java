package com.loopang.gateway.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class UserContextFilter implements GlobalFilter, Ordered {

//  private final Tracer tracer;
//  private static final String HEADER_TRACE_ID = "X-Trace-Id";
  private static final String HEADER_USER_UUID = "X-User-UUID";
  private static final String HEADER_EMAIL = "X-User-Email";
  private static final String HEADER_USER_NAME = "X-User-Name";
  private static final String HEADER_SLACK_ID = "X-User-Slack-Id";
  private static final String HEADER_ROLE = "X-User-Role";
  private static final String HEADER_ENABLED = "X-User-Enabled"; // 배열로 들어오는 String 처리
  private static final String HEADER_COMPANY_ID = "X-User-Company-Id";
  private static final String HEADER_HUB_ID = "X-User-Hub-Id";


  public UserContextFilter() {
//    this.tracer = tracer;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest cleanedRequest = exchange.getRequest().mutate()
        .headers(httpHeaders -> {
          httpHeaders.remove(HEADER_USER_UUID);
          httpHeaders.remove(HEADER_EMAIL);
          httpHeaders.remove(HEADER_USER_NAME);
          httpHeaders.remove(HEADER_SLACK_ID);
          httpHeaders.remove(HEADER_ROLE);
          httpHeaders.remove(HEADER_ENABLED);
          httpHeaders.remove(HEADER_COMPANY_ID);
          httpHeaders.remove(HEADER_HUB_ID);
        })
//        .header(HEADER_TRACE_ID, traceId)
        .build();
    ServerWebExchange mutatedExchange  = exchange.mutate().request(cleanedRequest).build();

    return exchange.getPrincipal()
        .cast(JwtAuthenticationToken.class)
        .map(jwtAuth -> {
          // 1. Keycloak 토큰 클레임 추출
          Map<String, Object> claims = jwtAuth.getTokenAttributes();
          String id = (String) claims.get("sub");
          String email = (String) claims.get("email");
          String name = (String) claims.get("name");
          String slackId = (String) claims.get("slack_id");
          String enabled = (String) claims.get("is_enabled");
          String companyId = (String) claims.get("companyId");
          String hubId = (String) claims.get("hubId");

          // role은 user-service가 Keycloak 사용자 attributes에 박는 top-level "role" 클레임에서 직접 추출한다.
          // user-service의 KeycloakIdentityProvider가 회원가입 시 ROLE_MASTER/HUB/DELIVERY/COMPANY 형식으로 저장한다.
          String role = (String) claims.get("role");

          log.debug("Global Filter - User Authenticated: {}", id);

          name = (name != null)
              ? URLEncoder.encode(name, StandardCharsets.UTF_8)
              : "";

          // 헤더 UserData 추가 (기존 헤더 초기화 후 신규 설정)
          ServerHttpRequest request  = mutatedExchange.getRequest().mutate()
              .header(HEADER_USER_UUID, id != null ? id : "")
              .header(HEADER_EMAIL, email != null ? email : "")
              .header(HEADER_USER_NAME, name)
              .header(HEADER_SLACK_ID, slackId != null ? slackId : "")
              .header(HEADER_ROLE, role != null ? role : "")
              .header(HEADER_ENABLED, enabled != null ? enabled : "")
              .header(HEADER_COMPANY_ID, companyId != null ? companyId : "")
              .header(HEADER_HUB_ID, hubId != null ? hubId : "")
              .build();

          return exchange.mutate().request(request).build();
        })
        .defaultIfEmpty(mutatedExchange) // 인증 안 된 경우(비로그인) 그대로 전달하여 App에서 null 처리 유도
        .flatMap(chain::filter);
  }

  @Override
  public int getOrder() {
    return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1;
  }
}