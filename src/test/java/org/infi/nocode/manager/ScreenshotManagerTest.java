package org.infi.nocode.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.*;
import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class ScreenshotManagerTest {
  @Test
  void allowsHttpsImagesButKeepsOtherExternalRequestsBlocked() {
    String origin = "http://localhost:8124";
    assertThat(ScreenshotManager.allowsResource(origin, origin + "/site/main.js", "script")).isTrue();
    assertThat(ScreenshotManager.allowsResource(origin, "https://images.example/photo.png", "image")).isTrue();
    assertThat(ScreenshotManager.allowsResource(origin, "https://images.example/main.js", "script")).isFalse();
    assertThat(ScreenshotManager.allowsResource(origin, "https://images.example/api", "fetch")).isFalse();
    assertThat(ScreenshotManager.allowsResource(origin, "http://images.example/photo.png", "image")).isFalse();
    assertThat(ScreenshotManager.allowsResource(origin, origin + ".evil/image.png", "image")).isFalse();
  }

  @Test
  @EnabledIfSystemProperty(named = "screenshot.browser", matches = ".+")
  void screenshotContainsExternalLazyImageAndCssBackground() throws Exception {
    try (var playwright = Playwright.create(new Playwright.CreateOptions()
        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        var browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setChannel(System.getProperty("screenshot.browser")).setHeadless(true))) {
      var page = browser.newPage(new Browser.NewPageOptions().setViewportSize(300, 200));
      String origin = "https://preview.example";
      page.route("**/*", route -> {
        var request = route.request();
        if (!ScreenshotManager.allowsResource(origin, request.url(), request.resourceType())) {
          route.abort();
        } else if (request.resourceType().equals("image")) {
          route.fulfill(new Route.FulfillOptions().setContentType("image/svg+xml").setBody(
              "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100'><path fill='red' d='M0 0h100v100H0z'/></svg>"));
        } else {
          route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
              <body style="margin:0"><img loading="lazy" width="100" height="100"
              src="https://images.example/photo.svg"><div style="position:absolute;left:100px;top:0;
              width:100px;height:100px;background-image:url(https://images.example/bg.svg)"></div>
              """));
        }
      });
      page.navigate(origin + "/");
      ScreenshotManager.waitForImages(page);
      var screenshot = ImageIO.read(new ByteArrayInputStream(page.screenshot()));
      assertThat(screenshot.getRGB(50, 50) & 0xffffff).isEqualTo(0xff0000);
      assertThat(screenshot.getRGB(150, 50) & 0xffffff).isEqualTo(0xff0000);
    }
  }
}
