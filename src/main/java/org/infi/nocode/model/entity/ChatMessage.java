package org.infi.nocode.model.entity;

import java.time.LocalDateTime;

public record ChatMessage(
    String id,
    String message,
    String messageType,
    String appId,
    String userId,
    LocalDateTime createTime) {}
