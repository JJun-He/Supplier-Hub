package com.supplierhub.search.application;

/** An adapter rejected an external item before it could become an OfferCandidate. */
public final class OfferMappingException extends RuntimeException {

	public OfferMappingException(Throwable cause) {
		super("Supplier offer item was invalid", cause);
	}
}
