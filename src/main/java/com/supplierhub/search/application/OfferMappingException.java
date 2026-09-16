package com.supplierhub.search.application;

/** 어댑터가 OfferCandidate 변환 전에 외부 항목을 거부한 경우다. */
public final class OfferMappingException extends RuntimeException {

	public OfferMappingException(Throwable cause) {
		super("Supplier offer item was invalid", cause);
	}
}
