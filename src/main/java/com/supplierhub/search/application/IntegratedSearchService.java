package com.supplierhub.search.application;

import static com.supplierhub.supplier.common.SupplierSearchRequest.MAX_PROPERTY_COUNT;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogMappingReader;
import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.Offer;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierCallResources;
import com.supplierhub.supplier.common.SupplierMetrics;
import com.supplierhub.supplier.common.SupplierOperation;
import com.supplierhub.supplier.common.SupplierFailureType;
import com.supplierhub.supplier.common.SupplierIntegrationException;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;
import com.supplierhub.supplier.common.SupplierSearchClient;
import com.supplierhub.supplier.common.SupplierSearchRequest;
import com.supplierhub.supplier.common.SupplierSearchRequest.PropertyMapping;
import com.supplierhub.supplier.common.SupplierSearchRequest.RoomTypeMapping;
import com.supplierhub.supplier.common.SupplierSearchResult;

@Service
public class IntegratedSearchService {

	private static final Logger log = LoggerFactory.getLogger(
		IntegratedSearchService.class
	);
	private static final Comparator<Offer> OFFER_ORDER = Comparator
		.comparingLong(Offer::propertyId)
		.thenComparingLong(Offer::roomTypeId)
		.thenComparing(Offer::supplier)
		.thenComparing(offer -> offer.price().totalAmount().currencyCode())
		.thenComparingLong(offer -> offer.price().totalAmount().amount())
		.thenComparing(Offer::breakfastIncluded)
		.thenComparingInt(Offer::availableRooms);

	private final ActiveCatalogMappingReader mappingReader;
	private final List<SupplierSearchClient> clients;
	private final Duration overallTimeout;
	private final int maxConcurrency;
	private final SupplierCallResources resources;
	private final SupplierMetrics metrics;

	public IntegratedSearchService(
		ActiveCatalogMappingReader mappingReader,
		List<SupplierSearchClient> clients,
		SupplierIntegrationProperties properties,
		SupplierCallResources resources,
		SupplierMetrics metrics
	) {
		this.mappingReader = Objects.requireNonNull(
			mappingReader,
			"mappingReader must not be null"
		);
		Objects.requireNonNull(clients, "clients must not be null");
		requireUniqueSuppliers(clients);
		requireClientsForEnabledSuppliers(clients, properties);
		this.clients = clients.stream()
			.filter(client -> properties.isEnabled(client.supplier()))
			.sorted(Comparator.comparing(SupplierSearchClient::supplier))
			.toList();
		this.overallTimeout = properties.search().overallTimeout();
		this.maxConcurrency = properties.search().maxConcurrency();
		this.resources = resources;
		this.metrics = metrics;
	}

	public IntegratedSearchResult search(SearchCriteria criteria) {
		Objects.requireNonNull(criteria, "criteria must not be null");
		long startedAt = System.nanoTime();
		try {
			IntegratedSearchResult result = search(criteria, startedAt);
			metrics.searchDuration(result.status().name(), startedAt);
			result.supplierResults().forEach(outcome -> metrics.searchResult(
				outcome.supplier(), outcome.status().name(), outcome.failureTypes()
			));
			return result;
		} catch (RuntimeException exception) {
			metrics.searchDuration("INTERNAL_ERROR", startedAt);
			throw exception;
		}
	}

	private IntegratedSearchResult search(SearchCriteria criteria, long startedAt) {
		List<ActiveCatalogMapping> rows = metrics.stage("DATABASE", mappingReader::findAllActive);
		Map<Supplier, List<PropertyMapping>> mappings = metrics.stage("MAPPING", () -> groupMappings(rows));
		List<SupplierSearchPlan> plans = clients.stream()
			.map(client -> plan(client, criteria, mappings.getOrDefault(
				client.supplier(),
				List.of()
			)))
			.toList();

		Duration remainingTimeout = remainingTimeout(startedAt);
		List<BatchSearchOutcome> completed = metrics.stage("SUPPLIERS", () -> remainingTimeout.isZero()
			|| plans.isEmpty()
			? List.of()
			: Flux.fromIterable(plans)
				.filter(plan -> plan.batchCount() > 0)
				.flatMap(this::execute, clients.size())
				.take(remainingTimeout)
				.collectList()
				.block());
		List<BatchSearchOutcome> safeCompleted = completed == null
			? List.of()
			: completed;
		return metrics.stage("ASSEMBLY", () -> {
			List<SupplierSearchAggregate> aggregates = plans.stream()
				.map(plan -> aggregate(plan, safeCompleted))
				.toList();
			List<SupplierSearchOutcome> supplierResults = enrichOutcomes(
				aggregates,
				rows
			);

			IntegratedSearchResult result = new IntegratedSearchResult(
				overallStatus(supplierResults),
				supplierResults
			);
			log.info(
				"Integrated Supplier search completed: status={}, acceptedOffers={}, elapsedMillis={}",
				result.status(),
				result.supplierResults().stream().mapToInt(SupplierSearchOutcome::acceptedOfferCount).sum(),
				Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
			);
			return result;
		});
	}

