import os
from IASLogging.logConf import Log
import argparse
#from iasRun import main

if __name__=="__main__":
 parser = argparse.ArgumentParser(description='Run a java or scala program.')
 parser.add_argument(
                        '-si',
                        '--SupervisorId',
                        help='The ID of the supervisor',
                        action='store',
                        required=True)
 parser.add_argument(
                        '-lso',
                        '--levelStdOut',
                        help='Logging level: Set the level of the message for the file logger, default: info level',
                        action='store',
                        choices=['info', 'debug', 'warning', 'error', 'critical'],
                        default='info',
                        required=False)
 parser.add_argument(
                        '-lcon',
                        '--levelConsole',
                        help='Logging level: Set the level of the message for the console logger, default: info level',
                        action='store',
                        choices=['info', 'debug', 'warning', 'error', 'critical'],
                        default='info',
                        required=False)
 
 parser.add_argument(
                        '-v',
                        '--verbose',
                        help='Increase the verbosity of the output',
                        action='store_true',
                        default=False,
                        required=False)
 parser.add_argument('-o',
		     '--otherArg',
                     nargs='+',
                     help="Insert other argument",
                     required="false")

 args = parser.parse_args()
 id=args.SupervisorId
 lso=args.levelStdOut
 lcon=args.levelConsole
 otherArgs=args.otherArg


 log = Log()
 logger=log.initLogging(os.path.basename(__file__),lso,lcon)

 cmd=""
 if not id:
  logger.error("Missing supervisor ID in command line")
 else:
  logger.info("ID of supervisor: %s", id)
  LOGID_PARAM="-i "+id
 
 for elem in otherArgs:
  cmd+=" "+elem 
 startScript="iasRun -l s "+LOGID_PARAM+" org.eso.ias.supervisor.Supervisor "+id
 logger.info("iasRun -l s %s org.eso.ias.supervisor.Supervisor %s %s",LOGID_PARAM,id,cmd)
 logger.info("Script iasSupervisor start iasRun script")
 os.system(startScript)

 """
 JAVA_PROPS=""
 OTHER_PARAMS=""
 TEMP_PARMS_ARRAY=[]
 

 if len(JAVA_PROPS)>0:
  logger.info("Found java properties: %s",JAVA_PROPS)
 
 if len(OTHER_PARAMS)==0:
  logger.error("Missing supervisor ID in command line") 
 else:
  TEMP=(OTHER_PARAMS )
  id=TEMP_PARMS_ARRAY[0]
  logger.info( "Supervisor ID= %s", id)
  LOGID_PARAM="-i "+str(id)


 TEMP=OTHER_PARAMS 
 ID=TEMP_PARMS_ARRAY[0]
 logger.info( "Supervisor ID= %s", id)

 CMD="iasRun -l s $JAVA_PROPS $LOGID_PARAM org.eso.ias.supervisor.Supervisor $OTHER_PARAMS"

 #echo Will run
 #echo $CMD

 #$CMD
 """
