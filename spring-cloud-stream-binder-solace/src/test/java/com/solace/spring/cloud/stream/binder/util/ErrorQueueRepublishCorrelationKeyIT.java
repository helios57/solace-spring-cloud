package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.config.model.ConfigMsgVpnQueue;
import com.solacesystems.jcsmp.*;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class ErrorQueueRepublishCorrelationKeyIT {
    private Queue errorQueue;
    private XMLMessageProducer producer;
    private final AtomicReference<FlowReceiverContainer> flowReceiverContainerReference = new AtomicReference<>();
    private ErrorQueueInfrastructure errorQueueInfrastructure;
    private RetryableTaskService retryableTaskService;

    private static final Log logger = LogFactory.getLog(ErrorQueueRepublishCorrelationKeyIT.class);

    @BeforeEach
    public void setUp(JCSMPSession jcsmpSession) throws Exception {
        retryableTaskService = Mockito.spy(new RetryableTaskService());

        String producerManagerKey = UUID.randomUUID().toString();
        JCSMPSessionProducerManager producerManager = new JCSMPSessionProducerManager(jcsmpSession);
        producer = producerManager.get(producerManagerKey);

        errorQueueInfrastructure = Mockito.spy(new ErrorQueueInfrastructure(producerManager, producerManagerKey,
                RandomStringUtils.randomAlphanumeric(20), new SolaceConsumerProperties(),
                retryableTaskService));
        errorQueue = JCSMPFactory.onlyInstance().createQueue(errorQueueInfrastructure.getErrorQueueName());
        jcsmpSession.provision(errorQueue, new EndpointProperties(), JCSMPSession.WAIT_FOR_CONFIRM);
    }

    @AfterEach
    public void tearDown(JCSMPSession jcsmpSession) throws Exception {
        if (producer != null) {
            producer.close();
        }

        Optional.ofNullable(flowReceiverContainerReference.getAndSet(null))
                .map(FlowReceiverContainer::getFlowReceiverReference)
                .map(FlowReceiverContainer.FlowReceiverReference::get)
                .ifPresent(Consumer::close);

        if (jcsmpSession != null && !jcsmpSession.isClosed()) {
            jcsmpSession.deprovision(errorQueue, JCSMPSession.WAIT_FOR_CONFIRM);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleSuccess(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        key.handleSuccess();
        assertTrue(messageContainer.isAcknowledged());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleSuccessStale(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue)
            throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = Mockito.spy(flowReceiverContainer.receive(5000));
        Mockito.when(messageContainer.isStale()).thenReturn(true);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        assertThrows(SolaceStaleMessageException.class, key::handleSuccess);
        assertEquals(0, key.getErrorQueueDeliveryAttempt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleError(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue, SempV2Api sempV2Api)
            throws Exception {
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        key.handleError(false);

        Mockito.verify(errorQueueInfrastructure, Mockito.times(1)).send(messageContainer, key);
        assertEquals(1, key.getErrorQueueDeliveryAttempt());
        validateNumEnqueuedMessages(vpnName, queue, 0, sempV2Api);
        validateNumEnqueuedMessages(vpnName, errorQueue, 1, sempV2Api);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleErrorRetry(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue,
                                     SempV2Api sempV2Api) throws Exception {
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        Mockito.doAnswer(invocation -> {
            if (key.getErrorQueueDeliveryAttempt() < errorQueueInfrastructure.getMaxDeliveryAttempts()) {
                throw new Exception("Test");
            } else {
                return invocation.callRealMethod();
            }
        }).when(errorQueueInfrastructure).send(messageContainer, key);

        key.handleError(false);

        int maxDeliveryAttempts = Math.toIntExact(errorQueueInfrastructure.getMaxDeliveryAttempts());
        Mockito.verify(errorQueueInfrastructure, Mockito.times(maxDeliveryAttempts)).send(messageContainer, key);
        assertEquals(maxDeliveryAttempts, key.getErrorQueueDeliveryAttempt());
        validateNumEnqueuedMessages(vpnName, queue, 0, sempV2Api);
        validateNumEnqueuedMessages(vpnName, errorQueue, 1, sempV2Api);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleErrorAsyncRetry(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue,
                                          SempV2Api sempV2Api) throws Exception {
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        logger.info(String.format("Shutting down ingress for queue %s", errorQueue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().ingressEnabled(false),
                vpnName, errorQueue.getName(),
                null, null);
        retryAssert(() -> assertFalse(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, errorQueue.getName(), null)
                .getData()
                .isIngressEnabled()));

        CountDownLatch latch = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            if (key.getErrorQueueDeliveryAttempt() == errorQueueInfrastructure.getMaxDeliveryAttempts()) {
                logger.info(String.format("Starting ingress for queue %s", errorQueue.getName()));
                sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().ingressEnabled(true),
                        vpnName, errorQueue.getName(),
                        null, null);
                retryAssert(() -> assertTrue(sempV2Api.monitor()
                        .getMsgVpnQueue(vpnName, errorQueue.getName(), null)
                        .getData()
                        .isIngressEnabled()));
                latch.countDown();
            }

            return invocation.callRealMethod();
        }).when(errorQueueInfrastructure).send(messageContainer, key);

        key.handleError(false);
        assertTrue(latch.await(1, TimeUnit.MINUTES));

        int maxDeliveryAttempts = Math.toIntExact(errorQueueInfrastructure.getMaxDeliveryAttempts());
        Mockito.verify(errorQueueInfrastructure, Mockito.times(maxDeliveryAttempts)).send(messageContainer, key);
        assertEquals(maxDeliveryAttempts, key.getErrorQueueDeliveryAttempt());
        validateNumEnqueuedMessages(vpnName, queue, 0, sempV2Api);
        validateNumEnqueuedMessages(vpnName, errorQueue, 1, sempV2Api);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleErrorRequeueFallback(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue,
                                               SempV2Api sempV2Api) throws Exception {
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        Mockito.doThrow(new JCSMPException("test")).when(errorQueueInfrastructure).send(messageContainer, key);

        key.handleError(false);

        int maxDeliveryAttempts = Math.toIntExact(errorQueueInfrastructure.getMaxDeliveryAttempts());
        Mockito.verify(errorQueueInfrastructure, Mockito.times(maxDeliveryAttempts)).send(messageContainer, key);
        assertEquals(maxDeliveryAttempts, key.getErrorQueueDeliveryAttempt());
        validateNumEnqueuedMessages(vpnName, errorQueue, 0, sempV2Api);

        if (isDurable) {
            validateNumEnqueuedMessages(vpnName, queue, 1, sempV2Api);
            Mockito.verify(flowReceiverContainer).acknowledgeRebind(messageContainer, true);
            assertEquals((Long) 2L, sempV2Api.monitor()
                    .getMsgVpnQueue(vpnName, queue.getName(), null)
                    .getData()
                    .getBindSuccessCount());
        } else {
            validateNumEnqueuedMessages(vpnName, queue, 0, sempV2Api);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleErrorRequeueFallbackSkipSync(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue,
                                                       SempV2Api sempV2Api) throws Exception {
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        Mockito.doThrow(new JCSMPException("test")).when(errorQueueInfrastructure).send(messageContainer, key);

        key.handleError(true);

        int maxDeliveryAttempts = Math.toIntExact(errorQueueInfrastructure.getMaxDeliveryAttempts());
        Mockito.verify(errorQueueInfrastructure, Mockito.times(maxDeliveryAttempts)).send(messageContainer, key);
        assertEquals(maxDeliveryAttempts, key.getErrorQueueDeliveryAttempt());
        validateNumEnqueuedMessages(vpnName, errorQueue, 0, sempV2Api);

        if (isDurable) {
            validateNumEnqueuedMessages(vpnName, queue, 1, sempV2Api);
            Mockito.verify(retryableTaskService)
                    .submit(new RetryableAckRebindTask(flowReceiverContainer, messageContainer, retryableTaskService));
            Mockito.verify(flowReceiverContainer).acknowledgeRebind(messageContainer, true);
            retryAssert(() -> assertEquals((Long) 2L, sempV2Api.monitor()
                    .getMsgVpnQueue(vpnName, queue.getName(), null)
                    .getData()
                    .getBindSuccessCount()));
        } else {
            validateNumEnqueuedMessages(vpnName, queue, 0, sempV2Api);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleErrorRequeueFallbackFail(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue,
                                                   SempV2Api sempV2Api) throws Exception {
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = flowReceiverContainer.receive(5000);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        Mockito.doThrow(new JCSMPException("test")).when(errorQueueInfrastructure).send(messageContainer, key);
        Mockito.doThrow(new JCSMPException("test")).doCallRealMethod().when(flowReceiverContainer)
                .acknowledgeRebind(messageContainer, true);

        key.handleError(false);

        int maxDeliveryAttempts = Math.toIntExact(errorQueueInfrastructure.getMaxDeliveryAttempts());
        Mockito.verify(errorQueueInfrastructure, Mockito.times(maxDeliveryAttempts)).send(messageContainer, key);
        assertEquals(maxDeliveryAttempts, key.getErrorQueueDeliveryAttempt());
        validateNumEnqueuedMessages(vpnName, errorQueue, 0, sempV2Api);

        if (isDurable) {
            validateNumEnqueuedMessages(vpnName, queue, 1, sempV2Api);
            Mockito.verify(retryableTaskService)
                    .submit(new RetryableAckRebindTask(flowReceiverContainer, messageContainer, retryableTaskService));
            Mockito.verify(flowReceiverContainer, Mockito.times(2))
                    .acknowledgeRebind(messageContainer, true);
            retryAssert(() -> assertEquals((Long) 2L, sempV2Api.monitor()
                    .getMsgVpnQueue(vpnName, queue.getName(), null)
                    .getData()
                    .getBindSuccessCount()));
        } else {
            validateNumEnqueuedMessages(vpnName, queue, 0, sempV2Api);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testHandleErrorStale(boolean isDurable, JCSMPSession jcsmpSession, Queue durableQueue)
            throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = createFlowReceiverContainer(jcsmpSession, queue);

        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        MessageContainer messageContainer = Mockito.spy(flowReceiverContainer.receive(5000));
        Mockito.when(messageContainer.isStale()).thenReturn(true);
        ErrorQueueRepublishCorrelationKey key = createKey(messageContainer, flowReceiverContainer, isDurable);

        assertThrows(SolaceStaleMessageException.class, () -> key.handleError(false));
        assertEquals(0, key.getErrorQueueDeliveryAttempt());
    }

    private FlowReceiverContainer createFlowReceiverContainer(JCSMPSession jcsmpSession, Queue queue)
            throws JCSMPException {
        if (flowReceiverContainerReference.compareAndSet(null, Mockito.spy(new FlowReceiverContainer(
                jcsmpSession,
                queue.getName(),
                new EndpointProperties(),
                new FixedBackOff(1, Long.MAX_VALUE))))) {
            flowReceiverContainerReference.get().bind();
        }
        return flowReceiverContainerReference.get();
    }

    private ErrorQueueRepublishCorrelationKey createKey(MessageContainer messageContainer,
                                                        FlowReceiverContainer flowReceiverContainer,
                                                        boolean isDurable) {
        return new ErrorQueueRepublishCorrelationKey(errorQueueInfrastructure,
                messageContainer,
                flowReceiverContainer,
                !isDurable,
                retryableTaskService);
    }

    private void validateNumEnqueuedMessages(String vpnName, Queue queue, int expectedCount, SempV2Api sempV2Api)
            throws InterruptedException {
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueueMsgs(vpnName, queue.getName(), null, null, null, null)
                .getData())
                .hasSize(expectedCount));
    }
}
