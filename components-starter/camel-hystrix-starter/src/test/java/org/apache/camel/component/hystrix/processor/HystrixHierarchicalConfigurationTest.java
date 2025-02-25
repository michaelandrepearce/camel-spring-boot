/*
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
package org.apache.camel.component.hystrix.processor;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.impl.engine.DefaultRoute;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.HystrixConfigurationDefinition;
import org.apache.camel.model.Model;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testing the Hystrix configuration hierarchy
 */
@SpringBootApplication
@CamelSpringBootTest
@DirtiesContext
@ContextConfiguration(classes = HystrixHierarchicalConfiguration.class)
@SpringBootTest(properties = {
    "debug=false",
    "camel.hystrix.enabled=true",
    "camel.hystrix.group-key=global-group-key",
    "camel.hystrix.thread-pool-key=global-thread-key",
    "camel.hystrix.core-pool-size=10"
})
public class HystrixHierarchicalConfigurationTest {
    @Autowired
    CamelContext camelContext;

    @Test
    public void testConfiguration() throws Exception {
        RouteDefinition routeDefinition = camelContext.getExtension(Model.class).getRouteDefinition("hystrix-route");
        CircuitBreakerDefinition hystrixDefinition = findCircuitBreaker(routeDefinition);

        assertNotNull(hystrixDefinition);

        Route rc = new DefaultRoute(camelContext, null, null, null, null);
        HystrixReifier reifier = new HystrixReifier(rc, hystrixDefinition);
        HystrixConfigurationDefinition config = reifier.buildHystrixConfiguration();

        assertEquals("local-conf-group-key", config.getGroupKey());
        assertEquals("global-thread-key", config.getThreadPoolKey());
        assertEquals("5", config.getCorePoolSize());
    }

    // **********************************************
    // Helper
    // **********************************************

    private CircuitBreakerDefinition findCircuitBreaker(RouteDefinition routeDefinition) throws Exception {
        return routeDefinition.getOutputs().stream()
            .filter(CircuitBreakerDefinition.class::isInstance)
            .map(CircuitBreakerDefinition.class::cast)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Unable to find a CircuitBreakerDefinition"));
    }
}

