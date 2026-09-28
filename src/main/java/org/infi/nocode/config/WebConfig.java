package org.infi.nocode.config;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** 配置密码编码、跨域和写请求校验头；业务身份权限由 AuthService 校验。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final PlatformProperties config;

  public WebConfig(PlatformProperties config) {
    this.config = config;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  org.springdoc.core.customizers.OpenApiCustomizer requestHeaderDocumentation() {
    return api -> {
      if (api.getPaths() != null)
        api.getPaths()
            .values()
            .forEach(
                path ->
                    path.readOperations()
                        .forEach(
                            operation ->
                                operation.addParametersItem(
                                    new io.swagger.v3.oas.models.parameters.Parameter()
                                        .in("header")
                                        .name("X-Nocode-Request")
                                        .description("浏览器写操作校验头，值为 1；Knife4j 联调时一并发送")
                                        .schema(
                                            new io.swagger.v3.oas.models.media.StringSchema()
                                                ._default("1")))));
    };
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(config.allowedOrigin())
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "X-Nocode-Request")
        .allowCredentials(true);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest req, HttpServletResponse res, Object handler)
                  throws IOException {
                res.setHeader("X-Content-Type-Options", "nosniff");
                // Cookie 会话的写请求必须携带自定义头，配合受限 CORS 防止跨站提交。
                if (!"GET".equals(req.getMethod())
                    && !"OPTIONS".equals(req.getMethod())
                    && !"1".equals(req.getHeader("X-Nocode-Request"))) {
                  res.setStatus(403);
                  res.setContentType("application/json;charset=UTF-8");
                  res.getWriter().write("{\"code\":403,\"message\":\"缺少请求校验头\",\"data\":null}");
                  return false;
                }
                return true;
              }
            })
        .addPathPatterns("/api/**");
  }
}
