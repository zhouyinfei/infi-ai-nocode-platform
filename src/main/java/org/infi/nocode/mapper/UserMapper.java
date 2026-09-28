package org.infi.nocode.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;
import org.infi.nocode.model.entity.User;

@Mapper
public interface UserMapper {
  User byAccount(String account);

  User byId(String id);

  int insert(Map<String, Object> row);

  int profile(
      @Param("id") String id,
      @Param("name") String name,
      @Param("avatar") String avatar,
      @Param("profile") String profile);

  int adminUpdate(@Param("id") String id, @Param("name") String name, @Param("role") String role);

  int delete(String id);

  long count(Map<String, Object> filter);

  List<User> list(Map<String, Object> filter);
}
