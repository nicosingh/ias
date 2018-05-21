#! /usr/bin/env python
'''
Writes the classpath in the stdout

@author: acaproni
'''

from IASTools.CommonDefs import CommonDefs
from IASLogging.logConf import Log
import os
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Get the classpath.')
    parser.add_argument(
                        '-le',
                        '--level',
                        help='Logging level: Set the level of the message for the logger, default: Debug level',
                        action='store',
                        choices=['info', 'debug', 'warning', 'error', 'critical'],
                        default='debug',
                        required=False)
    args = parser.parse_args()
    loggingLevel=args.level
    log=Log()
    logger=log.initLogging(os.path.basename(__file__),loggingLevel)
    logger.info(CommonDefs.buildClasspath())
