/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.NoSuchEndpointException;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.management.InstrumentationProcessor;
import org.apache.camel.model.FromDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.processor.Pipeline;
import org.apache.camel.processor.RoutePolicyProcessor;
import org.apache.camel.processor.UnitOfWorkProcessor;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.RouteContext;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.util.ObjectHelper;

/**
 * The context used to activate new routing rules
 *
 * @version $Revision$
 */
public class DefaultRouteContext implements RouteContext {
    private final Map<ProcessorDefinition<?>, AtomicInteger> nodeIndex = new HashMap<ProcessorDefinition<?>, AtomicInteger>();
    private final RouteDefinition route;
    private FromDefinition from;
    private final Collection<Route> routes;
    private Endpoint endpoint;
    private final List<Processor> eventDrivenProcessors = new ArrayList<Processor>();
    private CamelContext camelContext;
    private List<InterceptStrategy> interceptStrategies = new ArrayList<InterceptStrategy>();
    private InterceptStrategy managedInterceptStrategy;
    private boolean routeAdded;
    private Boolean trace;
    private Boolean stramCache;
    private Boolean handleFault;
    private Long delay;
    private Boolean autoStartup = Boolean.TRUE;
    private RoutePolicy routePolicy;

    public DefaultRouteContext(CamelContext camelContext, RouteDefinition route, FromDefinition from, Collection<Route> routes) {
        this.camelContext = camelContext;
        this.route = route;
        this.from = from;
        this.routes = routes;
    }

    /**
     * Only used for lazy construction from inside ExpressionType
     */
    public DefaultRouteContext(CamelContext camelContext) {
        this.camelContext = camelContext;
        this.routes = new ArrayList<Route>();
        this.route = new RouteDefinition("temporary");
    }

    public Endpoint getEndpoint() {
        if (endpoint == null) {
            endpoint = from.resolveEndpoint(this);
        }
        return endpoint;
    }

    public FromDefinition getFrom() {
        return from;
    }

    public RouteDefinition getRoute() {
        return route;
    }

    public CamelContext getCamelContext() {
        if (camelContext == null) {
            camelContext = getRoute().getCamelContext();
        }
        return camelContext;
    }

    public Processor createProcessor(ProcessorDefinition<?> node) throws Exception {
        return node.createOutputsProcessor(this);
    }

    public Endpoint resolveEndpoint(String uri) {
        return route.resolveEndpoint(uri);
    }

    public Endpoint resolveEndpoint(String uri, String ref) {
        Endpoint endpoint = null;
        if (uri != null) {
            endpoint = resolveEndpoint(uri);
            if (endpoint == null) {
                throw new NoSuchEndpointException(uri);
            }
        }
        if (ref != null) {
            endpoint = lookup(ref, Endpoint.class);
            if (endpoint == null) {
                throw new NoSuchEndpointException("ref:" + ref);
            }
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("Either 'uri' or 'ref' must be specified on: " + this);
        } else {
            return endpoint;
        }
    }

    public <T> T lookup(String name, Class<T> type) {
        return getCamelContext().getRegistry().lookup(name, type);
    }

    public <T> Map<String, T> lookupByType(Class<T> type) {
        return getCamelContext().getRegistry().lookupByType(type);
    }
    
