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
package ohmdb;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.MessageLite;
import io.netty.channel.nio.NioEventLoopGroup;
import ohmdb.discovery.BeaconService;
import ohmdb.interfaces.OhmServer;
import ohmdb.interfaces.OhmService;
import ohmdb.messages.ControlMessages;
import ohmdb.regionserver.RegistryFile;
import ohmdb.replication.ReplicatorService;
import ohmdb.util.FiberOnly;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.jetlang.channels.Channel;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.channels.MemoryRequestChannel;
import org.jetlang.channels.Request;
import org.jetlang.channels.RequestChannel;
import org.jetlang.core.Callback;
import org.jetlang.core.Disposable;
import org.jetlang.core.RunnableExecutorImpl;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.PoolFiberFactory;
import org.jetlang.fibers.ThreadFiber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static ohmdb.OhmStatic.bootStrapRegions;
import static ohmdb.OhmStatic.existingRegister;
import static ohmdb.OhmStatic.getRandomPath;
import static ohmdb.OhmStatic.recoverOhmServer;
import static ohmdb.log.OLog.moveAwayOldLogs;
import static ohmdb.messages.ControlMessages.CommandReply;
import static ohmdb.messages.ControlMessages.ServiceType;
import static ohmdb.messages.ControlMessages.StartService;
import static ohmdb.messages.ControlMessages.StopService;


/**
 * Holds information about all other services, can start/stop other services, etc.
 * Knows the 'root' information about this server as well, such as NodeId, etc.
 *
 * To shut down the 'server' service is to shut down the server.
 */
public class OhmDB extends AbstractService implements OhmServer {
    private static final Logger LOG = LoggerFactory.getLogger(OhmDB.class);

  public static void main(String[] args) throws Exception {
        String cfgPath = "/tmp/ohmdb-ryan-" + System.currentTimeMillis();

        if (args.length > 0) {
            cfgPath = args[0];
        }

        ConfigDirectory cfgDir = new ConfigDirectory(Paths.get(cfgPath));

        // manually set the node id into the file.
        if (args.length > 1) {
            cfgDir.setNodeIdFile(args[1]);
        }
        if (args.length > 2) {
            cfgDir.setClusterNameFile(args[2]);
        }

        instance = new OhmDB(cfgDir);
        instance.start();

        // issue startup commands here that are common/we always want:
        ControlMessages.StartService startBeacon = ControlMessages.StartService.newBuilder()
                .setService(ServiceType.Discovery)
                .setServicePort(54333)
                .setServiceArgv("")
                .build();
        instance.getCommandChannel().publish(startBeacon);

    }

    private static OhmServer instance = null;


    public OhmDB(ConfigDirectory configDirectory) throws IOException {
        this.configDirectory = configDirectory;

        String data = configDirectory.getNodeId();
        long toNodeId = 0;
        if (data != null) {
            try {
                toNodeId = Long.parseLong(data);
            } catch (NumberFormatException ignored) {
            }
        }

        if (toNodeId == 0) {
            Random r = new Random();
            toNodeId = r.nextLong();
            configDirectory.setNodeIdFile(Long.toString(toNodeId));
        }

        this.nodeId = toNodeId;

//        String clusterNameData = configDirectory.getClusterName();
//        if (clusterNameData == null) {
//            clusterNameData = "the-cluster";
//            configDirectory.setClusterNameFile(clusterNameData);
//        }
//        this.clusterName = clusterNameData;
    }

    /**
     * Returns the server, but it will be null if you aren't running inside one.
     * @return
     */
    public static OhmServer getServer() {
        return instance;
    }

    @Override
    public long getNodeId() {
        return nodeId;
    }

    @Override
    public ListenableFuture<OhmService> getService(final ServiceType serviceType) {
        final SettableFuture<OhmService> future = SettableFuture.create();
        serverFiber.execute(new Runnable() {
            @Override
            public void run() {

                // What happens iff the serviceRegistry has EMPTY?
                if (!serviceRegistry.containsKey(serviceType)) {
                    // listen to the registration stream:
                    final Disposable[] d = new Disposable[]{null};
                    d[0] = getServiceRegisteredChannel().subscribe(serverFiber, new Callback<ServiceStateChange>() {
                        @Override
                        public void onMessage(ServiceStateChange message) {
                            if (message.state != State.RUNNING) return;

                            if (message.service.getServiceType().equals(serviceType)) {
                                future.set(message.service);

                                assert d[0] != null;  // this is pretty much impossible because of how fibers work.
                                d[0].dispose();
                            }
                        }
                    });
                }

                future.set(serviceRegistry.get(serviceType));
            }
        });
        return future;

    }

