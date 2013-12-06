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
package ohmdb.interfaces;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import ohmdb.discovery.Beacon;
import ohmdb.messages.ControlMessages;
import org.jetlang.channels.RequestChannel;

import java.util.List;

/**
 * The internal/cross module interface to the discovery module.
 */
public interface DiscoveryModule extends OhmModule {
    RequestChannel<NodeInfoRequest, NodeInfoReply> getNodeInfo();

    ListenableFuture<ImmutableMap<Long, NodeInfo>> getState();

    public static class NodeInfoRequest {
        public final long nodeId;
        public final ControlMessages.ModuleType moduleType;

        public NodeInfoRequest(long nodeId, ControlMessages.ModuleType moduleType) {
            this.nodeId = nodeId;
            this.moduleType = moduleType;
        }

        @Override
        public String toString() {
            return "NodeInfoRequest{" +
                    "nodeId=" + nodeId +
                    ", moduleType=" + moduleType +
                    '}';
        }
    }

    public static class NodeInfoReply {
        /**
         * Was the node/module information found?
         */
        public final boolean found;
        public final List<String> addresses;
        public final int port;

        public NodeInfoReply(boolean found, List<String> addresses, int port) {
            this.found = found;
            this.addresses = addresses;
            this.port = port;
        }

        @Override
        public String toString() {
            return "NodeInfoReply{" +
                    "found=" + found +
                    ", addresses=" + addresses +
                    ", port=" + port +
                    '}';
        }

        public final static NodeInfoReply NO_REPLY = new NodeInfoReply(false, null, 0);
    }

    /**
     * Information about a node.
     */
    public static class NodeInfo {
        public final Beacon.Availability availability;
        public final long lastContactTime;
        public final ImmutableMap<ControlMessages.ModuleType, Integer> modules;

        public NodeInfo(Beacon.Availability availability, long lastContactTime) {
            this.availability = availability;
            this.lastContactTime = lastContactTime;
            ImmutableMap.Builder<ControlMessages.ModuleType, Integer> b = ImmutableMap.builder();
            for (Beacon.ModuleDescriptor moduleDescriptor : availability.getModulesList()) {
                b.put(moduleDescriptor.getModule(), moduleDescriptor.getModulePort());
            }
            modules = b.build();
        }

        public NodeInfo(Beacon.Availability availability) {
            this(availability, System.currentTimeMillis());
        }

        @Override
        public String toString() {
            return availability + " last contact: " + lastContactTime;
        }
    }
}
