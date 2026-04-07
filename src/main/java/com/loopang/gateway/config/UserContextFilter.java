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

          // role은 Keycloak의 realm_access.roles 배열에서 추출한다.
          // Keycloak이 발행하는 JWT는 top-level "role" 클레임이 없고
          // realm_access: { roles: [...] } 형태로 저장하기 때문이다.
          String role = extractRoleFromRealmAccess(claims);

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

  /**
   * Keycloak JWT의 {@code realm_access.roles} 배열에서 우리 시스템의 단일 role을 추출한다.
   *
   * <p>Keycloak이 기본으로 발행하는 JWT는 top-level {@code role} 클레임이 없고
   * 대신 다음과 같은 구조를 사용한다:
   * <pre>
   * "realm_access": {
   *   "roles": ["MASTER", "default-roles-my-realm", "offline_access", ...]
   * }
   * </pre>
   *
   * <p>우리 시스템에서 사용하는 4개 role(MASTER/HUB/DELIVERY/COMPANY) 중 하나를
   * 골라 {@code ROLE_} 프리픽스를 붙여 반환한다. 우선순위는 MASTER > HUB > DELIVERY > COMPANY.
   * 매칭되는 role이 없으면 빈 문자열 반환.</p>
   */
  @SuppressWarnings("unchecked")
  private String extractRoleFromRealmAccess(Map<String, Object> claims) {
    Object realmAccessObj = claims.get("realm_access");
    if (!(realmAccessObj instanceof Map)) {
      return "";
    }
    Map<String, Object> realmAccess = (Map<String, Object>) realmAccessObj;

    Object rolesObj = realmAccess.get("roles");
    if (!(rolesObj instanceof java.util.List<?> roles)) {
      return "";
    }

    if (roles.contains("MASTER")) {
      return "ROLE_MASTER";
    } else if (roles.contains("HUB")) {
      return "ROLE_HUB";
    } else if (roles.contains("DELIVERY")) {
      return "ROLE_DELIVERY";
    } else if (roles.contains("COMPANY")) {
      return "ROLE_COMPANY";
    }
    return "";
  }
}