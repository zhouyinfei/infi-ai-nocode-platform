package org.infi.nocode.manager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Optional;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.App;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PreviewServerTest {
  @TempDir Path root;
  PreviewServer server;
  PlatformProperties properties;
  org.springframework.data.redis.core.ValueOperations<String, String> values;
  String origin;
  HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void setup() throws Exception {
    properties = mock(PlatformProperties.class);
    when(properties.previewPort()).thenReturn(0);
    when(properties.previewBaseUrl()).thenReturn("http://localhost:8124");
    when(properties.allowedOrigin()).thenReturn("http://localhost:5173");
    var files = mock(ArtifactService.class);
    var store = mock(Store.class);
    String version = "12345678-1234-1234-1234-123456789abc";
    Files.createDirectories(root.resolve(version));
    Files.writeString(root.resolve("current"), version);
    Files.writeString(root.resolve(version).resolve("index.html"), "<link href='style.css'>hello");
    Files.writeString(root.resolve(version).resolve("style.css"), "body {color: red}");
    for (String key : new String[] {"xGBewB", "4232f8ff193f4789afec6317dfeb9048"}) {
      when(files.deployRoot(key)).thenReturn(root);
      when(store.published(key)).thenReturn(Optional.of(
          new App("1", "test", null, "test", "html", key, null, 0, "1", null, null)));
    }
    var redis = mock(StringRedisTemplate.class);
    values = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("infi:nocode:preview:test-ticket")).thenReturn("1:" + version);
    when(store.app("1")).thenReturn(new App("1", "test", null, "test", "html", null, null, 0, "1", null, null));
    when(files.site("1", version, org.infi.nocode.model.enums.CodeGenType.HTML)).thenReturn(root.resolve(version));
    server = new PreviewServer(properties, files, store, redis);
    server.start();
    HttpServer http = (HttpServer) ReflectionTestUtils.getField(server, "server");
    origin = "http://127.0.0.1:" + http.getAddress().getPort();
  }

  @AfterEach
  void stop() { server.stop(); }

  HttpResponse<String> get(String path) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void editorSelectsAcrossOriginsAndRestoresNavigation() throws Exception {
    Files.writeString(root.resolve("12345678-1234-1234-1234-123456789abc/index.html"),
        "<!doctype html><html><head></head><body><a id='target' href='#next'>编辑这个标题</a></body></html>");
    try (var playwright = com.microsoft.playwright.Playwright.create();
        var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true))) {
      var page = browser.newPage();
      String editorUrl = origin.replace("127.0.0.1", "localhost") + "/editor";
      page.route(editorUrl, route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
          .setContentType("text/html").setBody("<!doctype html><html><body></body></html>")));
      page.navigate(editorUrl);
      page.setContent("<iframe src='" + origin + "/preview/test-ticket/index.html'></iframe>");
      page.evaluate("window.received = []; window.addEventListener('message', e => window.received.push(e.data))");
      var link = page.frameLocator("iframe").locator("#target");
      link.waitFor();
      String enable = "document.querySelector('iframe').contentWindow.postMessage({type:'infi:editor-mode', enabled:true}, '" + origin + "')";
      page.evaluate(enable);
      page.waitForFunction("window.received.some(e => e.type === 'infi:editor-ready')");
      link.click();
      page.waitForFunction("window.received.some(e => e.type === 'infi:editor-select')");
      assertThat(page.evaluate("window.received.find(e => e.type === 'infi:editor-select').element.selector")).isEqualTo("#target");
      assertThat(page.evaluate("window.received.find(e => e.type === 'infi:editor-select').element.text")).isEqualTo("编辑这个标题");
      assertThat(link.evaluate("el => location.hash")).isEqualTo("");
      assertThat(link.evaluate("el => document.compatMode")).isEqualTo("CSS1Compat");
      link.press("Escape");
      page.waitForFunction("window.received.some(e => e.type === 'infi:editor-clear')");
      page.evaluate("window.received = []");
      page.evaluate(enable.replace("enabled:true", "enabled:false"));
      page.waitForFunction("window.received.some(e => e.type === 'infi:editor-ready')");
      link.click();
      assertThat(link.evaluate("el => location.hash")).isEqualTo("#next");
      assertThat(page.evaluate("window.received.some(e => e.type === 'infi:editor-select')")).isEqualTo(false);
    }
  }

  @Test
  void sameOriginSitesKeepScriptsIsolatedAndModulesWorking() throws Exception {
    when(properties.previewBaseUrl()).thenReturn(origin);
    when(properties.allowedOrigin()).thenReturn(origin);
    assertThat(server.published("xGBewB")).isEqualTo(origin + "/xGBewB/");
    assertThat(get("/xGBewB/").headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("sandbox allow-scripts allow-forms").doesNotContain("allow-same-origin");
    Path site = root.resolve("12345678-1234-1234-1234-123456789abc");
    Files.writeString(site.resolve("index.html"),
        "<!doctype html><html><head></head><body><h1 id='target'>hello</h1>"
            + "<script type='module' src='./main.js'></script></body></html>");
    Files.writeString(site.resolve("main.js"), """
        document.querySelector('h1').textContent = 'module loaded';
        try { window.parent.document.body.dataset.escaped = 'yes'; } catch (e) {}
        try { localStorage.setItem('escaped', 'yes'); } catch (e) {}
        """);
    try (var playwright = com.microsoft.playwright.Playwright.create();
        var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true))) {
      var page = browser.newPage();
      page.route(origin + "/editor", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
          .setContentType("text/html").setBody("<!doctype html><html><body></body></html>")));
      page.navigate(origin + "/editor");
      page.evaluate("window.received = []; window.addEventListener('message', e => window.received.push({data:e.data, origin:e.origin}))");
      page.evaluate("""
          const frame = document.createElement('iframe');
          frame.sandbox = 'allow-scripts allow-forms';
          frame.src = '/preview/test-ticket/index.html';
          frame.onload = () => frame.contentWindow.postMessage({type:'infi:editor-mode', enabled:true}, '*');
          document.body.append(frame);
          """);
      page.waitForFunction("window.received.some(e => e.origin === 'null' && e.data.type === 'infi:editor-ready')");
      var target = page.frameLocator("iframe").locator("#target");
      com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(target).hasText("module loaded");
      target.click();
      page.waitForFunction("window.received.some(e => e.data.type === 'infi:editor-select')");
      assertThat(page.evaluate("document.body.dataset.escaped || localStorage.getItem('escaped')")).isNull();
      // Published pages opened directly retain the server-enforced sandbox.
      page.navigate(origin + "/xGBewB/");
      com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#target")).hasText("module loaded");
      assertThat(page.evaluate("() => { try { localStorage.getItem('test'); return false; } catch(e) { return e.name === 'SecurityError'; } }"))
          .isEqualTo(true);
    }
    when(properties.previewBaseUrl()).thenReturn("https://coze.ziyuanzz.online");
    when(properties.allowedOrigin()).thenReturn("https://coze.ziyuanzz.online:443/");
    assertThat(get("/xGBewB/").headers().firstValue("Content-Security-Policy").orElseThrow())
        .doesNotContain("allow-same-origin");
  }

  @Test
  void editorResourceDoesNotDependOnWorkerContextClassLoader() throws Exception {
    Thread worker = Thread.currentThread();
    ClassLoader original = worker.getContextClassLoader();
    try (var isolated = new java.net.URLClassLoader(new java.net.URL[0], null)) {
      worker.setContextClassLoader(isolated);
      assertThatThrownBy(() -> new org.springframework.core.io.ClassPathResource("preview-editor.js")
          .getInputStream()).isInstanceOf(java.io.FileNotFoundException.class);
      assertThat(PreviewServer.loadEditorScript()).contains("infi:editor-mode", "infi:editor-ready");
    } finally {
      worker.setContextClassLoader(original);
    }
  }

  @Test
  void editorBridgeOnlyAppearsInTicketedHtml() throws Exception {
    var preview = get("/preview/test-ticket/index.html");
    assertThat(preview.statusCode()).isEqualTo(200);
    assertThat(preview.body()).contains("infi:editor-mode", "infi:editor-select", "hello");
    assertThat(get("/xGBewB/").body()).doesNotContain("infi:editor");
    assertThat(get("/preview/test-ticket/style.css").body()).isEqualTo("body {color: red}");
    var expired = get("/preview/expired/index.html");
    assertThat(expired.statusCode()).isEqualTo(410);
    assertThat(expired.body()).contains("票据已过期");
    assertThat(expired.headers().firstValue("Cache-Control")).contains("no-store");
  }

  @Test
  void distinguishesMissingArtifactFromRedisFailure() throws Exception {
    Files.delete(root.resolve("12345678-1234-1234-1234-123456789abc/index.html"));
    var missing = get("/preview/test-ticket/index.html");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.body()).contains("预览文件不存在");
    when(values.get("infi:nocode:preview:test-ticket"))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("private details"));
    var unavailable = get("/preview/test-ticket/index.html");
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(unavailable.body()).contains("preview-ticket").doesNotContain("private details", "test-ticket");
  }

  @Test
  void shortAddressAndRelativeAssetsWork() throws Exception {
    assertThat(server.published("xGBewB")).isEqualTo("http://localhost:8124/xGBewB/");
    var redirect = get("/xGBewB?hello=1");
    assertThat(redirect.statusCode()).isEqualTo(308);
    assertThat(redirect.headers().firstValue("Location")).contains("/xGBewB/?hello=1");
    assertThat(get("/xGBewB/").body()).contains("hello");
    assertThat(get("/xGBewB/style.css").body()).contains("color: red");
    assertThat(get("/site/4232f8ff193f4789afec6317dfeb9048/index.html").statusCode()).isEqualTo(200);
    assertThat(get("/xGBewB/artifact.json").statusCode()).isEqualTo(404);
    assertThat(get("/xGBewB/%2e%2e/current").statusCode()).isEqualTo(404);
    assertThat(get("/xxxxxx/").statusCode()).isEqualTo(404);
  }
}
