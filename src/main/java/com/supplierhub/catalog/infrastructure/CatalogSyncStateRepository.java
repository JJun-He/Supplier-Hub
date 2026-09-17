package com.supplierhub.catalog.infrastructure;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.supplierhub.catalog.domain.CatalogSyncState;
import com.supplierhub.catalog.domain.Supplier;

public interface CatalogSyncStateRepository extends JpaRepository<CatalogSyncState, Supplier> {

	@Modifying
	@Query(value = """
		insert into supplier_catalog_state (supplier) values (:supplier)
		on conflict (supplier) do nothing
		""", nativeQuery = true)
	void markInitialized(@Param("supplier") String supplier);

	@Query("select state.supplier from CatalogSyncState state")
	Set<Supplier> findInitializedSuppliers();

}
