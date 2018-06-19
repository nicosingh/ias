#!/usr/bin/bash
testCreateModule
iasRun -l s -v -lso error -lcon info org.scalatest.run org.eso.ias.utils.test.ISO8601Test
