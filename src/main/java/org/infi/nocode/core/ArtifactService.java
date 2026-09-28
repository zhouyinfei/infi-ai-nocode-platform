package org.infi.nocode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.infi.nocode.config.PlatformProperties;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.model.enums.CodeGenType;
import org.springframework.stereotype.Service;

/** 管理生成产物的校验、版本保存、受控构建和发布目录。 */
@Service
public class ArtifactService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ArtifactService.class);

  public static class InvalidArtifactJson extends BusinessException {
    InvalidArtifactJson() {
      super(502, "模型未返回有效的完整文件 JSON，请重试");
    }
  }

  /** Retry formatting once; every replacement still passes the full artifact validation. */
  public Artifact parseOrRepair(String raw, CodeGenType type,
      java.util.function.Function<String, String> repair) {
    try {
      return parse(raw, type);
    } catch (InvalidArtifactJson invalid) {
      log.info("Repairing artifact JSON once: type={}", type);
      return parse(repair.apply(raw), type);
    }
  }

  public record GeneratedFile(String path, String content) {}

  public record Artifact(String summary, List<GeneratedFile> files) {}

  private final ObjectMapper json;
  private final PlatformProperties p;

  public ArtifactService(ObjectMapper json, PlatformProperties p) {
    this.json = json;
    this.p = p;
  }

  /** 解析并校验完整产物，限制文件路径、数量、体积和必需入口。 */
  public Artifact parse(String text, CodeGenType type) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > p.maxTotalBytes() * 2)
      throw BusinessException.bad("模型输出超出限制");
    String raw = text.strip();
    if (raw.startsWith("```")) {
      raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }
    Artifact artifact;
    try {
      artifact = json.readerFor(Artifact.class)
          .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(raw);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Do not log exception messages: Jackson may include generated code/private content.
      var location = e.getLocation();
      log.warn("Invalid artifact JSON: characters={}, category={}, line={}, column={}",
          raw.length(), e.getClass().getSimpleName(),
          location == null ? -1 : location.getLineNr(),
          location == null ? -1 : location.getColumnNr());
      throw new InvalidArtifactJson();
    }
    if (artifact == null) throw new InvalidArtifactJson();
    if (artifact.files() == null
        || artifact.files().isEmpty()
        || artifact.files().size() > p.maxFiles()) throw BusinessException.bad("生成文件数量不合法");
    var names = new HashSet<String>();
    long total = 0;
    for (var f : artifact.files()) {
      if (f == null) throw BusinessException.bad("生成文件不能为空");
      validatePath(f.path(), type);
      if (f.content() == null || !names.add(f.path().toLowerCase(Locale.ROOT)))
        throw BusinessException.bad("文件内容为空或路径重复");
      total += f.content().getBytes(StandardCharsets.UTF_8).length;
      if (total > p.maxTotalBytes()) throw BusinessException.bad("生成文件总体积超出限制");
      // Block truly external references: drive letters (C:), file:// URLs, and Vite /@fs/ escapes.
      // Relative (../) and root-relative (/) paths are allowed — they stay within the project tree.
      if (type == CodeGenType.VUE_PROJECT
          && (f.content().contains("/@fs/")
              || f.content()
                  .matches(
                      "(?s).*(?:from\\s*|import\\s*\\(|import\\s*|url\\s*\\(|(?:src|href)\\s*=\\s*)\\s*['\"](?:[A-Za-z]:|file:).*")))
        throw BusinessException.bad("Vue 文件包含不允许的外部路径引用");
    }
    if (!names.contains("index.html")) throw BusinessException.bad("生成结果缺少 index.html");
    if (type == CodeGenType.HTML && artifact.files().size() != 1)
      throw BusinessException.bad("HTML 模式只能包含 index.html");
    if (type == CodeGenType.VUE_PROJECT
        && (!names.contains("src/app.vue")
            || (!names.contains("src/main.js") && !names.contains("src/main.ts"))))
      throw BusinessException.bad("Vue 工程缺少入口或 App.vue");
    return new Artifact(
        artifact.summary() == null
            ? "生成完成"
            : artifact.summary().substring(0, Math.min(artifact.summary().length(), 500)),
        artifact.files());
  }

  /** 校验相对路径和生成类型允许的文件范围，阻止越界访问。 */
  public void validatePath(String name, CodeGenType type) {
    if (name == null
        || name.length() > 180
        || name.contains("\\")
        || name.contains(":")
        || name.startsWith("/")
        || name.contains("\0")) throw BusinessException.bad("非法文件路径");
    String[] parts = name.split("/", -1);
    for (String part : parts)
      if (part.isBlank()
          || part.startsWith(".")
          || !part.matches("[A-Za-z0-9_@-][A-Za-z0-9_.@-]*")
          || part.endsWith(".")
          || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))
        throw BusinessException.bad("非法文件路径");
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.contains("node_modules")
        || lower.contains("package")
        || lower.contains("lock")
        || lower.contains("config")
        || lower.startsWith("dist/")) throw BusinessException.bad("禁止生成依赖或构建配置文件");
    if (!lower.matches(".*\\.(html|css|js|ts|vue|json|svg)$"))
      throw BusinessException.bad("不允许的文件类型");
    if (type != CodeGenType.VUE_PROJECT && lower.matches(".*\\.(ts|vue|json)$"))
      throw BusinessException.bad("静态模式不支持工程文件");
    if (type == CodeGenType.VUE_PROJECT && !name.equals("index.html") && !name.startsWith("src/"))
      throw BusinessException.bad("Vue 源码须位于 src 下");
  }

  /** 写入独立版本并完成构建后切换当前指针，失败时保留旧版本。 */
  public Path save(String appId, String version, Artifact artifact, CodeGenType type)
      throws IOException {
    Path root = version(appId, version);
    // 构建和入口校验全部成功后才更新 current，失败时保留旧预览版本。
    writeArtifact(root, artifact, type);
    atomicWrite(p.outputDir().toAbsolutePath().resolve(appId).resolve("current"), version);
    log.info("Artifact activated: appId={}, version={}, type={}, files={}",
        appId, version, type, artifact.files().size());
    return type == CodeGenType.VUE_PROJECT ? root.resolve("dist") : root;
  }

  /** 写入产物清单和源文件，Vue 工程同时执行受控构建。 */
  private void writeArtifact(Path root, Artifact artifact, CodeGenType type) throws IOException {
    artifact = parse(json.writeValueAsString(artifact), type);
    Files.createDirectories(root);
    for (var f : artifact.files()) {
      Path target = root.resolve(f.path()).normalize();
      if (!target.startsWith(root)) throw BusinessException.bad("路径越界");
      Files.createDirectories(target.getParent());
      Files.writeString(target, f.content());
    }
    Files.writeString(root.resolve("artifact.json"), json.writeValueAsString(artifact));
    if (type == CodeGenType.VUE_PROJECT) build(root);
    Path site = type == CodeGenType.VUE_PROJECT ? root.resolve("dist") : root;
    if (!Files.isRegularFile(site.resolve("index.html"))) throw BusinessException.bad("未生成可预览入口");
  }

  private static final java.util.concurrent.ConcurrentMap<String, Object> EXAMPLE_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Cache immutable source AND built assets; user versions always receive independent copies. */
  public Artifact useExample(String exampleId, String prompt, String appId, String version,
      CodeGenType type, java.util.function.Supplier<Artifact> generate) throws IOException {
    if (!exampleId.matches("[a-z0-9-]+")) throw BusinessException.bad("无效示例标识");
    String digest;
    try {
      digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest((type.value() + "\n" + prompt).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    Path parent = p.outputDir().toAbsolutePath().normalize().resolve("examples");
    Files.createDirectories(parent);
    Path cached = parent.resolve(exampleId + "-" + digest);
    synchronized (EXAMPLE_LOCKS.computeIfAbsent(cached.toString(), key -> new Object())) {
      // The OS releases this lock on process exit; an interrupted staging folder is never reused.
      try (var channel = java.nio.channels.FileChannel.open(
              parent.resolve(exampleId + "-" + digest + ".lock"),
              StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var lock = channel.lock()) {
        if (!Files.isDirectory(cached)) {
          log.info("Example cache initialization: exampleId={}, type={}", exampleId, type);
          Path staging = Files.createTempDirectory(parent, "staging-");
          writeArtifact(staging, generate.get(), type);
          try {
            Files.move(staging, cached, StandardCopyOption.ATOMIC_MOVE);
          } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, cached);
          }
        }
        log.debug("Copying example snapshot: exampleId={}, appId={}, version={}", exampleId, appId, version);
        Artifact artifact = parse(Files.readString(cached.resolve("artifact.json")), type);
        Path site = type == CodeGenType.VUE_PROJECT ? cached.resolve("dist") : cached;
        if (!Files.isRegularFile(site.resolve("index.html")))
          throw new BusinessException(503, "示例产物不完整，请联系管理员恢复");
        Path target = version(appId, version);
        try (var paths = Files.walk(cached)) {
          for (Path file : paths.filter(Files::isRegularFile).toList()) {
            if (file.getFileName().toString().equals("build.log")) continue;
            Path out = target.resolve(cached.relativize(file));
            Files.createDirectories(out.getParent());
            Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
          }
        }
        atomicWrite(p.outputDir().toAbsolutePath().resolve(appId).resolve("current"), version);
        return artifact;
      }
    }
  }

  /** 运行受控 Vue 构建并限制等待时间，失败不激活当前版本。 */
  private void build(Path root) throws IOException {
    long started = System.nanoTime();
    log.info("Vue build started: artifactId={}", root.getFileName());
    Path script = p.vueBuilderDir().toAbsolutePath().normalize().resolve("build.mjs");
    if (!Files.exists(script.getParent().resolve("node_modules/vite")))
      throw new BusinessException(503, "Vue 构建依赖未安装，请在 builder 目录运行 npm ci --ignore-scripts");
    var builder =
        new ProcessBuilder(
                p.nodeExecutable(), "--max-old-space-size=512", script.toString(), root.toString())
            .directory(script.getParent().toFile())
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("build.log").toFile());
    var env = builder.environment();
    String path = env.get("PATH"), systemRoot = env.get("SystemRoot"), temp = env.get("TEMP");
    // 子进程只继承启动必需的环境，避免模型或云服务凭据进入构建环境。
    env.clear();
    if (path != null) env.put("PATH", path);
    if (systemRoot != null) env.put("SystemRoot", systemRoot);
    if (temp != null) env.put("TEMP", temp);
    Process process = builder.start();
    try {
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        log.warn("Vue build timed out: artifactId={}, timeoutSeconds=120", root.getFileName());
        throw BusinessException.bad("Vue 构建超时");
      }
      if (process.exitValue() != 0) {
        log.warn("Vue build failed: artifactId={}, exitCode={}", root.getFileName(), process.exitValue());
        throw BusinessException.bad("Vue 构建失败，请调整需求后重试");
      }
      log.info("Vue build succeeded: artifactId={}, elapsedMs={}",
          root.getFileName(), (System.nanoTime() - started) / 1_000_000);
    } catch (InterruptedException e) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("构建中断", e);
    }
  }

  /** 校验应用及版本标识后解析产物目录。 */
  public Path version(String appId, String version) {
    if (!appId.matches("[0-9]+") || !version.matches("[a-f0-9-]{36}"))
      throw BusinessException.bad("无效产物标识");
    return p.outputDir().toAbsolutePath().normalize().resolve(appId).resolve(version);
  }

  /** 读取当前版本指针，无成功产物时返回业务异常。 */
  public String current(String appId) {
    try {
      return Files.readString(p.outputDir().toAbsolutePath().resolve(appId).resolve("current"))
          .strip();
    } catch (IOException e) {
      throw new BusinessException(404, "尚无成功生成结果");
    }
  }

  /** 读取当前产物清单，读取失败时返回无已有文件的兼容提示。 */
  public String context(String appId) {
    try {
      return Files.readString(version(appId, current(appId)).resolve("artifact.json"));
    } catch (Exception e) {
      return "无已有文件";
    }
  }

  /** 读取并重新校验当前草稿；不存在时返回空值，读取异常时阻止编辑。 */
  public Artifact currentArtifact(String appId, CodeGenType type) {
    String version;
    try { version = current(appId); }
    catch (BusinessException e) {
      if (e.status() == 404) return null;
      throw e;
    }
    try {
      return parse(Files.readString(version(appId, version).resolve("artifact.json")), type);
    } catch (IOException e) {
      log.warn("Current artifact read failed: appId={}, version={}, category={}",
          appId, version, e.getClass().getSimpleName());
      throw new BusinessException(503, "无法读取当前版本，已停止编辑以保护已有文件");
    }
  }

  /** 根据生成类型选择静态根目录或 Vue 构建输出目录。 */
  public Path site(String appId, String version, CodeGenType type) {
    Path root = version(appId, version);
    return type == CodeGenType.VUE_PROJECT ? root.resolve("dist") : root;
  }

  /** 从产物清单提取文件列表，清单不可读时返回空列表。 */
  public List<String> files(String appId) {
    try {
      Artifact a = json.readValue(context(appId), Artifact.class);
      return a.files().stream().map(GeneratedFile::path).toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  /** 仅从当前清单读取指定文件内容，不接受任意磁盘路径。 */
  public String source(String appId, String name) {
    try {
      Artifact a = json.readValue(context(appId), Artifact.class);
      return a.files().stream()
          .filter(f -> f.path().equals(name))
          .findFirst()
          .orElseThrow(() -> new BusinessException(404, "文件不存在"))
          .content();
    } catch (IOException e) {
      throw new BusinessException(404, "文件不存在");
    }
  }

  /** 复制公开资源至临时发布目录，校验入口后将目录移入版本位置。 */
  public Path publish(String appId, String key, CodeGenType type) throws IOException {
    log.debug("Publication snapshot preparation started: appId={}, type={}", appId, type);
    String version = current(appId);
    Path source = site(appId, version, type);
    Path root = deployRoot(key);
    Path target = root.resolve(version);
    if (Files.isRegularFile(target.resolve("index.html"))) return target;
    Path staging = root.resolve(version + ".staging-" + UUID.randomUUID());
    Files.createDirectories(staging);
    try (var stream = Files.walk(source)) {
      for (Path file : stream.filter(Files::isRegularFile).toList()) {
        String relative = source.relativize(file).toString();
        if (relative.equals("artifact.json") || relative.equals("build.log")) continue;
        Path out = staging.resolve(relative);
        Files.createDirectories(out.getParent());
        Files.copy(file, out);
      }
    }
    if (!Files.isRegularFile(staging.resolve("index.html")))
      throw BusinessException.bad("发布产物缺少入口文件");
    try {
      Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(staging, target);
    }
    return target;
  }

  /** 校验发布标识并解析发布根目录。 */
  public Path deployRoot(String key) {
    if (key == null || !key.matches("(?:[A-Za-z0-9]{6}|[a-f0-9]{32})"))
      throw new BusinessException(404, "部署不存在");
    return p.deployDir().toAbsolutePath().normalize().resolve(key);
  }

  /** 切换发布指针，使指定版本对外生效。 */
  public void activateDeploy(String key, String version) throws IOException {
    atomicWrite(deployRoot(key).resolve("current"), version);
  }

  /** 临时文件与指针位于同一目录，优先原子替换，避免读到写入一半的版本号。 */
  public void atomicWrite(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Path temp = Files.createTempFile(file.getParent(), "pointer-", ".tmp");
    Files.writeString(temp, content);
    try {
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
