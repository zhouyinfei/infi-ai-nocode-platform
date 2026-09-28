package org.infi.nocode.generator;

import java.util.Locale;
import org.infi.nocode.ai.AiGateway;
import org.infi.nocode.model.enums.CodeGenType;
import org.springframework.stereotype.Component;

/** 根据需求选择 HTML、多文件静态站点或 Vue 工程；应用创建后保持类型。 */
@Component
public class TypeRouter {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(TypeRouter.class);
  private final AiGateway ai;

  public TypeRouter(AiGateway ai) {
    this.ai = ai;
  }

  /** 优先根据明确的复杂度与页面数量规则路由，未命中时交由模型分类。 */
  public CodeGenType route(String prompt) {
    String p = prompt.toLowerCase(Locale.ROOT);
    if (p.matches("(?s).*(数据管理|管理系统|复杂交互|仪表盘|购物车|增删改查|dashboard|crud).*")) {
      log.debug("Generation type routed: source=rule, type=vue_project");
      return CodeGenType.VUE_PROJECT;
    }
    if (p.matches("(?s).*(多个页面|多页面|多页网站|multi.page).*")) {
      log.debug("Generation type routed: source=rule, type=multi_file");
      return CodeGenType.MULTI_FILE;
    }
    if (p.matches("(?s).*(单页|展示页|落地页|个人介绍|landing page).*")) {
      log.debug("Generation type routed: source=rule, type=html");
      return CodeGenType.HTML;
    }
    log.debug("Generation type classification delegated to AI");
    var type = CodeGenType.of(ai.classify(prompt).replace("`", "").strip());
    log.debug("Generation type routed: source=ai, type={}", type);
    return type;
  }
}
