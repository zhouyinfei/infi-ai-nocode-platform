package org.infi.nocode.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.infi.nocode.ai.AiGateway;
import org.infi.nocode.common.ApiResponse;
import org.infi.nocode.model.dto.Requests.Optimize;
import org.infi.nocode.ratelimiter.RateLimiter;
import org.infi.nocode.service.AuthService;
import org.springframework.web.bind.annotation.*;

/** 登录用户的提示词优化入口；按用户限流以控制模型调用频率。 */
@RestController
@RequestMapping("/api/prompts")
public class PromptController {
  private final AiGateway ai;
  private final AuthService auth;
  private final RateLimiter limiter;

  public PromptController(AiGateway ai, AuthService auth, RateLimiter limiter) {
    this.ai = ai;
    this.auth = auth;
    this.limiter = limiter;
  }

  /** 校验登录状态和调用频率，使用人工智能模型优化用户提交的提示词。 */
  @PostMapping("/optimize")
  public ApiResponse<String> optimize(
      @Valid @RequestBody Optimize body, HttpServletRequest request) {
    String user = auth.require(request).id();
    limiter.check("optimize:" + user, 5, 60);
    return ApiResponse.ok(ai.optimize(body.prompt()));
  }
}
