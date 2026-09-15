package com.supplierhub.search.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.supplierhub.search.application.IntegratedSearchResult;
import com.supplierhub.search.application.IntegratedSearchService;
import com.supplierhub.search.application.SearchStatus;
import com.supplierhub.search.domain.SearchCriteria;

@RestController
@RequestMapping("/api/v1/stays")
public class StaySearchController {

	private final IntegratedSearchService searchService;

	public StaySearchController(IntegratedSearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping("/search")
	public ResponseEntity<StaySearchResponse> search(
		@RequestParam
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate checkIn,
		@RequestParam
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate checkOut,
		@RequestParam int adults,
		@RequestParam(defaultValue = "0") int children
	) {
		SearchCriteria criteria = criteria(checkIn, checkOut, adults, children);
		IntegratedSearchResult result = searchService.search(criteria);
		HttpStatus responseStatus = result.status() == SearchStatus.FAILED
			? HttpStatus.SERVICE_UNAVAILABLE
			: HttpStatus.OK;
		return ResponseEntity
			.status(responseStatus)
			.body(StaySearchResponse.from(criteria, result));
	}

	private SearchCriteria criteria(
		LocalDate checkIn,
		LocalDate checkOut,
		int adults,
		int children
	) {
		try {
			return new SearchCriteria(checkIn, checkOut, adults, children);
		} catch (IllegalArgumentException exception) {
			throw new InvalidSearchCriteriaException(
				exception.getMessage(),
				exception
			);
		}
	}

}
