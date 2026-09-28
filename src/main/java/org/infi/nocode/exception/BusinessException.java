package org.infi.nocode.exception;

public class BusinessException extends RuntimeException {
  private final int status;

  public BusinessException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static BusinessException bad(String message) {
    return new BusinessException(400, message);
  }
}