    public void commit() {
        // now lets turn all of the event driven consumer processors into a single route
        if (!eventDrivenProcessors.isEmpty()) {
            Processor processor = Pipeline.newInstance(eventDrivenProcessors);

            // and wrap it in a unit of work so the UoW is on the top, so the entire route will be in the same UoW
            Processor unitOfWorkProcessor = new UnitOfWorkProcessor(this, processor);
            Processor target = unitOfWorkProcessor;

            // and then optionally add route policy processor if a custom policy is set
            RoutePolicyProcessor routePolicyProcessor = null;
            RoutePolicy policy = getRoutePolicy();
            if (policy != null) {
                routePolicyProcessor = new RoutePolicyProcessor(unitOfWorkProcessor, policy);
                // add it as service if we have not already done that (eg possible if two routes have the same service)
                if (!camelContext.hasService(policy)) {
                    try {
                        camelContext.addService(policy);
                    } catch (Exception e) {
                        throw ObjectHelper.wrapRuntimeCamelException(e);
                    }
                }
                target = routePolicyProcessor;
            }

            // and wrap it by a instrumentation processor that is to be used for performance stats
            // for this particular route
            InstrumentationProcessor wrapper = new InstrumentationProcessor();
            wrapper.setType("route");
            wrapper.setProcessor(target);

            // and create the route that wraps the UoW
            Route edcr = new EventDrivenConsumerRoute(this, getEndpoint(), wrapper);
            edcr.getProperties().put(Route.ID_PROPERTY, route.idOrCreate(getCamelContext().getNodeIdFactory()));
            edcr.getProperties().put(Route.PARENT_PROPERTY, Integer.toHexString(route.hashCode()));
            if (route.getGroup() != null) {
                edcr.getProperties().put(Route.GROUP_PROPERTY, route.getGroup());
            }

            // after the route is created then set the route on the policy processor so we get hold of it
            if (routePolicyProcessor != null) {
                routePolicyProcessor.setRoute(edcr);
            }

            routes.add(edcr);
        }
    }

    public void addEventDrivenProcessor(Processor processor) {
        eventDrivenProcessors.add(processor);
    }

    public List<InterceptStrategy> getInterceptStrategies() {
        return interceptStrategies;
    }

    public void setInterceptStrategies(List<InterceptStrategy> interceptStrategies) {
        this.interceptStrategies = interceptStrategies;
    }

    public void addInterceptStrategy(InterceptStrategy interceptStrategy) {
        getInterceptStrategies().add(interceptStrategy);
    }

    public void setManagedInterceptStrategy(InterceptStrategy interceptStrategy) {
        this.managedInterceptStrategy = interceptStrategy;
    }

    public InterceptStrategy getManagedInterceptStrategy() {
        return managedInterceptStrategy;
    }

    public boolean isRouteAdded() {
        return routeAdded;
    }

    public void setIsRouteAdded(boolean routeAdded) {
        this.routeAdded = routeAdded;
    }

    public void setTracing(Boolean tracing) {
        this.trace = tracing;
    }

    public boolean isTracing() {
        if (trace != null) {
            return trace;
        } else {
            // fallback to the option from camel context
            return getCamelContext().isTracing();
        }
    }

    public void setStreamCaching(Boolean cache) {
        this.stramCache = cache;
    }

    public boolean isStreamCaching() {
        if (stramCache != null) {
            return stramCache;
        } else {
            // fallback to the option from camel context
            return getCamelContext().isStreamCaching();
        }
    }

    public void setHandleFault(Boolean handleFault) {
        this.handleFault = handleFault;
    }

    public boolean isHandleFault() {
        if (handleFault != null) {
            return handleFault;
        } else {
            // fallback to the option from camel context
            return getCamelContext().isHandleFault();
        }
    }

    public void setDelayer(long delay) {
        this.delay = delay;
    }

    public Long getDelayer() {
        if (delay != null) {
            return delay;
        } else {
            // fallback to the option from camel context
            return getCamelContext().getDelayer();
        }
    }

    public void setAutoStartup(Boolean autoStartup) {
        this.autoStartup = autoStartup;
    }

    public boolean isAutoStartup() {
        if (autoStartup != null) {
            return autoStartup;
        }
        // default to true
        return true;
    }

    public RoutePolicy getRoutePolicy() {
        return routePolicy;
    }

    public void setRoutePolicy(RoutePolicy routePolicy) {
        this.routePolicy = routePolicy;
    }

    public int getAndIncrement(ProcessorDefinition<?> node) {
        AtomicInteger count = nodeIndex.get(node);
        if (count == null) {
            count = new AtomicInteger();
            nodeIndex.put(node, count);
        }
        return count.getAndIncrement();
    }
}
