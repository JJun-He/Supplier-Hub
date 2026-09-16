package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.*;

import com.supplierhub.catalog.domain.Supplier;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

class SupplierFailureMapperTests {

	@ParameterizedTest
	@CsvSource({
		"400,INVALID_REQUEST,false",
		"401,AUTHENTICATION_FAILED,false",
		"429,RATE_LIMITED,false",
		"500,UNAVAILABLE,true",
		"503,UNAVAILABLE,true",
		"404,UNKNOWN,false"
	})
	void classifiesHttpStatusAndRetryPolicy(
			int status, SupplierFailureType expected, boolean retryable) {
		for (Supplier supplier : Supplier.values()) {
			var error =
					SupplierHttpFailureMapper.statusFailure(
							supplier, HttpStatusCode.valueOf(status), "test");
			assertThat(error.getFailureType()).isEqualTo(expected);
			assertThat(error.isRetryable()).isEqualTo(retryable);
		}
	}

	static Stream<Throwable> timeouts() {
		return Stream.of(
				new TimeoutException(),
				new SocketTimeoutException(),
				new ConnectTimeoutException(),
				ReadTimeoutException.INSTANCE,
				WriteTimeoutException.INSTANCE);
	}

	@ParameterizedTest
	@MethodSource("timeouts")
	void recognizesNestedTimeoutCause(Throwable timeout) {
		var requestError =
				new WebClientRequestException(
						new RuntimeException(timeout),
						HttpMethod.GET,
						URI.create("http://localhost"),
						new HttpHeaders());
		var mapped =
				SupplierTransportFailureMapper.requestFailure(
						Supplier.SUPPLIER_A, "test", requestError);
		assertThat(mapped.getFailureType()).isEqualTo(SupplierFailureType.TIMEOUT);
		assertThat(mapped.isRetryable()).isTrue();
		assertThat(mapped.getCause()).isSameAs(requestError);
	}

	@Test
	void distinguishesNonTimeoutTransportAndUnclassifiedResponseFailures() {
		var requestError =
				new WebClientRequestException(
						new IOException("connection reset"),
						HttpMethod.GET,
						URI.create("http://localhost"),
						new HttpHeaders());
		var requestFailure =
				SupplierTransportFailureMapper.requestFailure(
						Supplier.SUPPLIER_A, "test", requestError);
		assertThat(requestFailure.getFailureType()).isEqualTo(SupplierFailureType.UNAVAILABLE);
		assertThat(requestFailure.isRetryable()).isTrue();
		var responseError =
				WebClientResponseException.create(200, "OK", new HttpHeaders(), new byte[0], null);
		var responseFailure =
				SupplierTransportFailureMapper.responseFailure(
						Supplier.SUPPLIER_A, "test", responseError);
		assertThat(responseFailure.getFailureType()).isEqualTo(SupplierFailureType.UNKNOWN);
		assertThat(responseFailure.isRetryable()).isFalse();
		assertThat(responseFailure.getCause()).isSameAs(responseError);
	}
}
