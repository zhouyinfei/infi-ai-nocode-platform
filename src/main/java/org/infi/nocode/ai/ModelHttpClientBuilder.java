package org.infi.nocode.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.*;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.*;

/**
 * 每次模型调用独享传输状态，统一注入供应商选项、跟踪 SSE 活动并关闭取消后的连接。
 */
final class ModelHttpClientBuilder implements HttpClientBuilder, AutoCloseable {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ModelHttpClientBuilder.class);
  private final JdkHttpClientBuilder delegate = new JdkHttpClientBuilder();
  private final Boolean thinking;
  private final AtomicReference<InputStream> stream = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile long lastActivity = System.nanoTime();

  ModelHttpClientBuilder(Boolean thinking) {
    this.thinking = thinking;
  }

  long lastActivity() {
    return lastActivity;
  }

  public Duration connectTimeout() {
    return delegate.connectTimeout();
  }

  public HttpClientBuilder connectTimeout(Duration timeout) {
    delegate.connectTimeout(timeout);
    return this;
  }

  public Duration readTimeout() {
    return delegate.readTimeout();
  }

  public HttpClientBuilder readTimeout(Duration timeout) {
    delegate.readTimeout(timeout);
    return this;
  }

  /** 按配置注入思考模式；不记录请求地址、认证头或请求正文。 */
  private HttpRequest prepare(HttpRequest request) {
    if (thinking == null) return request;
    try {
      ObjectNode body = (ObjectNode) new ObjectMapper().readTree(request.body());
      body.put("enable_thinking", thinking);
      return HttpRequest.builder()
          .url(request.url())
          .method(request.method())
          .headers(request.headers())
          .body(body.toString())
          .build();
    } catch (java.io.IOException e) {
      log.warn("Model request preparation failed: category={}", e.getClass().getSimpleName());
      throw new IllegalStateException("模型请求格式错误");
    }
  }

  /** 包装 HTTP 客户端，在流式调用结束或取消时确保只通知一次终止事件。 */
  public HttpClient build() {
    HttpClient client = delegate.build();
    return new HttpClient() {
      public SuccessfulHttpResponse execute(HttpRequest request) {
        return client.execute(prepare(request));
      }

      public void execute(
          HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        var ended = new AtomicBoolean();
        ServerSentEventListener events =
            new ServerSentEventListener() {
              public void onOpen(SuccessfulHttpResponse response) {
                log.debug("Model SSE connection opened");
                lastActivity = System.nanoTime();
                if (!closed.get()) listener.onOpen(response);
              }

              public void onEvent(ServerSentEvent event) {
                lastActivity = System.nanoTime();
                if (closed.get() || ended.get()) return;
                if ("[DONE]".equals(event.data().trim())) {
                  if (ended.compareAndSet(false, true)) listener.onClose();
                  close();
                } else listener.onEvent(event);
              }

              public void onError(Throwable error) {
                if (!closed.get() && ended.compareAndSet(false, true)) listener.onError(error);
              }

              public void onClose() {
                if (!closed.get() && ended.compareAndSet(false, true)) listener.onClose();
              }
            };
        client.execute(
            prepare(request),
            (input, ignored) -> {
              stream.set(input);
              if (closed.get()) {
                close();
                return;
              }
              try {
                parser.parse(input, events);
              } finally {
                stream.compareAndSet(input, null);
              }
            },
            events);
      }
    };
  }

  /** 幂等关闭当前输入流，阻止任务结束后继续接收模型内容。 */
  public void close() {
    closed.set(true);
    InputStream input = stream.getAndSet(null);
    if (input != null)
      try {
        input.close();
        log.debug("Model SSE input stream closed");
      } catch (java.io.IOException ignored) {
        log.debug("Model SSE input stream close failed: category={}", ignored.getClass().getSimpleName());
      }
  }
}
