package org.infi.nocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;
import org.infi.nocode.common.PageResult;
import org.infi.nocode.config.SecurityConfig;
import org.infi.nocode.controller.AppController;
import org.infi.nocode.controller.UserController;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.manager.PreviewServer;
import org.infi.nocode.manager.ScreenshotManager;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.App;
import org.infi.nocode.model.entity.User;
import org.infi.nocode.ratelimiter.RateLimiter;
import org.infi.nocode.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.infi.nocode.config.PlatformProperties;

@WebMvcTest({UserController.class, AppController.class})
@Import({SecurityConfig.class, AuthService.class})
class SecurityConfigTest {
  @Autowired MockMvc mvc;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
  @MockitoBean Store store;
  @MockitoBean RateLimiter limiter;
  @MockitoBean AppService apps;
  @MockitoBean TaskService tasks;
  @MockitoBean ArtifactService files;
  @MockitoBean PreviewServer preview;
  @MockitoBean ScreenshotManager screenshots;
  @MockitoBean PlatformProperties properties;

  @Test
  void loginSessionCanReadProfileAndOwnAppsAndLogoutRevokesAccess() throws Exception {
    var user = new User("1", "alice", passwords.encode("secret123"), "Alice", null, null, "user", null);
    when(store.userByAccount("alice")).thenReturn(Optional.of(user));
    when(store.user("1")).thenReturn(user);
    var app = new App("2", "My site", null, "prompt", "html", null, null, 0, "1", null, null);
    when(store.apps("1", false, "", 1, 6))
        .thenReturn(new PageResult<>(List.of(app), 1, 1, 6));

    var oldSession = new MockHttpSession();
    var result = mvc.perform(post("/api/users/login").session(oldSession)
            .header("X-Nocode-Request", "1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userAccount\":\"alice\",\"userPassword\":\"secret123\"}"))
        .andExpect(status().isOk()).andReturn();
    assertThat(oldSession.isInvalid()).isTrue();
    var session = (MockHttpSession) result.getRequest().getSession(false);
    assertThat(session.getAttribute("uid")).isEqualTo("1");
    mvc.perform(get("/api/users/me").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("1"));
    mvc.perform(get("/api/apps").param("pageSize", "6").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("2"));
    verify(store).apps("1", false, "", 1, 6);
    mvc.perform(post("/api/users/logout").session(session).header("X-Nocode-Request", "1"))
        .andExpect(status().isOk());
    assertThat(session.isInvalid()).isTrue();
    mvc.perform(get("/api/apps")).andExpect(status().isUnauthorized());
  }

  @Test
  void anonymousAndEmptySessionsAreRejectedButExamplesRemainPublic() throws Exception {
    mvc.perform(get("/api/apps")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mvc.perform(get("/api/users/me").session(new MockHttpSession()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/apps/examples")).andExpect(status().isOk());
    verifyNoInteractions(store);
  }
}
