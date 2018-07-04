#!/usr/bin/bash

testCreateModule
iasRun -l s -v org.scalatest.run org.eso.ias.utils.test.ISO8601Test
