package com.supplierhub.catalog.infrastructure;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import com.supplierhub.catalog.application.ActiveCatalogMapping;
import com.supplierhub.catalog.application.ActiveCatalogMappingReader;
import com.supplierhub.catalog.application.CatalogReadException;
import com.supplierhub.catalog.application.CatalogReadException.Reason;

@Component
public class JpaActiveCatalogMappingReader implements ActiveCatalogMappingReader {

	private final RoomTypeRepository roomTypeRepository;
	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transaction;
	private final long statementTimeoutNanos;
	private final long lockTimeoutNanos;

	public JpaActiveCatalogMappingReader(
		RoomTypeRepository roomTypeRepository,
		JdbcTemplate jdbcTemplate,
		PlatformTransactionManager transactionManager,
		CatalogDatabaseProperties properties
	) {
		this.roomTypeRepository = roomTypeRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setReadOnly(true);
		// The read must release its connection and local settings before returning,
		// including when a caller already has an open transaction.
		this.transaction.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW
		);
		this.statementTimeoutNanos = properties.readStatementTimeout().toNanos();
		this.lockTimeoutNanos = properties.readLockTimeout().toNanos();
	}

	@Override
	public List<ActiveCatalogMapping> findAllActive(long deadlineNanos) {
		remainingNanos(deadlineNanos);
		try {
			List<ActiveCatalogMapping> mappings = transaction.execute(status -> {
				configureTimeouts(deadlineNanos);
				remainingNanos(deadlineNanos);
				List<ActiveCatalogMapping> rows = roomTypeRepository
					.findAllActiveMappingsForSearch();
				remainingNanos(deadlineNanos);
				return rows;
			});
			remainingNanos(deadlineNanos);
			return mappings;
		} catch (RuntimeException exception) {
			if (exception instanceof CatalogReadException) {
				throw exception;
			}
			Reason reason = failureReason(exception);
			if (reason != null) {
				throw new CatalogReadException(reason, exception);
			}
			throw exception;
		}
	}

	private void configureTimeouts(long deadlineNanos) {
		jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
			// Connection acquisition may already have spent most of the budget.
			long remaining = remainingNanos(deadlineNanos);
			long statementMillis = ceilMillis(Math.min(statementTimeoutNanos, remaining));
			long lockMillis = ceilMillis(Math.min(lockTimeoutNanos, remaining));
			try (PreparedStatement statement = connection.prepareStatement("""
				select set_config('statement_timeout', ?, true),
				       set_config('lock_timeout', ?, true)
				""")) {
				statement.setString(1, statementMillis + "ms");
				statement.setString(2, lockMillis + "ms");
				statement.execute();
			}
			return null;
		});
	}

	private static long remainingNanos(long deadlineNanos) {
		long remaining = deadlineNanos - System.nanoTime();
		if (remaining <= 0) {
			throw new CatalogReadException(Reason.TIMEOUT);
		}
		return remaining;
	}

	private static long ceilMillis(long positiveNanos) {
		// Zero disables PostgreSQL timeouts, so round a positive remainder up.
		return 1 + (positiveNanos - 1) / 1_000_000;
	}

	static Reason failureReason(Throwable failure) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Throwable> pending = new ArrayDeque<>();
		pending.add(failure);
		while (!pending.isEmpty()) {
			Throwable cause = pending.removeFirst();
			if (!visited.add(cause)) {
				continue;
			}
			if (cause instanceof org.springframework.dao.QueryTimeoutException
				|| cause instanceof jakarta.persistence.QueryTimeoutException
				|| cause instanceof TransactionTimedOutException
				|| cause instanceof SQLTimeoutException) {
				return Reason.TIMEOUT;
			}
			if (cause instanceof DataAccessResourceFailureException
				|| cause instanceof SQLTransientConnectionException) {
				return Reason.UNAVAILABLE;
			}
			if (cause instanceof SQLException sqlException) {
				String state = sqlException.getSQLState();
				if ("57014".equals(state) || "55P03".equals(state)) {
					return Reason.TIMEOUT;
				}
				if (state != null && (state.startsWith("08")
					|| "53300".equals(state)
					|| "57P01".equals(state)
					|| "57P02".equals(state)
					|| "57P03".equals(state))) {
					return Reason.UNAVAILABLE;
				}
				if (sqlException.getNextException() != null) {
					pending.add(sqlException.getNextException());
				}
			}
			if (cause.getCause() != null) {
				pending.add(cause.getCause());
			}
		}
		return null;
	}

}
