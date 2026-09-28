package org.infi.nocode.ai;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.infi.nocode.config.ModelProperties;
import org.infi.nocode.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiOperationErrorTest {
  HttpServer server;
  int status = 404;
  String body =
      "{\"error\":{\"code\":\"model_not_found\",\"message\":\"private-provider-message\"}}";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        e -> {
          e.getRequestBody().readAllBytes();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(status, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.start();
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  LangChainGateway gateway() {
    return new LangChainGateway(
        new ModelProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "test-key",
            "test-model",
            Duration.ofSeconds(3),
            0,
            false,
            false,
            null,
            Duration.ofSeconds(5),
            false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"classify", "optimize"})
  void modelNotFoundIsAnActionableSanitizedBusinessError(String operation) {
    assertThatThrownBy(
            () -> {
              if (operation.equals("classify")) gateway().classify("一个网站");
              else gateway().optimize("一个网站");
            })
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.status()).isEqualTo(502);
              assertThat(e.getMessage())
                  .contains("模型不存在", "model-name")
                  .doesNotContain("private-provider-message", "test-key");
            });
  }

  @Test
  void invalidClassificationIsRejectedBeforeCreatingApp() {
    status = 200;
    body =
        "{\"id\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"这是一份网页代码\"},\"finish_reason\":\"stop\"}]}";
    assertThatThrownBy(() -> gateway().classify("一个网站"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未返回有效的生成类型");
  }
}
