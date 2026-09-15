package com.supplierhub.catalog.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.supplierhub.catalog.domain.CatalogSnapshot;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogProperty;
import com.supplierhub.catalog.domain.CatalogSnapshot.CatalogRoomType;
import com.supplierhub.catalog.domain.Property;
import com.supplierhub.catalog.domain.RoomType;
import com.supplierhub.catalog.infrastructure.PropertyRepository;
import com.supplierhub.catalog.infrastructure.RoomTypeRepository;
import com.supplierhub.supplier.common.SupplierIntegrationProperties;

@Service
public class CatalogSnapshotWriter implements CatalogSnapshotStore {

	private static final int DEACTIVATION_MISSING_COUNT = 2;

	private final PropertyRepository propertyRepository;
	private final RoomTypeRepository roomTypeRepository;
	private final int bulkMissingMinimumCount;
	private final double maximumMissingRatio;

	public CatalogSnapshotWriter(
		PropertyRepository propertyRepository,
		RoomTypeRepository roomTypeRepository,
		SupplierIntegrationProperties properties
	) {
		this.propertyRepository = propertyRepository;
		this.roomTypeRepository = roomTypeRepository;
		this.bulkMissingMinimumCount = properties.catalog()
			.bulkMissingMinimumCount();
		this.maximumMissingRatio = properties.catalog().maximumMissingRatio();
	}

	@Override
	@Transactional
	public CatalogSnapshotUpdate replace(CatalogSnapshot snapshot) {
		CatalogChangeSummary changes = new CatalogChangeSummary();
		List<Property> existingPropertyList =
			propertyRepository.findAllBySupplierOrderByIdAsc(snapshot.supplier());
		Map<String, Property> existingProperties = indexProperties(
			existingPropertyList
		);
		Map<Long, List<RoomType>> existingRoomTypes = groupRoomTypes(
			roomTypeRepository.findAllBySupplierOrderByPropertyAndId(
				snapshot.supplier()
			)
		);
		ensureReplacementIsSafe(
			snapshot,
			existingPropertyList,
			existingProperties,
			existingRoomTypes
		);
		Set<Long> seenPropertyIds = new HashSet<>();

		for (CatalogProperty catalogProperty : snapshot.properties()) {
			Property property = existingProperties.get(
				catalogProperty.supplierPropertyCode()
			);
			if (property == null) {
				property = propertyRepository.save(Property.create(
					snapshot.supplier(),
					catalogProperty.supplierPropertyCode(),
					catalogProperty.name()
				));
				changes.createdProperties++;
			} else {
				if (!property.isActive()) {
					changes.reactivatedProperties++;
				}
				property.refresh(catalogProperty.name());
			}

			seenPropertyIds.add(property.getId());
			replaceRoomTypes(
				property,
				catalogProperty.roomTypes(),
				existingRoomTypes.getOrDefault(property.getId(), List.of()),
				changes
			);
		}

		for (Property property : existingProperties.values()) {
			if (!seenPropertyIds.contains(property.getId())) {
				boolean wasActive = property.isActive();
				property.recordMissing(DEACTIVATION_MISSING_COUNT);
				if (wasActive && property.isActive()) {
					changes.suspectedMissingProperties++;
				} else if (wasActive) {
					changes.deactivatedProperties++;
					existingRoomTypes
						.getOrDefault(property.getId(), List.of())
						.forEach(roomType -> {
							if (roomType.isActive()) {
								roomType.deactivate();
								changes.deactivatedRoomTypes++;
							}
						});
				}
			}
		}

		return changes.toUpdate(snapshot);
	}

	private void replaceRoomTypes(
		Property property,
		List<CatalogRoomType> catalogRoomTypes,
		List<RoomType> existingRoomTypeList,
		CatalogChangeSummary changes
	) {
		Map<String, RoomType> existingRoomTypes = indexRoomTypes(
			existingRoomTypeList
		);
		Set<Long> seenRoomTypeIds = new HashSet<>();

		for (CatalogRoomType catalogRoomType : catalogRoomTypes) {
			RoomType roomType = existingRoomTypes.get(
				catalogRoomType.supplierRoomTypeCode()
			);
			if (roomType == null) {
				roomType = roomTypeRepository.save(RoomType.create(
					property,
					catalogRoomType.supplierRoomTypeCode(),
					catalogRoomType.name(),
					catalogRoomType.maxOccupancy()
				));
				changes.createdRoomTypes++;
			} else {
				if (!roomType.isActive()) {
					changes.reactivatedRoomTypes++;
				}
				roomType.refresh(
					catalogRoomType.name(),
					catalogRoomType.maxOccupancy()
				);
			}
			seenRoomTypeIds.add(roomType.getId());
		}

		for (RoomType roomType : existingRoomTypes.values()) {
			if (!seenRoomTypeIds.contains(roomType.getId())) {
				boolean wasActive = roomType.isActive();
				roomType.recordMissing(DEACTIVATION_MISSING_COUNT);
				if (wasActive && roomType.isActive()) {
					changes.suspectedMissingRoomTypes++;
				} else if (wasActive) {
					changes.deactivatedRoomTypes++;
				}
			}
		}
	}

