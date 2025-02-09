/*
 * Copyright 2016 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.keycloak.testsuite.admin.event;

import org.hamcrest.MatcherAssert;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.events.EventType;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.testsuite.AssertEvents;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test getting and filtering login-related events.
 *
 * @author Stan Silvert ssilvert@redhat.com (C) 2016 Red Hat Inc.
 */
public class LoginEventsTest extends AbstractEventTest {

    @Before
    public void init() {
        configRep.setEventsEnabled(true);
        saveConfig();
        testRealmResource().clearEvents();
        createAppClientInRealm(testRealmResource().toRepresentation().getRealm());
    }

    private List<EventRepresentation> events() {
        return testRealmResource().getEvents();
    }

    private void badLogin() {
        oauth.openLoginForm();
        loginPage.form().login("bad", "user");
    }

    private void pause(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void eventAttributesTest() {
        badLogin();
        List<EventRepresentation> events = events();
        assertEquals(1, events.size());
        EventRepresentation event = events.get(0);
        MatcherAssert.assertThat(event.getId(), AssertEvents.isUUID());
        assertTrue(event.getTime() > 0);
        assertNotNull(event.getIpAddress());
        assertEquals("LOGIN_ERROR", event.getType());
        assertEquals(realmName(), event.getRealmId());
        assertNull(event.getUserId()); // no user for bad login
        assertNull(event.getSessionId()); // no session for bad login
        assertEquals("user_not_found", event.getError());

        Map<String, String> details = event.getDetails();
        assertEquals("openid-connect", details.get("auth_method"));
        assertEquals("code", details.get("auth_type"));
        assertNotNull(details.get("redirect_uri"));
        assertNotNull(details.get("code_id"));
        assertEquals("bad", details.get("username"));
    }

    @Test
    public void clearEventsTest() {
        assertEquals(0, events().size());
        badLogin();
        badLogin();
        assertEquals(2, events().size());
        testRealmResource().clearEvents();
        assertEquals(0, events().size());
    }

    @Test
    public void loggingOfCertainTypeTest() {
        assertEquals(0, events().size());
        configRep.setEnabledEventTypes(Arrays.asList("REVOKE_GRANT"));
        saveConfig();

        badLogin();
        assertEquals(0, events().size());

        configRep.setEnabledEventTypes(Arrays.asList("LOGIN_ERROR"));
        saveConfig();

        badLogin();
        assertEquals(1, events().size());
    }

    @Test
    public void filterTest() {
        badLogin();
        badLogin();
        assertEquals(2, events().size());

        List<EventRepresentation> filteredEvents = testRealmResource().getEvents(Arrays.asList("REVOKE_GRANT"), null, null, null, null, null, null, null);
        assertEquals(0, filteredEvents.size());

        filteredEvents = testRealmResource().getEvents(Arrays.asList("LOGIN_ERROR"), null, null, null, null, null, null, null);
        assertEquals(2, filteredEvents.size());
    }

    @Test
    public void defaultMaxResults() {
        RealmResource realm = adminClient.realms().realm("test");
        EventRepresentation event = new EventRepresentation();
        event.setRealmId(realm.toRepresentation().getId());
        event.setType(EventType.LOGIN.toString());

        for (int i = 0; i < 110; i++) {
            testingClient.testing("test").onEvent(event);
        }

        assertEquals(100, realm.getEvents(null, null, null, null, null, null, null, null).size());
        assertEquals(105, realm.getEvents(null, null, null, null, null, null, 0, 105).size());
        assertTrue(realm.getEvents(null, null, null, null, null, null, 0, 1000).size() >= 110);
    }

    @Test
    public void orderResultsTest() {
        RealmResource realm = adminClient.realms().realm("test");
        EventRepresentation firstEvent = new EventRepresentation();
        firstEvent.setRealmId(realm.toRepresentation().getId());
        firstEvent.setType(EventType.LOGIN.toString());
        firstEvent.setTime(System.currentTimeMillis() - 1000);

        EventRepresentation secondEvent = new EventRepresentation();
        secondEvent.setRealmId(realm.toRepresentation().getId());
        secondEvent.setType(EventType.LOGOUT.toString());
        secondEvent.setTime(System.currentTimeMillis());

        testingClient.testing("test").onEvent(firstEvent);
        testingClient.testing("test").onEvent(secondEvent);

        List<EventRepresentation> events = realm.getEvents(null, null, null, null, null, null, null, null, "desc");
        assertEquals(2, events.size());
        assertEquals(EventType.LOGOUT.toString(), events.get(0).getType());
        assertEquals(EventType.LOGIN.toString(), events.get(1).getType());

        events = realm.getEvents(null, null, null, null, null, null, null, null, "asc");
        assertEquals(2, events.size());
        assertEquals(EventType.LOGOUT.toString(), events.get(1).getType());
        assertEquals(EventType.LOGIN.toString(), events.get(0).getType());
    }


    @Test
    public void filterByEpochTimeStamp() {
        RealmResource realm = adminClient.realms().realm("test");
        EventRepresentation event = new EventRepresentation();
        event.setType(EventType.LOGIN.toString());
        event.setRealmId(realm.toRepresentation().getId());

        long currentTime = System.currentTimeMillis();

        event.setTime(currentTime - 1000);
        testingClient.testing("test").onEvent(event);
        event.setTime(currentTime);
        testingClient.testing("test").onEvent(event);
        event.setTime(currentTime + 1000);
        testingClient.testing("test").onEvent(event);

        List<EventRepresentation> events = realm.getEvents(null, null, null, currentTime, currentTime, null, null, null, null);
        Assert.assertEquals(1, events.size());
        events = realm.getEvents(null, null, null, currentTime - 1000, currentTime + 1000, null, null, null, null);
        Assert.assertEquals(3, events.size());
    }

    @Test
    public void testErrorEventsAreNotStoredWhenDisabled() {
        configRep.setEventsEnabled(false);
        saveConfig();

        badLogin();
        assertEquals(0, events().size());
    }

    /*
    Removed this test because it takes too long.  The default interval for
    event cleanup is 15 minutes (900 seconds).  I don't have time to figure out
    a way to set the cleanup thread to a lower interval for testing.
    @Test
    public void eventExpirationTest() {
        configRep.setEventsExpiration(1L); //  second
        saveConfig();
        badLogin();
        assertEquals(1, events().size());
        pause(900); // pause 900 seconds
        assertEquals(0, events().size());
    }**/
}
