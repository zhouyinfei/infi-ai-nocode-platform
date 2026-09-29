package org.infi.nocode.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final Set<String> PUBLIC_PATHS =
      Set.of(
          "/api/users/login",
          "/api/users/register",
          "/api/apps/featured",
          "/api/apps/examples");

  private static boolean isPublicPath(String uri) {
    if (PUBLIC_PATHS.contains(uri)) return true;
    // /api/apps/*/preview, /api/apps/*/files, /api/apps/*/source, /api/covers/*
    if (uri.startsWith("/api/apps/") && uri.endsWith("/preview")) return true;
    if (uri.startsWith("/api/apps/") && uri.endsWith("/files")) return true;
    if (uri.startsWith("/api/apps/") && uri.endsWith("/source")) return true;
    if (uri.startsWith("/api/covers/")) return true;
    return false;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .cors(Customizer.withDefaults())
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers((RequestMatcher) request -> isPublicPath(request.getRequestURI()))
              .permitAll()
            // 登录接口只在 Session 中保存 uid，未使用 Spring Security 的 Authentication。
            // 此处沿用同一身份来源；账号有效性、角色及资源权限仍由 AuthService 校验。
            .requestMatchers("/api/**").access((authentication, context) -> {
              var session = context.getRequest().getSession(false);
              return new AuthorizationDecision(
                  session != null && session.getAttribute("uid") != null);
            })
            .anyRequest().permitAll()
        )
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint((req, res, authException) -> {
              res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
              res.setContentType(MediaType.APPLICATION_JSON_VALUE);
              res.setCharacterEncoding("UTF-8");
              res.getWriter().write("{\"code\":401,\"message\":\"未登录\",\"data\":null}");
            })
        );
    return http.build();
  }
}
