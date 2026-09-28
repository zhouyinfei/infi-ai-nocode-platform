package org.infi.nocode.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nocode")
public record PlatformProperties(
    Path outputDir,
    Path deployDir,
    Path screenshotDir,
    int previewPort,
    String previewBaseUrl,
    String allowedOrigin,
    Duration taskTimeout,
    int maxConcurrentTasks,
    int maxFiles,
    long maxTotalBytes,
    String nodeExecutable,
    Path vueBuilderDir,
    boolean screenshotEnabled,
    String browserChannel,
    String adminAccount,
    String adminPassword,
    Oss oss) {
  public record Oss(
      boolean enabled,
      String endpoint,
      String bucket,
      String accessKeyId,
      String accessKeySecret,
      String publicBaseUrl) {}
}
