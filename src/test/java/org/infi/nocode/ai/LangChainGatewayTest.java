package org.infi.nocode.ai;

import static org.assertj.core.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.infi.nocode.config.ModelProperties;
import org.infi.nocode.exception.BusinessException;
import org.junit.jupiter.api.Test;

class LangChainGatewayTest {
  @Test
  void retriesTransientFailureOnlyBeforeAnyContent() {
    for (boolean partial : new boolean[] {false, true}) {
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      var model =
          new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
              if (attempts.incrementAndGet() == 1) {
                if (partial) handler.onPartialResponse("partial");
                handler.onError(new HttpException(503, "private upstream response"));
              } else
                handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("complete")).build());
            }
          };
      var p =
          new ModelProperties(
              "http://unused",
              "test-key",
              "test-model",
              Duration.ofSeconds(1),
              2,
              false,
              false,
              null,
              Duration.ofSeconds(3),
              false);
      var gateway = new LangChainGateway(p, model);
      if (partial)
        assertThatThrownBy(
                () ->
                    gateway.generate(
                        org.infi.nocode.model.enums.CodeGenType.HTML,
                        "system",
                        "prompt",
                        token -> {}))
            .isInstanceOf(BusinessException.class);
      else
        assertThat(
                gateway.generate(
                    org.infi.nocode.model.enums.CodeGenType.HTML, "system", "prompt", token -> {}))
            .isEqualTo("complete");
      assertThat(attempts.get()).isEqualTo(partial ? 1 : 2);
    }
  }

  ModelProperties properties(Duration generation) {
    return new ModelProperties(
        "http://unused",
        "test-key",
        "test-model",
        Duration.ofMillis(10),
        0,
        false,
        false,
        null,
        generation,
        false);
  }

  @Test
  void activeStreamCanOutliveHttpTimeout() {
    var model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            Thread.startVirtualThread(
                () -> {
                  try {
                    handler.onPartialResponse("first");
                    Thread.sleep(80);
                    handler.onCompleteResponse(
                        ChatResponse.builder()
                            .aiMessage(AiMessage.from("complete"))
                            .finishReason(FinishReason.STOP)
                            .build());
                  } catch (InterruptedException e) {
                    handler.onError(e);
                  }
                });
          }
        };
    assertThat(
            new LangChainGateway(properties(Duration.ofSeconds(2)), model)
                .generate(
                    org.infi.nocode.model.enums.CodeGenType.HTML, "system", "prompt", token -> {}))
        .isEqualTo("complete");
  }

  @Test
  void deadlineReportsPartialOutputAndIgnoresLateTokens() {
    var callback = new AtomicReference<StreamingChatResponseHandler>();
    var model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            callback.set(handler);
            handler.onPartialResponse("partial");
          }
        };
    var tokens = new StringBuilder();
    assertThatThrownBy(
            () ->
                new LangChainGateway(properties(Duration.ofMillis(40)), model)
                    .generate(
                        org.infi.nocode.model.enums.CodeGenType.HTML,
                        "system",
                        "prompt",
                        tokens::append))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已返回部分代码");
    callback.get().onPartialResponse("late");
    assertThat(tokens.toString()).isEqualTo("partial");
  }

  @Test
  void upstreamHttpFailuresAreSpecificAndNeverExposeResponseBody() {
    for (int status : new int[] {401, 403, 404, 429, 500}) {
      var failure =
          LangChainGateway.generationFailure(
              new ExecutionException(new HttpException(status, "secret-key and private response")),
              0);
      assertThat(failure.getMessage()).doesNotContain("secret-key", "private response");
      assertThat(failure.getMessage())
          .contains(
              switch (status) {
                case 401, 403 -> "鉴权";
                case 404 -> "不存在";
                case 429 -> "限流";
                default -> "HTTP 500";
              });
    }
  }

  @Test
  void outputTokenLimitIsNotReportedAsPermissionFailure() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls.incrementAndGet();
            handler.onCompleteResponse(
                ChatResponse.builder()
                    .aiMessage(AiMessage.from("incomplete"))
                    .finishReason(FinishReason.LENGTH)
                    .build());
          }
        };
    assertThatThrownBy(
            () ->
                new LangChainGateway(properties(Duration.ofSeconds(2)), model)
                    .generate(
                        org.infi.nocode.model.enums.CodeGenType.HTML,
                        "system",
                        "prompt",
                        token -> {}))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("长度上限");
    assertThat(calls.get()).isEqualTo(4);
  }

  @Test
  void continuesAcrossJsonEscapeBoundaryAndPreservesStreamingOutput() throws Exception {
    String[] parts = {"{\"summary\":\"ok\",\"files\":[{\"path\":\"index.html\",\"content\":\"<html>\\",
        "nhello", "</html>\"}]}"};
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var tokens = new StringBuilder();
    var model = new StreamingChatModel() {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        int round = calls.getAndIncrement();
        if (round > 0) {
          assertThat(request.messages()).hasSize(4);
          assertThat(((AiMessage) request.messages().get(2)).text())
              .isEqualTo(String.join("", java.util.Arrays.copyOf(parts, round)));
        }
        handler.onPartialResponse(parts[round]);
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(parts[round]))
            .finishReason(round == 2 ? FinishReason.STOP : FinishReason.LENGTH).build());
      }
    };
    String result = new LangChainGateway(properties(Duration.ofSeconds(2)), model)
        .generate(org.infi.nocode.model.enums.CodeGenType.HTML, "system", "prompt", tokens::append);
    assertThat(result).isEqualTo(String.join("", parts)).isEqualTo(tokens.toString());
    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result)
        .get("files").get(0).get("content").asText()).isEqualTo("<html>\nhello</html>");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void emptyTruncatedOutputDoesNotStartContinuation() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var model = new StreamingChatModel() {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        calls.incrementAndGet();
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(""))
            .finishReason(FinishReason.LENGTH).build());
      }
    };
    assertThatThrownBy(() -> new LangChainGateway(properties(Duration.ofSeconds(2)), model)
        .generate(org.infi.nocode.model.enums.CodeGenType.HTML, "system", "prompt", token -> {}))
        .isInstanceOf(BusinessException.class).hasMessageContaining("未返回可续接");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void continuationTimeoutDoesNotRestartGenerationOrAcceptLateTokens() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var callback = new AtomicReference<StreamingChatResponseHandler>();
    var tokens = new StringBuilder();
    var model = new StreamingChatModel() {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        if (calls.incrementAndGet() == 1) {
          handler.onPartialResponse("first");
          handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("first"))
              .finishReason(FinishReason.LENGTH).build());
        } else {
          callback.set(handler);
          handler.onPartialResponse("second");
        }
      }
    };
    assertThatThrownBy(() -> new LangChainGateway(properties(Duration.ofMillis(200)), model)
        .generate(org.infi.nocode.model.enums.CodeGenType.HTML, "system", "prompt", tokens::append))
        .isInstanceOf(BusinessException.class).hasMessageContaining("超过等待时限");
    callback.get().onPartialResponse("late");
    assertThat(tokens.toString()).isEqualTo("firstsecond");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void continuationFailureRetriesOnlyCurrentRoundBeforeItsFirstToken() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var tokens = new StringBuilder();
    var model = new StreamingChatModel() {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        int round = calls.getAndIncrement();
        if (round == 1) {
          handler.onError(new HttpException(503, "unavailable"));
          return;
        }
        String part = round == 0 ? "first" : "second";
        handler.onPartialResponse(part);
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(part))
            .finishReason(round == 0 ? FinishReason.LENGTH : FinishReason.STOP).build());
      }
    };
    var config = new ModelProperties("http://unused", "key", "model", Duration.ofSeconds(1),
        2, false, false, null, Duration.ofSeconds(3), false);
    assertThat(new LangChainGateway(config, model)
        .generate(org.infi.nocode.model.enums.CodeGenType.HTML, "system", "prompt", tokens::append))
        .isEqualTo("firstsecond");
    assertThat(tokens.toString()).isEqualTo("firstsecond");
    assertThat(calls.get()).isEqualTo(3);
  }
}
