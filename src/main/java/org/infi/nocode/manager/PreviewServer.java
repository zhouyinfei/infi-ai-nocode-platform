package org.infi.nocode.manager;

import com.sun.net.httpserver.*;
import jakarta.annotation.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.enums.CodeGenType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 独立端口提供预览和发布文件；票据绑定版本，生成页面使用受限 CSP。 */
@Component
public class PreviewServer {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PreviewServer.class);
  private final PlatformProperties p;
  private final ArtifactService files;
  private final Store store;
  private final StringRedisTemplate redis;
  private HttpServer server;
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  public PreviewServer(PlatformProperties p, ArtifactService f, Store s, StringRedisTemplate r) {
    this.p = p;
    files = f;
    store = s;
    redis = r;
  }

  @PostConstruct
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", p.previewPort()), 0);
    server.createContext("/", this::serve);
    server.setExecutor(executor);
    server.start();
    log.info("Preview server started: port={}", server.getAddress().getPort());
  }

  /** 为当前产物版本签发短期预览票据，票据本身不写入日志。 */
  public String ticket(String appId) {
    String version = files.current(appId);
    String token = UUID.randomUUID().toString().replace("-", "");
    redis
        .opsForValue()
        .set("infi:nocode:preview:" + token, appId + ":" + version, Duration.ofHours(1));
    // 票据相当于临时访问凭证，日志仅记录应用及版本。
    log.debug("Preview ticket issued: appId={}, version={}", appId, version);
    return p.previewBaseUrl().replaceAll("/+$", "") + "/preview/" + token + "/index.html";
  }

  /** 根据发布标识构造公开访问地址。 */
  public String published(String key) {
    return p.previewBaseUrl().replaceAll("/+$", "") + "/" + key + "/";
  }

  private void serve(HttpExchange x) {
    String stage = "route";
    try {
      if (!Set.of("GET", "HEAD").contains(x.getRequestMethod())) {
        send(x, 405, "Method not allowed");
        return;
      }
      String path = x.getRequestURI().getPath();
      // Directory URLs keep relative CSS, JS and image URLs inside this site.
      if (path.matches("/[A-Za-z0-9]{6}|/[a-f0-9]{32}")) {
        store.published(path.substring(1)).orElseThrow();
        String query = x.getRequestURI().getRawQuery();
        x.getResponseHeaders().set("Location", path + "/" + (query == null ? "" : "?" + query));
        x.sendResponseHeaders(308, -1);
        return;
      }
      if (path.matches("/(?:[A-Za-z0-9]{6}|[a-f0-9]{32})/.*")) path = "/site" + path;
      String[] parts = path.split("/", 4);
      if (parts.length < 4) throw new IllegalArgumentException();
      Path root;
      CodeGenType type;
      if (parts[1].equals("preview")) {
        stage = "preview-ticket";
        String value = redis.opsForValue().get("infi:nocode:preview:" + parts[2]);
        if (value == null) {
          send(x, 410, "预览票据已过期或不存在，请点击工作台右上角的刷新预览。");
          return;
        }
        if (!value.matches("[0-9]+:[a-f0-9-]{36}"))
          throw new IllegalStateException("Invalid preview ticket data");
        String[] v = value.split(":", 2);
        stage = "preview-artifact";
        var app = store.app(v[0]);
        type = CodeGenType.of(app.codeGenType());
        root = files.site(app.id(), v[1], type);
      } else if (parts[1].equals("site")) {
        stage = "published-artifact";
        var app = store.published(parts[2]).orElseThrow();
        type = CodeGenType.of(app.codeGenType());
        Path base = files.deployRoot(parts[2]);
        String version = Files.readString(base.resolve("current")).strip();
        if (!version.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException();
        root = base.resolve(version);
      } else {
        throw new IllegalArgumentException();
      }
      root = root.toAbsolutePath().normalize();
      String relative = parts[3].isBlank() ? "index.html" : parts[3];
      if (relative.contains("\\")
          || relative.contains(":")
          || relative.startsWith("/")
          || Arrays.stream(relative.split("/")).anyMatch(s -> s.startsWith(".")))
        throw new IllegalArgumentException();
      Path target = root.resolve(relative).normalize();
      if (!target.startsWith(root)) throw new IllegalArgumentException();
      if (!Files.isRegularFile(target)
          && type == CodeGenType.VUE_PROJECT
          && !relative.substring(relative.lastIndexOf('/') + 1).contains("."))
        target = root.resolve("index.html");
      String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
      if (!name.matches(".*\\.(html|css|js|svg|png|jpg|jpeg|webp|ico|woff2?)$")
          || Files.isSymbolicLink(target)) throw new IllegalArgumentException();
      if (!Files.isRegularFile(target)) {
        log.warn("Preview resource missing: stage={}", stage);
        send(x, 404, parts[1].equals("preview")
            ? "预览文件不存在。请管理员检查 code_output 目录、当前版本文件及服务启动目录。"
            : "发布文件不存在。");
        return;
      }
      stage = "response-headers";
      String mime =
          name.endsWith("html")
              ? "text/html; charset=utf-8"
              : name.endsWith("css")
                  ? "text/css; charset=utf-8"
                  : name.endsWith("js")
                      ? "text/javascript; charset=utf-8"
                      : name.endsWith("svg")
                          ? "image/svg+xml"
                          : Objects.toString(
                              Files.probeContentType(target), "application/octet-stream");
      x.getResponseHeaders().set("Content-Type", mime);
      x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      x.getResponseHeaders().set("Cache-Control", "no-store");
      x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      // Opaque-origin sandboxed pages need CORS for local ES modules and fonts.
      x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      x.getResponseHeaders()
          .set(
              "Content-Security-Policy",
              "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self'"
                  + " 'unsafe-inline'; img-src 'self' data: https:; connect-src 'none'; frame-src"
                  + " 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; sandbox"
                  + " allow-scripts allow-forms"
                  + (sameOrigin(p.previewBaseUrl(), p.allowedOrigin()) ? "" : " allow-same-origin"));
      if (x.getRequestMethod().equals("HEAD")) {
        x.sendResponseHeaders(200, -1);
      } else {
        if (parts[1].equals("preview") && name.endsWith(".html")) {
          // Instrument only the ticketed preview response; artifacts and published sites stay clean.
          stage = "preview-editor-resource";
          String script = loadEditorScript();
          stage = "preview-html-read";
          String html = Files.readString(target);
          String bridge = "<script>" + script + "</script>";
          var head = java.util.regex.Pattern.compile("<head\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
          var doctype = java.util.regex.Pattern.compile("<!doctype\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
          int insertion = head.find() ? head.end() : doctype.find() ? doctype.end() : 0;
          html = html.substring(0, insertion) + bridge + html.substring(insertion);
          byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.sendResponseHeaders(200, bytes.length);
          x.getResponseBody().write(bytes);
        } else {
          x.sendResponseHeaders(200, Files.size(target));
          Files.copy(target, x.getResponseBody());
        }
      }
    } catch (Exception e) {
      // Do not log request URLs, ticket values, or exception messages containing private paths.
      boolean notFound = e instanceof IllegalArgumentException || e instanceof NoSuchElementException
          || (e instanceof org.infi.nocode.exception.BusinessException b && b.status() == 404);
      log.warn("Preview request failed: stage={}, category={}", stage, e.getClass().getSimpleName());
      try {
        send(x, notFound ? 404 : 503, notFound ? "页面或应用不存在。请刷新预览。"
            : "预览服务暂时不可用，请管理员检查后端日志。故障阶段：" + stage);
      } catch (Exception ignored) {
      }
    } finally {
      x.close();
    }
  }

  static String loadEditorScript() throws java.io.IOException {
    // HttpServer worker threads may have the system context loader, which cannot
    // see BOOT-INF/classes in an executable Spring Boot JAR. Use our defining loader.
    try (var resource = new org.springframework.core.io.ClassPathResource(
        "/preview-editor.js", PreviewServer.class).getInputStream()) {
      return new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private static boolean sameOrigin(String first, String second) {
    if (first == null || second == null) return true;
    URI a = URI.create(first), b = URI.create(second);
    int aPort = a.getPort() < 0 ? ("https".equalsIgnoreCase(a.getScheme()) ? 443 : 80) : a.getPort();
    int bPort = b.getPort() < 0 ? ("https".equalsIgnoreCase(b.getScheme()) ? 443 : 80) : b.getPort();
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost()) && aPort == bPort;
  }

  private void send(HttpExchange x, int status, String text) throws java.io.IOException {
    x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    x.getResponseHeaders().set("Cache-Control", "no-store");
    x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (x.getRequestMethod().equals("HEAD")) {
      x.sendResponseHeaders(status, -1);
      return;
    }
    x.sendResponseHeaders(status, bytes.length);
    x.getResponseBody().write(bytes);
  }

  @PreDestroy
  void stop() {
    if (server != null) server.stop(0);
    executor.shutdownNow();
    log.info("Preview server stopped");
  }
}
