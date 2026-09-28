package org.infi.nocode.ai.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.Path;
import java.util.*;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ToolManagerTest {
  @TempDir Path root;
  ObjectMapper json = new ObjectMapper();
  ArtifactService files;
  Artifact base = new Artifact("old", List.of(new GeneratedFile("index.html", "<h1>旧标题</h1>"),
      new GeneratedFile("style.css", "h1 { color: red; }")));
  ToolManager tools;

  @BeforeEach void setup() {
    var p = mock(PlatformProperties.class);
    when(p.outputDir()).thenReturn(root);
    when(p.maxFiles()).thenReturn(10);
    when(p.maxTotalBytes()).thenReturn(10000L);
    files = new ArtifactService(json, p);
    tools = new ToolManager(base, files, CodeGenType.MULTI_FILE, json);
  }

  @Test void patchPreservesOtherFilesAndOnlyActivatesAfterSave() throws Exception {
    String old = UUID.randomUUID().toString();
    files.save("1", old, base, CodeGenType.MULTI_FILE);
    assertThat(tools.file_list()).doesNotContain("color: red", "旧标题");
    tools.file_read("index.html");
    tools.file_modify("index.html", "旧标题", "新标题");
    tools.finish_edit("更新标题");
    assertThat(files.current("1")).isEqualTo(old);
    assertThat(files.source("1", "index.html")).contains("旧标题");
    String next = UUID.randomUUID().toString();
    files.save("1", next, tools.result(), CodeGenType.MULTI_FILE);
    assertThat(files.source("1", "index.html")).isEqualTo("<h1>新标题</h1>");
    assertThat(files.source("1", "style.css")).isEqualTo(base.files().get(1).content());
    assertThat(root.resolve("1").resolve(old).resolve("index.html")).hasContent("<h1>旧标题</h1>");
  }

  @Test void rejectsBlindEditsMissingAndAmbiguousMatchesWithoutChangingDraft() {
    assertThatThrownBy(() -> tools.file_write("index.html", "oops")).hasMessageContaining("先读取");
    assertThatThrownBy(() -> tools.file_delete("style.css")).hasMessageContaining("先读取");
    tools.file_read("index.html");
    assertThatThrownBy(() -> tools.file_modify("index.html", "不存在", "oops")).hasMessageContaining("未匹配");
    assertThatThrownBy(() -> tools.file_modify("index.html", "h1", "oops")).hasMessageContaining("多个匹配");
    assertThatThrownBy(() -> tools.file_delete("index.html")).hasMessageContaining("缺少 index.html");
    tools.finish_edit("unchanged");
    assertThat(tools.result().files()).isEqualTo(base.files());
  }

  @Test void supportsCreateAndDeleteAndRejectsUnsafeOrOversizedWrites() {
    tools.file_write("extra.js", "console.log('new')");
    tools.file_read("style.css");
    tools.file_delete("style.css");
    for (String path : List.of("../escape.js", "/outside.js", "package.json", "src/../../x.js"))
      assertThatThrownBy(() -> tools.file_write(path, "bad")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> tools.file_write("large.js", "x".repeat(10001))).hasMessageContaining("体积");
    tools.finish_edit("新增脚本，删除样式");
    assertThat(tools.result().files()).extracting(GeneratedFile::path).containsExactly("index.html", "extra.js");
  }

  @Test void toolSchemaAndErrorsAreUsableByModel() {
    assertThat(tools.specifications()).extracting(s -> s.name())
        .containsExactlyInAnyOrder("file_list", "file_read", "file_modify", "file_write", "file_delete", "finish_edit");
    assertThat(tools.specifications().stream().filter(s -> s.name().equals("file_modify")).findFirst().orElseThrow()
        .parameters().properties()).containsKeys("path", "oldText", "newText");
    assertThat(tools.execute(ToolExecutionRequest.builder().id("1").name("file_read").arguments("{}").build()))
        .contains("错误", "path");
    assertThat(tools.execute(ToolExecutionRequest.builder().id("2").name("file_write").arguments("not-json").build()))
        .contains("错误");
    assertThat(tools.finished()).isFalse();
  }
}
