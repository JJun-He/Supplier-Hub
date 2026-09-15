package com.supplierhub.search.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = StaySearchController.class)
public class SearchApiExceptionHandler {

	@ExceptionHandler(InvalidSearchCriteriaException.class)
	public ResponseEntity<ApiErrorResponse> invalidSearchCriteria(
		InvalidSearchCriteriaException exception
	) {
		return ResponseEntity.badRequest().body(new ApiErrorResponse(
			"INVALID_SEARCH_CRITERIA",
			exception.getMessage()
		));
	}

	@ExceptionHandler({
		MissingServletRequestParameterException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ApiErrorResponse> invalidRequestParameter() {
		return ResponseEntity.badRequest().body(new ApiErrorResponse(
			"INVALID_REQUEST_PARAMETER",
			"Required search parameters are missing or malformed"
		));
	}

}
