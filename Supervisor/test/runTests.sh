#!/usr/bin/bash
iasRun.py -l s org.scalatest.run org.eso.ias.supervisor.test.SupervisorTest
iasRun.py -l s org.scalatest.run org.eso.ias.supervisor.test.SupervisorWithKafkaTest