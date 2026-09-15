package com.supplierhub.supplier.common;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.supplierhub.catalog.domain.Supplier;
import com.supplierhub.search.domain.SearchCriteria;
import com.supplierhub.supplier.common.SupplierSearchRequest.PropertyMapping;
import com.supplierhub.supplier.common.SupplierSearchRequest.RoomTypeMapping;

class SupplierSearchRequestTests {

	private static final SearchCriteria CRITERIA = new SearchCriteria(
		LocalDate.of(2026, 9, 1),
		LocalDate.of(2026, 9, 2),
		1,
		0
	);

	@Test
	void rejectsMoreThanSupplierBulkLimit() {
		List<PropertyMapping> properties = IntStream.rangeClosed(1, 51)
			.mapToObj(index -> property(index, "P-" + index, index, "R-" + index))
			.toList();

		assertThatThrownBy(() -> new SupplierSearchRequest(
			Supplier.SUPPLIER_A,
			CRITERIA,
			properties
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsDuplicateRoomCodeInsideProperty() {
		assertThatThrownBy(() -> new PropertyMapping(
			1,
			"P-1",
			List.of(
				new RoomTypeMapping(1, "ROOM"),
				new RoomTypeMapping(2, "ROOM")
			)
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsPropertyCodeContainingCsvDelimiter() {
		assertThatThrownBy(() -> property(1, "P-1,P-2", 1, "R-1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must not contain a comma");
	}

	private PropertyMapping property(
		long propertyId,
		String propertyCode,
		long roomTypeId,
		String roomTypeCode
	) {
		return new PropertyMapping(
			propertyId,
			propertyCode,
			List.of(new RoomTypeMapping(roomTypeId, roomTypeCode))
		);
	}

}
