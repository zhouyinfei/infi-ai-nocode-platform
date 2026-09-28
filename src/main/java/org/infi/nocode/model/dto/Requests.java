package org.infi.nocode.model.dto;

import jakarta.validation.constraints.*;

public final class Requests {
  private Requests() {}

  public record Register(
      @Pattern(regexp = "[a-zA-Z0-9_]{4,32}", message = "账号需为4到32位字母、数字或下划线") @NotBlank
          String userAccount,
      @NotBlank @Size(min = 8, max = 64, message = "密码需为8到64位") String userPassword,
      @NotBlank String confirmPassword) {}

  public record Login(
      @NotBlank String userAccount, @NotBlank @Size(max = 64) String userPassword) {}

  public record Profile(
      @NotBlank @Size(max = 60) String userName,
      @Size(max = 1024) String userAvatar,
      @Size(max = 512) String userProfile) {}

  public record CreateApp(
      @Size(max = 100) String appName,
      @NotBlank @Size(max = 8000, message = "需求最多8000字") String initPrompt) {}

  public record EditApp(@NotBlank @Size(max = 100) String appName) {}

  public record Optimize(@NotBlank @Size(max = 8000) String prompt) {}

  public record Generate(
      @NotBlank @Size(max = 8000, message = "需求最多8000字") String message,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,64}") String requestId) {}

  public record AdminApp(
      @NotBlank @Size(max = 100) String appName, @Min(0) @Max(9999) int priority) {}

  public record AdminUser(
      @NotBlank @Size(max = 60) String userName,
      @Pattern(regexp = "user|admin") @NotBlank String userRole) {}
}
