package com.solace.spring.cloud.stream.binder.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonPersistedSubscriptionLoadBalancerTest {

    @Test
    void isMatchingTopicFilter_matchingTopicsA() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "vehicle/car/fiat",
                "animals/domestic/*",
                "animals/wild/wulf/wearwulf"
        ));

        assertTrue(lb.isMatchingTopicFilter("vehicle/car/fiat"));
        assertTrue(lb.isMatchingTopicFilter("animals/domestic/cat"));
        assertTrue(lb.isMatchingTopicFilter("animals/wild/wulf/wearwulf"));
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsB() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/*/cat"
        ));

        assertTrue(lb.isMatchingTopicFilter("animals/domestic/cat"));
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsC() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic/dog*"
        ));

        assertTrue(lb.isMatchingTopicFilter("animals/domestic/dog"));
        assertTrue(lb.isMatchingTopicFilter("animals/domestic/doggy"));
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsD() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/>",
                "vehicle/*/fiat/>"
        ));

        assertTrue(lb.isMatchingTopicFilter("animals/domestic/cat"));
        assertTrue(lb.isMatchingTopicFilter("vehicle/car/fiat/500"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsA() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/*"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic/cat"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsB() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic/cat"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsC() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic/cat"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsD() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic/*"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic"));
    }
}
