package org.infi.nocode;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "verify.vue", matches = "true")
class VueBuildTest {
  @Test
  void buildsVueAndPreservesLastGoodVersionOnFailure() throws Exception {
    Path root = Path.of("tmp/verification/vue").toAbsolutePath();
    Files.createDirectories(root);
    var service =
        new ArtifactService(
            new ObjectMapper(),
            new PlatformProperties(
                root.resolve("out"),
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
    String version = UUID.randomUUID().toString();
    var artifact =
        new Artifact(
            "Vue test",
            List.of(
                new GeneratedFile(
                    "index.html",
                    "<html><body><div id='app'></div><script type='module'"
                        + " src='./src/main.js'></script></body></html>"),
                new GeneratedFile(
                    "src/main.js",
                    "import { createApp } from 'vue'; import App from './App.vue';"
                        + " createApp(App).mount('#app');"),
                new GeneratedFile(
                    "src/App.vue",
                    "<script setup>import { ref } from 'vue'; const"
                        + " count=ref(0)</script><template><button @click='count++'>计数"
                        + " {{count}}</button></template>")));
    Path site = service.save("1", version, artifact, CodeGenType.VUE_PROJECT);
    assertThat(site.resolve("index.html")).exists();
    String exampleId = "vue-" + UUID.randomUUID();
    String firstCopy = UUID.randomUUID().toString(), secondCopy = UUID.randomUUID().toString();
    service.useExample(exampleId, "vue prompt", "2", firstCopy, CodeGenType.VUE_PROJECT, () -> artifact);
    service.useExample(exampleId, "vue prompt", "3", secondCopy, CodeGenType.VUE_PROJECT,
        () -> { throw new AssertionError("Vue example must reuse cached code"); });
    Path firstSite = service.site("2", firstCopy, CodeGenType.VUE_PROJECT);
    Path secondSite = service.site("3", secondCopy, CodeGenType.VUE_PROJECT);
    try (var tree = Files.walk(firstSite)) {
      for (Path file : tree.filter(Files::isRegularFile).toList())
        assertThat(Files.readAllBytes(secondSite.resolve(firstSite.relativize(file))))
            .isEqualTo(Files.readAllBytes(file));
    }
    assertThat(service.source("3", "src/App.vue")).contains("count");
    var broken =
        new Artifact(
            "broken",
            List.of(
                new GeneratedFile(
                    "index.html", "<script type='module' src='./src/main.js'></script>"),
                new GeneratedFile("src/main.js", "import './missing.js'"),
                new GeneratedFile("src/App.vue", "<template>Broken</template>")));
    assertThatThrownBy(
            () -> service.save("1", UUID.randomUUID().toString(), broken, CodeGenType.VUE_PROJECT))
        .hasMessageContaining("构建失败");
    assertThat(service.current("1")).isEqualTo(version);
  }
}
