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

package com.nfsdb.net.ha.comsumer;

import com.nfsdb.JournalWriter;
import com.nfsdb.Partition;
import com.nfsdb.ex.IncompatibleJournalException;
import com.nfsdb.ex.JournalException;
import com.nfsdb.ex.JournalNetworkException;
import com.nfsdb.misc.Interval;
import com.nfsdb.net.ha.AbstractChannelConsumer;
import com.nfsdb.net.ha.model.JournalServerState;
import com.nfsdb.std.ObjList;

import java.nio.channels.ReadableByteChannel;

public class JournalDeltaConsumer extends AbstractChannelConsumer {

    private final JournalWriter journal;
    private final JournalServerStateConsumer journalServerStateConsumer = new JournalServerStateConsumer();
    private final JournalSymbolTableConsumer journalSymbolTableConsumer;
    private final ObjList<PartitionDeltaConsumer> partitionDeltaConsumers = new ObjList<>();
    private JournalServerState state;
    private PartitionDeltaConsumer lagPartitionDeltaConsumer;

    public JournalDeltaConsumer(JournalWriter journal) {
        this.journal = journal;
        this.journalSymbolTableConsumer = new JournalSymbolTableConsumer(journal);
    }

    @Override
    public void free() {
        journalServerStateConsumer.free();
        journalSymbolTableConsumer.free();
        for (int i = 0, k = partitionDeltaConsumers.size(); i < k; i++) {
            partitionDeltaConsumers.getQuick(i).free();
        }
        if (lagPartitionDeltaConsumer != null) {
            lagPartitionDeltaConsumer.free();
        }
    }

    @Override
    protected void commit() throws JournalNetworkException {
        try {
            journal.commit(false, state.getTxn(), state.getTxPin());
        } catch (JournalException e) {
            throw new JournalNetworkException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doRead(ReadableByteChannel channel) throws JournalNetworkException {

        try {

            reset();
            journalServerStateConsumer.read(channel);
            this.state = journalServerStateConsumer.getValue();

            if (state.getTxn() == -1) {
                journal.notifyTxError();
                throw new IncompatibleJournalException("Server refused txn for %s", journal.getLocation());
            }

            if (state.getTxn() < journal.getTxn()) {
                journal.rollback(state.getTxn(), state.getTxPin());
                return;
            }

            journal.beginTx();
            createPartitions(state);

            if (state.isSymbolTables()) {
                journalSymbolTableConsumer.read(channel);
            }

            for (int i = 0, k = state.getNonLagPartitionCount(); i < k; i++) {
                JournalServerState.PartitionMetadata meta = state.getMeta(i);
                if (meta.getEmpty() == 0) {
                    PartitionDeltaConsumer partitionDeltaConsumer = getPartitionDeltaConsumer(meta.getPartitionIndex());
                    partitionDeltaConsumer.read(channel);
                }
            }

            if (state.getLagPartitionName() == null && journal.hasIrregularPartition()) {
                // delete lag partition
                journal.removeIrregularPartition();
            } else if (state.getLagPartitionName() != null) {
                if (lagPartitionDeltaConsumer == null || !journal.hasIrregularPartition()
                        || !state.getLagPartitionName().equals(journal.getIrregularPartition().getName())) {
                    Partition temp = journal.createTempPartition(state.getLagPartitionName());
                    lagPartitionDeltaConsumer = new PartitionDeltaConsumer(temp.open());
                    journal.setIrregularPartition(temp);
                }
                lagPartitionDeltaConsumer.read(channel);
            }
        } catch (JournalException e) {
            throw new JournalNetworkException(e);
        }
    }

    private void createPartitions(JournalServerState metadata) throws JournalException {
        int pc = journal.nonLagPartitionCount() - 1;
        for (int i = 0, k = metadata.getNonLagPartitionCount(); i < k; i++) {
            JournalServerState.PartitionMetadata partitionMetadata = metadata.getMeta(i);
            if (partitionMetadata.getPartitionIndex() > pc) {
                Interval interval = new Interval(partitionMetadata.getIntervalEnd(), partitionMetadata.getIntervalStart());
                journal.createPartition(interval, partitionMetadata.getPartitionIndex());
            }
        }
    }

    private PartitionDeltaConsumer getPartitionDeltaConsumer(int partitionIndex) throws JournalException {
        PartitionDeltaConsumer consumer = partitionDeltaConsumers.getQuiet(partitionIndex);
        if (consumer == null) {
            consumer = new PartitionDeltaConsumer(journal.getPartition(partitionIndex, true));
            partitionDeltaConsumers.extendAndSet(partitionIndex, consumer);
        }

        return consumer;
    }

    private void reset() throws JournalException {
        for (int i = 0, k = partitionDeltaConsumers.size(); i < k; i++) {
            PartitionDeltaConsumer c = partitionDeltaConsumers.getAndSetQuick(i, null);
            if (c != null) {
                c.free();
            }
            journal.getPartition(i, false).close();
        }
    }
}