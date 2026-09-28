package org.infi.nocode.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.infi.nocode.common.PageResult;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.model.entity.*;
import org.infi.nocode.model.enums.CodeGenType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 面向业务的数据访问入口：统一校验标识、处理不存在的记录，SQL 由 MyBatis Mapper 执行。 */
@Repository
public class Store {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(Store.class);
  private final UserMapper users;
  private final AppMapper apps;
  private final ChatHistoryMapper chats;

  public Store(UserMapper users, AppMapper apps, ChatHistoryMapper chats) {
    this.users = users;
    this.apps = apps;
    this.chats = chats;
  }

  /** 按账号查找用户，供登录及初始化使用；不记录账号或密码散列。 */
  public Optional<User> userByAccount(String account) {
    return Optional.ofNullable(users.byAccount(account));
  }

  /** 校验用户标识并读取用户；已删除用户必须重新登录。 */
  public User user(String id) {
    requireId(id);
    return Optional.ofNullable(users.byId(id))
        .orElseThrow(() -> new BusinessException(401, "请重新登录"));
  }

  /** 保存已编码的密码并返回新用户，密码编码由调用方完成。 */
  public User register(String account, String hash) {
    var row = new HashMap<String, Object>();
    row.put("account", account);
    row.put("hash", hash);
    users.insert(row);
    log.debug("User insert executed: userId={}", row.get("id"));
    return user(row.get("id").toString());
  }

  /** 更新用户资料，个人资料正文不写入日志。 */
  public void profile(String id, String name, String avatar, String profile) {
    requireId(id);
    users.profile(id, name, avatar, profile);
    log.debug("User profile update executed: userId={}", id);
  }

  /** 更新用户名称和角色，权限检查由业务入口负责。 */
  public void adminUser(String id, String name, String role) {
    requireId(id);
    users.adminUpdate(id, name, role);
    log.debug("User administration update executed: userId={}", id);
  }

  // 用户、应用和对话的逻辑删除必须在同一事务中完成，避免留下可访问的关联记录。
  /** 在同一事务中逻辑删除用户及关联应用、消息。 */
  @Transactional
  public void deleteUser(String id) {
    requireId(id);
    users.delete(id);
    apps.deleteByUser(id);
    chats.deleteByUser(id);
    log.debug("User cascade deletion executed; transaction pending: userId={}", id);
  }

  /** 校验应用标识并读取有效应用，不存在时返回业务异常。 */
  public App app(String id) {
    requireId(id);
    return Optional.ofNullable(apps.byId(id))
        .orElseThrow(() -> new BusinessException(404, "应用不存在"));
  }

  /** 持久化应用需求、生成类型及归属，并回读数据库生成的记录。 */
  public App create(String name, String prompt, String type, String owner) {
    requireId(owner);
    var row = new HashMap<String, Object>();
    row.put("name", name);
    row.put("prompt", prompt);
    row.put("type", type);
    row.put("owner", owner);
    apps.insert(row);
    log.debug("Application insert executed: appId={}", row.get("id"));
    return app(row.get("id").toString());
  }

  /** 更新应用名称，调用方须先校验写权限。 */
  public void edit(String id, String name) {
    requireId(id);
    apps.edit(id, name);
    log.debug("Application name update executed: appId={}", id);
  }

  /** 更新应用名称及精选优先级。 */
  public void adminApp(String id, String name, int priority) {
    requireId(id);
    apps.adminUpdate(id, name, priority);
    log.debug("Application administration update executed: appId={}, priority={}", id, priority);
  }

  /** 在同一事务中逻辑删除应用及关联对话。 */
  @Transactional
  public void deleteApp(String id) {
    requireId(id);
    apps.delete(id);
    chats.deleteByApp(id);
    log.debug("Application cascade deletion executed; transaction pending: appId={}", id);
  }

  /** 记录发布信息；应用已删除时拒绝更新，供上层回滚发布指针。 */
  public void deployed(String id, String key) {
    requireId(id);
    if (apps.deployed(id, key) != 1) throw new BusinessException(404, "应用已删除");
  }

  /** 更新封面引用，不记录可能包含签名的外部地址。 */
  public void cover(String id, String cover) {
    requireId(id);
    apps.cover(id, cover);
    log.debug("Application cover update executed: appId={}", id);
  }

  /** 按发布标识查找有效应用。 */
  public Optional<App> published(String key) {
    return Optional.ofNullable(apps.published(key));
  }

  /** 校验分页及筛选条件，查询应用列表与总数。 */
  public PageResult<App> apps(String owner, boolean featured, String query, int page, int size) {
    return apps(owner, featured, query, page, size, null, null, null);
  }

