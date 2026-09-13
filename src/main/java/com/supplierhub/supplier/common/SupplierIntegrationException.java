package com.supplierhub.supplier.common;

import com.supplierhub.catalog.domain.Supplier;

public class SupplierIntegrationException extends RuntimeException {

	private final Supplier supplier;
	private final SupplierFailureType failureType;
	private final boolean retryable;

	public SupplierIntegrationException(
		Supplier supplier,
		SupplierFailureType failureType,
		boolean retryable,
		String message
	) {
		super(message);
		this.supplier = supplier;
		this.failureType = failureType;
		this.retryable = retryable;
	}

	public SupplierIntegrationException(
		Supplier supplier,
		SupplierFailureType failureType,
		boolean retryable,
		String message,
		Throwable cause
	) {
		super(message, cause);
		this.supplier = supplier;
		this.failureType = failureType;
		this.retryable = retryable;
	}

	public Supplier getSupplier() {
		return supplier;
	}

	public SupplierFailureType getFailureType() {
		return failureType;
	}

	public boolean isRetryable() {
		return retryable;
	}

}
