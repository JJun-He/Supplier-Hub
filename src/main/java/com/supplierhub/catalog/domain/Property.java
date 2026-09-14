package com.supplierhub.catalog.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "supplier_property",
	uniqueConstraints = @UniqueConstraint(
		name = "uq_supplier_property_supplier_code",
		columnNames = {"supplier", "supplier_property_code"}
	)
)
public class Property {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Supplier supplier;

	@Column(name = "supplier_property_code", nullable = false, length = 100)
	private String supplierPropertyCode;

	@Column(nullable = false, length = 255)
	private String name;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "consecutive_missing_count", nullable = false)
	private int consecutiveMissingCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Property() {
	}

	private Property(
		Supplier supplier,
		String supplierPropertyCode,
		String name
	) {
		this.supplier = Objects.requireNonNull(supplier, "supplier must not be null");
		this.supplierPropertyCode = requireText(
			supplierPropertyCode,
			"supplierPropertyCode"
		);
		this.name = requireText(name, "name");
		this.active = true;
		this.consecutiveMissingCount = 0;
	}

	public static Property create(
		Supplier supplier,
		String supplierPropertyCode,
		String name
	) {
		return new Property(supplier, supplierPropertyCode, name);
	}

	public void refresh(String name) {
		this.name = requireText(name, "name");
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

	public Supplier getSupplier() {
		return supplier;
	}

	public String getSupplierPropertyCode() {
		return supplierPropertyCode;
	}

	public String getName() {
		return name;
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

}
