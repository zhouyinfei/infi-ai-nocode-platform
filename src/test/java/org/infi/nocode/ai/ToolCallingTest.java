package org.infi.nocode.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.infi.nocode.ai.tool.ToolManager;
import org.infi.nocode.config.*;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.api.Test;

class ToolCallingTest {
  ToolManager tools() {
    var p = mock(PlatformProperties.class);
    when(p.maxFiles()).thenReturn(10);
    when(p.maxTotalBytes()).thenReturn(10000L);
    var json = new ObjectMapper();
    return new ToolManager(new Artifact("old", List.of(new GeneratedFile("index.html", "old title"),
        new GeneratedFile("unrelated.js", "UNRELATED_SOURCE_MUST_NOT_BE_SENT"))),
        new ArtifactService(json, p), CodeGenType.MULTI_FILE, json);
  }

  LangChainGateway gateway(StreamingChatModel model) {
    return new LangChainGateway(new ModelProperties("http://unused", "key", "model", Duration.ofSeconds(1),
        0, false, false, null, Duration.ofSeconds(5), false), model);
  }

  ToolExecutionRequest call(String name, String args) {
    return ToolExecutionRequest.builder().id(name).name(name).arguments(args).build();
  }

  @Test void readsAndPatchesOneFileWithoutSendingUnrelatedSource() {
    var rounds = new AtomicInteger();
    var model = new StreamingChatModel() {
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        assertThat(request.toolSpecifications()).hasSize(6);
        assertThat(request.messages().toString()).doesNotContain("UNRELATED_SOURCE_MUST_NOT_BE_SENT");
        int round = rounds.getAndIncrement();
        var tool = switch (round) {
          case 0 -> call("file_list", "{}");
          case 1 -> call("file_read", "{\"path\":\"index.html\"}");
          case 2 -> call("file_modify", "{\"path\":\"index.html\",\"oldText\":\"old title\",\"newText\":\"new title\"}");
          default -> call("finish_edit", "{\"summary\":\"更新标题\"}");
        };
        if (round == 2) assertThat(((ToolExecutionResultMessage) request.messages().getLast()).text()).contains("old title");
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tool))
            .finishReason(FinishReason.TOOL_EXECUTION).build());
      }
    };
    var result = gateway(model).edit(CodeGenType.MULTI_FILE, "system", "change title", tools(), t -> {}, p -> {});
    assertThat(result.files().getFirst().content()).isEqualTo("new title");
    assertThat(result.files().get(1).content()).isEqualTo("UNRELATED_SOURCE_MUST_NOT_BE_SENT");
    assertThat(rounds.get()).isEqualTo(4);
  }

  @Test void refusesTruncatedToolCallsAndTextOnlyResponses() {
    for (boolean truncated : new boolean[] {true, false}) {
      var tools = tools();
      var model = new StreamingChatModel() {
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
          handler.onCompleteResponse(ChatResponse.builder().aiMessage(truncated
              ? AiMessage.from(call("file_write", "{\"path\":\"extra.js\",\"content\":\"abc\"}"))
              : AiMessage.from("done"))
              .finishReason(truncated ? FinishReason.LENGTH : FinishReason.STOP).build());
        }
      };
      assertThatThrownBy(() -> gateway(model).edit(CodeGenType.MULTI_FILE, "system", "edit", tools, t -> {}, p -> {}))
          .hasMessageContaining("草稿未保存");
      assertThat(tools.file_list()).doesNotContain("extra.js");
      assertThat(tools.finished()).isFalse();
    }
  }

  @Test void rejectsMixedFinishAndWriteBatchWithoutApplyingEither() {
    var count = new AtomicInteger();
    var tools = tools();
    var model = new StreamingChatModel() {
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        if (count.getAndIncrement() == 0) {
          handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(List.of(
              call("finish_edit", "{\"summary\":\"done\"}"),
              call("file_write", "{\"path\":\"extra.js\",\"content\":\"bad\"}"))))
              .finishReason(FinishReason.TOOL_EXECUTION).build());
        } else {
          assertThat(request.messages()).hasSize(5);
          handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(call("finish_edit", "{\"summary\":\"done\"}")))
              .finishReason(FinishReason.TOOL_EXECUTION).build());
        }
      }
    };
    var result = gateway(model).edit(CodeGenType.MULTI_FILE, "system", "edit", tools, t -> {}, p -> {});
    assertThat(result.files()).hasSize(2);
  }
}
