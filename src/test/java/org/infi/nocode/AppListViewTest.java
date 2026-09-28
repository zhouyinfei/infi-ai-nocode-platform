package org.infi.nocode;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.infi.nocode.controller.AppController.AppListView;
import org.infi.nocode.model.entity.App;
import org.junit.jupiter.api.Test;

class AppListViewTest {
  @Test
  void publishedUrlIsAddedWithoutChangingExistingAppFields() throws Exception {
    var app = new App("1", "My site", null, "prompt", "html", "abc123", null, 0, "owner", null, null);
    var mapper = new ObjectMapper();
    var json = mapper.readTree(mapper.writeValueAsString(new AppListView(app, "https://site.test/abc123")));
    assertThat(json.get("id").asText()).isEqualTo("1");
    assertThat(json.get("userId").asText()).isEqualTo("owner");
    assertThat(json.get("appName").asText()).isEqualTo("My site");
    assertThat(json.get("url").asText()).isEqualTo("https://site.test/abc123");
    assertThat(json.has("app")).isFalse();
    var unpublished = mapper.readTree(mapper.writeValueAsString(new AppListView(app, null)));
    assertThat(unpublished.get("url").isNull()).isTrue();
  }
}
