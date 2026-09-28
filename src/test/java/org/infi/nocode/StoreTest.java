package org.infi.nocode;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.mapper.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class StoreTest {
  @Test
  void rejectsMysqlNumericCoercionBeforeQuery() {
    for (String id : List.of("01", "1abc", "1.0", "0", "-1", "9223372036854775808"))
      assertThatThrownBy(() -> store.app(id)).isInstanceOf(BusinessException.class);
  }

  Store store;
  AppMapper appMapper;
  JdbcTemplate jdbc;

  @BeforeEach
  void setup() throws Exception {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:"
                    + java.util.UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
    String sql = Files.readString(Path.of("sql/create_table.sql"));
    for (String statement : sql.split(";")) {
      if (!statement.strip().startsWith("CREATE TABLE")) continue;
      jdbc.execute(statement.replace("COLLATE utf8mb4_unicode_ci", ""));
    }
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(jdbc.getDataSource());
    factory.setMapperLocations(
        new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
    var session = new SqlSessionTemplate(factory.getObject());
    appMapper = session.getMapper(AppMapper.class);
    store =
        new Store(
            session.getMapper(UserMapper.class),
            session.getMapper(AppMapper.class),
            session.getMapper(ChatHistoryMapper.class));
  }

  @Test
  void mapsLargeIdsNullableFieldsAndCleanupReferences() {
    String largeId = "9007199254740993";
    jdbc.update(
        "insert into `user` (id,userAccount,userPassword,userName) values (?,?,?,?)",
        largeId,
        "large",
        "hash",
        "Large");
    assertThat(store.user(largeId).id()).isEqualTo(largeId);
    assertThat(store.user(largeId).userAvatar()).isNull();
    store.profile(largeId, "Updated", "https://example.com/avatar.png", "Profile");
    store.adminUser(largeId, "Updated", "admin");
    assertThat(store.user(largeId).userProfile()).isEqualTo("Profile");
    assertThat(store.user(largeId).userRole()).isEqualTo("admin");
    var app = store.create("Original", "Prompt", "html", largeId);
    assertThat(app.userId()).isEqualTo(largeId);
    assertThat(app.deployedTime()).isNull();
    store.edit(app.id(), "Renamed");
    store.cover(app.id(), "/api/covers/test.png");
    store.deployed(app.id(), "test-key");
    assertThat(store.published("test-key").orElseThrow().appName()).isEqualTo("Renamed");
    assertThat(appMapper.activeIds()).contains(app.id());
    assertThat(appMapper.activeDeployKeys()).contains("test-key");
    assertThat(appMapper.activeCovers()).contains("/api/covers/test.png");
    var message = store.addMessage(app.id(), largeId, "user", "dated");
    assertThat(
            store
                .adminMessages(
                    "",
                    app.id(),
                    largeId,
                    "user",
                    1,
                    10,
                    message.createTime().minusSeconds(1),
                    message.createTime().plusSeconds(1))
                .total())
        .isEqualTo(1);
    assertThat(
            store
                .adminMessages(
                    "", app.id(), largeId, "user", 1, 10, message.createTime().plusSeconds(1), null)
                .total())
        .isZero();
  }

  @Test
  void mybatisWritesJoinSpringTransactionAndRollbackTogether() {
    var u = store.register("alice", "hash");
    var a = store.create("A", "p", "html", u.id());
    store.addMessage(a.id(), u.id(), "user", "retained");
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.executeWithoutResult(
        status -> {
          store.deleteApp(a.id());
          assertThatThrownBy(() -> store.app(a.id())).isInstanceOf(BusinessException.class);
          status.setRollbackOnly();
        });
    assertThat(store.app(a.id()).appName()).isEqualTo("A");
    assertThat(store.messages(a.id(), null, 10)).hasSize(1);
  }

  @Test
  void accountUniqueAndDeletedUserCannotBeLoaded() {
    var u = store.register("alice", "hash");
    assertThatThrownBy(() -> store.register("alice", "hash"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    store.deleteUser(u.id());
    assertThatThrownBy(() -> store.user(u.id())).isInstanceOf(BusinessException.class);
  }

  @Test
  void ownerListsAndLogicalDelete() {
    var u = store.register("alice", "hash");
    var b = store.register("bob", "hash");
    var a = store.create("Website", "prompt", "html", u.id());
    assertThat(store.apps(b.id(), false, "", 1, 10).total()).isZero();
    assertThat(store.apps(u.id(), false, "", 1, 10).records()).hasSize(1);
    store.addMessage(a.id(), u.id(), "user", "hello");
    store.deleteApp(a.id());
    assertThat(store.messages(a.id(), null, 10)).isEmpty();
    assertThatThrownBy(() -> store.app(a.id())).isInstanceOf(BusinessException.class);
  }

  @Test
  void cursorTiesDoNotDropMessagesOrCrossApplications() {
    var u = store.register("alice", "hash");
    var a = store.create("A", "p", "html", u.id());
    var b = store.create("B", "p", "html", u.id());
    for (int i = 0; i < 5; i++) store.addMessage(a.id(), u.id(), "user", "m" + i);
    jdbc.update("update chat_history set createTime='2026-01-01 12:00:00'");
    var first = store.messages(a.id(), null, 3);
    var second = store.messages(a.id(), first.getLast().id(), 3);
    assertThat(first).hasSize(3);
    assertThat(second).hasSize(2);
    assertThat(second).doesNotContainAnyElementsOf(first);
    assertThatThrownBy(() -> store.messages(b.id(), first.getFirst().id(), 3))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void deletedOwnerRemovesPublicApps() {
    var u = store.register("alice", "hash");
    var a = store.create("A", "p", "html", u.id());
    store.deployed(a.id(), "abc");
    store.adminApp(a.id(), "A", 10);
    assertThat(store.apps(null, true, "", 1, 10).total()).isEqualTo(1);
    store.deleteUser(u.id());
    assertThat(store.published("abc")).isEmpty();
  }

  @Test
  void managementFiltersMatchExactOwnerTypeAndPriority() {
    var u = store.register("alice", "hash");
    var a = store.create("Website", "p", "html", u.id());
    store.create("Vue app", "p", "vue_project", u.id());
    store.adminApp(a.id(), "Website", 9);
    assertThat(store.apps(null, false, "", 1, 10, "html", 9, u.id()).records())
        .extracting("id")
        .containsExactly(a.id());
    assertThat(store.apps(null, false, "", 1, 10, "vue_project", 9, u.id()).total()).isZero();
    assertThat(store.users("", 1, 10, "ali", "alice").total()).isEqualTo(1);
  }

  @Test
  void adminMessagesFilterAndDeleteOnlySelectedMessage() {
    var u = store.register("alice", "hash");
    var a = store.create("A", "p", "html", u.id());
    var one = store.addMessage(a.id(), u.id(), "user", "question");
    store.addMessage(a.id(), u.id(), "ai", "answer");
    assertThat(store.adminMessages("question", a.id(), u.id(), "user", 1, 20).total()).isEqualTo(1);
    store.deleteMessage(one.id());
    assertThat(store.adminMessages("", a.id(), u.id(), null, 1, 20).total()).isEqualTo(1);
    assertThatThrownBy(() -> store.deleteMessage(one.id())).isInstanceOf(BusinessException.class);
  }
}
