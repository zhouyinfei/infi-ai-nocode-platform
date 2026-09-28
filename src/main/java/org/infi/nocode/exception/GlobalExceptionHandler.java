package org.infi.nocode.exception;

import org.infi.nocode.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** 统一将业务异常、参数错误和未知故障转换为 API 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<?> business(BusinessException e) {
    return ResponseEntity.status(e.status())
        .body(new ApiResponse<>(e.status(), e.getMessage(), null));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    return business(
        BusinessException.bad(
            e.getBindingResult().getFieldErrors().getFirst().getDefaultMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return business(BusinessException.bad("请求参数格式不正确"));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> duplicate() {
    return business(new BusinessException(409, "账号或标识已存在"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    // Do not log request bodies, model credentials or raw provider error payloads.
    log.error("Request failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(new ApiResponse<>(500, "服务暂时不可用，请检查服务配置或稍后重试", null));
  }
}
