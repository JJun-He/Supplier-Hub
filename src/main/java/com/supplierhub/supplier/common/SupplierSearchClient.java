package com.supplierhub.supplier.common;

import reactor.core.publisher.Mono;

import com.supplierhub.catalog.domain.Supplier;

public interface SupplierSearchClient {

	Supplier supplier();

	Mono<SupplierSearchResult> search(SupplierSearchRequest request);

}
