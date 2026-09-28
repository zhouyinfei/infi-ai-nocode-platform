package org.infi.nocode.config;

import java.time.Duration;
import org.infi.nocode.model.enums.CodeGenType;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("langchain4j.open-ai.chat-model")
public record ModelProperties(
    String baseUrl,
    String apiKey,
    String modelName,
    Duration timeout,
    int maxRetries,
    boolean logRequests,
    boolean logResponses,
    GenerationTokens generationTokens,
    Duration generationTimeout,
    Boolean enableThinking) {
  public ModelProperties {
    if (generationTokens == null) generationTokens = new GenerationTokens(16000, 32000, 32000);
    if (generationTimeout == null) generationTimeout = Duration.ofMinutes(8);
    if (generationTimeout.isNegative() || generationTimeout.isZero())
      throw new IllegalArgumentException("generation-timeout 必须大于 0");
  }

  public record GenerationTokens(int html, int multiFile, int vueProject) {
    public GenerationTokens {
      // Provider/model limits vary; configure each budget within the selected model's limit.
      if (html < 1 || multiFile < 1 || vueProject < 1)
        throw new IllegalArgumentException("各生成类型的输出 tokens 必须为正整数，且配置值不得超过所用模型支持的上限");
    }

    public int forType(CodeGenType type) {
      return switch (type) {
        case HTML -> html;
        case MULTI_FILE -> multiFile;
        case VUE_PROJECT -> vueProject;
      };
    }
  }
}
