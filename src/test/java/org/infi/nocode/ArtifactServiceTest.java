package org.infi.nocode;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ArtifactServiceTest {
  @TempDir Path root;
  ArtifactService files;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setup() {
    files =
        new ArtifactService(
            json,
            new PlatformProperties(
                root.resolve("output"),
                root.resolve("deploy"),
                root.resolve("shots"),
                0,
                "http://localhost:8124",
                "http://localhost:5173",
                Duration.ofMinutes(10),
                4,
                60,
                2000000,
                "node",
                Path.of("builder"),
                false,
                "chrome",
                null,
                null,
                null));
  }

  @Test
  void repairsMalformedOutputOnceAndValidatesReplacement() throws Exception {
    String valid = json.writeValueAsString(new Artifact("商城", List.of(
        new GeneratedFile("index.html", "<div id=\"app\"></div>"),
        new GeneratedFile("src/main.js", "import App from './App.vue'"),
        new GeneratedFile("src/App.vue", "<template>购物车</template>"))));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    String truncated = valid.substring(0, valid.length() - 4);
    var result = files.parseOrRepair(truncated, CodeGenType.VUE_PROJECT, raw -> {
      assertThat(raw).isEqualTo(truncated);
      calls.incrementAndGet();
      return valid;
    });
    assertThat(result.files()).hasSize(3);
    assertThat(calls.get()).isEqualTo(1);
    files.parseOrRepair(valid, CodeGenType.VUE_PROJECT, raw -> {
      throw new AssertionError("valid output needs no repair");
    });
    assertThatThrownBy(() -> files.parseOrRepair(truncated, CodeGenType.VUE_PROJECT, raw -> {
      calls.incrementAndGet();
      return truncated;
    })).isInstanceOf(ArtifactService.InvalidArtifactJson.class);
    assertThat(calls.get()).isEqualTo(2);
    assertThatThrownBy(() -> files.parseOrRepair(truncated, CodeGenType.VUE_PROJECT,
        raw -> valid.replace("src/App.vue", "../App.vue")))
        .isInstanceOf(BusinessException.class).hasMessageContaining("非法文件路径");
  }

  @Test
  void repairsMissingVueFilesOnceAndRejectsIncompleteOrUnsafeReplacement() throws Exception {
    var required = List.of(
        new GeneratedFile("index.html", "<div id=\"app\"></div>"),
        new GeneratedFile("src/main.ts", "import App from './App.vue'"),
        new GeneratedFile("src/App.vue", "<template>商城</template>"));
    String complete = json.writeValueAsString(new Artifact("商城", required));
    for (int missing = 0; missing < required.size(); missing++) {
      var partial = new ArrayList<>(required);
      partial.remove(missing);
      String incomplete = json.writeValueAsString(new Artifact("商城", partial));
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      assertThat(files.parseOrRepair(incomplete, CodeGenType.VUE_PROJECT, raw -> {
        assertThat(raw).isEqualTo(incomplete);
        calls.incrementAndGet();
        return complete;
      }).files()).containsExactlyElementsOf(required);
      assertThat(calls.get()).isEqualTo(1);
      assertThatThrownBy(() -> files.parseOrRepair(incomplete, CodeGenType.VUE_PROJECT, raw -> {
        calls.incrementAndGet();
        return incomplete;
      })).isInstanceOf(ArtifactService.MissingRequiredFiles.class);
      assertThat(calls.get()).isEqualTo(2);
      assertThatThrownBy(() -> files.parseOrRepair(incomplete, CodeGenType.VUE_PROJECT,
          raw -> complete.replace("src/App.vue", "../App.vue")))
          .isInstanceOf(BusinessException.class).hasMessageContaining("非法文件路径");
    }
  }

  @Test
  void doesNotRepairUnsafePathsAndHandlesNullOutput() {
    assertThatThrownBy(() -> files.parseOrRepair(
        "{\"files\":[{\"path\":\"../index.html\",\"content\":\"x\"}]}",
        CodeGenType.HTML, raw -> { throw new AssertionError("must reject unsafe paths"); }))
        .isInstanceOf(BusinessException.class).hasMessageContaining("非法文件路径");
    assertThatThrownBy(() -> files.parse("null", CodeGenType.HTML))
        .isInstanceOf(ArtifactService.InvalidArtifactJson.class);
    assertThatThrownBy(() -> files.parse("{\"files\":[null]}", CodeGenType.HTML))
        .isInstanceOf(BusinessException.class).hasMessageContaining("生成文件不能为空");
  }

  @Test
  void rejectsTruncatedOrRepeatedJsonAfterContinuation() throws Exception {
    String valid = json.writeValueAsString(
        new Artifact("ok", List.of(new GeneratedFile("index.html", "<html></html>"))));
    assertThat(files.parse(valid, CodeGenType.HTML).files()).hasSize(1);
    for (String invalid : List.of(valid.substring(0, valid.length() - 2), valid + valid,
        valid + " extra explanation")) {
      assertThatThrownBy(() -> files.parse(invalid, CodeGenType.HTML))
          .isInstanceOf(BusinessException.class).hasMessageContaining("完整文件 JSON");
    }
  }

  @Test
  void exampleSurvivesRestartAndUserEdits() throws Exception {
    var artifact = new Artifact("example", List.of(new GeneratedFile("index.html", "shared")));
    String first = UUID.randomUUID().toString();
    files.useExample("portfolio-v1", "prompt", "1", first, CodeGenType.HTML, () -> artifact);
    files.save("1", UUID.randomUUID().toString(),
        new Artifact("edited", List.of(new GeneratedFile("index.html", "private edit"))), CodeGenType.HTML);
    setup(); // New service instance, same persistent output directory.
    files.useExample("portfolio-v1", "prompt", "2", UUID.randomUUID().toString(), CodeGenType.HTML,
        () -> { throw new AssertionError("cached example must not call AI again"); });
    assertThat(files.source("2", "index.html")).isEqualTo("shared");
    assertThat(files.source("1", "index.html")).isEqualTo("private edit");
  }

  @Test
  void concurrentUsersGenerateExampleOnlyOnce() throws Exception {
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 1; i <= 6; i++) {
        String id = Integer.toString(i);
        futures.add(executor.submit(() -> {
          start.await();
          return files.useExample("portfolio-v1", "prompt", id, UUID.randomUUID().toString(),
              CodeGenType.HTML, () -> {
                count.incrementAndGet();
                return new Artifact("example", List.of(new GeneratedFile("index.html", "shared")));
              });
        }));
      }
      start.countDown();
      for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(count.get()).isEqualTo(1);
    for (int i = 1; i <= 6; i++) assertThat(files.source("" + i, "index.html")).isEqualTo("shared");
  }

  @Test
  void failedExampleCanRetryAndChangedPromptHasSeparateCache() throws Exception {
    assertThatThrownBy(() -> files.useExample("portfolio-v1", "prompt", "1",
        UUID.randomUUID().toString(), CodeGenType.HTML,
        () -> new Artifact("bad", List.of(new GeneratedFile("other.html", "missing entry")))))
        .isInstanceOf(BusinessException.class);
    files.useExample("portfolio-v1", "prompt", "2", UUID.randomUUID().toString(), CodeGenType.HTML,
        () -> new Artifact("ok", List.of(new GeneratedFile("index.html", "original"))));
    files.useExample("portfolio-v1", "updated prompt", "3", UUID.randomUUID().toString(), CodeGenType.HTML,
        () -> new Artifact("new", List.of(new GeneratedFile("index.html", "updated"))));
    assertThat(files.source("2", "index.html")).isEqualTo("original");
    assertThat(files.source("3", "index.html")).isEqualTo("updated");
  }

  @Test
  void rejectsTraversalAndExecutableConfig() {
    for (String name :
        List.of(
            "../x.html",
            "/x.html",
            "C:/x.html",
            "a\\x.html",
            "src/../../x.js",
            "package.json",
            "vite.config.js",
            "src/.env",
            "con.html"))
      assertThatThrownBy(() -> files.validatePath(name, CodeGenType.VUE_PROJECT))
          .isInstanceOf(BusinessException.class);
  }

  @Test
  void requiresTypeSpecificEntryAndUniqueFiles() throws Exception {
    var dup =
        new Artifact(
            "ok",
            List.of(new GeneratedFile("index.html", "a"), new GeneratedFile("INDEX.html", "b")));
    assertThatThrownBy(() -> files.parse(json.writeValueAsString(dup), CodeGenType.MULTI_FILE))
        .isInstanceOf(BusinessException.class);
    var invalid = new Artifact("ok", List.of(new GeneratedFile("index.html", "a")));
    assertThatThrownBy(() -> files.parse(json.writeValueAsString(invalid), CodeGenType.VUE_PROJECT))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void previewAndPublishedVersionStayIndependent() throws Exception {
    String v1 = UUID.randomUUID().toString(),
        v2 = UUID.randomUUID().toString(),
        key = "xGBewB";
    files.save(
        "1",
        v1,
        new Artifact("v1", List.of(new GeneratedFile("index.html", "first"))),
        CodeGenType.HTML);
    Path published = files.publish("1", key, CodeGenType.HTML);
    files.activateDeploy(key, v1);
    files.save(
        "1",
        v2,
        new Artifact("v2", List.of(new GeneratedFile("index.html", "second"))),
        CodeGenType.HTML);
    assertThat(Files.readString(published.resolve("index.html"))).isEqualTo("first");
    assertThat(files.current("1")).isEqualTo(v2);
    assertThat(Files.readString(files.deployRoot(key).resolve("current"))).isEqualTo(v1);
    assertThat(Files.exists(published.resolve("artifact.json"))).isFalse();
  }
}
