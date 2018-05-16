import logging
import sys
import os, errno
import datetime


class Log():

  def GetLoggerFile(fileName,self):
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
    level=0
    file=("{0}/logs/{1}.log".format(logPath, str(fileName)+str(now)))
    LEVELS = { 'debug':logging.DEBUG,
            'info':logging.INFO,
            'warning':logging.WARNING,
            'error':logging.ERROR,
            'critical':logging.CRITICAL,
            }

    if len(sys.argv) > 1:
     level_name = sys.argv[1]
     if (LEVELS.get(level_name, logging.NOTSET)==0):
      level=logging.DEBUG
    logging.basicConfig(level=level,format='%(asctime)s%(msecs)d  | %(levelname)s | [%(filename)s %(lineno)d] [%(threadName)s] | %(message)s',
                        datefmt='%Y-%m-%d %H:%M:%S.', filename=file)
    #path of the file


    # set up logging to file - see previous section for more details

    # define a Handler which writes INFO messages or higher to the sys.stderr
    console = logging.StreamHandler()
    # console.setLevel(logging.INFO)
    # set a format which is simpler for console use
    formatter = logging.Formatter('%(asctime)s%(msecs)d %(levelname)-8s [%(filename)s %(lineno)d] %(message)s' , '%H:%M:%S.')
    # tell the handler to use this format
    console.setFormatter(formatter)
    # add the handler to the root logger
    logging.getLogger('').addHandler(console)

    # Now, define a couple of other loggers which might represent areas in your
    # application:

    logger1 = logging.getLogger(__name__)

    return logger1
