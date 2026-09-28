package org.infi.nocode.ai;

import java.util.function.Consumer;
import org.infi.nocode.model.enums.CodeGenType;

/** 模型能力边界：分类、优化、首次生成和基于隔离草稿的增量编辑。 */
public interface AiGateway {
  /** 返回平台支持的生成类型标识，由实现校验模型输出。 */
  String classify(String prompt);

  /** 保留用户目标和约束，将原始需求整理成可执行提示词。 */
  String optimize(String prompt);

  /** 将流式片段交给回调，并返回完整产物文本供后续校验。 */
  String generate(CodeGenType type, String system, String context, Consumer<String> token);

  /** 通过工具修改草稿并报告进度，只有完整校验通过后才返回待保存产物。 */
  org.infi.nocode.core.ArtifactService.Artifact edit(CodeGenType type, String system,
      String context, org.infi.nocode.ai.tool.ToolManager tools, Consumer<String> token,
      Consumer<String> progress);
}
