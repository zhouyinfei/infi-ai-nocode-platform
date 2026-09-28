package org.infi.nocode;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.infi.nocode.controller.AdminController;
import org.infi.nocode.exception.GlobalExceptionHandler;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.User;
import org.infi.nocode.service.AppService;
import org.infi.nocode.service.AuthService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminAccessTest {
  @ParameterizedTest
  @CsvSource({"users,anonymous,401", "apps,anonymous,401", "users,user,403",
      "apps,user,403", "users,admin,200", "apps,admin,200"})
  void managementRequiresAdministrator(String resource, String role, int status) throws Exception {
    var store = mock(Store.class);
    var apps = mock(AppService.class);
    var mvc = MockMvcBuilders.standaloneSetup(new AdminController(new AuthService(store), store, apps))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
    var session = new MockHttpSession();
    if (!role.equals("anonymous")) {
      session.setAttribute("uid", "1");
      when(store.user("1")).thenReturn(new User("1", "account", "hash", "Name", null, null, role, null));
    }
    when(store.user("2")).thenReturn(new User("2", "other", "hash", "Other", null, null, "user", null));
    String path = "/api/admin/" + resource;
    mvc.perform(get(path).session(session)).andExpect(status().is(status));
    String body = resource.equals("users")
        ? "{\"userName\":\"Other\",\"userRole\":\"user\"}"
        : "{\"appName\":\"App\",\"priority\":1}";
    mvc.perform(put(path + "/2").session(session).contentType("application/json").content(body))
        .andExpect(status().is(status));
    mvc.perform(delete(path + "/2").session(session)).andExpect(status().is(status));
    if (status != 200) {
      verify(store, never()).adminUser(anyString(), anyString(), anyString());
      verify(store, never()).deleteUser(anyString());
      verify(store, never()).adminApp(anyString(), anyString(), anyInt());
      verifyNoInteractions(apps);
    }
  }
}
