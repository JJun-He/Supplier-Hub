package com.supplierhub.supplier.common;

import java.util.Objects;
import java.util.UUID;

import reactor.util.context.ContextView;

/** 검색 요청과 Supplier 배치의 로그를 연결하며 고객 응답에는 노출하지 않는다. */
public record SearchCallContext(String searchId, int batchIndex) {

	public SearchCallContext {
		Objects.requireNonNull(searchId, "searchId must not be null");
		if (searchId.isBlank() || batchIndex < 0) {
			throw new IllegalArgumentException("searchId must not be blank and batchIndex must not be negative");
		}
	}

	public static SearchCallContext from(ContextView context) {
		return context.<SearchCallContext>getOrEmpty(SearchCallContext.class)
			.orElseGet(SearchCallContext::direct);
	}

	public static SearchCallContext direct() {
		return new SearchCallContext(UUID.randomUUID().toString(), 0);
	}
}
