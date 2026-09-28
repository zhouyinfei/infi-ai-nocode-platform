package org.infi.nocode.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.infi.nocode.common.ApiResponse;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.manager.*;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.dto.Requests.*;
import org.infi.nocode.ratelimiter.RateLimiter;
import org.infi.nocode.service.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 应用工作台接口：先校验读写权限，再委托业务服务执行生成、预览和发布。 */
@RestController
@RequestMapping("/api/apps")
public class AppController {
  private final Store store;
  private final AuthService auth;
  private final AppService apps;
  private final TaskService tasks;
  private final ArtifactService files;
  private final PreviewServer preview;
  private final RateLimiter limits;
  private final ScreenshotManager screenshots;

  public AppController(
      Store s,
      AuthService a,
      AppService ap,
      TaskService t,
      ArtifactService f,
      PreviewServer p,
      RateLimiter l,
      ScreenshotManager ss) {
    store = s;
    auth = a;
    apps = ap;
    tasks = t;
    files = f;
    preview = p;
    limits = l;
    screenshots = ss;
  }

  @GetMapping("/examples")
  public ApiResponse<?> examples() {
    return ApiResponse.ok(ExampleCatalog.ALL);
  }

  @GetMapping("/featured")
  public ApiResponse<?> featured(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int pageSize) {
    var result = store.apps(null, true, query, page, pageSize);
    return ApiResponse.ok(
        new org.infi.nocode.common.PageResult<>(
            result.records().stream()
                .map(
                    a ->
                        Map.of(
                            "id",
                            a.id(),
                            "appName",
                            a.appName(),
                            "creatorName",
                            Objects.toString(store.user(a.userId()).userName(), "未命名用户"),
                            "cover",
                            Objects.toString(a.cover(), ""),
                            "codeGenType",
                            a.codeGenType(),
                            "createTime",
                            a.createTime(),
                            "url",
                            preview.published(a.deployKey())))
                .toList(),
            result.total(),
            page,
            pageSize));
  }

  @GetMapping
  public ApiResponse<?> mine(
      HttpServletRequest r,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int pageSize) {
    var result = store.apps(auth.require(r).id(), false, query, page, pageSize);
    return ApiResponse.ok(
        new org.infi.nocode.common.PageResult<>(
            result.records().stream()
                .map(a -> new AppListView(a,
                    a.deployedTime() != null && a.deployKey() != null
                        ? preview.published(a.deployKey()) : null))
                .toList(),
            result.total(), page, pageSize));
  }

  public record AppListView(
      @com.fasterxml.jackson.annotation.JsonUnwrapped org.infi.nocode.model.entity.App app,
      String url) {}

  @PostMapping
  public ApiResponse<?> create(@Valid @RequestBody CreateApp body, HttpServletRequest r) {
    String user = auth.require(r).id();
    limits.check("create:" + user, 10, 60);
    return ApiResponse.ok(apps.create(user, body.appName(), body.initPrompt()));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> get(@PathVariable String id, HttpServletRequest r) {
    auth.read(id, auth.require(r));
    return ApiResponse.ok(store.app(id));
  }

  @PutMapping("/{id}")
  public ApiResponse<?> edit(
      @PathVariable String id, @Valid @RequestBody EditApp body, HttpServletRequest r) {
    auth.owner(id, auth.require(r).id());
    store.edit(id, body.appName());
    return ApiResponse.ok(store.app(id));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<?> delete(@PathVariable String id, HttpServletRequest r) {
    auth.owner(id, auth.require(r).id());
    apps.delete(id);
    return ApiResponse.ok(true);
  }

  @GetMapping("/{id}/messages")
  public ApiResponse<?> messages(
      @PathVariable String id,
      @RequestParam(required = false) String before,
      @RequestParam(defaultValue = "30") int limit,
      HttpServletRequest r) {
    auth.read(id, auth.require(r));
    return ApiResponse.ok(store.messages(id, before, limit));
  }

  @PostMapping("/{id}/generate")
  public ApiResponse<?> generate(
      @PathVariable String id, @Valid @RequestBody Generate body, HttpServletRequest r) {
    String user = auth.require(r).id();
    auth.owner(id, user);
    limits.check("generate:" + user, 10, 60);
    return ApiResponse.ok(tasks.start(id, user, body.message(), body.requestId()));
  }

  @GetMapping("/{id}/task")
  public ApiResponse<?> latest(@PathVariable String id, HttpServletRequest r) {
    auth.owner(id, auth.require(r).id());
    return ApiResponse.ok(tasks.latest(id));
  }

  @GetMapping(value = "/{id}/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable String id, @PathVariable String taskId, HttpServletRequest r) {
    auth.owner(id, auth.require(r).id());
    return tasks.subscribe(taskId, id);
  }

  @GetMapping("/{id}/preview")
  public ApiResponse<?> preview(@PathVariable String id, HttpServletRequest r) {
    auth.read(id, auth.require(r));
    return ApiResponse.ok(Map.of("url", preview.ticket(id)));
  }

  @GetMapping("/{id}/files")
  public ApiResponse<?> files(@PathVariable String id, HttpServletRequest r) {
    auth.read(id, auth.require(r));
    return ApiResponse.ok(files.files(id));
  }

  @GetMapping("/{id}/source")
  public ApiResponse<?> source(
      @PathVariable String id, @RequestParam String path, HttpServletRequest r) {
    auth.read(id, auth.require(r));
    return ApiResponse.ok(files.source(id, path));
  }

  @PostMapping("/{id}/deploy")
  public ApiResponse<?> deploy(@PathVariable String id, HttpServletRequest r) throws Exception {
    String user = auth.require(r).id();
    auth.owner(id, user);
    limits.check("deploy:" + user, 5, 60);
    return ApiResponse.ok(apps.deploy(id));
  }

  @PostMapping("/{id}/screenshot")
  public ApiResponse<?> screenshot(@PathVariable String id, HttpServletRequest r) {
    String user = auth.require(r).id();
    auth.owner(id, user);
    limits.check("screenshot:" + user, 3, 60);
    return ApiResponse.ok(screenshots.capture(id, preview.ticket(id)));
  }
}