	private void ensureReplacementIsSafe(
		CatalogSnapshot snapshot,
		List<Property> existingPropertyList,
		Map<String, Property> existingProperties,
		Map<Long, List<RoomType>> existingRoomTypes
	) {
		if (snapshot.properties().isEmpty()
			&& existingPropertyList.stream().anyMatch(Property::isActive)) {
			throw new CatalogSnapshotRejectedException(
				"Empty catalog cannot replace active property mappings"
			);
		}

		ensureBulkMissingIsSafe(
			"properties",
			existingPropertyList.stream()
				.filter(Property::isActive)
				.map(Property::getSupplierPropertyCode)
				.collect(Collectors.toSet()),
			snapshot.properties().stream()
				.map(CatalogProperty::supplierPropertyCode)
				.collect(Collectors.toSet())
		);

		Set<RoomTypeKey> existingActiveRoomTypes = existingRoomTypes.values()
			.stream()
			.flatMap(List::stream)
			.filter(roomType -> roomType.isActive()
				&& roomType.getProperty().isActive())
			.map(roomType -> new RoomTypeKey(
				roomType.getProperty().getSupplierPropertyCode(),
				roomType.getSupplierRoomTypeCode()
			))
			.collect(Collectors.toSet());
		Set<RoomTypeKey> snapshotRoomTypes = snapshot.properties().stream()
			.flatMap(property -> property.roomTypes().stream()
				.map(roomType -> new RoomTypeKey(
					property.supplierPropertyCode(),
					roomType.supplierRoomTypeCode()
				)))
			.collect(Collectors.toSet());
		ensureBulkMissingIsSafe(
			"room types",
			existingActiveRoomTypes,
			snapshotRoomTypes
		);

		for (CatalogProperty catalogProperty : snapshot.properties()) {
			Property existingProperty = existingProperties.get(
				catalogProperty.supplierPropertyCode()
			);
			if (existingProperty == null || !catalogProperty.roomTypes().isEmpty()) {
				continue;
			}

			boolean hasActiveRoomTypes = existingRoomTypes
				.getOrDefault(existingProperty.getId(), List.of())
				.stream()
				.anyMatch(RoomType::isActive);
			if (hasActiveRoomTypes) {
				throw new CatalogSnapshotRejectedException(
					"Empty room type catalog cannot replace active mappings"
				);
			}
		}
	}

	private <T> void ensureBulkMissingIsSafe(
		String mappingType,
		Set<T> existingActiveMappings,
		Set<T> snapshotMappings
	) {
		if (existingActiveMappings.isEmpty()) {
			return;
		}
		long missingCount = existingActiveMappings.stream()
			.filter(mapping -> !snapshotMappings.contains(mapping))
			.count();
		double missingRatio = (double) missingCount
			/ existingActiveMappings.size();
		boolean allActiveMappingsMissing = missingCount
			== existingActiveMappings.size();
		boolean bulkMissingThresholdExceeded = missingCount
			>= bulkMissingMinimumCount
			&& missingRatio > maximumMissingRatio;
		if (allActiveMappingsMissing || bulkMissingThresholdExceeded) {
			throw new CatalogSnapshotRejectedException(
				"Bulk missing " + mappingType + " rejected: missing="
					+ missingCount + ", active="
					+ existingActiveMappings.size() + ", ratio="
					+ missingRatio
			);
		}
	}

	private Map<String, Property> indexProperties(List<Property> properties) {
		Map<String, Property> indexed = new HashMap<>();
		for (Property property : properties) {
			indexed.put(property.getSupplierPropertyCode(), property);
		}
		return indexed;
	}

	private Map<String, RoomType> indexRoomTypes(List<RoomType> roomTypes) {
		Map<String, RoomType> indexed = new HashMap<>();
		for (RoomType roomType : roomTypes) {
			indexed.put(roomType.getSupplierRoomTypeCode(), roomType);
		}
		return indexed;
	}

	private Map<Long, List<RoomType>> groupRoomTypes(List<RoomType> roomTypes) {
		Map<Long, List<RoomType>> grouped = new HashMap<>();
		for (RoomType roomType : roomTypes) {
			grouped.computeIfAbsent(
				roomType.getProperty().getId(),
				ignored -> new ArrayList<>()
			).add(roomType);
		}
		return grouped;
	}

	private record RoomTypeKey(
		String supplierPropertyCode,
		String supplierRoomTypeCode
	) {
	}

	private static final class CatalogChangeSummary {

		private int createdProperties;
		private int createdRoomTypes;
		private int reactivatedProperties;
		private int reactivatedRoomTypes;
		private int suspectedMissingProperties;
		private int suspectedMissingRoomTypes;
		private int deactivatedProperties;
		private int deactivatedRoomTypes;

		private CatalogSnapshotUpdate toUpdate(CatalogSnapshot snapshot) {
			return new CatalogSnapshotUpdate(
				snapshot.properties().size(),
				snapshot.properties().stream()
					.mapToInt(property -> property.roomTypes().size())
					.sum(),
				createdProperties,
				createdRoomTypes,
				reactivatedProperties,
				reactivatedRoomTypes,
				suspectedMissingProperties,
				suspectedMissingRoomTypes,
				deactivatedProperties,
				deactivatedRoomTypes
			);
		}

	}

}
