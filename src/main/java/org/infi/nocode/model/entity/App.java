package org.infi.nocode.model.entity;

import java.time.LocalDateTime;

public record App(
    String id,
    String appName,
    String cover,
    String initPrompt,
    String codeGenType,
    String deployKey,
    LocalDateTime deployedTime,
    int priority,
    String userId,
    LocalDateTime createTime,
    LocalDateTime updateTime) {}
