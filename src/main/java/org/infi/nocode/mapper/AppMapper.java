package org.infi.nocode.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;
import org.infi.nocode.model.entity.App;

@Mapper
public interface AppMapper {
  App byId(String id);

  App published(String key);

  int insert(Map<String, Object> row);

  int edit(@Param("id") String id, @Param("name") String name);

  int adminUpdate(
      @Param("id") String id, @Param("name") String name, @Param("priority") int priority);

  int delete(String id);

  int deleteByUser(String userId);

  int deployed(@Param("id") String id, @Param("key") String key);

  int cover(@Param("id") String id, @Param("cover") String cover);

  long count(Map<String, Object> filter);

  List<App> list(Map<String, Object> filter);

  List<String> activeIds();

  List<String> activeDeployKeys();

  List<String> activeCovers();
}