    @Override
    public ImmutableMap<ServiceType, OhmService> getServices() throws ExecutionException, InterruptedException {
        final SettableFuture<ImmutableMap<ServiceType, OhmService>> future = SettableFuture.create();
        serverFiber.execute(new Runnable() {
            @Override
            public void run() {
                future.set(ImmutableMap.copyOf(serviceRegistry));
            }
        });
        return future.get();
    }
    @Override
    public ListenableFuture<ImmutableMap<ServiceType, OhmService>> getServices2() {
        final SettableFuture<ImmutableMap<ServiceType, OhmService>> future = SettableFuture.create();
        serverFiber.execute(new Runnable() {
            @Override
            public void run() {
                future.set(ImmutableMap.copyOf(serviceRegistry));
            }
        });
        return future;
    }

    /**** Implementation ****/
    private Fiber tabletServicesFiber;

    private Fiber serverFiber;
    private final ConfigDirectory configDirectory;

    // The mapping between service name and the instance.
    private final Map<ServiceType, OhmService> serviceRegistry = new HashMap<>();

     private final long nodeId;

    private final Channel<MessageLite> commandChannel = new MemoryChannel<>();

    private PoolFiberFactory fiberPool;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    @Override
    public Channel<MessageLite> getCommandChannel() {
        return commandChannel;
    }

    public RequestChannel<MessageLite, CommandReply> commandRequests = new MemoryRequestChannel<>();
    @Override
    public RequestChannel<MessageLite, CommandReply> getCommandRequests() {
        return commandRequests;
    }

    private final Channel<ServiceStateChange> serviceRegisteredChannel = new MemoryChannel<>();
    @Override
    public Channel<ServiceStateChange> getServiceRegisteredChannel() {
        return serviceRegisteredChannel;
    }

    @Override
    public ConfigDirectory getConfigDirectory() {
        return configDirectory;
    }

    @Override
    public Channel<ConfigKeyUpdated> getConfigUpdateChannel() {

        // TODO this
        return null;
    }


    @FiberOnly
    private void processCommandMessage(MessageLite msg) throws Exception {
        if (msg instanceof StartService) {
            StartService message = (StartService) msg;
            startService(message.getService(), message.getServicePort(), message.getServiceArgv());
        }
        else if (msg instanceof StopService) {
            StopService message = (StopService)msg;

            stopService(message.getService(), message.getHardStop(), message.getStopReason());
        }
    }

    @FiberOnly
    private void processCommandRequest(Request<MessageLite, CommandReply> request) {
        MessageLite r = request.getRequest();
        try {
            String stdout = "";

            if (r instanceof StartService) {
                StartService message = (StartService)r;
                startService(message.getService(), message.getServicePort(), message.getServiceArgv());

                stdout = String.format("Service %s started", message.getService());
            } else if (r instanceof StopService) {
                StopService message = (StopService)r;

                stopService(message.getService(), message.getHardStop(), message.getStopReason());

                stdout = String.format("Service %s started", message.getService());
            } else {
                CommandReply reply = CommandReply.newBuilder()
                        .setCommandSuccess(false)
                        .setCommandStderr(String.format("Unknown message type: %s", r.getClass()))
                        .build();
                request.reply(reply);
                return;
            }

            CommandReply reply = CommandReply.newBuilder()
                    .setCommandSuccess(true)
                    .setCommandStdout(stdout)
                    .build();
            request.reply(reply);

        } catch (Exception e) {
            CommandReply reply = CommandReply.newBuilder()
                    .setCommandSuccess(false)
                    .setCommandStderr(e.toString())
                    .build();
            request.reply(reply);
        }
    }

    private class ServiceListenerPublisher implements Listener {
        private final OhmService service;

