package org.infi.nocode.ratelimiter;

import java.util.List;
import org.infi.nocode.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 基于 Redis 固定时间窗口限制请求频率。 */
@Component
public class RateLimiter {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(RateLimiter.class);
  private final StringRedisTemplate redis;
  private final String namespace;
  // 计数与首次设置过期时间在同一个 Lua 脚本中完成，避免产生永不过期的计数器。
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],ARGV[1])"
              + " end; return n",
          Long.class);

  public RateLimiter(
      StringRedisTemplate redis,
      @org.springframework.beans.factory.annotation.Value(
              "${nocode.rate-limit-namespace:infi:nocode:rate:}")
          String namespace) {
    this.redis = redis;
    this.namespace = namespace;
  }

  /** 通过 Redis 原子脚本执行固定窗口计数，超限时拒绝请求。 */
  public void check(String key, int max, int seconds) {
    Long n = redis.execute(SCRIPT, List.of(namespace + key), Integer.toString(seconds));
    if (n != null && n > max) {
      // key 可能包含客户端地址，不写入日志。
      log.debug("Rate limit exceeded: max={}, windowSeconds={}", max, seconds);
      throw new BusinessException(429, "操作过于频繁，请稍后重试");
    }
  }
}
