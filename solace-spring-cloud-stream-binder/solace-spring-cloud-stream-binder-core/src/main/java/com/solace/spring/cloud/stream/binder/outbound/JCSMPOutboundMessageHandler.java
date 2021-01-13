package com.solace.spring.cloud.stream.binder.outbound;

import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.util.ClosedChannelBindingException;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionProducerManager;
import com.solace.spring.cloud.stream.binder.util.SolaceMessageConversionException;
import com.solace.spring.cloud.stream.binder.util.XMLMessageMapper;
import com.solacesystems.jcsmp.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.context.Lifecycle;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.messaging.*;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class JCSMPOutboundMessageHandler implements MessageHandler, Lifecycle {
	private final String id = UUID.randomUUID().toString();
	private final Topic topic;
	private final JCSMPSession jcsmpSession;
	private final SolaceProducerProperties properties;
	private MessageChannel errorChannel;
	private JCSMPSessionProducerManager producerManager;
	private XMLMessageProducer producer;
	private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();
	private boolean isRunning = false;
	private ErrorMessageStrategy errorMessageStrategy;

	private static final Log logger = LogFactory.getLog(JCSMPOutboundMessageHandler.class);

	public JCSMPOutboundMessageHandler(ProducerDestination destination,
									   JCSMPSession jcsmpSession,
									   MessageChannel errorChannel,
									   JCSMPSessionProducerManager producerManager,
									   SolaceProducerProperties properties) {
		this.topic = JCSMPFactory.onlyInstance().createTopic(destination.getName());
		this.jcsmpSession = jcsmpSession;
		this.errorChannel = errorChannel;
		this.producerManager = producerManager;
		this.properties = properties;
	}

	@Override
	public void handleMessage(Message<?> message) throws MessagingException {
		if (! isRunning()) {
			String msg0 = String.format("Cannot send message using handler %s", id);
			String msg1 = String.format("Message handler %s is not running", id);
			throw handleMessagingException(msg0, message, new ClosedChannelBindingException(msg1));
		}

		Destination targetTopic = topic;

		try {
			Object targetDestinationHeader = message.getHeaders().get(BinderHeaders.TARGET_DESTINATION);
			if (targetDestinationHeader instanceof String && StringUtils.hasText((String)targetDestinationHeader)) {
				targetTopic = JCSMPFactory.onlyInstance().createTopic((String)targetDestinationHeader);
			} else if (targetDestinationHeader instanceof Destination) {
				targetTopic = (Destination) targetDestinationHeader;
			} else if (targetDestinationHeader != null) {
				throw new IllegalArgumentException("Incorrect type specified for header '" + BinderHeaders.TARGET_DESTINATION + "'. Expected [String or Destination] but actual type is [" + targetDestinationHeader.getClass() + "]");
			}
		} catch (IllegalArgumentException e) {
			throw handleMessagingException(
					String.format("Unable to parse header %s", BinderHeaders.TARGET_DESTINATION), message, e);
		}

		Message messageExcludedHeaders = MessageBuilder
				.withPayload(message.getPayload())
				.copyHeaders(excludeHeaders(message.getHeaders()))
				.build();

		XMLMessage xmlMessage = xmlMessageMapper.map(messageExcludedHeaders);

		try {
			producer.send(xmlMessage, targetTopic);
		} catch (JCSMPException e) {
			throw handleMessagingException(
					String.format("Unable to send message to topic %s", targetTopic.getName()), message, e);
		}
	}

	private MessageHeaders excludeHeaders(MessageHeaders headers) {
		List<Map.Entry<String, Object>> entries = headers.entrySet().stream()
				.filter(entry -> !this.properties.getHeaderExclusions().contains(entry.getKey()))
				.collect(Collectors.toList());
		Map<String, Object> nextHeaders = new HashMap<String, Object>();
		nextHeaders.forEach((headerName, header) -> nextHeaders.put(headerName, header));
		return new MessageHeaders(nextHeaders);
	}

	@Override
	public void start() {
		logger.info(String.format("Creating producer to topic %s <message handler ID: %s>", topic.getName(), id));
		if (isRunning()) {
			logger.warn(String.format("Nothing to do, message handler %s is already running", id));
			return;
		}

		try {
			producer = producerManager.get(id);
		} catch (Exception e) {
			String msg = String.format("Unable to get a message producer for session %s", jcsmpSession.getSessionName());
			logger.warn(msg, e);
			throw new RuntimeException(msg, e);
		}

		isRunning = true;
	}

	@Override
	public void stop() {
		if (!isRunning()) return;
		logger.info(String.format("Stopping producer to topic %s <message handler ID: %s>", topic.getName(), id));
		producerManager.release(id);
		isRunning = false;
	}

	@Override
	public boolean isRunning() {
		return isRunning;
	}

	public void setErrorMessageStrategy(ErrorMessageStrategy errorMessageStrategy) {
		this.errorMessageStrategy = errorMessageStrategy;
	}

	private MessagingException handleMessagingException(String msg, Message<?> message, Exception e)
			throws MessagingException {
		logger.warn(msg, e);
		MessagingException exception = new MessagingException(message, msg, e);
		if (errorChannel != null) errorChannel.send(errorMessageStrategy.buildErrorMessage(exception, null));
		return exception;
	}
}
