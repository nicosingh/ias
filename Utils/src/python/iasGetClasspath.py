#! /usr/bin/env python
'''
Writes the classpath in the stdout

@author: acaproni
'''

from IASTools.CommonDefs import CommonDefs
from IASLogging.logConf import Log

if __name__ == '__main__':
    log=Log()
    logger=log.GetLoggerFile()
    logger.info(CommonDefs.buildClasspath())