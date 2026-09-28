package org.infi.nocode.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.infi.nocode.common.ApiResponse;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.dto.Requests.*;
import org.infi.nocode.ratelimiter.RateLimiter;
import org.infi.nocode.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** 用户注册、会话登录和资料维护；日志不包含账号、密码或会话标识。 */
@RestController
@RequestMapping("/api/users")
public class UserController {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(UserController.class);
  private final Store store;
  private final PasswordEncoder passwords;
  private final AuthService auth;
  private final RateLimiter limiter;

  public UserController(Store s, PasswordEncoder p, AuthService a, RateLimiter l) {
    store = s;
    passwords = p;
    auth = a;
    limiter = l;
  }

  @PostMapping("/register")
  public ApiResponse<?> register(@Valid @RequestBody Register r, HttpServletRequest req) {
    limiter.check("register:" + req.getRemoteAddr(), 5, 3600);
    if (!r.userPassword().equals(r.confirmPassword())) throw BusinessException.bad("两次密码不一致");
    if (r.userPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw BusinessException.bad("密码 UTF-8 长度不能超过72字节");
    var user = store.register(r.userAccount(), passwords.encode(r.userPassword()));
    log.info("User registered: userId={}", user.id());
    return ApiResponse.ok(user);
  }

  @PostMapping("/login")
  public ApiResponse<?> login(@Valid @RequestBody Login r, HttpServletRequest req) {
    limiter.check("login:" + req.getRemoteAddr(), 20, 300);
    var user =
        store
            .userByAccount(r.userAccount())
            .orElseThrow(() -> new BusinessException(401, "账号或密码错误"));
    if (!passwords.matches(r.userPassword(), user.userPassword()))
      throw new BusinessException(401, "账号或密码错误");
    // 登录成功后重建会话，防止沿用登录前的会话标识。
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    req.getSession(true).setAttribute("uid", user.id());
    log.info("User logged in: userId={}", user.id());
    return ApiResponse.ok(user);
  }

  @PostMapping("/logout")
  public ApiResponse<?> logout(HttpServletRequest req) {
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    return ApiResponse.ok(true);
  }

  @GetMapping("/me")
  public ApiResponse<?> me(HttpServletRequest req) {
    return ApiResponse.ok(auth.require(req));
  }

  @PutMapping("/me")
  public ApiResponse<?> profile(@Valid @RequestBody Profile r, HttpServletRequest req) {
    var user = auth.require(req);
    String avatar = r.userAvatar();
    if (avatar != null && !avatar.isBlank() && !avatar.matches("https?://[^\\s]+"))
      throw BusinessException.bad("头像必须是 HTTP 或 HTTPS 地址");
    store.profile(user.id(), r.userName(), avatar, r.userProfile());
    log.info("User profile updated: userId={}", user.id());
    return ApiResponse.ok(store.user(user.id()));
  }
}
