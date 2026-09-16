package com.supplierhub.search.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.application.IntegratedSearchResult;
import com.supplierhub.search.application.IntegratedSearchService;
import com.supplierhub.search.application.SearchOffer;
import com.supplierhub.search.application.SearchStatus;
import com.supplierhub.search.application.SupplierSearchOutcome;
import com.supplierhub.search.application.SupplierSearchStatus;
import com.supplierhub.search.domain.DailyInventory;
import com.supplierhub.search.domain.Money;
import com.supplierhub.search.domain.Offer;
import com.supplierhub.search.domain.Price;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierFailureType;

class StaySearchControllerTests {

	private static final SearchCriteria CRITERIA = new SearchCriteria(
		LocalDate.of(2026, 9, 1),
		LocalDate.of(2026, 9, 4),
		2,
		0
	);

	private IntegratedSearchService searchService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		searchService = mock(IntegratedSearchService.class);
		mockMvc = MockMvcBuilders
			.standaloneSetup(new StaySearchController(searchService))
			.setControllerAdvice(new SearchApiExceptionHandler())
			.build();
	}

	@Test
	void returnsGroupedCustomerSearchResponseWithoutSupplierCodes()
		throws Exception {
		Offer deluxe = offer(
			1,
			10,
			Supplier.SUPPLIER_A,
			2,
			1,
			false,
			429_000
		);
		Offer suite = offer(
			1,
			20,
			Supplier.SUPPLIER_A,
			4,
			2,
			true,
			600_000
		);
		when(searchService.search(CRITERIA)).thenReturn(new IntegratedSearchResult(
			SearchStatus.COMPLETE,
			List.of(outcome(
				Supplier.SUPPLIER_A,
				SupplierSearchStatus.SUCCESS,
				List.of(
					new SearchOffer(deluxe, "Riverside Hotel", "Deluxe"),
					new SearchOffer(suite, "Riverside Hotel", "Suite")
				),
				0,
				0,
				List.of()
			))
		));

		mockMvc.perform(searchRequest())
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value("COMPLETE"))
			.andExpect(jsonPath("$.searchCriteria.checkIn").value("2026-09-01"))
			.andExpect(jsonPath("$.searchCriteria.checkOut").value("2026-09-04"))
			.andExpect(jsonPath("$.searchCriteria.adults").value(2))
			.andExpect(jsonPath("$.searchCriteria.children").value(0))
			.andExpect(jsonPath("$.stays", hasSize(1)))
			.andExpect(jsonPath("$.stays[0].stayId").value(1))
			.andExpect(jsonPath("$.stays[0].stayName").value("Riverside Hotel"))
			.andExpect(jsonPath("$.stays[0].roomTypes", hasSize(2)))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].roomTypeId").value(10))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].roomTypeName").value("Deluxe"))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].maxOccupancy").value(2))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].offers[0].supplier")
				.value("SUPPLIER_A"))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].offers[0].availableRooms")
				.value(1))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].offers[0].breakfastIncluded")
				.value(false))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].offers[0].price.currency")
				.value("KRW"))
			.andExpect(jsonPath(
				"$.stays[0].roomTypes[0].offers[0].price.totalAmountIncludingTax"
			)
				.value(429_000))
			.andExpect(jsonPath("$.supplierResults[0].status").value("SUCCESS"))
			.andExpect(jsonPath("$.supplierResults[0].acceptedOfferCount").value(2))
			.andExpect(jsonPath("$..supplierPropertyCode").doesNotExist())
			.andExpect(jsonPath("$..supplierRoomTypeCode").doesNotExist());
	}

	@ParameterizedTest
	@EnumSource(value = SupplierFailureType.class, names = {"TIMEOUT", "INTERNAL_ERROR"})
	void returnsHttpOkAndFailureDetailsForPartialSearch(SupplierFailureType failureType) throws Exception {
		Offer offer = offer(
			1,
			10,
			Supplier.SUPPLIER_A,
			2,
			1,
			false,
			429_000
		);
		when(searchService.search(any(SearchCriteria.class))).thenReturn(
			new IntegratedSearchResult(
				SearchStatus.PARTIAL,
				List.of(
					outcome(
						Supplier.SUPPLIER_A,
						SupplierSearchStatus.SUCCESS,
						List.of(new SearchOffer(
							offer,
							"Riverside Hotel",
							"Deluxe"
						)),
						0,
						0,
						List.of()
					),
					outcome(
						Supplier.SUPPLIER_B,
						SupplierSearchStatus.FAILED,
						List.of(),
						0,
						0,
						List.of(failureType)
					)
				)
			)
		);

		mockMvc.perform(searchRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PARTIAL"))
			.andExpect(jsonPath("$.stays", hasSize(1)))
			.andExpect(jsonPath("$.supplierResults", hasSize(2)))
			.andExpect(jsonPath("$.supplierResults[1].supplier")
				.value("SUPPLIER_B"))
			.andExpect(jsonPath("$.supplierResults[1].status").value("FAILED"))
			.andExpect(jsonPath("$.supplierResults[1].failureTypes[0]")
				.value(failureType.name()));
	}

	@ParameterizedTest
	@EnumSource(value = SupplierFailureType.class, names = {"CATALOG_UNAVAILABLE", "INTERNAL_ERROR"})
	void returnsHttpServiceUnavailableWhileKeepingFailureBody(SupplierFailureType failureType) throws Exception {
		when(searchService.search(any(SearchCriteria.class))).thenReturn(
			new IntegratedSearchResult(
				SearchStatus.FAILED,
				List.of(outcome(
					Supplier.SUPPLIER_A,
					SupplierSearchStatus.FAILED,
					List.of(),
					0,
					0,
					List.of(failureType)
				))
			)
		);

		mockMvc.perform(searchRequest())
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.stays").isEmpty())
			.andExpect(jsonPath("$.supplierResults[0].failureTypes[0]")
				.value(failureType.name()));
	}

	@Test
	void returnsEmptyArraysForSuccessfulSearchWithoutAvailableOffers()
		throws Exception {
		when(searchService.search(any(SearchCriteria.class))).thenReturn(
			new IntegratedSearchResult(
				SearchStatus.COMPLETE,
				List.of(outcome(
					Supplier.SUPPLIER_A,
					SupplierSearchStatus.SUCCESS,
					List.of(),
					0,
					1,
					List.of()
				))
			)
		);

		mockMvc.perform(searchRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETE"))
			.andExpect(jsonPath("$.stays").isArray())
			.andExpect(jsonPath("$.stays").isEmpty())
			.andExpect(jsonPath("$.supplierResults[0].failureTypes").isArray())
			.andExpect(jsonPath("$.supplierResults[0].failureTypes").isEmpty())
			.andExpect(jsonPath("$.supplierResults[0].unavailableOfferCount")
				.value(1));
	}

	@Test
	void rejectsInvalidSearchCriteriaBeforeCallingSupplier() throws Exception {
		mockMvc.perform(get("/api/v1/stays/search")
				.param("checkIn", "2026-09-04")
				.param("checkOut", "2026-09-01")
				.param("adults", "2")
				.param("children", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SEARCH_CRITERIA"))
			.andExpect(jsonPath("$.message").value(
				"checkOut must be after checkIn"
			));
		verifyNoInteractions(searchService);
	}

	@Test
	void rejectsStayLongerThanThirtyNightsBeforeCallingSupplier()
		throws Exception {
		mockMvc.perform(get("/api/v1/stays/search")
				.param("checkIn", "2026-09-01")
				.param("checkOut", "2026-10-02")
				.param("adults", "2"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SEARCH_CRITERIA"))
			.andExpect(jsonPath("$.message").value(
				"stay must not exceed 30 nights"
			));
		verifyNoInteractions(searchService);
	}

	@Test
	void defaultsChildrenToZero() throws Exception {
		when(searchService.search(CRITERIA)).thenReturn(
			new IntegratedSearchResult(
				SearchStatus.COMPLETE,
				List.of(outcome(
					Supplier.SUPPLIER_A,
					SupplierSearchStatus.SUCCESS,
					List.of(),
					0,
					0,
					List.of()
				))
			)
		);

		mockMvc.perform(get("/api/v1/stays/search")
				.param("checkIn", "2026-09-01")
				.param("checkOut", "2026-09-04")
				.param("adults", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.searchCriteria.children").value(0));
	}

	@Test
	void rejectsMissingOrMalformedSearchParameter() throws Exception {
		mockMvc.perform(get("/api/v1/stays/search")
				.param("checkIn", "2026-09-01")
				.param("checkOut", "2026-09-04"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));

		mockMvc.perform(get("/api/v1/stays/search")
				.param("checkIn", "not-a-date")
				.param("checkOut", "2026-09-04")
				.param("adults", "2"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"))
			.andExpect(jsonPath("$.message").value(
				"Required search parameters are missing or malformed"
			));
		verifyNoInteractions(searchService);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
	searchRequest() {
		return get("/api/v1/stays/search")
			.param("checkIn", "2026-09-01")
			.param("checkOut", "2026-09-04")
			.param("adults", "2")
			.param("children", "0");
	}

	private SupplierSearchOutcome outcome(
		Supplier supplier,
		SupplierSearchStatus status,
		List<SearchOffer> searchOffers,
		int rejectedOfferCount,
		int unavailableOfferCount,
		List<SupplierFailureType> failureTypes
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

	private Offer offer(
		long propertyId,
		long roomTypeId,
		Supplier supplier,
		int maxOccupancy,
		int availableRooms,
		boolean breakfastIncluded,
		long totalAmount
	) {
		return Offer.createAvailable(
			propertyId,
			roomTypeId,
			supplier,
			maxOccupancy,
			breakfastIncluded,
			Price.totalOnly(Money.of("KRW", totalAmount)),
			CRITERIA,
			List.of(
				new DailyInventory(LocalDate.of(2026, 9, 1), availableRooms),
				new DailyInventory(LocalDate.of(2026, 9, 2), availableRooms),
				new DailyInventory(LocalDate.of(2026, 9, 3), availableRooms)
			)
		).orElseThrow();
	}

}
