package com.supplierhub.catalog.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogProperty;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogRoomType;

class CatalogSnapshotTests {

	@Test
	void rejectsPropertyCodeContainingCsvDelimiter() {
		assertThatThrownBy(() -> new CatalogProperty(
			"P-1,P-2",
			"Property",
			List.of(new CatalogRoomType("R-1", "Room", 2))
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must not contain a comma");
	}

}
