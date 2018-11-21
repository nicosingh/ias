package org.eso.ias.webserversender;

import org.apache.commons.cli.*;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eso.ias.cdb.CdbReader;
import org.eso.ias.cdb.json.CdbFiles;
import org.eso.ias.cdb.json.CdbJsonFiles;
import org.eso.ias.cdb.json.JsonReader;
import org.eso.ias.cdb.pojos.IasDao;
import org.eso.ias.cdb.pojos.LogLevelDao;
import org.eso.ias.cdb.rdb.RdbReader;
import org.eso.ias.heartbeat.HbEngine;
import org.eso.ias.heartbeat.HbMsgSerializer;
import org.eso.ias.heartbeat.HbProducer;
import org.eso.ias.heartbeat.HeartbeatStatus;
import org.eso.ias.heartbeat.publisher.HbKafkaProducer;
import org.eso.ias.heartbeat.serializer.HbJsonSerializer;
import org.eso.ias.kafkautils.FilteredKafkaIasiosConsumer;
import org.eso.ias.kafkautils.FilteredKafkaIasiosConsumer.FilterIasValue;
import org.eso.ias.kafkautils.KafkaHelper;
import org.eso.ias.kafkautils.KafkaStringsConsumer.StartPosition;
import org.eso.ias.kafkautils.SimpleKafkaIasiosConsumer.IasioListener;
import org.eso.ias.logging.IASLogger;
import org.eso.ias.types.IASTypes;
import org.eso.ias.types.IASValue;
import org.eso.ias.types.IasValueJsonSerializer;
import org.eso.ias.types.IasValueSerializerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@WebSocket(maxTextMessageSize = 64 * 1024)
public class WebServerSender implements IasioListener {

	/**
	 * The identifier of the sender
	 */
	public final String senderID;

	/**
	* IAS Core Kafka Consumer to get messages from the Core
	*/
	private final FilteredKafkaIasiosConsumer kafkaConsumer;

	/**
	 * The name of the topic where webserver senders get
	 * monitor point values and alarms
	 */
	private final String sendersInputKTopicName;

	/**
	 * The name of the java property to get the name of the
	 * topic where the ias core push values
	 */
	private static final String IASCORE_TOPIC_NAME_PROP_NAME = "org.eso.ias.senders.kafka.inputstream";

	/**
	 * The list of kafka servers to connect to
	 */
	private final String kafkaServers;

	/**
	 * The name of the property to pass the kafka servers to connect to
	 */
	private static final String KAFKA_SERVERS_PROP_NAME = "org.eso.ias.senders.kafka.servers";

	/**
	 * Web Server host as String
	 */
	private final String webserverUri;

	/**
	 * The name of the property to pass the webserver host to connect to
	 */
	private static final String WEBSERVER_URI_PROP_NAME = "org.eso.ias.senders.webserver.uri";

	/**
	 * Default webserver host to connect to
	 */
	private static final String DEFAULT_WEBSERVER_URI = "ws://localhost:8000/core/";

	/**
	 * The serializer/deserializer to convert the string
	 * received by the BSDB in a IASValue
	*/
	private final IasValueJsonSerializer serializer = new IasValueJsonSerializer();

	/**
	 * The logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(WebServerSender.class);

	/**
	 * WebSocket session required to send messages to the Web server
	 */
	private Optional<Session> sessionOpt;

	/**
	 * Same as the webserverUri but as a URI object
	 */
	private final URI uri;

	/**
	 * Web socket client
	 */
	private WebSocketClient client;

	/**
	 * Time in seconds to wait before attempt to reconnect
	 */
	private int reconnectionInterval = 2;

	/**
	 * The sender of heartbeats
	 */
	private final HbEngine hbEngine;

	/**
	 * A flag set to <code>true</code> if the socket is connected
	 */
	public final AtomicBoolean socketConnected = new AtomicBoolean(false);

	/**
	 * The interface of the listener to be notified of Strings received
	 * by the WebServer sender.
	 */
	public interface WebServerSenderListener {

		public void stringEventSent(String event);
	}

	/**
	 * The listener to be notified of Strings received
	 * by the WebServer sender.
	 */
	private final Optional<WebServerSenderListener> senderListener;

	/**
	 * Count down to wait until the connection is established
	 */
	private CountDownLatch connectionReady;

	/**
	 * User java properties
	 */
	Properties props;

