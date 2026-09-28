package org.infi.nocode.manager;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.mapper.AppMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retain live pointers and local covers; purge only artifacts older than seven days. */
@Component
public class ArtifactCleanup {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ArtifactCleanup.class);
  private final PlatformProperties p;
  private final AppMapper apps;

  public ArtifactCleanup(PlatformProperties p, AppMapper apps) {
    this.p = p;
    this.apps = apps;
  }

  /** 扫描有效应用、发布和封面引用，仅清理超过保留期限的无用产物。 */
  @Scheduled(cron = "0 20 3 * * *")
  public void run() {
    long started = System.nanoTime();
    log.info("Artifact cleanup started");
    try {
      Set<String> activeApps = new HashSet<>(apps.activeIds());
      Set<String> deploys = new HashSet<>(apps.activeDeployKeys());
      Set<String> covers = new HashSet<>(apps.activeCovers());
      cleanVersions(p.outputDir(), activeApps);
      cleanVersions(p.deployDir(), deploys);
      Path screenshots = p.screenshotDir().toAbsolutePath().normalize();
      if (Files.isDirectory(screenshots))
        try (var stream = Files.list(screenshots)) {
          for (Path f : stream.toList())
            if (!covers.contains("/api/covers/" + f.getFileName()) && old(f))
              remove(screenshots, f);
        }
      log.info("Artifact cleanup completed: activeApps={}, elapsedMs={}",
          activeApps.size(), (System.nanoTime() - started) / 1_000_000);
    } catch (Exception e) {
      log.warn("Artifact cleanup skipped: {}", e.getClass().getSimpleName());
    }
  }

  /** 保留当前指针及共享示例，清理失效归属和过期历史版本。 */
  void cleanVersions(Path directory, Set<String> active) throws Exception {
    Path root = directory.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) return;
    try (var owners = Files.list(root)) {
      for (Path owner : owners.toList()) {
        if (!Files.isDirectory(owner) || Files.isSymbolicLink(owner)) continue;
        // Shared example snapshots outlive the users and applications that first created them.
        if (root.equals(p.outputDir().toAbsolutePath().normalize())
            && owner.getFileName().toString().equals("examples")) continue;
        if (!active.contains(owner.getFileName().toString())) {
          if (old(owner)) remove(root, owner);
          continue;
        }
        String current =
            Files.isRegularFile(owner.resolve("current"))
                ? Files.readString(owner.resolve("current")).strip()
                : "";
        try (var versions = Files.list(owner)) {
          for (Path v : versions.toList())
            if (!v.getFileName().toString().equals("current")
                && !v.getFileName().toString().equals(current)
                && old(v)) remove(root, v);
        }
      }
    }
  }

  private boolean old(Path path) throws Exception {
    return Files.getLastModifiedTime(path)
        .toInstant()
        .isBefore(Instant.now().minus(Duration.ofDays(7)));
  }

  /** 校验清理边界后删除目标，符号链接仅删除链接自身。 */
  private void remove(Path root, Path target) throws Exception {
    Path normalized = target.toAbsolutePath().normalize();
    if (normalized.equals(root) || !normalized.startsWith(root))
      throw new IllegalArgumentException("Cleanup boundary violation");
    if (Files.isSymbolicLink(normalized)) {
      Files.delete(normalized);
      return;
    }
    try (var tree = Files.walk(normalized)) {
      for (Path entry : tree.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(entry);
    }
  }
}
