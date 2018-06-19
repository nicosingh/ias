import logging
import sys
import os, errno
import datetime


class Log():
  @staticmethod
  def initLogging (nameFile,stdoutLevel,consoleLevel):
    #take the path for logs folder inside $IAS_ROOT

    logPath=os.environ["IAS_ROOT"]
    #If the file doesn't exist it's created
    try:
        os.makedirs(logPath+"/logs")
    except OSError as e:
        if e.errno != errno.EEXIST:
         raise

    #Format of the data for filename
    now = datetime.datetime.utcnow().strftime('%Y-%m-%d_%H:%M:%S.%f')[:-3]
    LEVELS = { 'debug':logging.DEBUG,
            'info':logging.INFO,
            'warning':logging.WARNING,
            'error':logging.ERROR,
            'critical':logging.CRITICAL,
            }
    stdLevel_name = stdoutLevel
    consoleLevel= consoleLevel
    stdLevel = LEVELS.get(stdLevel_name, logging.NOTSET)
    consoleLevel = LEVELS.get(consoleLevel, logging.NOTSET)
    file=("{0}/logs/{1}.log".format(logPath, str(nameFile)+str(now)))

    logger = logging.getLogger(__name__)
    logger.setLevel(logging.INFO) 
    # create file handler which logs even debug messages
    fh = logging.FileHandler(file)
    fh.setLevel(stdLevel)
    # create console handler with a higher log level
    ch = logging.StreamHandler()
    ch.setLevel(consoleLevel)
    # create formatter and add it to the handlers
    formatterConsole = logging.Formatter('%(asctime)s%(msecs)d %(levelname)-8s [%(filename)s %(lineno)d] %(message)s' , '%H:%M:%S.')
    formatterFile =  logging.Formatter('%(asctime)s%(msecs)d  | %(levelname)s | [%(filename)s %(lineno)d] [%(threadName)s] | %(message)s')
    fh.setFormatter(formatterFile)
    ch.setFormatter(formatterConsole)
    # add the handlers to the logger 
    logger.addHandler(fh)
    logger.addHandler(ch)
    return logger
