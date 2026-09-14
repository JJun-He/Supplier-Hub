package com.supplierhub.supplier.common;

import org.springframework.http.HttpStatusCode;

import com.supplierhub.catalog.domain.Supplier;

public final class SupplierHttpFailureMapper {

	private SupplierHttpFailureMapper() {
	}

	public static SupplierIntegrationException statusFailure(
		Supplier supplier,
		HttpStatusCode statusCode,
		String requestDescription
	) {
		int status = statusCode.value();
		SupplierFailureType failureType = switch (status) {
			case 400 -> SupplierFailureType.INVALID_REQUEST;
			case 401 -> SupplierFailureType.AUTHENTICATION_FAILED;
			case 429 -> SupplierFailureType.RATE_LIMITED;
			default -> status >= 500
				? SupplierFailureType.UNAVAILABLE
				: SupplierFailureType.UNKNOWN;
		};

		return new SupplierIntegrationException(
			supplier,
			failureType,
			status >= 500,
			requestDescription + " returned an error status"
		);
	}

}
