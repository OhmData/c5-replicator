/*
 * Copyright (C) 2013  Ohm Data
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ohmdb.replication;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.ArrayList;
import java.util.List;

import static ohmdb.replication.Raft.LogEntry;

/**

 */
public class InRamLog implements RaftLogAbstraction {

    private static class Entry {
        public final long index;
        public final long term;
        public final byte[] data;

        private Entry(long index, long term, byte[] data) {
            this.index = index;
            this.term = term;
            this.data = data;
        }
    }

    private final ArrayList<LogEntry> log = new ArrayList<>();

    public InRamLog() {
    }

    @Override
    public ListenableFuture<Boolean> logEntries(List<LogEntry> entries) {
        // add them, for great justice.
        assert log.size() == entries.get(0).getIndex();
        // TODO more assertions

        log.addAll(entries);


        SettableFuture<Boolean> r = SettableFuture.create();
        r.set(true);
        return r;
    }

    @Override
    public LogEntry getLogEntry(long index) {
        return log.get((int) index);
    }

    @Override
    public synchronized long getLogTerm(long index) {
        assert index < log.size();
        return log.get((int) index).getTerm();
    }

    @Override
    public synchronized long getLastTerm() {
        if (log.isEmpty()) return 0;
        return log.get(log.size()-1).getTerm();
    }

    @Override
    public synchronized long getLastIndex() {
        return log.size();
    }

    @Override
    public synchronized ListenableFuture<Boolean> truncateLog(long entryIndex) {
        log.subList((int) entryIndex, log.size()).clear();
        SettableFuture<Boolean> r = SettableFuture.create();
        r.set(true);
        return r;
    }
}
