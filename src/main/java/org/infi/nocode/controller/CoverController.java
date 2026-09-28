package org.infi.nocode.controller;

import java.nio.file.*;
import org.infi.nocode.config.PlatformProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 提供本地封面图片访问，并限制文件名和访问路径。 */
@RestController
public class CoverController {
  private final PlatformProperties p;

  public CoverController(PlatformProperties p) {
    this.p = p;
  }

  /** 校验封面文件名并返回本地 PNG 图片，文件名不合法或文件不存在时返回未找到。 */
  @GetMapping("/api/covers/{name}")
  public ResponseEntity<?> cover(@PathVariable String name) {
    if (!name.matches("[0-9]+-[a-f0-9-]{36}\\.png")) return ResponseEntity.notFound().build();
    Path path = p.screenshotDir().toAbsolutePath().resolve(name);
    if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(path));
  }
}
