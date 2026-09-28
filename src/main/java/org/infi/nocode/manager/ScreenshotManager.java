package org.infi.nocode.manager;

import com.aliyun.oss.OSSClientBuilder;
import com.microsoft.playwright.*;
import java.nio.file.*;
import java.util.*;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.mapper.Store;
import org.springframework.stereotype.Component;

/** 使用本机浏览器截图，可选上传 OSS；失败以提示返回，不影响已发布网站。 */
@Component
public class ScreenshotManager {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ScreenshotManager.class);
  private final PlatformProperties p;
  private final Store store;

  public ScreenshotManager(PlatformProperties p, Store store) {
    this.p = p;
    this.store = store;
  }

  /** 使用浏览器生成封面并按配置上传 OSS；失败返回提示而不影响发布结果。 */
  public String capture(String appId, String url) {
    if (!p.screenshotEnabled()) return "截图功能未启用";
    long started = System.nanoTime();
    log.info("Screenshot started: appId={}, ossEnabled={}", appId, p.oss().enabled());
    String id = appId + "-" + UUID.randomUUID() + ".png";
    Path target = p.screenshotDir().toAbsolutePath().resolve(id);
    try {
      Files.createDirectories(target.getParent());
      try (Playwright playwright =
              Playwright.create(
                  new Playwright.CreateOptions()
                      .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
          Browser browser =
              playwright
                  .chromium()
                  .launch(
                      new BrowserType.LaunchOptions()
                          .setHeadless(true)
                          .setChannel(p.browserChannel()))) {
        var page =
            browser.newPage(
                new Browser.NewPageOptions()
                    .setViewportSize(1440, 900)
                    .setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK));
        String origin =
            java.net.URI.create(p.previewBaseUrl()).getScheme()
                + "://"
                + java.net.URI.create(p.previewBaseUrl()).getAuthority();
        page.route(
            "**/*",
            route -> {
              String request = route.request().url();
              if (allowsResource(origin, request, route.request().resourceType())) route.resume();
              else route.abort();
            });
        page.navigate(url, new Page.NavigateOptions().setTimeout(20000));
        waitForImages(page);
        page.screenshot(
            new Page.ScreenshotOptions().setPath(target).setFullPage(false).setTimeout(10000));
      }
      String cover = "/api/covers/" + id;
      if (p.oss().enabled()) {
        var o = p.oss();
        if (o.accessKeyId().startsWith("YOUR_") || o.accessKeySecret().startsWith("YOUR_"))
          return "网站已发布；OSS 凭据尚未配置，截图保存在本地";
        var client =
            new OSSClientBuilder().build(o.endpoint(), o.accessKeyId(), o.accessKeySecret());
        try {
          client.putObject(o.bucket(), "covers/" + id, target.toFile());
        } finally {
          client.shutdown();
        }
        cover = o.publicBaseUrl().replaceAll("/$", "") + "/covers/" + id;
        if (cover.length() > 512) return "网站已发布；封面地址过长，未写入数据库";
      }
      store.cover(appId, cover);
      log.info("Screenshot updated: appId={}, elapsedMs={}",
          appId, (System.nanoTime() - started) / 1_000_000);
      return "封面已更新";
    } catch (Exception e) {
      // 截图是发布后的附加步骤，失败不撤销已生效的网站；不记录带票据的 URL。
      log.warn("Screenshot or upload failed: appId={}, category={}", appId, e.getClass().getSimpleName());
      return "网站已发布；截图或上传失败，可稍后重试";
    }
  }

  static boolean allowsResource(String origin, String url, String resourceType) {
    // Match the preview's img-src policy without enabling external scripts or fetches.
    return url.startsWith(origin + "/")
        || ("image".equals(resourceType) && url.startsWith("https://"));
  }

  static void waitForImages(Page page) {
    page.evaluate("""
        async () => {
          const visible = Array.from(document.images).filter(img => {
            const rect = img.getBoundingClientRect();
            return rect.bottom > 0 && rect.right > 0 &&
              rect.top < innerHeight && rect.left < innerWidth;
          });
          // Trigger native lazy loading in the viewport before waiting for decoding.
          visible.forEach(img => { img.loading = 'eager'; });
          let timer;
          try {
            await Promise.race([
              Promise.all(visible.map(img => img.decode().catch(() => {}))),
              new Promise(resolve => { timer = setTimeout(resolve, 8000); })
            ]);
          } finally {
            clearTimeout(timer);
          }
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        }
        """);
  }
}