        private ServiceListenerPublisher(OhmService service) {
            this.service = service;
        }

        @Override
        public void starting() {
            LOG.debug("Starting service {}", service);
            publishEvent(State.STARTING);
        }

        @Override
        public void running() {
            LOG.debug("Running service {}", service);
            publishEvent(State.RUNNING);
        }

        @Override
        public void stopping(State from) {
            LOG.debug("Stopping service {}", service);
            publishEvent(State.STOPPING);
        }

        @Override
        public void terminated(State from) {
            // TODO move this into a subscriber of ourselves?
            LOG.debug("Terminated service {}", service);
            serviceRegistry.remove(service.getServiceType());
            publishEvent(State.TERMINATED);
        }

        @Override
        public void failed(State from, Throwable failure) {
            LOG.debug("Failed service {}", service);
            publishEvent(State.FAILED);
        }

        private void publishEvent(State state) {
            ServiceStateChange p = new ServiceStateChange(service, state);
            getServiceRegisteredChannel().publish(p);
        }

    }

    @FiberOnly
    private boolean startService(final ServiceType serviceType, final int servicePort, String serviceArgv) throws Exception {
        if (serviceRegistry.containsKey(serviceType)) {
            // already running, dont start twice?
            LOG.warn("Service {} already running", serviceType);
            throw new Exception("Cant start running service: " + serviceType);
        }

        switch (serviceType) {
            case Discovery: {
                Map<ServiceType, Integer> l = new HashMap<>();
                for (ServiceType name : serviceRegistry.keySet()) {
                    l.put(name, 1);
                }

                OhmService service = new BeaconService(this.nodeId, servicePort, fiberPool.create(), workerGroup, l, this);
                service.addListener(new ServiceListenerPublisher(service), serverFiber);

                service.start();
                serviceRegistry.put(serviceType, service);
                break;
            }
            case Replication: {
                OhmService service = new ReplicatorService(fiberPool, bossGroup, workerGroup, servicePort, this);
                service.addListener(new ServiceListenerPublisher(service), serverFiber);

                service.start();
                serviceRegistry.put(serviceType, service);
                break;
            }
            default:
                throw new Exception("No such service as " + serviceType);
        }

        return true;
    }

    @FiberOnly
    private void stopService(ServiceType serviceType, boolean hardStop, String stopReason) {
        Service theService = serviceRegistry.get(serviceType);
        if (theService == null) {
            LOG.debug("Cant stop service {}, not in registry", serviceType);
            return ;
        }

        theService.stop();
    }

    @Override
    protected void doStart() {
      Configuration conf = HBaseConfiguration.create();
      Path path;
      path = Paths.get(getRandomPath());
      RegistryFile registryFile;
      try {
        registryFile = new RegistryFile(path);
        moveAwayOldLogs(path.toString());

        if (existingRegister(registryFile)) {
          recoverOhmServer(conf, path, registryFile);
        } else {
          bootStrapRegions(conf, path, registryFile);
        }
      } catch (Exception e) {
        notifyFailed(e);
      }


      try {
          // TODO we probably shouldnt run services threads as daemons, instead prefer to do orderly shutdown.
        tabletServicesFiber = new ThreadFiber(new RunnableExecutorImpl(), "Tablet-Services", true);
        tabletServicesFiber.execute(new RegionServer(8080));

        serverFiber = new ThreadFiber(new RunnableExecutorImpl(), "OhmDb-Server", false);
            fiberPool = new PoolFiberFactory(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            commandChannel.subscribe(serverFiber, new Callback<MessageLite>() {
                @Override
                public void onMessage(MessageLite message) {
                    try {
                        processCommandMessage(message);
                    } catch (Exception e) {
                        LOG.warn("exception during message processing", e);
                    }
                }
            });

            commandRequests.subscribe(serverFiber, new Callback<Request<MessageLite, CommandReply>>() {
                @Override
                public void onMessage(Request<MessageLite, CommandReply> request) {
                    processCommandRequest(request);
                }
            });

            serverFiber.start();
            tabletServicesFiber.start();

        notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }


    @Override
    protected void doStop() {

        // stop service set.

        // write any last minute persistent data to disk (is there any?)

        serverFiber.dispose();

        notifyStopped();
    }

}
