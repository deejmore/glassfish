/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007-20120-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.services.impl;

import com.sun.enterprise.v3.services.impl.monitor.GrizzlyMonitoring;
import com.sun.enterprise.config.serverbeans.VirtualServer;
import org.jvnet.hk2.config.types.Property;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.grizzly.config.dom.Protocol;
import com.sun.hk2.component.ExistingSingletonInhabitant;
import org.glassfish.api.container.EndpointRegistrationException;
import org.glassfish.api.deployment.ApplicationContainer;
import org.glassfish.internal.grizzly.V3Mapper;
import org.jvnet.hk2.component.Inhabitant;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.sun.enterprise.util.Result;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.config.GrizzlyListener;
import org.glassfish.grizzly.http.server.HttpRequestProcessor;
import org.glassfish.grizzly.http.server.StaticResourcesService;
import org.glassfish.grizzly.http.server.util.Mapper;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.UnsafeFutureImpl;

/**
 * This class is responsible for configuring Grizzly.
 *
 * @author Jerome Dochez
 * @author Jeanfrancois Arcand
 */
public class GrizzlyProxy implements NetworkProxy {
    final Logger logger;
    final NetworkListener networkListener;

    protected GrizzlyListener grizzlyListener;
    private int portNumber;

    public final static String LEADER_FOLLOWER
            = "com.sun.grizzly.useLeaderFollower";

    public final static String AUTO_CONFIGURE
            = "com.sun.grizzly.autoConfigure";

    // <http-listener> 'address' attribute
    private InetAddress address;

    private GrizzlyService grizzlyService;

    private VirtualServer vs;


    public GrizzlyProxy(GrizzlyService service, NetworkListener listener) {
        grizzlyService = service;       
        logger = service.getLogger();
        networkListener = listener;
    }

    /**
     * Create a <code>GrizzlyServiceListener</code> based on a NetworkListener
     * configuration object.
     */
    public void initialize() throws IOException {
        String port = networkListener.getPort();
        portNumber = 8080;
        if (port == null) {
            logger.severe("Cannot find port information from domain.xml");
            throw new RuntimeException("Cannot find port information from domain configuration");
        }
        try {
            portNumber = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            logger.log(Level.SEVERE, "Cannot parse port value : {0}, using port 8080", port);
        }
        try {
            address = InetAddress.getByName(networkListener.getAddress());
        } catch (UnknownHostException ex) {
            logger.log(Level.SEVERE, "Unknown address " + address, ex);
        }

        createGrizzlyListener(networkListener);

        grizzlyListener.configure(networkListener);
    }

    protected void createGrizzlyListener(final NetworkListener networkListener) {
        if("light-weight-listener".equals(networkListener.getProtocol())) {
            createServiceInitializerListener(networkListener);
        } else {
            createGlassfishListener(networkListener);
        }
    }

    protected void createGlassfishListener(final NetworkListener networkListener) {
        grizzlyListener = new GlassfishNetworkListener();

        registerMonitoringStatsProviders();

        final Protocol httpProtocol = networkListener.findHttpProtocol();

        if (httpProtocol != null) {
            final V3Mapper mapper = new V3Mapper(logger);
            mapper.setPort(portNumber);
            mapper.setId(networkListener.getName());

            final ContainerMapper containerMapper = new ContainerMapper(
                    grizzlyService, grizzlyListener);
            containerMapper.setMapper(mapper);
            containerMapper.setDefaultHost(grizzlyListener.getDefaultVirtualServer());
            containerMapper.configureMapper();
            embeddedHttp.setAdapter(containerMapper);

//                String ct = httpProtocol.getHttp().getDefaultResponseType();
//                containerMapper.setDefaultContentType(ct);
            final Collection<VirtualServer> list = grizzlyService.getHabitat().getAllByContract(VirtualServer.class);
            final String vsName = httpProtocol.getHttp().getDefaultVirtualServer();
            for (VirtualServer virtualServer : list) {
                if (virtualServer.getId().equals(vsName)) {
                    vs = virtualServer;
                    embeddedHttp.setWebAppRootPath(vs.getDocroot());

                    if (!grizzlyService.hasMapperUpdateListener()
                            && vs.getProperty() != null && !vs.getProperty().isEmpty()) {
                        for (Property p : vs.getProperty()) {
                            String name = p.getName();
                            if (name.startsWith("alternatedocroot")) {
                                String value = p.getValue();
                                String[] mapping = value.split(" ");

                                if (mapping.length != 2) {
                                    logger.log(Level.WARNING, "Invalid alternate_docroot {0}", value);
                                    continue;
                                }

                                String docBase = mapping[1].substring("dir=".length());
                                String urlPattern = mapping[0].substring("from=".length());
                                try {
                                    final StaticResourcesService staticResourceService =
                                            new StaticResourcesService(docBase);
                                    ArrayList<String> al = toArray(vs.getHosts(), ";");
                                    al.add(grizzlyListener.getDefaultVirtualServer());
                                    registerEndpoint(urlPattern, al, staticResourceService, null);
                                } catch (EndpointRegistrationException ex) {
                                    logger.log(Level.SEVERE, "Unable to set alternate_docroot", ex);
                                }

                            }
                        }
                    }
                    break;
                }
            }

            containerMapper.addRootFolder(embeddedHttp.getWebAppRootPath());

            Inhabitant<Mapper> onePortMapper = new ExistingSingletonInhabitant<Mapper>(mapper);
            grizzlyService.getHabitat().addIndex(onePortMapper,
                    Mapper.class.getName(), (networkListener.getAddress() + networkListener.getPort()));
            grizzlyService.notifyMapperUpdateListeners(networkListener, mapper);
        }

        boolean autoConfigure = false;
        // Avoid overriding the default with false
        if (System.getProperty(AUTO_CONFIGURE) != null) {
            autoConfigure = true;
        }
        embeddedHttp.getController().setAutoConfigure(autoConfigure);

        boolean leaderFollower = false;
        // Avoid overriding the default with false
        if (System.getProperty(LEADER_FOLLOWER) != null) {
            leaderFollower = true;
        }
        embeddedHttp.getController().useLeaderFollowerStrategy(leaderFollower);
        
    }

