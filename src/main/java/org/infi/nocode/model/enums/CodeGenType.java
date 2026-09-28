package org.infi.nocode.model.enums;

import org.infi.nocode.exception.BusinessException;

public enum CodeGenType {
  HTML("原生 HTML 模式", "html"),
  MULTI_FILE("原生多文件模式", "multi_file"),
  VUE_PROJECT("Vue 工程模式", "vue_project");
  private final String label;
  private final String value;

  CodeGenType(String label, String value) {
    this.label = label;
    this.value = value;
  }

  public String label() {
    return label;
  }

  public String value() {
    return value;
  }

  public static CodeGenType of(String value) {
    for (var type : values()) if (type.value.equals(value)) return type;
    throw BusinessException.bad("不支持的代码生成类型");
  }
}
