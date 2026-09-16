package com.supplierhub.supplier.supplierb;

import java.util.List;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.supplierhub.shared.InvalidValueException;
import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogProperty;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogRoomType;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.supplier.common.SupplierCatalogClient;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierHttpFailureMapper;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierJson;
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
			.bodyToMono(JsonNode.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(this::toSnapshot)
			.onErrorMap(
				cause -> !(cause instanceof SupplierIntegrationException)
					&& SupplierTransportFailureMapper.isResourceFailure(cause),
				cause -> SupplierTransportFailureMapper.resourceFailure(
					supplier(), "Supplier request", cause
				)
			)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier B catalog request",
					cause
				)
			)
			.onErrorMap(
				WebClientResponseException.class,
				cause -> SupplierTransportFailureMapper.responseFailure(
					supplier(), "Supplier B catalog request", cause
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
				InvalidValueException.class,
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

	private CatalogSnapshot toSnapshot(JsonNode response) {
		String code = SupplierJson.text(response, "resultCode");
		if (!SUCCESS_CODE.equals(code)) {
			throw SupplierBFailureMapper.bodyFailure(code, "catalog");
		}
		List<CatalogProperty> properties = SupplierJson.array(SupplierJson.field(response, "data"), "items")
			.stream().map(this::toProperty).toList();
		return new CatalogSnapshot(supplier(), properties);
	}

	private CatalogProperty toProperty(JsonNode item) {
		List<CatalogRoomType> rooms = SupplierJson.array(item, "rooms").stream()
			.map(room -> new CatalogRoomType(
				SupplierJson.text(room, "roomId"), SupplierJson.text(room, "roomName"),
				SupplierJson.integer(room, "maxOccupancy")
			)).toList();
		return new CatalogProperty(SupplierJson.text(item, "propertyId"), SupplierJson.text(item, "propertyName"), rooms);
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier B catalog response was incomplete"
		);
	}

}
