package org.eso.ias.plugin.publisher.impl;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.eso.ias.plugin.publisher.MonitorPointData;
import org.eso.ias.plugin.publisher.PublisherBase;
import org.eso.ias.plugin.publisher.PublisherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The publisher of monitor point values through Kafka.
 * <P>
 * <code>KafkaPublisher</code> is an unbuffered publisher because 
 * Kafka already does its own buffering and optimizations.
 * 
 * @author acaproni
 *
 */
public class KafkaPublisher extends PublisherBase {
	
	/**
	 * The logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(KafkaPublisher.class);
	
	/**
	 * The name of the property to set the path of the kafka provided configuration file 
	 */
	public static final String KAFKA_CONFIG_FILE_PROP_NAME = "org.eso.ias.plugin.kafka.config";
	
	/**
	 * The default path of the kafka provided configuration file 
	 */
	public static final String KAFKA_CONFIG_FILE_DEFAULT_PATH = "/org/eso/ias/plugin/publisher/impl/KafkaPublisher.properties";
	
	/**
	 * The name of the (required) java property to set the number of the partition
	 */
	public static final String KAFKA_PARTITION_PROP_NAME = "org.eso.ias.plugin.kafka.partition";
	
	/**
	 * The partition number is mandatory and must be defined by setting the
	 * {@value #KAFKA_PARTITION_PROP_NAME} java property.  
	 * 
	 */
	private Integer partition;
	
	/**
	 * The topic name for kafka publisher (in the actual implementation all the 
	 * plugins publishes on the same topic but each one has its own partition).
	 */
	public static final String topicName="PluginsKTopic";
	
	/**
	 * The kafka producer
	 */
	private Producer<String, String> producer = null;
	

	public KafkaPublisher(String pluginId, String serverName, int port, ScheduledExecutorService executorSvc) {
		super(pluginId, serverName, port, executorSvc);
	}

	/**
	 * Push a monitor point values in the kafka topic and partition.
	 */
	@Override
	protected long publish(MonitorPointData mpData) throws PublisherException {
		String jsonStrToSend = mpData.toJsonString();
		// The partition is explicitly set: the passed key will not used
		// for partitioning in the topic
		ProducerRecord<String, String> record = new ProducerRecord<String, String>(topicName, partition,pluginId,jsonStrToSend);
		Future<RecordMetadata> future = producer.send(record);
		
		return jsonStrToSend.length();
	}

	/**
	 * Initializes the kafka producer
	 */
	@Override
	protected void start() throws PublisherException {
		
		// Is there a user defined properties file?
		String userKafkaPropFilePath = System.getProperty(KAFKA_CONFIG_FILE_PROP_NAME);
		InputStream userInStream = null;
		if (userKafkaPropFilePath!=null){
			try {
				userInStream=new FileInputStream(userKafkaPropFilePath);
			} catch (IOException ioe) {
				throw new PublisherException("Cannot open the user defined file of properties",ioe);
			}
		}
		
		try (InputStream defaultInStream = KafkaPublisher.class.getResourceAsStream(KAFKA_CONFIG_FILE_DEFAULT_PATH)){
			mergeProperties(defaultInStream, userInStream);
		} catch (IOException ioe) {
			throw new PublisherException("Cannot open the default input file of properties",ioe);
		} catch (PublisherException pe) {
			throw new PublisherException("Cannot merge properties",pe);
		} finally {
			if (userInStream!=null) try {
				userInStream.close();
			} catch (IOException ioe) {
				throw new PublisherException("Error closing the user defined file of properties",ioe);
			}
		}
		
		// Force the hardcoded properties
		System.getProperties().put("bootstrap.servers", serverName+":"+serverPort);
		System.getProperties().put("client.id",pluginId);
		System.getProperties().put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		System.getProperties().put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		
		// The partition number is mandatory: check if it has been defined
		partition = Integer.getInteger(KAFKA_PARTITION_PROP_NAME);
		if (partition==null) {
			throw new PublisherException("Kafka partition not assigned ("+KAFKA_PARTITION_PROP_NAME+" java property expected)");
		}
		if (partition<0) {
			throw new PublisherException("Invalid Kafka partition number "+partition+": must be greater then 0");
		}
		
		producer = new KafkaProducer<>(System.getProperties());	
		logger.info("Kafka producer initialized");
		
		logsInfo();
	}

	/**
	 * Close the kafka producer
	 */
	@Override
	protected void shutdown() throws PublisherException {
		if (producer!=null) {
			producer.close();
		}
	}
	
	/**
	 * Issue some logs about kafka topic that can be useful for debugging
	 */
	private void logsInfo() {
		List<PartitionInfo> partitions = producer.partitionsFor(topicName);
		logger.info("Kafka topic {} has {} partitions",topicName,partitions.size());
		partitions.forEach(p -> logger.debug(p.toString()) );
	}

}