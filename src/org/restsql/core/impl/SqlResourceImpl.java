/* Copyright (c) restSQL Project Contributors. Licensed under MIT. */
package org.restsql.core.impl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.restsql.core.ColumnMetaData;
import org.restsql.core.Config;
import org.restsql.core.Factory;
import org.restsql.core.NameValuePair;
import org.restsql.core.Request;
import org.restsql.core.SqlBuilder;
import org.restsql.core.SqlResource;
import org.restsql.core.SqlResourceException;
import org.restsql.core.SqlResourceMetaData;
import org.restsql.core.TableMetaData;
import org.restsql.core.Trigger;
import org.restsql.core.Request.Type;
import org.restsql.core.SqlBuilder.SqlStruct;
import org.restsql.core.sqlresource.SqlResourceDefinition;

/**
 * Represents a SQL Resource, an queryable and updatable database "view". Loads metadata on creation and caches it.
 * 
 * @author Mark Sawers
 */
public class SqlResourceImpl implements SqlResource {
	private final SqlResourceDefinition definition;
	private final SqlResourceMetaData metaData;
	private final String name;
	private final SqlBuilder sqlBuilder;
	private final List<Trigger> triggers;

	public SqlResourceImpl(final String name, final SqlResourceDefinition definition,
			final SqlResourceMetaData metaData, final SqlBuilder sqlBuilder, final List<Trigger> triggers)
			throws SqlResourceException {
		this.name = name;
		this.definition = definition;
		definition.getQuery().setValue(SqlUtils.removeWhitespaceFromSql(definition.getQuery().getValue()));
		this.metaData = metaData;
		this.sqlBuilder = sqlBuilder;
		this.triggers = triggers;
	}

	@Override
	public TableMetaData getChildTable() {
		return metaData.getChild();
	}

	@Override
	public SqlResourceDefinition getDefinition() {
		return definition;
	}

	@Override
	public TableMetaData getJoinTable() {
		return metaData.getJoin();
	}

	@Override
	public SqlResourceMetaData getMetaData() {
		return metaData;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public TableMetaData getParentTable() {
		return metaData.getParent();
	}

	@Override
	public Map<String, TableMetaData> getTables() {
		return metaData.getTableMap();
	}

	@Override
	public List<Trigger> getTriggers() {
		return triggers;
	}

	@Override
	public boolean isHierarchical() {
		return metaData.isHierarchical();
	}

	/**
	 * Executes query.
	 * 
	 * @param request Request object
	 * @throws SqlResourceException if a database access error occurs
	 * @throws TriggerException if a trigger error occurs
	 * @return list of rows, where each row is a map of name-value pairs
	 */
	@SuppressWarnings("unchecked")
	@Override
	public List<Map<String, Object>> readAsCollection(final Request request) throws SqlResourceException {
		return (List<Map<String, Object>>) read(request, false);
	}

	/**
	 * Executes query.
	 * 
	 * @param request Request object
	 * @throws SqlResourceException if the request is invalid or a database access error or trigger exception occurs
	 * @return xml string
	 */
	@Override
	public String readAsXml(final Request request) throws SqlResourceException {
		return (String) read(request, true);
	}

	/**
	 * Executes database write (insert, update or delete).
	 * 
	 * @param request Request object
	 * @throws SqlResourceException if the request is invalid or a database access error or trigger exception occurs
	 * @return rows affected
	 */
	@Override
	public int write(final Request request) throws SqlResourceException {
		TriggerManager.executeTriggers(getName(), request, true);

		int rowsAffected = 0;
		boolean doParent = true;
		Connection connection = null;

		try {
			connection = Factory.getConnection(definition.getDefaultDatabase());
			if (metaData.isHierarchical()) {
				final Request childRequest = Factory.getRequestForChild(request.getType(), getName(), request
						.getResourceIdentifiers(), request.getLogger());
				if (request.getChildrenParameters() != null) {
					// Delete, update or insert specified children
					for (final List<NameValuePair> childRowParams : request.getChildrenParameters()) {
						if (request.getType() == Type.INSERT) {
							// Need to add the parent pks, since inserts ignore the resIds
							childRowParams.addAll(request.getResourceIdentifiers());
						} // else deletes and updates use resIds
						childRequest.setParameters(childRowParams);
						rowsAffected += write(connection, childRequest, false);
					}
					// Don't touch the parent(s)
					doParent = false;
				} else if (request.getType() == Request.Type.DELETE) {
					// Delete all children and the parent(s)
					if (request.getResourceIdentifiers() == null) {
						childRequest.setParameters(request.getParameters());
					}
					rowsAffected += write(connection, childRequest, false);
				} // else just insert or update the parent(s)
			}

			if (doParent) {
				rowsAffected += write(connection, request, true);
			}

			TriggerManager.executeTriggers(getName(), request, false);
		} catch (final SQLException exception) {
			throw new SqlResourceException(exception);
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (final SQLException ignored) {
				}
			}
		}
		return rowsAffected;
	}

	private List<Map<String, Object>> buildReadResultsFlatCollection(final ResultSet resultSet)
			throws SQLException {
		final List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
		while (resultSet.next()) {
			final Map<String, Object> row = new HashMap<String, Object>(metaData.getAllReadColumns().size());
			for (final ColumnMetaData column : metaData.getAllReadColumns()) {
				// Simple name, value pairs will do
				if (!column.isNonqueriedForeignKey()) {
					row.put(column.getColumnLabel(), SqlUtils.getObjectByColumnNumber(column, resultSet));
				}
			}
			results.add(row);
		}
		return results;
	}

