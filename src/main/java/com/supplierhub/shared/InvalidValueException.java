package com.supplierhub.shared;

/** 값 검증에서 명시적으로 거부한 입력을 예상 밖 내부 예외와 구분한다. */
public final class InvalidValueException extends IllegalArgumentException {

	public InvalidValueException(String message) {
		super(message);
	}

	public InvalidValueException(String message, Throwable cause) {
		super(message, cause);
	}

}
