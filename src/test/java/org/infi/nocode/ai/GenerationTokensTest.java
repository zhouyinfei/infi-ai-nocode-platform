package org.infi.nocode.ai;

import static org.assertj.core.api.Assertions.*;

import org.infi.nocode.config.ModelProperties.GenerationTokens;
import org.infi.nocode.model.enums.CodeGenType;
import org.junit.jupiter.api.Test;

class GenerationTokensTest {
  @Test
  void rejectsNonPositiveLimits() {
    assertThatThrownBy(() -> new GenerationTokens(-1, 32000, 32000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GenerationTokens(16000, 0, 32000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GenerationTokens(16000, 32000, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GenerationTokens(0, 7999, 19999))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allowsModelSupportedBudgetsAboveOldBoundaries() {
    var limits = new GenerationTokens(16000, 32000, 65536);
    assertThat(limits.forType(CodeGenType.HTML)).isEqualTo(16000);
    assertThat(limits.forType(CodeGenType.MULTI_FILE)).isEqualTo(32000);
    assertThat(limits.forType(CodeGenType.VUE_PROJECT)).isEqualTo(65536);
  }

  @Test
  void allowsLowerConfiguredLimitsPerType() {
    var limits = new GenerationTokens(2000, 4000, 12000);
    assertThat(limits.forType(CodeGenType.HTML)).isEqualTo(2000);
    assertThat(limits.forType(CodeGenType.MULTI_FILE)).isEqualTo(4000);
    assertThat(limits.forType(CodeGenType.VUE_PROJECT)).isEqualTo(12000);
  }
}