	private List<SupplierSearchOutcome> enrichOutcomes(
		List<SupplierSearchAggregate> aggregates,
		List<ActiveCatalogMapping> mappings
	) {
		Set<Long> offeredRoomTypeIds = new HashSet<>();
		aggregates.forEach(result -> result.offers().forEach(
			offer -> offeredRoomTypeIds.add(offer.roomTypeId())
		));
		if (offeredRoomTypeIds.isEmpty()) {
			return aggregates.stream()
				.map(aggregate -> aggregate.toOutcome(List.of()))
				.toList();
		}

		Map<Long, ActiveCatalogMapping> catalogByRoomType = new HashMap<>();
		for (ActiveCatalogMapping mapping : mappings) {
			if (offeredRoomTypeIds.contains(mapping.roomTypeId())) {
				catalogByRoomType.put(mapping.roomTypeId(), mapping);
			}
		}

		return aggregates.stream()
			.map(aggregate -> aggregate.toOutcome(
				aggregate.offers().stream()
					.map(offer -> enrichOffer(offer, catalogByRoomType))
					.toList()
			))
			.toList();
	}

	private SearchOffer enrichOffer(
		Offer offer,
		Map<Long, ActiveCatalogMapping> catalogByRoomType
	) {
		ActiveCatalogMapping mapping = catalogByRoomType.get(offer.roomTypeId());
		if (mapping == null
			|| mapping.propertyId() != offer.propertyId()
			|| mapping.supplier() != offer.supplier()) {
			throw new IllegalStateException(
				"Accepted offer must have matching active catalog metadata"
			);
		}
		return new SearchOffer(
			offer,
			mapping.propertyName(),
			mapping.roomTypeName()
		);
	}

	private Flux<BatchSearchOutcome> execute(SupplierSearchPlan plan) {
		return Flux.range(0, plan.batchCount())
			.map(plan::request)
			.flatMap(indexedRequest -> executeBatch(
				plan.client(),
				indexedRequest
			), maxConcurrency);
	}

	private Mono<BatchSearchOutcome> executeBatch(
		SupplierSearchClient client,
		IndexedRequest indexedRequest
	) {
		return resources.execute(client.supplier(), SupplierOperation.SEARCH,
			() -> Mono.defer(() -> client.search(indexedRequest.request()))
			.switchIfEmpty(Mono.error(invalidResponse(
				client.supplier(),
				"Supplier search completed without a result"
			)))
			.doOnNext(result -> {
				if (result.supplier() != client.supplier()) {
					throw invalidResponse(client.supplier(), "Supplier search result did not match the requested supplier");
				}
			}))
			.map(result -> successfulBatch(client, indexedRequest, result))
			.onErrorResume(cause -> {
				SupplierFailureType failureType = failureType(cause);
				var event = failureType == SupplierFailureType.INTERNAL_ERROR
					? log.atError() : log.atWarn();
				event.setCause(cause).log(
					"Supplier search batch failed: supplier={}, batch={}, failureType={}",
					client.supplier(), indexedRequest.index(), failureType
				);
				return Mono.just(BatchSearchOutcome.failed(
					client.supplier(),
					indexedRequest.index(),
					failureType
				));
			});
	}

	private BatchSearchOutcome successfulBatch(
		SupplierSearchClient client,
		IndexedRequest indexedRequest,
		SupplierSearchResult result
	) {
		return BatchSearchOutcome.succeeded(
			client.supplier(),
			indexedRequest.index(),
			result
		);
	}

