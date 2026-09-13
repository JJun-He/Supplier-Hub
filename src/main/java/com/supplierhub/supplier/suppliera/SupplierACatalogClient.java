package com.supplierhub.supplier.suppliera;

import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogProperty;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogRoomType;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierCatalogClient;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierTransportFailureMapper;

@Component
public class SupplierACatalogClient implements SupplierCatalogClient {

	private final WebClient webClient;

	public SupplierACatalogClient(
		@Qualifier("supplierAWebClient") WebClient webClient
	) {
		this.webClient = webClient;
	}

	@Override
	public Supplier supplier() {
		return Supplier.SUPPLIER_A;
	}

	@Override
	public Mono<CatalogSnapshot> fetchCatalog() {
		return webClient.get()
			.uri("/a/v1/hotels")
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::httpFailure)
			.bodyToMono(HotelsResponse.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(this::toSnapshot)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier A catalog request",
					cause
				)
			)
			.onErrorMap(
				DecodingException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier A catalog response was invalid",
					cause
				)
			)
			.onErrorMap(
				IllegalArgumentException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier A catalog response was incomplete",
					cause
				)
			);
	}

	private Mono<? extends Throwable> httpFailure(ClientResponse response) {
		int status = response.statusCode().value();
		SupplierFailureType failureType = switch (status) {
			case 400 -> SupplierFailureType.INVALID_REQUEST;
			case 401 -> SupplierFailureType.AUTHENTICATION_FAILED;
			case 429 -> SupplierFailureType.RATE_LIMITED;
			default -> status >= 500
				? SupplierFailureType.UNAVAILABLE
				: SupplierFailureType.UNKNOWN;
		};
		boolean retryable = status >= 500;

		return Mono.error(new SupplierIntegrationException(
			supplier(),
			failureType,
			retryable,
			"Supplier A catalog returned an error status"
		));
	}

	private CatalogSnapshot toSnapshot(HotelsResponse response) {
		if (response.items() == null) {
			throw invalidResponse();
		}

		List<CatalogProperty> properties = response.items().stream()
			.map(this::toProperty)
			.toList();
		return new CatalogSnapshot(supplier(), properties);
	}

	private CatalogProperty toProperty(Hotel hotel) {
		if (hotel == null || hotel.roomTypes() == null) {
			throw invalidResponse();
		}

		List<CatalogRoomType> roomTypes = hotel.roomTypes().stream()
			.map(this::toRoomType)
			.toList();
		return new CatalogProperty(
			hotel.hotelCode(),
			hotel.hotelName(),
			roomTypes
		);
	}

	private CatalogRoomType toRoomType(RoomType roomType) {
		if (roomType == null) {
			throw invalidResponse();
		}
		return new CatalogRoomType(
			roomType.roomTypeCode(),
			roomType.roomTypeName(),
			roomType.maxOccupancy()
		);
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier A catalog response was incomplete"
		);
	}

	private record HotelsResponse(List<Hotel> items) {
	}

	private record Hotel(
		String hotelCode,
		String hotelName,
		List<RoomType> roomTypes
	) {
	}

	private record RoomType(
		String roomTypeCode,
		String roomTypeName,
		int maxOccupancy
	) {
	}

}
