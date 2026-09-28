package org.infi.nocode.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.*;
import java.util.*;
import org.infi.nocode.core.ArtifactService;
import org.infi.nocode.core.ArtifactService.*;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.model.enums.CodeGenType;

/** 每个任务持有独立内存草稿；工具只能操作草稿，不能访问宿主文件系统。 */
public final class ToolManager {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ToolManager.class);
  private final Map<String, String> draft = new LinkedHashMap<>();
  private final Map<String, String> readVersions = new HashMap<>();
  private final ArtifactService artifacts;
  private final CodeGenType type;
  private final ObjectMapper json;
  private Artifact result;

  public ToolManager(Artifact base, ArtifactService artifacts, CodeGenType type, ObjectMapper json) {
    base.files().forEach(f -> draft.put(f.path(), f.content()));
    this.artifacts = artifacts;
    this.type = type;
    this.json = json;
    log.debug("Edit draft initialized: type={}, files={}", type, draft.size());
  }

  /** 导出模型可调用的工具定义。 */
  public List<ToolSpecification> specifications() {
    return ToolSpecifications.toolSpecificationsFrom(this);
  }

  /** 列出当前草稿文件名，供模型选择需要读取的文件。 */
  @Tool("列出当前应用的源文件路径，不返回源码。先用此工具了解文件结构。")
  public String file_list() { return encode(draft.keySet()); }

  /** 读取草稿并登记已读版本，作为后续修改的前置条件。 */
  @Tool("读取一个源文件的完整内容。修改或删除已有文件前必须先读取。")
  public String file_read(@P("应用内相对文件路径") String path) {
    String content = existing(path);
    readVersions.put(path, content);
    log.debug("Draft read version registered: type={}, characters={}", type, content.length());
    return encode(Map.of("path", path, "content", content));
  }

  /** 仅替换唯一匹配的已读片段，校验完整候选草稿后生效。 */
  @Tool("精确替换一个已读取文件中唯一匹配的文本片段。旧文本必须非空且仅出现一次；其他内容不变。")
  public String file_modify(@P("文件路径") String path,
      @P("需要替换的原文，必须唯一匹配") String oldText,
      @P("替换后的新文本，可以为空") String newText) {
    String content = requireRead(path);
    if (oldText == null || oldText.isEmpty() || newText == null)
      throw BusinessException.bad("替换原文不能为空，新文本必须是字符串");
    int start = content.indexOf(oldText);
    if (start < 0 || content.indexOf(oldText, start + 1) >= 0)
      throw BusinessException.bad("原文未匹配或存在多个匹配，请重新读取并提供唯一的上下文片段");
    put(path, content.substring(0, start) + newText + content.substring(start + oldText.length()));
    // The model knows the exact resulting file after this patch.
    readVersions.put(path, draft.get(path));
    return "文件片段已修改：" + path;
  }

  /** 新增或覆盖草稿文件，覆盖已有文件前必须读取最新版本。 */
  @Tool("新增一个源文件，或整文件替换已读取的文件。小改动优先使用 file_modify。")
  public String file_write(@P("文件路径") String path, @P("完整文件内容") String content) {
    artifacts.validatePath(path, type);
    if (draft.containsKey(path)) requireRead(path);
    if (content == null) throw BusinessException.bad("文件内容必须是字符串");
    put(path, content);
    readVersions.put(path, content);
    log.debug("Draft read version registered: type={}, characters={}", type, content.length());
    return "文件已写入草稿：" + path;
  }

  /** 校验删除后的完整草稿，保留必需入口文件。 */
  @Tool("删除一个已读取且确实不再需要的源文件。不能删除必需的入口文件。")
  public String file_delete(@P("文件路径") String path) {
    requireRead(path);
    // 先校验候选副本，验证失败不污染已完成的修改。
    var candidate = new LinkedHashMap<>(draft);
    candidate.remove(path);
    validate(candidate, "删除文件");
    draft.remove(path);
    readVersions.remove(path);
    log.debug("Draft file deleted: type={}, remainingFiles={}", type, draft.size());
    return "已从草稿删除：" + path;
  }

  /** 校验完整草稿并标记编辑完成；持久化及构建由上层负责。 */
  @Tool("所有修改完成后调用。校验完整草稿并结束工具调用，不要返回完整源码。")
  public String finish_edit(@P("简短中文修改说明，不包含源码") String summary) {
    if (summary == null || summary.isBlank()) throw BusinessException.bad("请提供修改说明");
    result = validate(draft, summary);
    log.debug("Edit draft validated: type={}, files={}", type, draft.size());
    return "草稿校验通过，等待平台保存并构建";
  }

  public boolean finished() { return result != null; }

  /** 生成面向用户的工具进度说明，文件路径须先通过校验。 */
  public String describe(ToolExecutionRequest request) {
    String action = switch (request.name()) {
      case "file_list" -> "正在查看文件目录";
      case "file_read" -> "正在读取文件";
      case "file_modify" -> "正在修改文件片段";
      case "file_write" -> "正在写入文件";
      case "file_delete" -> "正在删除草稿文件";
      case "finish_edit" -> "正在校验编辑结果";
      default -> "正在处理文件操作";
    };
    try {
      var args = json.readTree(request.arguments());
      if (args != null && args.has("path") && args.get("path").isTextual()) {
        String path = args.get("path").textValue();
        artifacts.validatePath(path, type);
        return action + "：" + path;
      }
    } catch (Exception ignored) { }
    return action;
  }
  /** 返回已完成且通过校验的产物，未完成时拒绝读取。 */
  public Artifact result() {
    if (result == null) throw BusinessException.bad("编辑尚未完成");
    return result;
  }

  /** 分发工具调用，将参数和业务错误反馈给模型以便修正。 */
  public String execute(ToolExecutionRequest request) {
    if (finished()) return "错误：本轮编辑已结束";
    try {
      JsonNode args = json.readTree(request.arguments());
      if (args == null || !args.isObject()) throw BusinessException.bad("工具参数必须是 JSON 对象");
      return switch (request.name()) {
        case "file_list" -> file_list();
        case "file_read" -> file_read(text(args, "path"));
        case "file_modify" -> file_modify(text(args, "path"), text(args, "oldText"), text(args, "newText"));
        case "file_write" -> file_write(text(args, "path"), text(args, "content"));
        case "file_delete" -> file_delete(text(args, "path"));
        case "finish_edit" -> finish_edit(text(args, "summary"));
        default -> "错误：未知工具";
      };
    } catch (BusinessException e) {
      log.debug("Edit tool rejected: type={}, status={}", type, e.status());
      return "错误：" + e.getMessage();
    } catch (Exception e) {
      log.debug("Edit tool arguments failed: type={}, category={}", type, e.getClass().getSimpleName());
      return "错误：工具参数无效，请检查参数名称及 JSON 格式";
    }
  }

  private String text(JsonNode args, String key) {
    if (!args.has(key) || !args.get(key).isTextual()) throw BusinessException.bad("缺少字符串参数：" + key);
    return args.get(key).textValue();
  }

  private String existing(String path) {
    artifacts.validatePath(path, type);
    if (!draft.containsKey(path)) throw BusinessException.bad("文件不存在：" + path);
    return draft.get(path);
  }

  // 修改前必须读取最新内容，避免模型使用过期片段覆盖本轮草稿。
  private String requireRead(String path) {
    String content = existing(path);
    if (!content.equals(readVersions.get(path))) throw BusinessException.bad("请先读取文件：" + path);
    return content;
  }

  /** 先校验候选副本再更新草稿，失败不污染已完成修改。 */
  private void put(String path, String content) {
    // 先校验候选副本，验证失败不污染已完成的修改。
    var candidate = new LinkedHashMap<>(draft);
    candidate.put(path, content);
    validate(candidate, "修改文件");
    draft.put(path, content);
    log.debug("Draft file updated: type={}, files={}, characters={}", type, draft.size(), content.length());
  }

  /** 复用产物服务对草稿执行与首次生成相同的完整校验。 */
  private Artifact validate(Map<String, String> files, String summary) {
    var artifact = new Artifact(summary,
        files.entrySet().stream().map(e -> new GeneratedFile(e.getKey(), e.getValue())).toList());
    return artifacts.parse(encode(artifact), type);
  }

  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception e) { throw BusinessException.bad("文件数据编码失败"); }
  }
}
