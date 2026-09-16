package com.supplierhub.supplier.supplierb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.OfferMappingException;
import com.supplierhub.search.application.OfferNormalizationResult;
import com.supplierhub.search.application.OfferNormalizer;
import com.supplierhub.search.domain.DailyInventory;
import com.supplierhub.search.domain.Money;
import com.supplierhub.search.domain.OfferCandidate;
import com.supplierhub.search.domain.Price;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierHttpFailureMapper;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierJson;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;
import com.supplierhub.supplier.common.SupplierSearchClient;
import com.supplierhub.supplier.common.SupplierSearchRequest;
import com.supplierhub.supplier.common.SupplierSearchRequest.PropertyMapping;
import com.supplierhub.supplier.common.SupplierSearchResult;
import com.supplierhub.supplier.common.SupplierTransportFailureMapper;

@Component
public class SupplierBSearchClient implements SupplierSearchClient {

	private static final String SUCCESS_CODE = "0000";

	private final WebClient webClient;
	private final SupplierIntegrationProperties properties;

	public SupplierBSearchClient(
		@Qualifier("supplierBSearchWebClient") WebClient webClient,
		SupplierIntegrationProperties properties
	) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public Supplier supplier() {
		return Supplier.SUPPLIER_B;
	}

	@Override
	public Mono<SupplierSearchResult> search(SupplierSearchRequest request) {
		requireMatchingSupplier(request);
		Map<SupplierItemKey, InternalMapping> mappings = mappings(request);
		String propertyIds = request.properties().stream()
			.map(PropertyMapping::supplierPropertyCode)
			.collect(Collectors.joining(","));

		return webClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/b/api/search")
				.queryParam("propertyIds", "{codes}")
				.queryParam("checkIn", request.criteria().checkIn())
				.queryParam("checkOut", request.criteria().checkOut())
				.queryParam("adults", request.criteria().adults())
				.queryParam("children", request.criteria().children())
				.build(Map.of("codes", propertyIds)))
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::httpFailure)
			.bodyToMono(JsonNode.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(response -> normalize(response, request, mappings))
			.timeout(properties.search().callTimeout())
			.onErrorMap(
				cause -> !(cause instanceof SupplierIntegrationException)
					&& SupplierTransportFailureMapper.isTimeout(cause),
				cause -> SupplierTransportFailureMapper.timeoutFailure(
					supplier(),
					"Supplier B search request",
					cause
				)
			)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier B search request",
					cause
				)
			)
			.onErrorMap(
				WebClientResponseException.class,
				cause -> SupplierTransportFailureMapper.responseFailure(
					supplier(), "Supplier B search request", cause
				)
			)
			.onErrorMap(
				DecodingException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier B search response was invalid",
					cause
				)
			);
	}

	private SupplierSearchResult normalize(
		JsonNode response,
		SupplierSearchRequest request,
		Map<SupplierItemKey, InternalMapping> mappings
	) {
		List<JsonNode> items;
		try {
			String code = SupplierJson.text(response, "resultCode");
			if (!SUCCESS_CODE.equals(code)) {
				throw SupplierBFailureMapper.bodyFailure(code, "search");
			}
			items = SupplierJson.array(SupplierJson.field(response, "data"), "items");
		} catch (IllegalArgumentException exception) {
			throw new SupplierIntegrationException(
				supplier(), SupplierFailureType.INVALID_RESPONSE, false,
				"Supplier search envelope was invalid", exception
			);
		}
		OfferNormalizationResult normalized = OfferNormalizer.normalize(
			request.criteria(),
			supplier(),
			items,
			item -> toCandidate(item, mappings)
		);
		return new SupplierSearchResult(
			supplier(),
			normalized.offers(),
			normalized.rejectedOfferCount(),
			normalized.unavailableOfferCount()
		);
	}

	private OfferCandidate toCandidate(JsonNode item, Map<SupplierItemKey, InternalMapping> mappings) {
		try {
			InternalMapping mapping = mappings.get(new SupplierItemKey(
				SupplierJson.text(item, "propertyId"), SupplierJson.text(item, "roomId")
			));
			if (mapping == null) {
				throw new IllegalArgumentException("search item must have an active internal mapping");
			}
			if (!SupplierJson.bool(item, "taxIncluded")) {
				throw new IllegalArgumentException("totalPrice must include tax");
			}
			List<DailyInventory> inventory = SupplierJson.array(item, "inventory").stream()
				.map(day -> new DailyInventory(
					SupplierJson.date(day, "date"), SupplierJson.integer(day, "remainingRooms")
				)).toList();
			return new OfferCandidate(
				mapping.propertyId(), mapping.roomTypeId(), supplier(),
				SupplierJson.integer(item, "maxOccupancy"), SupplierJson.bool(item, "breakfastIncluded"),
				Price.totalOnly(Money.of(SupplierJson.text(item, "currency"), SupplierJson.longInteger(item, "totalPrice"))),
				inventory
			);
		} catch (IllegalArgumentException | ArithmeticException exception) {
			throw new OfferMappingException(exception);
		}
	}

	private Map<SupplierItemKey, InternalMapping> mappings(
		SupplierSearchRequest request
	) {
		Map<SupplierItemKey, InternalMapping> mappings = new HashMap<>();
		for (PropertyMapping property : request.properties()) {
			for (SupplierSearchRequest.RoomTypeMapping roomType : property.roomTypes()) {
				mappings.put(
					new SupplierItemKey(
						property.supplierPropertyCode(),
						roomType.supplierRoomTypeCode()
					),
					new InternalMapping(property.propertyId(), roomType.roomTypeId())
				);
			}
		}
		return Map.copyOf(mappings);
	}

	private void requireMatchingSupplier(SupplierSearchRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (request.supplier() != supplier()) {
			throw new IllegalArgumentException(
				"search request supplier must match the client supplier"
			);
		}
	}

	private Mono<? extends Throwable> httpFailure(ClientResponse response) {
		return Mono.error(SupplierHttpFailureMapper.statusFailure(
			supplier(),
			response.statusCode(),
			"Supplier B search"
		));
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier B search response was incomplete"
		);
	}

	private record SupplierItemKey(
		String supplierPropertyCode,
		String supplierRoomTypeCode
	) {
	}

	private record InternalMapping(long propertyId, long roomTypeId) {
	}

}