  /** 校验分页及筛选条件，查询应用列表与总数。 */
  public PageResult<App> apps(
      String owner,
      boolean featured,
      String query,
      int page,
      int size,
      String type,
      Integer priority,
      String userId) {
    validatePage(page, size);
    if (owner != null) requireId(owner);
    type = nonblank(type);
    userId = nonblank(userId);
    if (type != null) CodeGenType.of(type);
    if (userId != null) requireId(userId);
    var filter = pageFilter(query, page, size);
    filter.put("owner", owner);
    filter.put("featured", featured);
    filter.put("type", type);
    filter.put("priority", priority);
    filter.put("userId", userId);
    return new PageResult<>(apps.list(filter), apps.count(filter), page, size);
  }

  /** 按分页条件查询用户列表，不记录账号及搜索正文。 */
  public PageResult<User> users(String query, int page, int size) {
    return users(query, page, size, "", "");
  }

  /** 按分页条件查询用户列表，不记录账号及搜索正文。 */
  public PageResult<User> users(String query, int page, int size, String account, String name) {
    var filter = pageFilter(query, page, size);
    filter.put("account", like(account));
    filter.put("name", like(name));
    return new PageResult<>(users.list(filter), users.count(filter), page, size);
  }

  /** 限制消息字节数后保存对话，日志只记录关联标识。 */
  public ChatMessage addMessage(String appId, String userId, String type, String text) {
    app(appId);
    requireId(userId);
    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000)
      throw BusinessException.bad("消息过长");
    var row = new HashMap<String, Object>();
    row.put("appId", appId);
    row.put("userId", userId);
    row.put("type", type);
    row.put("text", text);
    chats.insert(row);
    log.debug("Chat message insert executed: appId={}, messageId={}", appId, row.get("id"));
    return chats.byId(row.get("id").toString());
  }

  /** 校验游标归属，按时间游标读取应用的历史消息。 */
  public List<ChatMessage> messages(String appId, String before, int limit) {
    requireId(appId);
    if (before != null) requireId(before);
    if (limit < 1 || limit > 100) throw BusinessException.bad("分页大小须在1到100之间");
    ChatMessage cursor =
        before == null
            ? null
            : Optional.ofNullable(chats.cursor(before, appId))
                .orElseThrow(() -> BusinessException.bad("无效消息游标"));
    return chats.messages(appId, before, cursor == null ? null : cursor.createTime(), limit);
  }

  /** 校验时间范围、标识和消息类型后分页查询对话。 */
  public PageResult<ChatMessage> adminMessages(
      String query, String appId, String userId, String type, int page, int size) {
    return adminMessages(query, appId, userId, type, page, size, null, null);
  }

  /** 校验时间范围、标识和消息类型后分页查询对话。 */
  public PageResult<ChatMessage> adminMessages(
      String query,
      String appId,
      String userId,
      String type,
      int page,
      int size,
      LocalDateTime start,
      LocalDateTime end) {
    if (start != null && end != null && start.isAfter(end))
      throw BusinessException.bad("开始时间不能晚于结束时间");
    appId = nonblank(appId);
    userId = nonblank(userId);
    type = nonblank(type);
    if (appId != null) requireId(appId);
    if (userId != null) requireId(userId);
    if (type != null && !Set.of("user", "ai").contains(type)) throw BusinessException.bad("无效消息类型");
    var filter = pageFilter(query, page, size);
    filter.put("appId", appId);
    filter.put("userId", userId);
    filter.put("type", type);
    filter.put("start", start);
    filter.put("end", end);
    return new PageResult<>(chats.list(filter), chats.count(filter), page, size);
  }

  /** 逻辑删除指定消息，不存在时返回业务异常。 */
  public void deleteMessage(String id) {
    requireId(id);
    if (chats.delete(id) != 1) throw new BusinessException(404, "消息不存在");
    log.debug("Chat message deletion executed: messageId={}", id);
  }

  private static String nonblank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String like(String value) {
    return "%" + Objects.toString(value, "") + "%";
  }

  private Map<String, Object> pageFilter(String query, int page, int size) {
    validatePage(page, size);
    var filter = new HashMap<String, Object>();
    filter.put("query", like(query));
    filter.put("size", size);
    filter.put("offset", (page - 1) * size);
    return filter;
  }

  private void requireId(String id) {
    if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw BusinessException.bad("无效资源 ID");
    try {
      Long.parseLong(id);
    } catch (NumberFormatException e) {
      throw BusinessException.bad("资源 ID 超出范围");
    }
  }

  private void validatePage(int page, int size) {
    if (page < 1 || page > 100000 || size < 1 || size > 100) throw BusinessException.bad("无效分页参数");
  }
}