	private SupplierSearchPlan plan(
		SupplierSearchClient client,
		SearchCriteria criteria,
		List<PropertyMapping> mappings
	) {
		return new SupplierSearchPlan(client, criteria, mappings);
	}

	private SupplierSearchAggregate aggregate(
		SupplierSearchPlan plan,
		List<BatchSearchOutcome> allCompleted
	) {
		if (plan.batchCount() == 0) {
			return new SupplierSearchAggregate(
				plan.client().supplier(),
				SupplierSearchStatus.FAILED,
				List.of(),
				0,
				0,
				List.of(SupplierFailureType.CATALOG_UNAVAILABLE)
			);
		}

		List<BatchSearchOutcome> completed = allCompleted.stream()
			.filter(outcome -> outcome.supplier() == plan.client().supplier())
			.toList();
		List<SupplierSearchResult> successes = completed.stream()
			.filter(BatchSearchOutcome::isSuccess)
			.map(BatchSearchOutcome::result)
			.toList();
		List<Offer> offers = successes.stream()
			.flatMap(result -> result.offers().stream())
			.sorted(OFFER_ORDER)
			.toList();
		int rejectedOfferCount = successes.stream()
			.mapToInt(SupplierSearchResult::rejectedOfferCount)
			.sum();
		int unavailableOfferCount = successes.stream()
			.mapToInt(SupplierSearchResult::unavailableOfferCount)
			.sum();
		Set<SupplierFailureType> failureTypes = EnumSet.noneOf(
			SupplierFailureType.class
		);
		completed.stream()
			.filter(outcome -> !outcome.isSuccess())
			.map(BatchSearchOutcome::failureType)
			.forEach(failureTypes::add);
		int timedOutBatchCount = plan.batchCount() - completed.size();
		if (timedOutBatchCount > 0) {
			failureTypes.add(SupplierFailureType.TIMEOUT);
			log.warn(
				"Supplier search exceeded overall timeout: supplier={}, timedOutBatches={}",
				plan.client().supplier(),
				timedOutBatchCount
			);
		}
		if (rejectedOfferCount > 0) {
			failureTypes.add(SupplierFailureType.INVALID_RESPONSE);
		}

		SupplierSearchStatus status;
		if (successes.isEmpty()) {
			status = SupplierSearchStatus.FAILED;
		} else if (failureTypes.isEmpty()) {
			status = SupplierSearchStatus.SUCCESS;
		} else {
			status = SupplierSearchStatus.PARTIAL;
		}
		return new SupplierSearchAggregate(
			plan.client().supplier(),
			status,
			offers,
			rejectedOfferCount,
			unavailableOfferCount,
			List.copyOf(failureTypes)
		);
	}

	private SearchStatus overallStatus(
		List<SupplierSearchOutcome> supplierResults
	) {
		if (supplierResults.stream().allMatch(
			result -> result.status() == SupplierSearchStatus.SUCCESS
		)) {
			return SearchStatus.COMPLETE;
		}
		if (supplierResults.stream().allMatch(
			result -> result.status() == SupplierSearchStatus.FAILED
		)) {
			return SearchStatus.FAILED;
		}
		return SearchStatus.PARTIAL;
	}

	private Map<Supplier, List<PropertyMapping>> groupMappings(
		List<ActiveCatalogMapping> rows
	) {
		Map<Supplier, Map<Long, PropertyMappingBuilder>> grouped = new EnumMap<>(
			Supplier.class
		);
		for (ActiveCatalogMapping row : rows.stream()
			.sorted(Comparator
				.comparing(ActiveCatalogMapping::supplier)
				.thenComparingLong(ActiveCatalogMapping::propertyId)
				.thenComparingLong(ActiveCatalogMapping::roomTypeId))
			.toList()) {
			Map<Long, PropertyMappingBuilder> supplierMappings = grouped
				.computeIfAbsent(row.supplier(), ignored -> new LinkedHashMap<>());
			PropertyMappingBuilder property = supplierMappings.computeIfAbsent(
				row.propertyId(),
				ignored -> new PropertyMappingBuilder(
					row.propertyId(),
					row.supplierPropertyCode()
				)
			);
			property.roomTypes().add(new RoomTypeMapping(
				row.roomTypeId(),
				row.supplierRoomTypeCode()
			));
		}

		Map<Supplier, List<PropertyMapping>> result = new EnumMap<>(Supplier.class);
		grouped.forEach((supplier, properties) -> result.put(
			supplier,
			properties.values().stream()
				.map(PropertyMappingBuilder::build)
				.toList()
		));
		return result;
	}

