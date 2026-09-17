package com.supplierhub.catalog.application;

import java.util.List;
import java.util.Set;

import com.supplierhub.catalog.domain.Supplier;

/** 같은 DB 스냅샷에서 읽은 활성 매핑과 최초 반영 완료 여부. */
public record ActiveCatalogSnapshot(
	List<ActiveCatalogMapping> mappings,
	Set<Supplier> initializedSuppliers
) {

	public ActiveCatalogSnapshot {
		mappings = List.copyOf(mappings);
		initializedSuppliers = Set.copyOf(initializedSuppliers);
	}

}
