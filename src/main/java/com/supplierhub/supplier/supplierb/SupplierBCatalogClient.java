package com.supplierhub.supplier.supplierb;

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
import com.supplierhub.supplier.common.SupplierHttpFailureMapper;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierTransportFailureMapper;

@Component
public class SupplierBCatalogClient implements SupplierCatalogClient {

	private static final String SUCCESS_CODE = "0000";

	private final WebClient webClient;

	public SupplierBCatalogClient(
		@Qualifier("supplierBWebClient") WebClient webClient
	) {
		this.webClient = webClient;
	}

	@Override
	public Supplier supplier() {
		return Supplier.SUPPLIER_B;
	}

	@Override
	public Mono<CatalogSnapshot> fetchCatalog() {
		return webClient.get()
			.uri("/b/api/properties")
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::httpFailure)
			.bodyToMono(PropertiesResponse.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(this::toSnapshot)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier B catalog request",
					cause
				)
			)
			.onErrorMap(
				DecodingException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier B catalog response was invalid",
					cause
				)
			)
			.onErrorMap(
				IllegalArgumentException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier B catalog response was incomplete",
					cause
				)
			);
	}

	private Mono<? extends Throwable> httpFailure(ClientResponse response) {
		return Mono.error(SupplierHttpFailureMapper.statusFailure(
			supplier(),
			response.statusCode(),
			"Supplier B catalog"
		));
	}

	private CatalogSnapshot toSnapshot(PropertiesResponse response) {
		if (!SUCCESS_CODE.equals(response.resultCode())) {
			throw bodyFailure(response.resultCode());
		}
		if (response.data() == null || response.data().items() == null) {
			throw invalidResponse();
		}

		List<CatalogProperty> properties = response.data().items().stream()
			.map(this::toProperty)
			.toList();
		return new CatalogSnapshot(supplier(), properties);
	}

	private CatalogProperty toProperty(Property property) {
		if (property == null || property.rooms() == null) {
			throw invalidResponse();
		}

		List<CatalogRoomType> roomTypes = property.rooms().stream()
			.map(this::toRoomType)
			.toList();
		return new CatalogProperty(
			property.propertyId(),
			property.propertyName(),
			roomTypes
		);
	}

	private CatalogRoomType toRoomType(Room room) {
		if (room == null) {
			throw invalidResponse();
		}
		return new CatalogRoomType(
			room.roomId(),
			room.roomName(),
			room.maxOccupancy()
		);
	}

	private SupplierIntegrationException bodyFailure(String resultCode) {
		if (resultCode == null) {
			return invalidResponse();
		}
		SupplierFailureType failureType = switch (resultCode) {
			case "E400" -> SupplierFailureType.INVALID_REQUEST;
			case "E401" -> SupplierFailureType.AUTHENTICATION_FAILED;
			case "E429" -> SupplierFailureType.RATE_LIMITED;
			case "E500", "E503" -> SupplierFailureType.UNAVAILABLE;
			default -> SupplierFailureType.INVALID_RESPONSE;
		};
		boolean retryable = "E500".equals(resultCode)
			|| "E503".equals(resultCode);

		return new SupplierIntegrationException(
			supplier(),
			failureType,
			retryable,
			"Supplier B catalog returned a failure result"
		);
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier B catalog response was incomplete"
		);
	}

	private record PropertiesResponse(
		String resultCode,
		String resultMessage,
		Data data
	) {
	}

	private record Data(List<Property> items) {
	}

	private record Property(
		String propertyId,
		String propertyName,
		List<Room> rooms
	) {
	}

	private record Room(
		String roomId,
		String roomName,
		int maxOccupancy
	) {
	}

}
