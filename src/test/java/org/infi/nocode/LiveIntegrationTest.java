package org.infi.nocode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.infi.nocode.ai.AiGateway;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.service.TaskService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfSystemProperty(named = "verify.integration", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.config.additional-location=optional:file:./src/main/resources/application-local.yml",
      "nocode.preview-port=18124",
      "nocode.preview-base-url=http://localhost:18124",
      "nocode.screenshot-enabled=false",
      "nocode.rate-limit-namespace=infi:nocode:verification:${random.uuid}:rate:",
      "spring.session.redis.namespace=infi:nocode:verification:${random.uuid}:session"
    })
class LiveIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired Store store;
  @Autowired TaskService tasks;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @Autowired org.infi.nocode.ratelimiter.RateLimiter limiter;
  @MockitoBean AiGateway ai;
  List<String> accounts = new ArrayList<>();

  @Test
  void redisRateLimiterRejectsExcessRequests() {
    String key = UUID.randomUUID().toString();
    limiter.check(key, 1, 60);
    assertThatThrownBy(() -> limiter.check(key, 1, 60))
        .isInstanceOf(org.infi.nocode.exception.BusinessException.class)
        .hasMessageContaining("频繁");
    limiter.check(UUID.randomUUID().toString(), 1, 60);
  }

  record Reply(int status, JsonNode body) {}

  HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  Reply call(HttpClient client, String path, String method, Object body) throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
            .header("Content-Type", "application/json")
            .header("X-Nocode-Request", "1")
            .timeout(Duration.ofSeconds(30));
    req.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), json.readTree(response.body()));
  }

  HttpClient register() throws Exception {
    String account = "verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    accounts.add(account);
    var c = client();
    assertThat(
            call(
                    c,
                    "/users/register",
                    "POST",
                    Map.of(
                        "userAccount",
                        account,
                        "userPassword",
                        "Test-only-1234",
                        "confirmPassword",
                        "Test-only-1234"))
                .status())
        .isEqualTo(200);
    assertThat(
            call(
                    c,
                    "/users/login",
                    "POST",
                    Map.of("userAccount", account, "userPassword", "Test-only-1234"))
                .status())
        .isEqualTo(200);
    return c;
  }

  @AfterEach
  void cleanup() {
    for (String account : accounts) {
      var ids = jdbc.queryForList("select id from `user` where userAccount=?", Long.class, account);
      for (Long id : ids) {
        jdbc.update("delete from chat_history where userId=?", id);
        jdbc.update("delete from app where userId=?", id);
        jdbc.update("delete from `user` where id=?", id);
      }
    }
  }

  @Test
  void realMysqlRedisSessionsOwnershipGenerationAndDeployment() throws Exception {
    var anonymous = client();
    assertThat(call(anonymous, "/users/me", "GET", null).status()).isEqualTo(401);
    var alice = register();
    var bob = register();
    assertThat(call(alice, "/admin/users", "GET", null).status()).isEqualTo(403);
    var created = call(alice, "/apps", "POST", Map.of("appName", "集成验证", "initPrompt", "简单单页展示页"));
    assertThat(created.status()).isEqualTo(200);
    String appId = created.body().path("data").path("id").asText();
    assertThat(call(bob, "/apps/" + appId, "GET", null).status()).isEqualTo(403);
    assertThat(call(bob, "/apps/" + appId, "DELETE", null).status()).isEqualTo(403);
    var artifact =
        new Artifact(
            "集成测试页面",
            List.of(
                new GeneratedFile(
                    "index.html",
                    "<!doctype html><html lang='zh'><body><h1>Integration OK</h1></body></html>")));
    when(ai.generate(
            any(org.infi.nocode.model.enums.CodeGenType.class), anyString(), anyString(), any()))
        .thenAnswer(
            i -> {
              java.util.function.Consumer<String> token = i.getArgument(3);
              token.accept("test");
              return json.writeValueAsString(artifact);
            });
    String request = UUID.randomUUID().toString();
    var generation =
        call(
            alice,
            "/apps/" + appId + "/generate",
            "POST",
            Map.of("message", "生成展示页", "requestId", request));
    assertThat(generation.status()).isEqualTo(200);
    String taskId = generation.body().path("data").path("id").asText();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (tasks.status(taskId, appId).state().equals("running") && System.nanoTime() < deadline)
      Thread.sleep(50);
    assertThat(tasks.status(taskId, appId).state()).isEqualTo("succeeded");
    var duplicate =
        call(
            alice,
            "/apps/" + appId + "/generate",
            "POST",
            Map.of("message", "生成展示页", "requestId", request));
    assertThat(duplicate.body().path("data").path("id").asText()).isEqualTo(taskId);
    verify(ai, times(1))
        .generate(
            any(org.infi.nocode.model.enums.CodeGenType.class), anyString(), anyString(), any());
    assertThat(call(alice, "/apps/" + appId + "/messages", "GET", null).body().path("data").size())
        .isEqualTo(2);
    String preview =
        call(alice, "/apps/" + appId + "/preview", "GET", null)
            .body()
            .path("data")
            .path("url")
            .asText();
    var html =
        anonymous.send(
            HttpRequest.newBuilder(URI.create(preview)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(html.statusCode()).isEqualTo(200);
    assertThat(html.body()).contains("Integration OK");
    assertThat(html.headers().firstValue("Content-Security-Policy")).isPresent();
    assertThat(
            anonymous
                .send(
                    HttpRequest.newBuilder(
                            URI.create(preview.replace("index.html", "artifact.json")))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(404);
    var deployed = call(alice, "/apps/" + appId + "/deploy", "POST", null);
    assertThat(deployed.status()).isEqualTo(200);
    String publicUrl = deployed.body().path("data").path("url").asText();
    assertThat(publicUrl).matches("http://localhost:18124/[A-Za-z0-9]{6}");
    assertThat(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(
                    HttpRequest.newBuilder(URI.create(publicUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(200);
    store.adminApp(appId, "精选验证", 99);
    assertThat(call(bob, "/apps/" + appId, "GET", null).status()).isEqualTo(200);
    assertThat(call(anonymous, "/apps/" + appId + "/messages", "GET", null).status())
        .isEqualTo(401);
    assertThat(
            call(
                    bob,
                    "/apps/" + appId + "/generate",
                    "POST",
                    Map.of("message", "unauthorized", "requestId", UUID.randomUUID().toString()))
                .status())
        .isEqualTo(403);
    assertThat(call(bob, "/apps/" + appId + "/deploy", "POST", null).status()).isEqualTo(403);
    var events =
        alice.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/apps/"
                            + appId
                            + "/tasks/"
                            + taskId
                            + "/events"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(events.body()).contains("event:status", "succeeded");
    doReturn("broken JSON")
        .when(ai)
        .generate(
            any(org.infi.nocode.model.enums.CodeGenType.class), anyString(), anyString(), any());
    var failed =
        call(
            alice,
            "/apps/" + appId + "/generate",
            "POST",
            Map.of("message", "修改页面", "requestId", UUID.randomUUID().toString()));
    String failureId = failed.body().path("data").path("id").asText();
    deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (tasks.status(failureId, appId).state().equals("running") && System.nanoTime() < deadline)
      Thread.sleep(50);
    assertThat(tasks.status(failureId, appId).state()).isEqualTo("failed");
    assertThat(
            anonymous
                .send(
                    HttpRequest.newBuilder(URI.create(preview)).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .body())
        .contains("Integration OK");
    assertThat(call(alice, "/apps/" + appId, "DELETE", null).status()).isEqualTo(200);
    assertThat(
            anonymous
                .send(
                    HttpRequest.newBuilder(URI.create(publicUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(404);
    assertThat(call(alice, "/users/logout", "POST", null).status()).isEqualTo(200);
    assertThat(call(alice, "/users/me", "GET", null).status()).isEqualTo(401);
  }

  @Test
  void interruptedTaskReleasesStaleLockAndDoesNotRestartAutomatically() throws Exception {
    var alice = register();
    String appId =
        call(alice, "/apps", "POST", Map.of("initPrompt", "单页展示页"))
            .body()
            .path("data")
            .path("id")
            .asText();
    String id = UUID.randomUUID().toString();
    var stale =
        new TaskService.Status(id, appId, "running", "generating", "old", java.time.Instant.now());
    redis
        .opsForValue()
        .set("infi:nocode:task:" + id, json.writeValueAsString(stale), Duration.ofMinutes(5));
    redis.opsForValue().set("infi:nocode:latest:" + appId, id, Duration.ofMinutes(5));
    redis.opsForValue().set("infi:nocode:lock:" + appId, "stale-token", Duration.ofMinutes(5));
    assertThat(tasks.latest(appId).state()).isEqualTo("failed");
    assertThat(redis.hasKey("infi:nocode:lock:" + appId)).isFalse();
    verifyNoInteractions(ai);
  }

  @Test
  void sameAppCannotGenerateOrDeleteWhileAJobOwnsTheLock() throws Exception {
    var alice = register();
    String appId =
        call(alice, "/apps", "POST", Map.of("initPrompt", "单页展示页"))
            .body()
            .path("data")
            .path("id")
            .asText();
    String lock = tasks.acquire(appId);
    try {
      assertThat(
              call(
                      alice,
                      "/apps/" + appId + "/generate",
                      "POST",
                      Map.of("message", "生成", "requestId", UUID.randomUUID().toString()))
                  .status())
          .isEqualTo(409);
      assertThat(call(alice, "/apps/" + appId, "DELETE", null).status()).isEqualTo(409);
    } finally {
      tasks.release(appId, lock);
    }
    assertThat(call(alice, "/apps/" + appId, "GET", null).status()).isEqualTo(200);
  }

  @Test
  void optimizeIsAuthenticatedAndAdminMessagesRequireAdminRole() throws Exception {
    assertThat(call(client(), "/prompts/optimize", "POST", Map.of("prompt", "a website")).status())
        .isEqualTo(401);
    var alice = register();
    when(ai.optimize("a website")).thenReturn("清晰的网站需求");
    assertThat(
            call(alice, "/prompts/optimize", "POST", Map.of("prompt", "a website"))
                .body()
                .path("data")
                .asText())
        .isEqualTo("清晰的网站需求");
    assertThat(call(alice, "/admin/messages", "GET", null).status()).isEqualTo(403);
    String userId = call(alice, "/users/me", "GET", null).body().path("data").path("id").asText();
    store.adminUser(userId, "Test admin", "admin");
    assertThat(call(alice, "/admin/messages?query=not-found", "GET", null).status()).isEqualTo(200);
  }
}
