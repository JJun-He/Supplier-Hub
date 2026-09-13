package com.supplierhub.catalog.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.supplierhub.catalog.domain.Property;
import com.supplierhub.catalog.domain.Supplier;

public interface PropertyRepository extends JpaRepository<Property, Long> {

	Optional<Property> findBySupplierAndSupplierPropertyCode(
		Supplier supplier,
		String supplierPropertyCode
	);

	List<Property> findAllByActiveTrueOrderByIdAsc();

}
