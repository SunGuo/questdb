/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * As a special exception, the copyright holders give permission to link the
 * code of portions of this program with the OpenSSL library under certain
 * conditions as described in each individual source file and distribute
 * linked combinations including the program with the OpenSSL library. You
 * must comply with the GNU Affero General Public License in all respects for
 * all of the code used other than as permitted herein. If you modify file(s)
 * with this exception, you may extend this exception to your version of the
 * file(s), but you are not obligated to do so. If you do not wish to do so,
 * delete this exception statement from your version. If you delete this
 * exception statement from all source files in the program, then also delete
 * it in the license file.
 *
 ******************************************************************************/

package com.nfsdb.ql.impl.map;

import com.nfsdb.factory.configuration.RecordMetadata;
import com.nfsdb.misc.Unsafe;
import com.nfsdb.ql.Record;
import com.nfsdb.ql.RecordCursor;
import com.nfsdb.ql.StorageFacade;
import com.nfsdb.std.AbstractImmutableIterator;
import com.nfsdb.std.ObjList;

final class MapRecordSource extends AbstractImmutableIterator<Record> implements RecordCursor {
    private final MapRecord record;
    private final MapValues values;
    private final ObjList<MapRecordValueInterceptor> interceptors;
    private final int interceptorsLen;
    private int count;
    private long address;

    MapRecordSource(MapRecord record, MapValues values, ObjList<MapRecordValueInterceptor> interceptors) {
        this.record = record;
        this.values = values;
        this.interceptors = interceptors;
        this.interceptorsLen = interceptors != null ? interceptors.size() : 0;
    }

    @Override
    public Record getByRowId(long rowId) {
        return null;
    }

    @Override
    public RecordMetadata getMetadata() {
        return record.getMetadata();
    }

    @Override
    public StorageFacade getStorageFacade() {
        return null;
    }

    @Override
    public boolean hasNext() {
        return count > 0;
    }

    @Override
    public Record next() {
        long address = this.address;
        this.address = address + Unsafe.getUnsafe().getInt(address);
        count--;
        if (interceptorsLen > 0) {
            notifyInterceptors(address);
        }
        return record.init(address);
    }

    MapRecordSource init(long address, int count) {
        this.address = address;
        this.count = count;
        return this;
    }

    private void notifyInterceptors(long address) {
        for (int i = 0; i < interceptorsLen; i++) {
            interceptors.getQuick(i).beforeRecord(values.of(address, false));
        }
    }
}