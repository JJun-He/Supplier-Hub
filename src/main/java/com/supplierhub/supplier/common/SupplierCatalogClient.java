package com.supplierhub.supplier.common;

import reactor.core.publisher.Mono;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.Supplier;

public interface SupplierCatalogClient {

	Supplier supplier();

	Mono<CatalogSnapshot> fetchCatalog();

}
