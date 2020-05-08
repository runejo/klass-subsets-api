/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bo.ssb.archetypes;

import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.spi.CDI;
import javax.json.JsonObject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.helidon.microprofile.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

class MainTest {
    private static Server server;

    @BeforeAll
    public static void startTheServer() throws Exception {
        server = Main.startServer();
    }


    @Test
    void testGetAllSubsets() {

        Client client = ClientBuilder.newClient();

        JsonObject jsonObject = client
                .target(getConnectionString("/subsets"))
                .request()
                .get(JsonObject.class);
        Assertions.assertEquals("", jsonObject.getString("subsets"),
                "get all subsets");
    }

    @Test
    void testGetSubset(){
        Client client = ClientBuilder.newClient();
        JsonObject jsonObject = client
                .target(getConnectionString("/subsets/1"))
                .request()
                .get(JsonObject.class);
        Assertions.assertEquals("{id: 1}", jsonObject.toString(),
                "get subset 1");
    }

    @Test
    void testPutSubset(){
        //TODO: Never run this in prod. That might change data on lds?

        String random = Float.toString(new Random().nextFloat());
        String subset1 = "{id: 1, test:"+random+"}";

        Client client = ClientBuilder.newClient();
        Response r = client
                .target(getConnectionString("/subsets/1"))
                .request()
                .put(Entity.entity(subset1, MediaType.APPLICATION_JSON));
        Assertions.assertEquals(204, r.getStatus(), "PUT status code");

        JsonObject jsonObject = client
                .target(getConnectionString("/subsets/1"))
                .request()
                .get(JsonObject.class);
        Assertions.assertEquals(subset1, jsonObject.toString(),
                "update subset value");
    }

    @Test
    void testMetrics(){
        Client client = ClientBuilder.newClient();
        Response r = client
                .target(getConnectionString("/metrics"))
                .request()
                .get();
        Assertions.assertEquals(200, r.getStatus(), "GET metrics status code");
    }

    @Test
    void testHealth(){
        Client client = ClientBuilder.newClient();
        Response r = client
                .target(getConnectionString("/health"))
                .request()
                .get();
        Assertions.assertEquals(200, r.getStatus(), "GET health status code");
    }

    @AfterAll
    static void destroyClass() {
        CDI<Object> current = CDI.current();
        ((SeContainer) current).close();
    }

    private String getConnectionString(String path) {
        return "http://localhost:" + server.port() + path;
    }
}
