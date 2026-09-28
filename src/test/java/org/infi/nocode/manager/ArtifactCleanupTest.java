package org.infi.nocode.manager;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactCleanupTest {
  @TempDir Path root;

  @Test
  void retainsLiveVersionButRemovesOldUnreferencedArtifacts() throws Exception {
    Path live = Files.createDirectories(root.resolve("1/live"));
    Path old = Files.createDirectories(root.resolve("1/old"));
    Path recent = Files.createDirectories(root.resolve("1/recent"));
    Path deleted = Files.createDirectories(root.resolve("2/old"));
    Files.writeString(live.resolve("index.html"), "live");
    Files.writeString(old.resolve("index.html"), "old");
    Files.writeString(root.resolve("1/current"), "live");
    var age = FileTime.from(Instant.now().minus(Duration.ofDays(8)));
    Files.setLastModifiedTime(live, age);
    Files.setLastModifiedTime(old, age);
    Files.setLastModifiedTime(deleted.getParent(), age);
    Path example = Files.createDirectories(root.resolve("examples/portfolio-cache"));
    Files.writeString(example.resolve("index.html"), "shared example");
    Files.setLastModifiedTime(example, age);
    Files.setLastModifiedTime(example.getParent(), age);
    var properties = org.mockito.Mockito.mock(org.infi.nocode.config.PlatformProperties.class);
    org.mockito.Mockito.when(properties.outputDir()).thenReturn(root);
    new ArtifactCleanup(properties, null).cleanVersions(root, Set.of("1"));
    assertThat(example.resolve("index.html")).exists();
    assertThat(live.resolve("index.html")).exists();
    assertThat(old).doesNotExist();
    assertThat(recent).exists();
    assertThat(root.resolve("2")).doesNotExist();
    assertThat(root.resolve("1/current")).exists();
  }
}
