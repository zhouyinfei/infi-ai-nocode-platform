package org.infi.nocode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.generator.TypeRouter;
import org.infi.nocode.manager.PreviewServer;
import org.infi.nocode.manager.ScreenshotManager;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.service.*;
import org.junit.jupiter.api.Test;

class ExampleCatalogTest {
  @Test
  void homepageExamplesArePublicAndNeverTreatedAsAnAppId() throws Exception {
    var store = mock(Store.class);
    var auth = mock(AuthService.class);
    var controller = new org.infi.nocode.controller.AppController(
        store, auth, mock(AppService.class), mock(TaskService.class),
        mock(ArtifactService.class), mock(PreviewServer.class),
        mock(org.infi.nocode.ratelimiter.RateLimiter.class), mock(ScreenshotManager.class));
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
        .standaloneSetup(controller).build();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/apps/examples"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.code").value(0))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.data.length()").value(4));
    verifyNoInteractions(store, auth);
  }

  @Test
  void concisePromptsHaveStableUniqueIdentitiesAndEditsAreCustomRequests() {
    assertThat(ExampleCatalog.ALL).hasSize(4);
    assertThat(ExampleCatalog.ALL.stream().map(ExampleCatalog.Example::id)).doesNotHaveDuplicates();
    for (var example : ExampleCatalog.ALL) {
      assertThat(example.prompt().length()).isBetween(60, 140);
      assertThat(example.prompt().lines().count()).isEqualTo(3);
      assertThat(ExampleCatalog.match(example.prompt())).contains(example);
      assertThat(ExampleCatalog.match(example.prompt() + " 改成粉色")).isEmpty();
    }
  }

  @Test
  void examplesUseCanonicalTypesWithoutCallingClassificationModel() {
    var store = mock(Store.class);
    var router = mock(TypeRouter.class);
    var service = new AppService(store, router, mock(TaskService.class), mock(ArtifactService.class),
        mock(PreviewServer.class), mock(ScreenshotManager.class));
    for (var example : ExampleCatalog.ALL) {
      service.create("1", example.title(), example.prompt());
      verify(store).create(example.title(), example.prompt(), example.codeGenType(), "1");
    }
    verifyNoInteractions(router);
  }
}
