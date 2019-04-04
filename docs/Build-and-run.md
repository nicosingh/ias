# How to build and run the &brvbar;AS

This twiki pages explains how to install and run the &brvbar;AS after you checkout the sources from the repository.

## Prerequisites

IAS is built on CentOS, v7.5 64bit, at the time of writing. We plan to upgrade the tools and the operating system until almost ready to deliver the tool in operation. At that time the operating system and the tools will be frozen in favour of stability.

### Dependencies

The following tools must be installed on the linux box:
* ant
* ant-contrib
* maven
* java jdk 1.8 (at the present we adopt [OpenJDk](http://openjdk.java.net/))
* [scala](http://www.scala-lang.org/) v.2.12.6
* python 3.5
* [zookeeper](https://zookeeper.apache.org/) (shipped with kafka)
* [kafka](https://kafka.apache.org/) v2.12-2.0.0
* Oracle 11 Xe (optional) if you want to use the RDB implementation of the CDB. Installation instructions in [[ConfigurationDatabase]]

### Environment

Some environment variable must be set to let the build succeed:
- `JAVA_HOME`
- `JRE_HOME`
- `SCALA_HOME`
- `IAS_ROOT`: IAS Integration area
- `KAFKA_HOME`: optional, for usage of ias debugging tools that make use of kafka.

IAS binaries will be installed in the IAS_ROOT folder. The structure of the folder is generated at build time.

In addition to that environment variables, before building the sources or runnning the IAS, you must source the `ias-bash-profile.sh` that you can find in `Tools/config`

To build the IAS, cd into the main folder of the project and launch `ant build`. It will go through all the source modules, build and install in the integration area.

### Installation of kafka and zookeeper

The installation of kafka and zookeeper can be done as you prefer (i.e. RPMs or tgz or sopurces). Configuration files are provided in `Tools/config` for the single-server use case.

At the bottom of `Tools/config/kafka-server.properties` you can see the customization we did:

```
############################ IAS  #############################

auto.create.topics.enable=true
delete.topic.enable=true
log.dirs=/opt/kafkadata

# The number of hours to keep a log file before deleting it (in hours), tertiary to log.retention.ms property
# The default is 168
log.retention.hours=1

# Creates a new segment every 5 minutes
# @see https://github.com/IntegratedAlarmSystem-Group/ias/issues/97
log.roll.ms=300000

offsets.retention.check.interval.ms=60000
offsets.retention.minutes=5
```

The only customization for zookeeper is about the folder the save data into:
```
dataDir=/opt/zookeeperdata
```

For performance reasons, `/opt/zookeeperdata` and `/opt/kafkadata` must be in a high speed, low latency hard disk installed in the server. Remotely mounted folders must not be used. The biggest amount of RAM in the server the better as kafka largely uses available RAM to improve performances.

We install the servers from tar files into `/opt`. To start the servers (and clean the environment to avoid left-over) run this bash script (`zookeeper.properties` and `server.properties`are those in `Tools/config` after renaming and copying in the proper folders in `/opt`):
```
echo "Cleaning folders..."
rm -rf /opt/kafkadata/* /opt/zookeeperdata/*
cd /opt/kafka_2.12-2.0.0
echo  "Starting zookeeper..."
bin/zookeeper-server-start.sh -daemon config/zookeeper.properties
sleep 2
echo  "Starting kafka..."
bin/kafka-server-start.sh -daemon config/server.properties
echo "Done"
echo
```

The BSDB is composed of several kafka queues and having kafka (and zookeeper) running is a prerequisite: Kafka server must be started before running any other tool of the IAS.

There is no need to shutdown kafka after shutting down IAS components like converters and Supervisors: kafka is daemon service always running and ready to support communications of the core.
Avalid reason to restart the service is to clean the content of the folders in `/opt` but keep in mind that kafka already automatically removes data older than one hour.

All the monitor points and alarms as well as the heartbeats are pushed in the BSDB as human readable JSON strings to ease debugging.

## How to run the tests

Each IAS module has (or should have) tests associated with it. Unit tests are in the `src/test` folder of the module and they serve to check the correctness of the code and as examples so we kindly suggest you to look at the tests to see how the code in the `src/main` folder is supposed to work and be used.

To run a test of a module, go into the `test` folder of the module the run `ant build test`.
The output of the test is the output generated by running unit tests. We use [ScalaTest](http://www.scalatest.org/) for scala unit tests that produces a nice colored output: user defined text color for the output generated by the code, green color for successfully executed tests and red for errors. [junit](http://junit.org/junit4/) is the selected testing tool for java sources.

## Run the IAS

Prerequisite to run the IAS:
* zookeeper and kafka servers up and running
* if you plan to run the CDB over RDBMS:
** set the sever and login details of hibernate in `Cdb/src/main/resources/org/eso/ias/hibernate/config/hibernate.cfg.xml`
** build and install the cdb by running `ant build install` in `Cdb/src/main`
** ensure the RDBMS is up and running
* in the bash 
** set `JAVA_HOME`, `SCALA_HOME`, `KAFKA_HOME` and `IAS_ROOT` environment variables
** source `Tools/config/ias-bash-profile.sh`
* Run the plugins (for testing you can use the `DummyPlugin` or the `MultyDummiPlugin` in the contrib repo)
* Properly configure the CDB with the Supervisors, DASUs and ASCEs to deploy
* start the converter (omit `-jcdb path` if using the RDB implementation of the CDB): `iasConverter ConverterID -jcdb /usr/src/cdb/ -Dorg.eso.ias.converter.kafka.servers=${KAFKA_SERVER}:9092`
* start the Supervisor (omit `-jcdb path` if using the RDB implementation of the CDB): `iasSupervisor SupervisoID -jcdb /usr/src/cdb/ -Dorg.eso.ias.kafka.brokers=${KAFKA_SERVER}:9092`
* start optional core components like the web server sender (mandatory to have alarms displayed in the browser), the email sender, the LTDB feeder 

To check if the IAS components are running and producing monitor points and alarms you must check what is pushed in the BSDB:
* `iasDumpKafkaTopic` allows to check what is pushed in the BSDB topics by plugins, Converters and DASUs, heartbeats.
* `iasValueDumper` dumps the monitor points and alarms pushed in the core topic in a more human readable way than a JSON string

## Stop the IAS

The IAS is designed in such a way that each part can be stopped and restarted independently. 
Stopping a Supervisor will kill all the DASUs and ASCEs deployed into it and the alarms they produce will be marked invalid in the panels. If the Supervisor is started again, it will restart all the DASUs and ASCEs and after few moments all the alarms they produce will appear valid in the panels.

To stop the IAS you need to kill the Supervisors, converters and optional components or their docker containers. IAS core components catch the kill signal and free the allocated resources before termination.

Plugins must also be killed or their containers terminated.

Stopping kafka is not mandatory but restarting ensures that it releases all the resources and free disk space.

## Change of CDB configuration

IAS components read the configuration only once at boot: changing the CDB require to restart all affected core components.
* Converter: need to be restarted if the definition of a IASIO has been changed in the CDB.
* Supervisor: need to be restarted if the change in the configuration affects the inputs of the DASUs/ASCEs or the output they produce; changes in the properties or the class of the TF executed by ASCEs needs a restarting of the Supervisor as well
* restarting optional tools depends on what they do and we cannot give a rule here. However, the IAS is designed so that each part can be shut down and restarted independently without affecting the others. 

Note that the configuration of plugins is not part of the CDB: no need to restart the plugins after changing the CDB.

As a rule of thumb: if ensure restart!

## Change of transfer function

Changing the code of a TF or its configuration in the CDB is effective only after restarting the Supervisor where is deployed the ASCE that executes the transfer function. Also notes that the same TF can be run by several ASCEs.
Changing the TF means: a change of the java/scala class to load, the source code, the properties passed to the ASCE. Needless to say that changing the source requires a build.