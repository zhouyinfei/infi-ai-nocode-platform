package org.infi.nocode.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

public record User(
    String id,
    String userAccount,
    @JsonIgnore String userPassword,
    String userName,
    String userAvatar,
    String userProfile,
    String userRole,
    LocalDateTime createTime) {}
