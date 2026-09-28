package org.infi.nocode.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.infi.nocode.common.ApiResponse;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.dto.Requests.*;
import org.infi.nocode.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 管理端接口；所有入口都校验管理员身份，删除操作遵循关联逻辑删除规则。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AdminController.class);
  private final AuthService auth;
  private final Store store;
  private final AppService apps;

  public AdminController(AuthService a, Store s, AppService ap) {
    auth = a;
    store = s;
    apps = ap;
  }

  /** 校验管理员身份，按关键词、账号和姓名分页查询用户。 */
  @GetMapping("/users")
  public ApiResponse<?> users(
      HttpServletRequest r,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String account,
      @RequestParam(defaultValue = "") String name) {
    auth.admin(r);
    return ApiResponse.ok(store.users(query, page, pageSize, account, name));
  }

  /** 更新用户姓名和角色，禁止当前管理员取消自己的管理员角色。 */
  @PutMapping("/users/{id}")
  public ApiResponse<?> user(
      @PathVariable String id, @Valid @RequestBody AdminUser body, HttpServletRequest r) {
    var admin = auth.admin(r);
    store.user(id);
    if (admin.id().equals(id) && !body.userRole().equals("admin"))
      throw BusinessException.bad("不能取消自己的管理员角色");
    store.adminUser(id, body.userName(), body.userRole());
    log.info("Administrator updated user: actorId={}, userId={}, role={}", admin.id(), id, body.userRole());
    return ApiResponse.ok(true);
  }

  /** 校验管理员身份并在事务中删除用户，禁止删除自己或仍具有管理员角色的用户。 */
  @DeleteMapping("/users/{id}")
  @Transactional
  public ApiResponse<?> deleteUser(@PathVariable String id, HttpServletRequest r) {
    var admin = auth.admin(r);
    if (admin.id().equals(id)) throw BusinessException.bad("不能删除当前管理员");
    if (store.user(id).userRole().equals("admin")) throw BusinessException.bad("请先将该管理员降为普通用户");
    store.deleteUser(id);
    log.info("Administrator requested user deletion in transaction: actorId={}, userId={}", admin.id(), id);
    return ApiResponse.ok(true);
  }

  /** 校验管理员身份，按关键词、生成类型、优先级和用户分页查询应用。 */
  @GetMapping("/apps")
  public ApiResponse<?> apps(
      HttpServletRequest r,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String codeGenType,
      @RequestParam(required = false) Integer priority,
      @RequestParam(required = false) String userId) {
    auth.admin(r);
    return ApiResponse.ok(
        store.apps(null, false, query, page, pageSize, codeGenType, priority, userId));
  }

  /** 校验管理员身份及应用是否存在，更新应用名称和优先级。 */
  @PutMapping("/apps/{id}")
  public ApiResponse<?> app(
      @PathVariable String id, @Valid @RequestBody AdminApp body, HttpServletRequest r) {
    auth.admin(r);
    store.app(id);
    store.adminApp(id, body.appName(), body.priority());
    log.info("Administrator updated application: appId={}, priority={}", id, body.priority());
    return ApiResponse.ok(true);
  }

  /** 校验管理员身份，委托应用服务删除指定应用。 */
  @DeleteMapping("/apps/{id}")
  public ApiResponse<?> deleteApp(@PathVariable String id, HttpServletRequest r) {
    auth.admin(r);
    apps.delete(id);
    return ApiResponse.ok(true);
  }

  /** 校验管理员身份，按应用、用户、消息类型和时间范围等条件分页查询消息。 */
  @GetMapping("/messages")
  public ApiResponse<?> messages(
      HttpServletRequest r,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) String appId,
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) String messageType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false)
          @org.springframework.format.annotation.DateTimeFormat(
              iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
          java.time.LocalDateTime start,
      @RequestParam(required = false)
          @org.springframework.format.annotation.DateTimeFormat(
              iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
          java.time.LocalDateTime end) {
    auth.admin(r);
    return ApiResponse.ok(
        store.adminMessages(query, appId, userId, messageType, page, pageSize, start, end));
  }

  /** 校验管理员身份，删除指定消息。 */
  @DeleteMapping("/messages/{id}")
  public ApiResponse<?> deleteMessage(@PathVariable String id, HttpServletRequest r) {
    auth.admin(r);
    store.deleteMessage(id);
    log.info("Administrator deleted message: messageId={}", id);
    return ApiResponse.ok(true);
  }
}