	/**
	 * Constructor
	 *
	 * @param senderID Identifier of the WebServerSender
	 * @param kafkaServers Kafka servers URL
	 * @param props the properties to get kafka servers, topic names and webserver uri
	 * @param listener The listenr of the messages sent to the websocket server
	 * @param hbFrequency the frequency of the heartbeat (seconds)
	 * @param hbProducer the sender of HBs
	 * @param acceptedIds The IDs of the IASIOs to consume
	 * @param acceptedTypes The IASTypes to consume
	 * @throws URISyntaxException
	 */
	public WebServerSender(
			String senderID,
			String kafkaServers,
			Properties props,
			WebServerSenderListener listener,
			int hbFrequency,
			HbProducer hbProducer,
			Set<String> acceptedIds,
			Set<IASTypes> acceptedTypes) throws URISyntaxException {
		Objects.requireNonNull(senderID);
		if (senderID.trim().isEmpty()) {
			throw new IllegalArgumentException("Invalid empty converter ID");
		}
		this.senderID=senderID.trim();


		Objects.requireNonNull(kafkaServers);
		if (kafkaServers.trim().isEmpty()) {
			throw new IllegalArgumentException("Invalid empty kafka servers list");
		}
		this.kafkaServers=kafkaServers.trim();

		Objects.requireNonNull(props);
		this.props=props;
		this.props.put("group.id", this.senderID + ".kafka.group");
 		sendersInputKTopicName = props.getProperty(IASCORE_TOPIC_NAME_PROP_NAME, KafkaHelper.IASIOs_TOPIC_NAME);
		webserverUri = props.getProperty(WEBSERVER_URI_PROP_NAME, DEFAULT_WEBSERVER_URI);
		uri = new URI(webserverUri);
		logger.debug("Websocket connection URI: "+ webserverUri);
		logger.debug("Kafka server: "+ kafkaServers);
		senderListener = Optional.ofNullable(listener);

		logger.debug("*********** acceptedIds: " + Arrays.toString(acceptedIds.toArray()));
		logger.debug("*********** acceptedTypes: " + Arrays.toString(acceptedTypes.toArray()));
		FilterIasValue filter = new FilterIasValue() {
			public boolean accept(IASValue<?> value) {
				assert(value!=null);

				// Locally copy the sets that are immutable and volatile
		        // In case the setFilter is called in the mean time...
				Set<String> acceptedIdsNow = acceptedIds;
				Set<IASTypes> acceptedTypesNow = acceptedTypes;

				boolean acceptedById = acceptedIdsNow.isEmpty() || acceptedIdsNow.contains(value.id);
				boolean acceptedByType = acceptedTypesNow.isEmpty() || acceptedTypesNow.contains(value.valueType);
				return acceptedById || acceptedByType;
			}
		};
		kafkaConsumer = new FilteredKafkaIasiosConsumer(kafkaServers, sendersInputKTopicName, this.senderID, filter);

		if (hbFrequency<=0) {
			throw new IllegalArgumentException("Invalid frequency "+hbFrequency);
		}

		Objects.requireNonNull(hbProducer);
		hbEngine = HbEngine.apply(senderID, hbFrequency, hbProducer);
	}