	// Private utils

	private List<Map<String, Object>> buildReadResultsHierachicalCollection(final ResultSet resultSet)
			throws SQLException {
		final List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
		final List<Object> currentParentPkValues = new ArrayList<Object>(metaData.getParent()
				.getPrimaryKeys().size());
		boolean newParent = false;
		final int numberParentElementColumns = metaData.getParentReadColumns().size();
		final int numberChildElementColumns = metaData.getChildReadColumns().size();
		final String childRowElementName = metaData.getChild().getTableAlias() + "s";
		Map<String, Object> parentRow = null;
		List<Map<String, Object>> childRows = null;

		while (resultSet.next()) {
			// Assess state of parent
			if (currentParentPkValues.isEmpty()) {
				// First row
				newParent = true;
			} else {
				// Not the first row, check if parent differs from the last
				newParent = false;
				for (int i = 0; i < currentParentPkValues.size(); i++) {
					final ColumnMetaData column = metaData.getParent().getPrimaryKeys().get(i);
					if (!currentParentPkValues.get(i).equals(
							SqlUtils.getObjectByColumnLabel(column, resultSet))) {
						newParent = true;
						break;
					}
				}
			}

			// Set current parent row pk values as well as in the parent row object
			if (newParent) {
				childRows = new ArrayList<Map<String, Object>>();
				parentRow = new HashMap<String, Object>(numberParentElementColumns);
				parentRow.put(childRowElementName, childRows);
				results.add(parentRow);
				currentParentPkValues.clear();

				for (final ColumnMetaData column : metaData.getParentReadColumns()) {
					final Object value = SqlUtils.getObjectByColumnLabel(column, resultSet);
					if (column.isPrimaryKey()) {
						currentParentPkValues.add(value);
					}
					parentRow.put(column.getColumnLabel(), value);
				}
			}

			// Populate the child row object
			Map<String, Object> childRow = new HashMap<String, Object>(numberChildElementColumns);
			boolean nullPk = false;
			for (final ColumnMetaData column : metaData.getChildReadColumns()) {
				final Object value = SqlUtils.getObjectByColumnLabel(column, resultSet);
				if (column.isPrimaryKey()) {
					nullPk = value == null;
				}
				childRow.put(column.getColumnLabel(), value);
			}
			if (nullPk) {
				childRow = null;
			} else {
				childRows.add(childRow);
			}

		}
		return results;
	}

	private Object read(final Request request, final boolean returnXml) throws SqlResourceException {
		TriggerManager.executeTriggers(getName(), request, true);

		final Object results;
		Connection connection = null;
		String sql = null;
		try {
			connection = Factory.getConnection(definition.getDefaultDatabase());
			final Statement statement = connection.createStatement();
			sql = sqlBuilder.buildSelectSql(metaData, definition.getQuery().getValue(), request
					.getResourceIdentifiers(), request.getParameters());
			Config.logger.debug(sql);
			request.getLogger().addSql(sql);
			final ResultSet resultSet = statement.executeQuery(sql);
			if (metaData.isHierarchical()) {
				if (returnXml) {
					results = ResultsSerializer.serializeReadHierarchical(this,
							buildReadResultsHierachicalCollection(resultSet));
				} else {
					results = buildReadResultsHierachicalCollection(resultSet);
				}
			} else {
				if (returnXml) {
					results = ResultsSerializer.serializeReadFlat(this, resultSet);
				} else {
					results = buildReadResultsFlatCollection(resultSet);
				}
			}
			resultSet.close();
			statement.close();
		} catch (final SQLException exception) {
			throw new SqlResourceException(exception, sql);
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (final SQLException ignored) {
				}
			}
		}

		TriggerManager.executeTriggers(getName(), request, false);
		return results;
	}

	private int write(final Connection connection, final Request request, final boolean doParent)
			throws SqlResourceException {
		int rowsAffected = 0;
		final Map<String, SqlBuilder.SqlStruct> sqls = sqlBuilder.buildWriteSql(metaData, request, doParent);

		// Remove sql for main table
		final String mainTableName = doParent ? metaData.getParent().getQualifiedTableName() : metaData
				.getChild().getQualifiedTableName();
		final SqlBuilder.SqlStruct mainTableSqlStruct = sqls.remove(mainTableName);

		// Do the main table if insert
		if (request.getType() == Type.INSERT) {
			rowsAffected += write(connection, request, mainTableSqlStruct, true);
		}

		// Do extensions next
		for (final SqlBuilder.SqlStruct sqlStruct : sqls.values()) {
			rowsAffected += write(connection, request, sqlStruct, false);
		}

		// Do the main table if update or delete
		if (request.getType() != Type.INSERT) {
			rowsAffected += write(connection, request, mainTableSqlStruct, true);
		}

		return rowsAffected;
	}

	private int write(final Connection connection, final Request request, final SqlStruct sqlStruct,
			final boolean doMain) throws SqlResourceException {
		int rowsAffected = 0;
		if (sqlStruct != null) {
			if (!doMain && sqlStruct.isClauseEmpty()) {
				// do not execute update on extension, which would affect all rows
			} else {
				final String sql = sqlStruct.getSql();
				try {
					final Statement statement = connection.createStatement();
					Config.logger.debug(sql);
					request.getLogger().addSql(sql);
					rowsAffected = statement.executeUpdate(sql);
					statement.close();
				} catch (final SQLException exception) {
					throw new SqlResourceException(exception, sql);
				}
			}
		}
		return rowsAffected;
	}
}
