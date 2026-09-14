package com.supplierhub.mock;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MockSupplierControllerTests {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new MockSupplierController())
			.build();
	}

	@Test
	void persistsSupplierModeForFollowingSearchRequests() throws Exception {
		mockMvc.perform(post("/control/a/mode").param("value", "error"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.a").value("error"));

		mockMvc.perform(supplierARequest())
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
		mockMvc.perform(supplierBRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resultCode").value("0000"));

		mockMvc.perform(get("/a/v1/hotels"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)));
	}

	@Test
	void requestHeaderOverridesPersistedMode() throws Exception {
		mockMvc.perform(post("/control/a/mode").param("value", "error"))
			.andExpect(status().isOk());

		mockMvc.perform(supplierARequest().header("X-Mock-Scenario", "normal"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	void supplierBUsesHttp200BodyFailureMode() throws Exception {
		mockMvc.perform(post("/control/b/mode").param("value", "error"))
			.andExpect(status().isOk());

		mockMvc.perform(supplierBRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resultCode").value("E503"));
	}

	@Test
	void generatesAvailabilityForRequestedStayDatesAndPropertyCodes()
		throws Exception {
		mockMvc.perform(supplierARequest())
			.andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].hotelCode").value("A-10023"))
			.andExpect(jsonPath("$.items[0].dailyRates", hasSize(2)))
			.andExpect(jsonPath("$.items[0].dailyRates[0].date")
				.value("2027-01-10"))
			.andExpect(jsonPath("$.items[0].dailyRates[1].date")
				.value("2027-01-11"));
	}

	@Test
	void generatesSupplierBPriceAndInventoryForRequestedStayDates()
		throws Exception {
		mockMvc.perform(supplierBRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items", hasSize(1)))
			.andExpect(jsonPath("$.data.items[0].totalPrice").value(302_000))
			.andExpect(jsonPath("$.data.items[0].inventory", hasSize(2)))
			.andExpect(jsonPath("$.data.items[0].inventory[0].date")
				.value("2027-01-10"))
			.andExpect(jsonPath("$.data.items[0].inventory[1].date")
				.value("2027-01-11"));
	}

	@Test
	void rejectsUnsupportedControlValues() throws Exception {
		mockMvc.perform(post("/control/c/mode").param("value", "broken"))
			.andExpect(status().isBadRequest());
	}

	private MockHttpServletRequestBuilder supplierARequest() {
		return get("/a/v1/availability")
			.param("hotelCodes", "A-10023")
			.param("checkIn", "2027-01-10")
			.param("checkOut", "2027-01-12")
			.param("adults", "2")
			.param("children", "0");
	}

	private MockHttpServletRequestBuilder supplierBRequest() {
		return get("/b/api/search")
			.param("propertyIds", "B77120")
			.param("checkIn", "2027-01-10")
			.param("checkOut", "2027-01-12")
			.param("adults", "2")
			.param("children", "0");
	}

}