    protected void createServiceInitializerListener(final NetworkListener networkListener) {
        grizzlyListener = new ServiceInitializerListener(grizzlyService.getHabitat(), logger);
    }

    static ArrayList<String> toArray(String list, String token){
        return new ArrayList<String>(Arrays.asList(list.split(token)));
    }

    /**
     * Stops the Grizzly service.
     */
    @Override
    public void stop() throws IOException {
        grizzlyListener.stop();
    }

    @Override
    public void destroy() {
        if(!grizzlyListener.isGenericListener()) {
            grizzlyService.getHabitat().removeIndex(Mapper.class.getName(),
                        (networkListener.getAddress() + networkListener.getPort()));
            unregisterMonitoringStatsProviders();
        }
    }

    @Override
    public String toString() {
        return "GrizzlyProxy{" +
                "virtual server=" + vs +
                ", address=" + address +
                ", portNumber=" + portNumber +
                '}';
    }


    /*
    * Registers a new endpoint (adapter implementation) for a particular
    * context-root. All request coming with the context root will be dispatched
    * to the adapter instance passed in.
    * @param contextRoot for the adapter
    * @param endpointAdapter servicing requests.
    */
    @Override
    public void registerEndpoint(String contextRoot, Collection<String> vsServers,
            HttpRequestProcessor endpointService,
            ApplicationContainer container) throws EndpointRegistrationException {
        
        if(grizzlyListener.isGenericListener()) {
            return;
        }

        // e.g., there is no admin service in an instance
        if (contextRoot == null) {
            return;
        }

        if (endpointService == null) {
            throw new EndpointRegistrationException(
                "The endpoint adapter is null");
        }
        ((ContainerMapper) grizzlyListener.getEmbeddedHttp().getAdapter())
            .register(contextRoot, vsServers, endpointService, container);
    }

    /**
     * Removes the context-root from our list of endpoints.
     */
    @Override
    public void unregisterEndpoint(String contextRoot, ApplicationContainer app) throws EndpointRegistrationException {
        if(grizzlyListener.isGenericListener()) {
            return;
        }
        ((ContainerMapper) grizzlyListener.getEmbeddedHttp().getAdapter())
            .unregister(contextRoot);
    }

    @Override
    public Future<Result<Thread>> start() throws IOException {
        final FutureImpl<Result<Thread>> future = UnsafeFutureImpl.<Result<Thread>>create();
        final long t1 = System.currentTimeMillis();

        grizzlyListener.start();

        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Grizzly Framework {0} started in: {1}ms - bound to [{2}{3}{4}{5}",
                    new Object[]{Grizzly.getDotedVersion(),
                    System.currentTimeMillis() - t1,
                    grizzlyListener.getAddress(), ':', grizzlyListener.getPort(), ']'});
        }

        future.result(new Result<Thread>(Thread.currentThread()));
        return future;
    }

    @Override
    public int getPort() {
        return portNumber;
    }

    @Override
    public InetAddress getAddress() {
        return address;
    }

    public GrizzlyListener getUnderlyingListener() {
        return grizzlyListener;
    }

    protected void registerMonitoringStatsProviders() {
        final String name = networkListener.getName();
        final GrizzlyMonitoring monitoring = grizzlyService.getMonitoring();

        monitoring.registerThreadPoolStatsProvider(name);
        monitoring.registerKeepAliveStatsProvider(name);
        monitoring.registerFileCacheStatsProvider(name);
        monitoring.registerConnectionQueueStatsProvider(name);
    }

    protected void unregisterMonitoringStatsProviders() {
        final String name = networkListener.getName();
        final GrizzlyMonitoring monitoring = grizzlyService.getMonitoring();

        monitoring.unregisterThreadPoolStatsProvider(name);
        monitoring.unregisterKeepAliveStatsProvider(name);
        monitoring.unregisterFileCacheStatsProvider(name);
        monitoring.unregisterConnectionQueueStatsProvider(name);
    }
}