	/**
	 * Operations performed on connection close
	 *
	 * @param statusCode
	 * @param reason
	 */
	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		logger.info("WebSocket connection closed. status: " + statusCode + ", reason: " + reason);
		socketConnected.set(false);
	   sessionOpt = Optional.empty();
		 if (statusCode != 1001) {
			 logger.info("Trying to reconnect");
			 this.connect();
		 } else {
			 logger.info("The Server is going away");
			 this.shutdown();
		 }
	}

	/**
	 * Operations performed on connection start
	 *
	 * @param session
	 */
	@OnWebSocketConnect
	public void onConnect(Session session) {
	   sessionOpt = Optional.ofNullable(session);
	   this.connectionReady.countDown();
	   socketConnected.set(true);
	   logger.info("WebSocket got connect. remoteAdress: " + session.getRemoteAddress());
   }

	@OnWebSocketMessage
    public void onMessage(String message) {
		notifyListener(message);
    }

	/**
	 * This method receives IASValues published in the BSDB.
	 *
	 * @see {@link IasioListener#iasiosReceived(Collection)}
	 */
	@Override
	public synchronized void iasiosReceived(Collection<IASValue<?>> events) {
        if (!socketConnected.get()) {
			logger.debug("The WebSocket is not connected: discard the event");
            return;
        }

        events.forEach( event -> {
            final String value;
            try {
                value = serializer.iasValueToString(event);
            } catch (IasValueSerializerException avse){
                logger.error("Error converting the event into a string", avse);
                return;
            }

            sessionOpt.ifPresent( session -> {
                session.getRemote().sendStringByFuture(value);
                logger.debug("Value sent: " + value);
                this.notifyListener(value);
            });
        });


    }

	public void setUp() {
		hbEngine.start();
		connect();
		try {
			kafkaConsumer.setUp(this.props);
			kafkaConsumer.startGettingEvents(StartPosition.END, this);
			logger.info("Kafka consumer starts getting events");
 	    }
 	    catch (Throwable t) {
 	        logger.error("Kafka consumer initialization fails", t);
 	        System.exit(-1);
 	    }
 	    hbEngine.updateHbState(HeartbeatStatus.RUNNING);
	}

	/**
	 * Initializes the WebSocket connection
	 */
	public void connect() {
		try {
			sessionOpt = Optional.empty();
			this.connectionReady = new CountDownLatch(1);
			client = new WebSocketClient();
			client.start();
			client.connect(this, this.uri, new ClientUpgradeRequest());
			if(!this.connectionReady.await(reconnectionInterval, TimeUnit.SECONDS)) {
				logger.info("The connection with the server is taking too long. Trying again.");
				connect();
			}
			logger.debug("Connection started!");
		}
		catch( Exception e) {
			logger.error("Error on WebSocket connection", e);
			logger.info("Trying to reconnect.");
			connect();
		}
	}

	/**
	 * Shutdown the WebSocket client and Kafka consumer
	 */
	public void shutdown() {
		hbEngine.updateHbState(HeartbeatStatus.EXITING);
		kafkaConsumer.tearDown();
		sessionOpt = Optional.empty();
		try {
			client.stop();
			logger.debug("Connection stopped!");
		}
		catch( Exception e) {
			logger.error("Error on Websocket stop");
		}
		hbEngine.shutdown();
	}

	/**
	 * Notify the passed string to the listener.
	 *
	 * @param strToNotify The string to notify to the listener
	 */
	protected void notifyListener(String strToNotify) {
		senderListener.ifPresent(listener -> listener.stringEventSent(strToNotify));
	}

	/**
	 * Set the time to wait before attempt to reconnect
	 *
	 * @param interval time in seconds
	 */
	public void setReconnectionInverval(int interval) {
		reconnectionInterval = interval;
	}

	/**
	 * Print the usage string
	 *
	 * @param options The options expected in the command line
	 */
	private static void printUsage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp( "WebServerSender Sender-ID ", options );
	}

	public static void main(String[] args) throws Exception {

		// Use apache CLI for command line parsing
		Options options = new Options();
        options.addOption("h", "help", false, "Print help and exit");
        options.addOption("j", "jcdb", true, "Use the JSON Cdb at the passed path");
		options.addOption(Option.builder("t").longOpt("filter-types").desc("Space separated list of types to send (LONG, INT, SHORT, BYTE, DOUBLE, FLOAT, BOOLEAN, CHAR, STRING, ALARM)").hasArgs().argName("TYPES").build());
		options.addOption(Option.builder("i").longOpt("filter-ids").desc("Space separated list of ids to send").hasArgs().argName("IASIOS-IDS").build());
        options.addOption("x", "logLevel", true, "Set the log level (TRACE, DEBUG, INFO, WARN, ERROR)");

		// Parse the command line
        CommandLineParser parser = new DefaultParser();
        CommandLine cmdLine = null;
        try {
            cmdLine = parser.parse(options, args);
        } catch (Exception e) {
			logger.error("Error parsing the comamnd line: " + e.getMessage());
			printUsage(options);
			System.exit(-1);
        }

		// Get help option
		boolean help = cmdLine.hasOption('h');

		// Get Required Sender ID
		List<String> remaingArgs = cmdLine.getArgList();
        Optional<String> senderId;
        if (remaingArgs.isEmpty()) {
            senderId = Optional.empty();
        } else {
            senderId = Optional.of(remaingArgs.get(0));
        }

		// Show help or error message if Sender ID is not specified
		if (help) {
			printUsage(options);
			System.exit(0);
		}
		if (!senderId.isPresent()) {
			System.err.println("Missing Sender ID");
			printUsage(options);
			System.exit(-1);
		}

		// Get filtered TYPES
		Set<IASTypes> acceptedTypes = new HashSet<>();
		if (cmdLine.hasOption("t")) {
			List<String> typesNames = Arrays.asList(cmdLine.getOptionValues('t'));
			for (String typeName : typesNames) {
				if (typeName.toUpperCase().equals("LONG")) acceptedTypes.add(IASTypes.LONG);
		    	else if (typeName.toUpperCase().equals("INT")) acceptedTypes.add(IASTypes.INT);
		    	else if (typeName.toUpperCase().equals("SHORT")) acceptedTypes.add(IASTypes.SHORT);
		    	else if (typeName.toUpperCase().equals("BYTE")) acceptedTypes.add(IASTypes.BYTE);
		    	else if (typeName.toUpperCase().equals("DOUBLE")) acceptedTypes.add(IASTypes.DOUBLE);
		    	else if (typeName.toUpperCase().equals("FLOAT")) acceptedTypes.add(IASTypes.FLOAT);
		    	else if (typeName.toUpperCase().equals("BOOLEAN")) acceptedTypes.add(IASTypes.BOOLEAN);
		    	else if (typeName.toUpperCase().equals("CHAR")) acceptedTypes.add(IASTypes.CHAR);
		    	else if (typeName.toUpperCase().equals("STRING")) acceptedTypes.add(IASTypes.STRING);
		    	else if (typeName.toUpperCase().equals("ALARM")) acceptedTypes.add(IASTypes.ALARM);
		    	else {
					System.err.println("Unsupported Type");
					printUsage(options);
					System.exit(-1);
				}
			}
			String types = "";
			for (IASTypes type: acceptedTypes) {
				types += "," + type.typeName;
			}
			logger.info("Sender will accept IASIOS of types: [" + types.substring(1) + "]");
		}

		// Get filtered IASIOS ids
		Set<String> acceptedIds = new HashSet<>();
		if (cmdLine.hasOption("i")) {
			acceptedIds = new HashSet<String>(Arrays.asList(cmdLine.getOptionValues('i')));
			String ids = "";
			for (String item: acceptedIds) {
				ids += "," + item;
			}
			logger.info("Sender will accept IASIOS with ids: [" + ids.substring(1) + "]");
		}

		// Get Optional CDB filepath
		CdbReader cdbReader = null;
		if (cmdLine.hasOption("j")) {
			String cdbPath = cmdLine.getOptionValue('j').trim();
            File f = new File(cdbPath);
            if (!f.isDirectory() || !f.canRead()) {
                System.err.println("Invalid file path "+cdbPath);
                System.exit(-3);
            }

            CdbFiles cdbFiles=null;
            try {
                cdbFiles= new CdbJsonFiles(f);
            } catch (Exception e) {
                System.err.println("Error initializing JSON CDB "+e.getMessage());
                System.exit(-4);
            }
            cdbReader = new JsonReader(cdbFiles);
        } else {
			cdbReader = new RdbReader();
        }

		// Read ias configuration from CDB
		Optional<IasDao> optIasdao = cdbReader.getIas();
		cdbReader.shutdown();

		if (!optIasdao.isPresent()) {
			throw new IllegalArgumentException("IAS DAO not fund");
		}

		// Set the log level
		Optional<LogLevelDao> logLvl=null;
		Optional<String> logLevelName = Optional.ofNullable(cmdLine.getOptionValue('x'));
        try {
            logLvl = logLevelName.map(name -> LogLevelDao.valueOf(name));
        } catch (Exception e) {
            System.err.println("Unrecognized log level");
			printUsage(options);
            System.exit(-1);
        }
		Optional<LogLevelDao> logLevelFromIasOpt = Optional.ofNullable(optIasdao.get().getLogLevel());
        IASLogger.setLogLevel(
             logLvl.map(l -> l.toLoggerLogLevel()).orElse(null),
             logLevelFromIasOpt.map(l -> l.toLoggerLogLevel()).orElse(null),
             null);

		// Set HB frequency
		int frequency = optIasdao.get().getHbFrequency();

		// Set serializer of HB messages
		HbMsgSerializer hbSerializer = new HbJsonSerializer();

		// Set kafka server from properties or default
		String kServers=System.getProperty(KAFKA_SERVERS_PROP_NAME);
		if (kServers==null || kServers.isEmpty()) {
			kServers=optIasdao.get().getBsdbUrl();
		}
		if (kServers==null || kServers.isEmpty()) {
			kServers=KafkaHelper.DEFAULT_BOOTSTRAP_BROKERS;
		}

		String id = senderId.get();
		HbProducer hbProd = new HbKafkaProducer(id, kServers, hbSerializer);

		WebServerSender ws=null;
		try {
			ws = new WebServerSender(
				id,
				kServers,
				System.getProperties(),
				null,
				frequency,
				hbProd,
				acceptedIds,
				acceptedTypes);
		} catch (URISyntaxException e) {
			logger.error("Could not instantiate the WebServerSender",e);
			System.exit(-1);
		}
		ws.setUp();
	}

}
