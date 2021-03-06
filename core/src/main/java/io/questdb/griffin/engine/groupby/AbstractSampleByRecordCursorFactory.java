/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.groupby;

import io.questdb.cairo.*;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.FunctionParser;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.EmptyTableRecordCursor;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.model.QueryModel;
import io.questdb.std.*;
import org.jetbrains.annotations.NotNull;

public class AbstractSampleByRecordCursorFactory implements RecordCursorFactory {

    protected final RecordCursorFactory base;
    protected final Map map;
    private final DelegatingRecordCursor cursor;
    private final ObjList<Function> recordFunctions;
    private final ObjList<GroupByFunction> groupByFunctions;
    private final RecordSink mapSink;
    private final RecordMetadata metadata;

    public AbstractSampleByRecordCursorFactory(
            CairoConfiguration configuration,
            RecordCursorFactory base,
            @NotNull TimestampSampler timestampSampler,
            @Transient @NotNull QueryModel model,
            @Transient @NotNull ListColumnFilter listColumnFilter,
            @Transient @NotNull FunctionParser functionParser,
            @Transient @NotNull SqlExecutionContext executionContext,
            @Transient @NotNull BytecodeAssembler asm,
            @Transient @NotNull SampleByCursorLambda cursorLambda,
            @Transient @NotNull ArrayColumnTypes keyTypes,
            @Transient @NotNull ArrayColumnTypes valueTypes
    ) throws SqlException {
        final int columnCount = model.getColumns().size();
        final RecordMetadata metadata = base.getMetadata();
        this.groupByFunctions = new ObjList<>(columnCount);
        valueTypes.add(ColumnType.TIMESTAMP); // first value is always timestamp

        GroupByUtils.prepareGroupByFunctions(
                model,
                metadata,
                functionParser,
                executionContext,
                groupByFunctions,
                valueTypes
        );

        this.recordFunctions = new ObjList<>(columnCount);
        final GenericRecordMetadata groupByMetadata = new GenericRecordMetadata();
        final IntIntHashMap symbolTableIndex = new IntIntHashMap();

        GroupByUtils.prepareGroupByRecordFunctions(
                model,
                metadata,
                listColumnFilter,
                groupByFunctions,
                recordFunctions,
                groupByMetadata,
                keyTypes,
                valueTypes.getColumnCount(),
                symbolTableIndex,
                false
        );

        // sink will be storing record columns to map key
        this.mapSink = RecordSinkFactory.getInstance(asm, metadata, listColumnFilter, false);
        // this is the map itself, which we must not forget to free when factory closes
        this.map = MapFactory.createMap(configuration, keyTypes, valueTypes);
        try {
            this.base = base;
            this.metadata = groupByMetadata;
            this.cursor = cursorLambda.createCursor(
                    map,
                    mapSink,
                    timestampSampler,
                    metadata.getTimestampIndex(),
                    groupByFunctions,
                    recordFunctions,
                    symbolTableIndex,
                    keyTypes.getColumnCount()
            );
        } catch (SqlException | CairoException e) {
            map.close();
            throw e;
        }
    }

    @Override
    public void close() {
        for (int i = 0, n = recordFunctions.size(); i < n; i++) {
            recordFunctions.getQuick(i).close();
        }
        map.close();
        base.close();
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) {
        final RecordCursor baseCursor = base.getCursor(executionContext);
        map.clear();

        // This factory fills gaps in data. To do that we
        // have to know all possible key values. Essentially, every time
        // we sample we return same set of key values with different
        // aggregation results and timestamp

        int n = groupByFunctions.size();
        final Record baseCursorRecord = baseCursor.getRecord();
        while (baseCursor.hasNext()) {
            MapKey key = map.withKey();
            mapSink.copy(baseCursorRecord, key);
            MapValue value = key.createValue();
            if (value.isNew()) {
                // timestamp is always stored in value field 0
                value.putLong(0, Numbers.LONG_NaN);
                // have functions reset their columns to "zero" state
                // this would set values for when keys are not found right away
                for (int i = 0; i < n; i++) {
                    groupByFunctions.getQuick(i).setNull(value);
                }
            }
        }

        // empty map? this means that base cursor was empty
        if (map.size() == 0) {
            baseCursor.close();
            return EmptyTableRecordCursor.INSTANCE;
        }

        // because we pass base cursor twice we have to go back to top
        // for the second run
        baseCursor.toTop();
        boolean next = baseCursor.hasNext();
        // we know base cursor has value
        assert next;
        return initFunctionsAndCursor(executionContext, baseCursor);
    }

    @Override
    public RecordMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isRandomAccessCursor() {
        return false;
    }

    @NotNull
    protected RecordCursor initFunctionsAndCursor(SqlExecutionContext executionContext, RecordCursor baseCursor) {
        cursor.of(baseCursor);
        // init all record function for this cursor, in case functions require metadata and/or symbol tables
        for (int i = 0, m = recordFunctions.size(); i < m; i++) {
            recordFunctions.getQuick(i).init(cursor, executionContext);
        }
        return cursor;
    }
}
