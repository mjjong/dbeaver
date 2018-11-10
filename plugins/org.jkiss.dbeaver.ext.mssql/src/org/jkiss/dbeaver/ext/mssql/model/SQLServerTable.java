/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.mssql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mssql.SQLServerUtils;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.DBSObjectCache;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectCache;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * SQLServerTable
 */
public class SQLServerTable extends SQLServerTableBase implements DBPScriptObject
{
    private static final Log log = Log.getLog(SQLServerTable.class);

    private CheckConstraintCache checkConstraintCache = new CheckConstraintCache();
    private String ddl;

    public SQLServerTable(SQLServerSchema schema)
    {
        super(schema);
    }

    // Copy constructor
    public SQLServerTable(DBRProgressMonitor monitor, SQLServerSchema schema, DBSEntity source) throws DBException {
        super(monitor, schema, source);

        DBSObjectCache<SQLServerTable, SQLServerTableColumn> colCache = getContainer().getTableCache().getChildrenCache(this);
        // Copy columns
        for (DBSEntityAttribute srcColumn : CommonUtils.safeCollection(source.getAttributes(monitor))) {
            if (DBUtils.isHiddenObject(srcColumn)) {
                continue;
            }
            SQLServerTableColumn column = new SQLServerTableColumn(monitor, this, srcColumn);
            colCache.cacheObject(column);
        }
    }

    public SQLServerTable(
        SQLServerSchema catalog,
        ResultSet dbResult)
    {
        super(catalog, dbResult);
    }

    @Override
    public boolean isView()
    {
        return false;
    }

    @Override
    public Collection<SQLServerTableColumn> getAttributes(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        List<SQLServerTableColumn> childColumns = getContainer().getTableCache().getChildren(monitor, getContainer(), this);
        if (childColumns == null) {
            return Collections.emptyList();
        }
        List<SQLServerTableColumn> columns = new ArrayList<>(childColumns);
        columns.sort(DBUtils.orderComparator());
        return columns;
    }

    @Override
    public SQLServerTableColumn getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull String attributeName)
        throws DBException
    {
        return getContainer().getTableCache().getChild(monitor, getContainer(), this, attributeName);
    }

    public SQLServerTableColumn getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull long columnId)
        throws DBException
    {
        for (SQLServerTableColumn col : getAttributes(monitor)) {
            if (col.getObjectId() == columnId) {
                return col;
            }
        }
        log.error("Column '" + columnId + "' not found in table '" + getFullyQualifiedName(DBPEvaluationContext.DML) + "'");
        return null;
    }

    @Override
    @Association
    public synchronized Collection<SQLServerTableIndex> getIndexes(DBRProgressMonitor monitor)
        throws DBException
    {
        return this.getContainer().getIndexCache().getObjects(monitor, getSchema(), this);
    }

    public SQLServerTableIndex getIndex(DBRProgressMonitor monitor, long indexId) throws DBException {
        for (SQLServerTableIndex index : getIndexes(monitor)) {
            if (index.getObjectId() == indexId) {
                return index;
            }
        }
        log.error("Index '" + indexId + "' not found in table '" + getFullyQualifiedName(DBPEvaluationContext.DML) + "'");
        return null;
    }

    public SQLServerTableIndex getIndex(DBRProgressMonitor monitor, String name) throws DBException {
        for (SQLServerTableIndex index : getIndexes(monitor)) {
            if (CommonUtils.equalObjects(name, index.getName())) {
                return index;
            }
        }
        log.error("Index '" + name + "' not found in table '" + getFullyQualifiedName(DBPEvaluationContext.DML) + "'");
        return null;
    }

    @Nullable
    @Override
    @Association
    public synchronized Collection<SQLServerTableUniqueKey> getConstraints(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        return getContainer().getUniqueConstraintCache().getObjects(monitor, getSchema(), this);
    }

    @Nullable
    @Association
    public synchronized Collection<SQLServerTableCheckConstraint> getCheckConstraints(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        return checkConstraintCache.getAllObjects(monitor, this);
    }

    @Override
    @Association
    public Collection<SQLServerTableForeignKey> getReferences(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        return null;//loadForeignKeys(monitor, true);
    }

    @Override
    public synchronized Collection<SQLServerTableForeignKey> getAssociations(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        return null;//foreignKeys.getCachedObjects();
    }

    @Override
    public String getObjectDefinitionText(DBRProgressMonitor monitor, Map<String, Object> options) throws DBException {
        if (CommonUtils.getOption(options, DBPScriptObject.OPTION_REFRESH)) {
            ddl = null;
        }
        if (ddl == null) {
            ddl = JDBCUtils.generateTableDDL(monitor, this, options, false);
        }
        return ddl;
    }

    @Nullable
    @Override
    public String getDescription()
    {
        return null;/*additionalInfo.description;*/
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        getContainer().getIndexCache().clearObjectCache(this);
        getContainer().getUniqueConstraintCache().clearObjectCache(this);

        return getContainer().getTableCache().refreshObject(monitor, getContainer(), this);
    }

    /**
     * Constraint cache implementation
     */
    static class CheckConstraintCache extends JDBCObjectCache<SQLServerTable, SQLServerTableCheckConstraint> {

        @Override
        protected JDBCStatement prepareObjectsStatement(JDBCSession session, SQLServerTable table) throws SQLException {
            JDBCPreparedStatement dbStat = session.prepareStatement("SELECT * FROM " + SQLServerUtils.getSystemTableName(table.getDatabase(), "check_constraints") + " WHERE parent_object_id=?");
            dbStat.setLong(1, table.getObjectId());
            return dbStat;
        }

        @Override
        protected SQLServerTableCheckConstraint fetchObject(JDBCSession session, SQLServerTable table, JDBCResultSet resultSet) throws SQLException, DBException {
            return new SQLServerTableCheckConstraint(table, resultSet);
        }
    }

}
