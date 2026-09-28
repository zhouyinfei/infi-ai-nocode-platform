package org.infi.nocode.config;

import org.infi.nocode.mapper.Store;
import org.springframework.boot.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 仅在配置账号不存在时初始化管理员，不自动提升已有账号权限。 */
@Component
public class AdminBootstrap implements ApplicationRunner {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AdminBootstrap.class);
  private final PlatformProperties p;
  private final Store s;
  private final PasswordEncoder encoder;

  public AdminBootstrap(PlatformProperties p, Store s, PasswordEncoder e) {
    this.p = p;
    this.s = s;
    encoder = e;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (p.adminAccount() == null
        || p.adminAccount().startsWith("YOUR_")
        || p.adminPassword() == null
        || p.adminPassword().startsWith("YOUR_")) return;
    if (p.adminPassword().length() < 8) throw new IllegalStateException("管理员密码至少8位");
    // Never promote an existing self-registered account automatically.
    if (s.userByAccount(p.adminAccount()).isEmpty()) {
      var user = s.register(p.adminAccount(), encoder.encode(p.adminPassword()));
      s.adminUser(user.id(), user.userName(), "admin");
    }
  }
}
