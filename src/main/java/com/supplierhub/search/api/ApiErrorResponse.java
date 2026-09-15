package com.supplierhub.search.api;

import java.util.Objects;

public record ApiErrorResponse(String code, String message) {

	public ApiErrorResponse {
		Objects.requireNonNull(code, "code must not be null");
		Objects.requireNonNull(message, "message must not be null");
	}

}