	private SupplierFailureType failureType(Throwable cause) {
		if (cause instanceof SupplierIntegrationException exception) {
			return exception.getFailureType();
		}
		return SupplierFailureType.INTERNAL_ERROR;
	}

	private Duration remainingTimeout(long startedAt) {
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		if (elapsed.compareTo(overallTimeout) >= 0) {
			return Duration.ZERO;
		}
		return overallTimeout.minus(elapsed);
	}

	private SupplierIntegrationException invalidResponse(
		Supplier supplier,
		String message
	) {
		return new SupplierIntegrationException(
			supplier,
			SupplierFailureType.INVALID_RESPONSE,
			false,
			message
		);
	}

	private void requireUniqueSuppliers(List<SupplierSearchClient> clients) {
		Map<Supplier, SupplierSearchClient> indexed = new EnumMap<>(Supplier.class);
		for (SupplierSearchClient client : clients) {
			Objects.requireNonNull(client, "client must not be null");
			if (indexed.put(client.supplier(), client) != null) {
				throw new IllegalArgumentException(
					"clients must contain each supplier at most once"
				);
			}
		}
	}

	private void requireClientsForEnabledSuppliers(
		List<SupplierSearchClient> clients,
		SupplierIntegrationProperties properties
	) {
		Set<Supplier> missingClients = EnumSet.noneOf(Supplier.class);
		for (Supplier supplier : Supplier.values()) {
			if (properties.isEnabled(supplier)) {
				missingClients.add(supplier);
			}
		}
		clients.stream()
			.map(SupplierSearchClient::supplier)
			.forEach(missingClients::remove);
		if (!missingClients.isEmpty()) {
			throw new IllegalArgumentException(
				"Enabled Suppliers have no search client: "
					+ missingClients
			);
		}
	}

	private record SupplierSearchPlan(
		SupplierSearchClient client,
		SearchCriteria criteria,
		List<PropertyMapping> mappings
	) {

		private int batchCount() {
			return (mappings.size() + MAX_PROPERTY_COUNT - 1)
				/ MAX_PROPERTY_COUNT;
		}

		private IndexedRequest request(int index) {
			int start = index * MAX_PROPERTY_COUNT;
			return new IndexedRequest(index, new SupplierSearchRequest(
				client.supplier(),
				criteria,
				mappings.subList(
					start,
					Math.min(start + MAX_PROPERTY_COUNT, mappings.size())
				)
			));
		}
	}

	private record IndexedRequest(int index, SupplierSearchRequest request) {
	}

	private record BatchSearchOutcome(
		Supplier supplier,
		int index,
		SupplierSearchResult result,
		SupplierFailureType failureType
	) {

		private static BatchSearchOutcome succeeded(
			Supplier supplier,
			int index,
			SupplierSearchResult result
		) {
			return new BatchSearchOutcome(supplier, index, result, null);
		}

		private static BatchSearchOutcome failed(
			Supplier supplier,
			int index,
			SupplierFailureType failureType
		) {
			return new BatchSearchOutcome(supplier, index, null, failureType);
		}

		private boolean isSuccess() {
			return result != null;
		}

	}

	private record SupplierSearchAggregate(
		Supplier supplier,
		SupplierSearchStatus status,
		List<Offer> offers,
		int rejectedOfferCount,
		int unavailableOfferCount,
		List<SupplierFailureType> failureTypes
	) {

		private SupplierSearchOutcome toOutcome(
			List<SearchOffer> searchOffers
		) {
			return new SupplierSearchOutcome(
				supplier,
				status,
				searchOffers,
				rejectedOfferCount,
				unavailableOfferCount,
				failureTypes
			);
		}

	}

	private record PropertyMappingBuilder(
		long propertyId,
		String supplierPropertyCode,
		List<RoomTypeMapping> roomTypes
	) {

		private PropertyMappingBuilder(
			long propertyId,
			String supplierPropertyCode
		) {
			this(propertyId, supplierPropertyCode, new ArrayList<>());
		}

		private PropertyMapping build() {
			return new PropertyMapping(
				propertyId,
				supplierPropertyCode,
				roomTypes
			);
		}

	}

}
