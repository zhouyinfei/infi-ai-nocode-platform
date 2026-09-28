package org.infi.nocode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.infi.nocode.ai.AiGateway;
import org.infi.nocode.exception.BusinessException;
import org.infi.nocode.generator.TypeRouter;
import org.infi.nocode.mapper.Store;
import org.infi.nocode.model.entity.*;
import org.infi.nocode.model.enums.CodeGenType;
import org.infi.nocode.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthAndRoutingTest {
  @Test
  void complexityTakesPriorityOverPageCount() {
    var ai = mock(AiGateway.class);
    var router = new TypeRouter(ai);
    assertThat(router.route("个人单页展示页")).isEqualTo(CodeGenType.HTML);
    assertThat(router.route("多个页面的公司网站")).isEqualTo(CodeGenType.MULTI_FILE);
    assertThat(router.route("单页库存数据管理仪表盘")).isEqualTo(CodeGenType.VUE_PROJECT);
    verifyNoInteractions(ai);
  }

  @Test
  void ambiguousRequestUsesOnlyAllowedModelResult() {
    var ai = mock(AiGateway.class);
    when(ai.classify("一个网站")).thenReturn("not_valid");
    assertThatThrownBy(() -> new TypeRouter(ai).route("一个网站"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void anonymousNonAdminAndWrongOwnerAreRejected() {
    var store = mock(Store.class);
    var auth = new AuthService(store);
    var request = new MockHttpServletRequest();
    assertThatThrownBy(() -> auth.require(request)).isInstanceOf(BusinessException.class);
    request.getSession().setAttribute("uid", "1");
    when(store.user("1"))
        .thenReturn(new User("1", "alice", "hash", "Alice", null, null, "user", null));
    assertThatThrownBy(() -> auth.admin(request)).isInstanceOf(BusinessException.class);
    when(store.app("2"))
        .thenReturn(new App("2", "test", null, null, "html", null, null, 0, "other", null, null));
    assertThatThrownBy(() -> auth.owner("2", "1")).isInstanceOf(BusinessException.class);
  }

  @Test
  void passwordsAreSaltedAndChecked() {
    var encoder = new BCryptPasswordEncoder();
    String a = encoder.encode("secret123"), b = encoder.encode("secret123");
    assertThat(a).isNotEqualTo(b);
    assertThat(encoder.matches("secret123", a)).isTrue();
    assertThat(encoder.matches("wrong", a)).isFalse();
  }
}
