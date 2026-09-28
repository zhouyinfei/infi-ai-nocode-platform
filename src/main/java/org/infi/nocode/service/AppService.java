package org.infi.nocode.service;

import java.util.*;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.generator.TypeRouter;
import org.infi.nocode.manager.*;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.App;
import org.infi.nocode.model.enums.CodeGenType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 处理应用创建、事务删除与发布，协调产物、任务锁和封面截图。 */
@Service
public class AppService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AppService.class);
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
  private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private final Store store;
  private final TypeRouter router;
  private final TaskService tasks;
  private final ArtifactService files;
  private final PreviewServer preview;
  private final ScreenshotManager screenshots;

  public AppService(
      Store s,
      TypeRouter r,
      TaskService t,
      ArtifactService f,
      PreviewServer p,
      ScreenshotManager ss) {
    store = s;
    router = r;
    tasks = t;
    files = f;
    preview = p;
    screenshots = ss;
  }

  /** 优先匹配标准示例，否则根据需求路由类型并创建应用。 */
  public App create(String userId, String name, String prompt) {
    var type = ExampleCatalog.match(prompt)
        .map(example -> CodeGenType.of(example.codeGenType()))
        .orElseGet(() -> router.route(prompt));
    var app = store.create(
        name == null || name.isBlank() ? prompt.substring(0, Math.min(prompt.length(), 24)) : name,
        prompt,
        type.value(),
        userId);
    log.info("Application creation completed: userId={}, type={}", userId, type);
    return app;
  }

  /** 在事务内删除应用；事务完成后释放应用锁。 */
  @Transactional
  public void delete(String id) {
    String token = tasks.acquire(id);
    // 必须等事务提交或回滚后再释放锁，避免其他操作读到删除前的数据。
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                log.info("Application deletion transaction completed: appId={}, status={}", id, status);
                tasks.release(id, token);
              }
            });
    store.deleteApp(id);
  }

  /** 持有应用锁发布版本；元数据更新失败时恢复原发布指针，截图失败不撤销发布。 */
  public Map<String, String> deploy(String id) throws Exception {
    long started = System.nanoTime();
    String token = tasks.acquire(id);
    try {
      log.info("Deployment started: appId={}", id);
      var app = store.app(id);
      String key =
          app.deployKey() != null && app.deployKey().matches("[A-Za-z0-9]{6}")
              ? app.deployKey() : newDeployKey();
      var target = files.publish(id, key, CodeGenType.of(app.codeGenType()));
      java.nio.file.Path pointer = files.deployRoot(key).resolve("current");
      String previous =
          java.nio.file.Files.exists(pointer) ? java.nio.file.Files.readString(pointer) : null;
      // 先准备完整发布目录，再切换指针；数据库写入失败则恢复原指针。
      files.activateDeploy(key, target.getFileName().toString());
      try {
        store.deployed(id, key);
      } catch (Exception e) {
        log.warn("Deployment metadata failed; restoring pointer: appId={}, category={}",
            id, e.getClass().getSimpleName());
        if (previous != null) files.atomicWrite(pointer, previous);
        else java.nio.file.Files.deleteIfExists(pointer);
        throw e;
      }
      log.info("Deployment activated: appId={}, version={}, elapsedMs={}",
          id, target.getFileName(), (System.nanoTime() - started) / 1_000_000);
      String url = preview.published(key);
      String screenshot = screenshots.capture(id, url);
      return Map.of("url", url, "message", screenshot);
    } catch (Exception e) {
      log.warn("Deployment failed: appId={}, category={}", id, e.getClass().getSimpleName());
      throw e;
    } finally {
      tasks.release(id, token);
    }
  }

  private String newDeployKey() {
    for (int attempt = 0; attempt < 100; attempt++) {
      StringBuilder key = new StringBuilder(6);
      for (int i = 0; i < 6; i++) key.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      String candidate = key.toString();
      if (store.published(candidate).isEmpty()
          && !java.nio.file.Files.exists(files.deployRoot(candidate))) return candidate;
    }
    throw new IllegalStateException("无法分配发布地址，请重试");
  }
}
