package com.supplierhub.supplier.common;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.supplierhub.catalog.domain.Supplier;

public final class SupplierTransportFailureMapper {

	private SupplierTransportFailureMapper() {
	}

	public static SupplierIntegrationException requestFailure(
		Supplier supplier,
		String requestDescription,
		WebClientRequestException cause
	) {
		if (isTimeout(cause)) {
			return timeoutFailure(supplier, requestDescription, cause);
		}
		return new SupplierIntegrationException(
			supplier,
			SupplierFailureType.UNAVAILABLE,
			true,
			requestDescription + " failed",
			cause
		);
	}

	public static SupplierIntegrationException responseFailure(
		Supplier supplier, String requestDescription, WebClientResponseException cause
	) {
		return new SupplierIntegrationException(
			supplier, SupplierFailureType.UNKNOWN, false,
			requestDescription + " response could not be read", cause
		);
	}

	public static SupplierIntegrationException timeoutFailure(
		Supplier supplier,
		String requestDescription,
		Throwable cause
	) {
		return new SupplierIntegrationException(
			supplier,
			SupplierFailureType.TIMEOUT,
			true,
			requestDescription + " timed out",
			cause
		);
	}

	public static boolean isTimeout(Throwable cause) {
		Throwable current = cause;
		while (current != null) {
			if (current instanceof TimeoutException
				|| current instanceof SocketTimeoutException
				|| current instanceof ConnectTimeoutException
				|| current instanceof ReadTimeoutException
				|| current instanceof WriteTimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

}
