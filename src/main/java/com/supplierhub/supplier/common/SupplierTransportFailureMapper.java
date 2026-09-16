package com.supplierhub.supplier.common;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;

import org.springframework.core.io.buffer.DataBufferLimitException;
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
		if (isResourceFailure(cause)) {
			return resourceFailure(supplier, requestDescription, cause);
		}
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
		if (isResourceFailure(cause)) {
			return resourceFailure(supplier, requestDescription, cause);
		}
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

	public static boolean isResourceFailure(Throwable cause) {
		return resourceFailureType(cause) != null;
	}

	public static SupplierIntegrationException resourceFailure(
		Supplier supplier, String requestDescription, Throwable cause
	) {
		SupplierFailureType type = resourceFailureType(cause);
		if (type == null) {
			throw new IllegalArgumentException("cause must be a resource failure");
		}
		return new SupplierIntegrationException(
			supplier, type, false, requestDescription + " exceeded resource limits", cause
		);
	}

	private static SupplierFailureType resourceFailureType(Throwable cause) {
		Throwable current = cause;
		while (current != null) {
			if (current instanceof DataBufferLimitException) {
				return SupplierFailureType.RESPONSE_TOO_LARGE;
			}
			// Reactor Netty 1.3.x의 풀 예외 의존은 이 경계에만 둔다.
			if (current instanceof PoolAcquirePendingLimitException
				|| current instanceof PoolAcquireTimeoutException) {
				return SupplierFailureType.CAPACITY_EXCEEDED;
			}
			current = current.getCause();
		}
		return null;
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
