package org.eso.ias.kafkautils.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eso.ias.kafkautils.SimpleStringConsumer;
import org.eso.ias.kafkautils.SimpleStringConsumer.KafkaConsumerListener;
import org.eso.ias.kafkautils.SimpleStringConsumer.StartPosition;
import org.eso.ias.kafkautils.SimpleStringProducer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test the consumer and producer by submitting strings
 * and checking their reception.
 * <P>
 * Depending on the setting in the broker 
 * (especially regarding the log and offset retention times)
 * this test could receive also messages present in the log 
 * before it connects.
 * <BR>Therefore this tests discards all the messages returned
 * by the poll but not produced by the test itself: it cares
 * to receive all the strings it published.
 * 
 * @author acaproni
 *
 */
public class ConsumerProducerTest implements KafkaConsumerListener {
	
	
	/**
	 * The number of events to wait for
	 */
	private CountDownLatch numOfEventsToReceive;
	
	/**
	 * The logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(ConsumerProducerTest.class);
	
	/**
	 * The consumer to get events
	 */
	private SimpleStringConsumer consumer;
	
	/**
	 * The producer to publish events
	 */
	private SimpleStringProducer producer;
	
	/**
	 * The topic to send to and receive strings from
	 */
	private final String topicName = "ConsumerProducerTest-topic";
	
	/**
	 * The number of processed messages including the
	 * discarded ones
	 */
	private final AtomicInteger processedMessages = new AtomicInteger(0);
	
	/**
	 * The strings effectively published
	 */
	private final List<String> sentStrings = new ArrayList<>();
	
	
	/**
	 * The prefix of each string generated by {@link #buildStringsToSend(int)}
	 */
	private static final String GENERATED_STRINGS_PREFIX="Generated-string-";
	
	/**
	 * The suffix of each string generated by {@link #buildStringsToSend(int)}
	 */
	private static final String GENERATED_STRINGS_SUFFIX="-at-"+(new Date(System.currentTimeMillis()).toString());
	
	/**
	 * Build and returns an array of strings to send
	 * <P>
	 * Each string has the format prefix[NUM]suffix.
	 * 
	 * @param n The number of strings to generate
	 * @return The generated strings
	 */
	private List<String> buildStringsToSend(int n) {
		if (n<=0) {
			throw new IllegalArgumentException("n must be greater than 0");
		}
		List<String> ret = new ArrayList<>(n);
		for (int t=0; t<n; t++) {
			ret.add(GENERATED_STRINGS_PREFIX+t+GENERATED_STRINGS_SUFFIX);
		}
		return ret;
	}
	
	/**
	 * The list of strings red from the topic.
	 * <P>
	 * It contains only the strings that this test produced
	 */
	private final List<String> receivedStrings = Collections.synchronizedList(new ArrayList<>());
	
	/**
	 * The list of strings red from the topic but discarded.
	 * <P>
	 * It contains only the strings that this test did not produce
	 */
	private final List<String> discardedStrings = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Initialize
	 */
	@Before
	public void setUp() throws Exception {
		logger.info("Initializing...");
		consumer = new SimpleStringConsumer(SimpleStringProducer.DEFAULT_BOOTSTRAP_SERVERS, topicName, this);
		consumer.setUp();
		producer = new SimpleStringProducer(SimpleStringProducer.DEFAULT_BOOTSTRAP_SERVERS, topicName, "Consumer-ID");
		producer.setUp();
		
		consumer.startGettingEvents(StartPosition.END);
		logger.info("Initialized.");
	}
	
	/**
	 * Clean up
	 */
	@After
	public void tearDown() {
		logger.info("Closing...");
		receivedStrings.clear();
		consumer.tearDown();
		producer.tearDown();
		logger.info("Closed after processing {} messages",processedMessages.get());
	}
	
	/**
	 * Publish the passed strings in the topic and wait for their 
	 * reception.
	 */
	@Test
	public void testSendReceive() throws Exception{
		int nrOfStrings = 1500;
		numOfEventsToReceive = new CountDownLatch(nrOfStrings);
		List<String> strsToSend = buildStringsToSend(nrOfStrings);
		for (String str: strsToSend) {
			// Sends the string synchronously to get
			// an exception in case of error
			producer.push(str, null, str);
			sentStrings.add(str);
		}
		producer.flush();
		logger.info("{} strings sent",strsToSend.size());
		assertEquals(nrOfStrings,strsToSend.size());
		
		// Wait until all the strings have been received
		// or the timeout elapses
		if (!numOfEventsToReceive.await(1, TimeUnit.MINUTES)) {
			logger.error("TIMEOUT received "+receivedStrings.size()+" instead of "+nrOfStrings);
			for (String str: strsToSend) {
				if (!receivedStrings.contains(str)) {
					logger.error("[{}] never received",str);
				}
			}
			for (String str: discardedStrings) {
				logger.error("[{}] discarded",str);
				assertFalse("This string should not have been discarded!",sentStrings.contains(str));
			}
		}
		assertEquals(nrOfStrings, receivedStrings.size());
		logger.info("{} strings received",receivedStrings.size());
		logger.info("{} strings discarded",discardedStrings.size());
		
		// Check if the receives strings match with the strings sent
		receivedStrings.forEach(str -> assertTrue("Got a string not produced by this test: ["+str+"]",strsToSend.contains(str)));
	}

	/**
	 * This method could get notifications for messages
	 * published before depending on the log and offset
	 * retention times. Therefore it discards the strings
	 * not published by this test
	 *  
	 * @see org.eso.ias.kafkautils.SimpleStringConsumer.KafkaConsumerListener#stringEventReceived(java.lang.String)
	 */
	@Override
	public synchronized void stringEventReceived(String event) {
		if (sentStrings.contains(event)) {
			receivedStrings.add(event);
			numOfEventsToReceive.countDown();
		} else {
			discardedStrings.add(event);
		}
		processedMessages.incrementAndGet();
		
	}

}
