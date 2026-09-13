package com.supplierhub.catalog.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.supplierhub.catalog.domain.RoomType;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

	Optional<RoomType> findByPropertyIdAndSupplierRoomTypeCode(
		Long propertyId,
		String supplierRoomTypeCode
	);

	@Query("""
		select roomType
		from RoomType roomType
		join fetch roomType.property property
		where roomType.active = true
		  and property.active = true
		order by property.id, roomType.id
		""")
	List<RoomType> findAllActiveForSearch();

}
