package org.infi.nocode.ai;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.infi.nocode.config.ModelProperties;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModelHttpClientTest {
  @ParameterizedTest
  @EnumSource(CodeGenType.class)
  void doneCompletesWithoutWaitingForSocketCloseAndSendsProviderOption(CodeGenType type)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(workers);
    var requestBody = new AtomicReference<String>();
    server.createContext(
        "/chat/completions",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          String events =
              "data:"
                  + " {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"完整页面\"},\"finish_reason\":null}]}\n\n"
                  + "data:"
                  + " {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                  + "data: [DONE]\n\n";
          exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
          exchange.getResponseBody().flush();
          try {
            Thread.sleep(6000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var p =
          new ModelProperties(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-key",
              "model",
              Duration.ofSeconds(2),
              0,
              false,
              false,
              null,
              Duration.ofSeconds(10),
              false);
      assertTimeoutPreemptively(
          Duration.ofSeconds(4),
          () ->
              assertThat(new LangChainGateway(p).generate(type, "system", "prompt", part -> {}))
                  .isEqualTo("完整页面"));
      assertThat(requestBody.get()).contains("\"enable_thinking\":false");
      int sentLimit =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(requestBody.get())
              .path("max_tokens")
              .asInt();
      assertThat(sentLimit).isEqualTo(type == CodeGenType.HTML ? 16000 : 32000);
    } finally {
      server.stop(0);
      workers.shutdownNow();
    }
  }
}
