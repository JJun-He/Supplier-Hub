package com.supplierhub.catalog.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.domain.RoomType;
import com.supplierhub.catalog.domain.Supplier;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

	Optional<RoomType> findByPropertyIdAndSupplierRoomTypeCode(
		Long propertyId,
		String supplierRoomTypeCode
	);

	@Query("""
		select roomType
		from RoomType roomType
		join fetch roomType.property property
		where property.supplier = :supplier
		order by property.id, roomType.id
		""")
	List<RoomType> findAllBySupplierOrderByPropertyAndId(
		@Param("supplier") Supplier supplier
	);

	@Query("""
		select new com.supplierhub.catalog.application.ActiveCatalogMapping(
			property.id,
			property.supplier,
			property.supplierPropertyCode,
			roomType.id,
			roomType.supplierRoomTypeCode
		)
		from RoomType roomType
		join roomType.property property
		where roomType.active = true
		  and property.active = true
		order by property.supplier, property.id, roomType.id
		""")
	List<ActiveCatalogMapping> findAllActiveMappingsForSearch();

}
