package org.infi.nocode.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.infi.nocode.ai.AiGateway;
import org.infi.nocode.ai.tool.ToolManager;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.*;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;

class TaskServiceEditingTest {
  @TempDir Path root;
  ArtifactService files;
  AiGateway ai;
  Store store;
  TaskService tasks;
  String old;

  @BeforeEach void setup() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var p = mock(PlatformProperties.class);
    when(p.outputDir()).thenReturn(root);
    when(p.maxFiles()).thenReturn(10);
    when(p.maxTotalBytes()).thenReturn(10000L);
    when(p.maxConcurrentTasks()).thenReturn(1);
    files = new ArtifactService(json, p);
    old = UUID.randomUUID().toString();
    files.save("1", old, new Artifact("old", List.of(new GeneratedFile("index.html", "old title"),
        new GeneratedFile("style.css", "PRIVATE_SOURCE_SENTINEL"))), CodeGenType.MULTI_FILE);
    store = mock(Store.class);
    when(store.app("1")).thenReturn(new App("1", "test", null, "initial request", "multi_file", null, null, 0, "1", null, null));
    when(store.messages("1", null, 12)).thenReturn(List.of(
        new ChatMessage("2", "HISTORICAL_FULL_CODE", "ai", "1", "1", null),
        new ChatMessage("1", "prior user request", "user", "1", "1", null)));
    ai = mock(AiGateway.class);
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    tasks = new TaskService(store, files, ai, redis, p, json);
  }

  @AfterEach void close() { tasks.close(); }

  void run() {
    ReflectionTestUtils.invokeMethod(tasks, "run",
        new TaskService.Status(UUID.randomUUID().toString(), "1", "running", "queued", "", Instant.now()),
        "1", "change title", "lock");
  }

  @Test void routesExistingAppThroughToolsWithoutLeakingSourceIntoPrompt() {
    when(ai.edit(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
      String context = invocation.getArgument(2);
      assertThat(context).contains("prior user request", "change title")
          .doesNotContain("PRIVATE_SOURCE_SENTINEL", "HISTORICAL_FULL_CODE", "old title");
      ToolManager tools = invocation.getArgument(3);
      tools.file_read("index.html");
      tools.file_modify("index.html", "old title", "new title");
      tools.finish_edit("更新标题");
      return tools.result();
    });
    run();
    verify(ai, never()).generate(any(), anyString(), anyString(), any());
    assertThat(files.current("1")).isNotEqualTo(old);
    assertThat(files.source("1", "index.html")).isEqualTo("new title");
    assertThat(files.source("1", "style.css")).isEqualTo("PRIVATE_SOURCE_SENTINEL");
    verify(store).addMessage(eq("1"), eq("1"), eq("ai"), argThat(text ->
        text.contains("更新标题") && !text.contains("PRIVATE_SOURCE_SENTINEL") && !text.contains("new title")));
  }

  @Test void failedToolSessionKeepsCurrentVersion() {
    when(ai.edit(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
      ToolManager tools = invocation.getArgument(3);
      tools.file_read("index.html");
      tools.file_modify("index.html", "old title", "unfinished");
      throw new org.infi.nocode.exception.BusinessException(502, "模拟中途失败");
    });
    run();
    assertThat(files.current("1")).isEqualTo(old);
    assertThat(files.source("1", "index.html")).isEqualTo("old title");
    verify(store).addMessage("1", "1", "ai", "生成未完成：模拟中途失败");
  }
}
