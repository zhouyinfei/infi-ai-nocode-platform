package org.infi.nocode.service;

import jakarta.servlet.http.HttpServletRequest;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.User;
import org.springframework.stereotype.Service;

/** 集中校验登录状态、管理员角色及应用读写权限。 */
@Service
public class AuthService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AuthService.class);
  private final Store store;

  public AuthService(Store store) {
    this.store = store;
  }

  /** 每次读取当前用户，确保已删除账号无法继续使用旧会话。 */
  public User require(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null || session.getAttribute("uid") == null) {
      log.debug("Authentication denied: missing session identity");
      throw new BusinessException(401, "请先登录");
    }
    return store.user(session.getAttribute("uid").toString());
  }

  /** 要求有效登录且具备管理员角色。 */
  public User admin(HttpServletRequest request) {
    var user = require(request);
    if (!"admin".equals(user.userRole())) {
      log.debug("Administrator access denied: userId={}", user.id());
      throw new BusinessException(403, "需要管理员权限");
    }
    return user;
  }

  /** 仅允许应用创建者执行写操作。 */
  public void owner(String appId, String userId) {
    if (!store.app(appId).userId().equals(userId)) {
      log.debug("Application write denied: appId={}, userId={}", appId, userId);
      throw new BusinessException(403, "只能操作自己的应用");
    }
  }

  /** 创建者和管理员可读；其他登录用户仅可查看已经发布的精选应用。 */
  public void read(String appId, User user) {
    var app = store.app(appId);
    if (!app.userId().equals(user.id())
        && !"admin".equals(user.userRole())
        && !(app.priority() > 0 && app.deployedTime() != null)) {
      log.debug("Application read denied: appId={}, userId={}", appId, user.id());
      throw new BusinessException(403, "无权查看该应用");
    }
  }
}
