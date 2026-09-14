package com.supplierhub.catalog.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "supplier_room_type",
	uniqueConstraints = @UniqueConstraint(
		name = "uq_supplier_room_type_property_code",
		columnNames = {"property_id", "supplier_room_type_code"}
	)
)
public class RoomType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
		name = "property_id",
		nullable = false,
		foreignKey = @ForeignKey(name = "fk_supplier_room_type_property")
	)
	private Property property;

	@Column(name = "supplier_room_type_code", nullable = false, length = 100)
	private String supplierRoomTypeCode;

	@Column(nullable = false, length = 255)
	private String name;

	@Column(name = "max_occupancy", nullable = false)
	private int maxOccupancy;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "consecutive_missing_count", nullable = false)
	private int consecutiveMissingCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected RoomType() {
	}

	private RoomType(
		Property property,
		String supplierRoomTypeCode,
		String name,
		int maxOccupancy
	) {
		this.property = Objects.requireNonNull(property, "property must not be null");
		this.supplierRoomTypeCode = requireText(
			supplierRoomTypeCode,
			"supplierRoomTypeCode"
		);
		this.name = requireText(name, "name");
		this.maxOccupancy = requirePositive(maxOccupancy);
		this.active = true;
		this.consecutiveMissingCount = 0;
	}

	public static RoomType create(
		Property property,
		String supplierRoomTypeCode,
		String name,
		int maxOccupancy
	) {
		return new RoomType(
			property,
			supplierRoomTypeCode,
			name,
			maxOccupancy
		);
	}

	public void refresh(String name, int maxOccupancy) {
		this.name = requireText(name, "name");
		this.maxOccupancy = requirePositive(maxOccupancy);
		this.active = true;
		this.consecutiveMissingCount = 0;
	}

	public void deactivate() {
		this.active = false;
	}

	public void recordMissing(int deactivationThreshold) {
		if (deactivationThreshold <= 0) {
			throw new IllegalArgumentException(
				"deactivationThreshold must be positive"
			);
		}
		if (!active) {
			return;
		}
		consecutiveMissingCount = Math.min(
			consecutiveMissingCount + 1,
			deactivationThreshold
		);
		if (consecutiveMissingCount >= deactivationThreshold) {
			active = false;
		}
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Property getProperty() {
		return property;
	}

	public String getSupplierRoomTypeCode() {
		return supplierRoomTypeCode;
	}

	public String getName() {
		return name;
	}

	public int getMaxOccupancy() {
		return maxOccupancy;
	}

	public boolean isActive() {
		return active;
	}

	public int getConsecutiveMissingCount() {
		return consecutiveMissingCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

	private static int requirePositive(int maxOccupancy) {
		if (maxOccupancy <= 0) {
			throw new IllegalArgumentException("maxOccupancy must be positive");
		}
		return maxOccupancy;
	}

}
