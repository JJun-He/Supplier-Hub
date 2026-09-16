package com.supplierhub.catalog.application;

import java.util.Objects;

public class CatalogReadException extends RuntimeException {

	public enum Reason {
		TIMEOUT,
		UNAVAILABLE
	}

	private final Reason reason;

	public CatalogReadException(Reason reason) {
		this(reason, null);
	}

	public CatalogReadException(Reason reason, Throwable cause) {
		super("Catalog mappings could not be read", cause);
		this.reason = Objects.requireNonNull(reason, "reason must not be null");
	}

	public Reason reason() {
		return reason;
	}

}
