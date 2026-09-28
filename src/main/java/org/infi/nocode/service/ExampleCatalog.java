package org.infi.nocode.service;

import java.util.List;
import java.util.Optional;

/** Canonical example prompts shared by the UI, type routing and artifact cache. */
public final class ExampleCatalog {
  public record Example(String id, String title, String description, String codeGenType, String prompt) {}

  public static final List<Example> ALL = List.of(
      new Example("portfolio-v2", "个人作品集", "极简排版 · 项目筛选 · 联系方式", "html", """
          做一个极简风格的中文个人作品集，暖白背景搭配绿色点缀，适配手机。
          包含个人介绍、作品展示和联系方式，作品支持分类筛选和详情查看。
          使用示例文案和插画，导航可跳转，点击邮箱即可联系。
          """.strip()),
      new Example("coffee-v2", "品牌官网", "咖啡品牌 · 多页导航 · 门店菜单", "multi_file", """
          为「山屿咖啡」做一个温暖复古的中文官网，使用奶油白和咖啡棕配色。
          包含首页、菜单、品牌故事和门店四个页面，展示饮品价格与门店信息。
          菜单支持分类筛选，页面可互相跳转，适配手机浏览。
          """.strip()),
      new Example("ecommerce-v2", "电商商城", "商品浏览 · 购物车 · 订单结算", "vue_project", """
          做一个时尚的中文电商页面，使用橙色活力配色和卡片布局，适配手机。
          展示多款商品图片和价格，支持分类筛选、搜索、加入购物车和结算。
          购物车可调整数量删除商品，订单页填写地址并提交，数据本地保存。
          """.strip()),
      new Example("blog-v2", "个人博客", "文章搜索 · 分类阅读 · 深浅主题", "html", """
          做一个关于技术、阅读和生活的中文个人博客，采用简洁的杂志风格。
          包含作者介绍和六篇示例文章，支持关键词搜索、分类筛选和正文阅读。
          提供深浅主题切换并记住选择，适配手机，附上邮箱联系入口。
          """.strip()));

  public static Optional<Example> match(String prompt) {
    return ALL.stream().filter(e -> e.prompt().equals(prompt.strip())).findFirst();
  }

  private ExampleCatalog() {}
}
