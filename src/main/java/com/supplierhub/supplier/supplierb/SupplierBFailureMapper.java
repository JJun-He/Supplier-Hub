package com.supplierhub.supplier.supplierb;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;

final class SupplierBFailureMapper {

	private SupplierBFailureMapper() {
	}

	static SupplierIntegrationException bodyFailure(String code, String operation) {
		SupplierFailureType type = switch (code == null ? "" : code) {
			case "E400" -> SupplierFailureType.INVALID_REQUEST;
			case "E401" -> SupplierFailureType.AUTHENTICATION_FAILED;
			case "E429" -> SupplierFailureType.RATE_LIMITED;
			case "E500", "E503" -> SupplierFailureType.UNAVAILABLE;
			default -> SupplierFailureType.INVALID_RESPONSE;
		};
		return new SupplierIntegrationException(
			Supplier.SUPPLIER_B, type, "E500".equals(code) || "E503".equals(code),
			"Supplier B " + operation + " returned a failure result"
		);
	}
}
