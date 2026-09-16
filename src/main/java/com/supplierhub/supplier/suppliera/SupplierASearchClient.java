package com.supplierhub.supplier.suppliera;

import java.util.Comparator;
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

import com.supplierhub.shared.InvalidValueException;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.OfferMappingException;
import com.supplierhub.search.application.OfferNormalizationResult;
import com.supplierhub.search.application.OfferNormalizer;
import com.supplierhub.search.domain.DailyInventory;
import com.supplierhub.search.domain.Money;
import com.supplierhub.search.domain.NightlyPrice;
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
public class SupplierASearchClient implements SupplierSearchClient {

	private final WebClient webClient;
	private final SupplierIntegrationProperties properties;

	public SupplierASearchClient(
		@Qualifier("supplierASearchWebClient") WebClient webClient,
		SupplierIntegrationProperties properties
	) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public Supplier supplier() {
		return Supplier.SUPPLIER_A;
	}

	@Override
	public Mono<SupplierSearchResult> search(SupplierSearchRequest request) {
		requireMatchingSupplier(request);
		Map<SupplierItemKey, InternalMapping> mappings = mappings(request);
		String hotelCodes = request.properties().stream()
			.map(PropertyMapping::supplierPropertyCode)
			.collect(Collectors.joining(","));

		return webClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/a/v1/availability")
				.queryParam("hotelCodes", "{codes}")
				.queryParam("checkIn", request.criteria().checkIn())
				.queryParam("checkOut", request.criteria().checkOut())
				.queryParam("adults", request.criteria().adults())
				.queryParam("children", request.criteria().children())
				.build(Map.of("codes", hotelCodes)))
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::httpFailure)
			.bodyToMono(JsonNode.class)
			.switchIfEmpty(Mono.error(invalidResponse()))
			.map(response -> normalize(response, request, mappings))
			.timeout(properties.search().callTimeout())
			.onErrorMap(
				cause -> !(cause instanceof SupplierIntegrationException)
					&& SupplierTransportFailureMapper.isResourceFailure(cause),
				cause -> SupplierTransportFailureMapper.resourceFailure(
					supplier(), "Supplier request", cause
				)
			)
			.onErrorMap(
				cause -> !(cause instanceof SupplierIntegrationException)
					&& SupplierTransportFailureMapper.isTimeout(cause),
				cause -> SupplierTransportFailureMapper.timeoutFailure(
					supplier(),
					"Supplier A search request",
					cause
				)
			)
			.onErrorMap(
				WebClientRequestException.class,
				cause -> SupplierTransportFailureMapper.requestFailure(
					supplier(),
					"Supplier A search request",
					cause
				)
			)
			.onErrorMap(
				WebClientResponseException.class,
				cause -> SupplierTransportFailureMapper.responseFailure(
					supplier(), "Supplier A search request", cause
				)
			)
			.onErrorMap(
				DecodingException.class,
				cause -> new SupplierIntegrationException(
					supplier(),
					SupplierFailureType.INVALID_RESPONSE,
					false,
					"Supplier A search response was invalid",
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
			items = SupplierJson.array(response, "items");
		} catch (InvalidValueException exception) {
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
			normalized.unavailableOfferCount(),
			normalized.duplicateOfferCount()
		);
	}

	private OfferCandidate toCandidate(JsonNode item, Map<SupplierItemKey, InternalMapping> mappings) {
		try {
			InternalMapping mapping = mappings.get(new SupplierItemKey(
				SupplierJson.text(item, "hotelCode"), SupplierJson.text(item, "roomTypeCode")
			));
			if (mapping == null) {
				throw new InvalidValueException("availability item must have an active internal mapping");
			}
			String currency = SupplierJson.text(item, "currency");
			List<JsonNode> rates = SupplierJson.array(item, "dailyRates");
			List<NightlyPrice> prices = rates.stream().map(rate -> new NightlyPrice(
				SupplierJson.date(rate, "date"),
				Money.of(currency, SupplierJson.longInteger(rate, "nightlyRate")),
				Money.of(currency, SupplierJson.longInteger(rate, "taxAmount"))
			)).sorted(Comparator.comparing(NightlyPrice::date)).toList();
			List<DailyInventory> inventory = rates.stream().map(rate -> new DailyInventory(
				SupplierJson.date(rate, "date"), SupplierJson.integer(rate, "remainingRooms")
			)).toList();
			return new OfferCandidate(
				mapping.propertyId(), mapping.roomTypeId(), supplier(),
				SupplierJson.integer(item, "maxOccupancy"), SupplierJson.bool(item, "breakfastIncluded"),
				Price.fromNightlyPrices(prices), inventory
			);
		} catch (InvalidValueException exception) {
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
			"Supplier A search"
		));
	}

	private SupplierIntegrationException invalidResponse() {
		return new SupplierIntegrationException(
			supplier(),
			SupplierFailureType.INVALID_RESPONSE,
			false,
			"Supplier A search response was incomplete"
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
