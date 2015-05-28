/*
 * Copyright (c) 2014. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.ql.ops;

import com.nfsdb.ql.Record;
import com.nfsdb.ql.SymFacade;
import com.nfsdb.storage.ColumnType;

public class DoubleRecordSourceColumn extends AbstractVirtualColumn {
    private final int index;

    public DoubleRecordSourceColumn(int index) {
        super(ColumnType.DOUBLE);
        this.index = index;
    }

    @Override
    public double getDouble(Record rec) {
        return rec.getDouble(index);
    }

    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public void prepare(SymFacade facade) {
    }
}