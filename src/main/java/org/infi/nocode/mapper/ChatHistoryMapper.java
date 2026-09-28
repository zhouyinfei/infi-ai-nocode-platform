package org.infi.nocode.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;
import org.infi.nocode.model.entity.ChatMessage;

@Mapper
public interface ChatHistoryMapper {
  int insert(Map<String, Object> row);

  ChatMessage byId(String id);

  ChatMessage cursor(@Param("id") String id, @Param("appId") String appId);

  List<ChatMessage> messages(
      @Param("appId") String appId,
      @Param("beforeId") String beforeId,
      @Param("beforeTime") LocalDateTime beforeTime,
      @Param("limit") int limit);

  long count(Map<String, Object> filter);

  List<ChatMessage> list(Map<String, Object> filter);

  int delete(String id);

  int deleteByApp(String appId);

  int deleteByUser(String userId);
}
