import os
from IASLogging.logConf import Log
import argparse
import iasRun

if __name__=="__main__":
 parser = argparse.ArgumentParser(description='Run iasSupervisor Script.')
 parser.add_argument(
                        '-l',
                        '--language',
                        help='The programming language: one between scala (or shortly s) and java (or j)',
                        action='store',
                        choices=['java', 'j', 'scala','s'],
                        required=True)
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
                     required=False)


 parser.add_argument('-j',
		     '--javaArg',
                     nargs='+',
                     help="Insert java argument",
                     required=False)

 args = parser.parse_args()
 id=args.SupervisorId
 lso=args.levelStdOut
 lcon=args.levelConsole
 otherArgs=args.otherArg
 javaArgs=args.javaArg
 verbose=args.verbose
 language=args.language
 log = Log()
 logger=log.initLogging(os.path.basename(__file__),lso,lcon)

 oArg=""
 jArg=""
 lsoVal=""
 lconVal=""
 if not id:
  logger.error("Missing supervisor ID in command line")
 else:
  logger.info("ID of supervisor: %s", id)
  LOGID_PARAM="-i "+id
 # Build the command line

 if otherArgs:
  for elem in otherArgs:
   oArg+=" "+elem
 if javaArgs:
  for elem in javaArgs:
   jArg+=" "+elem
 if lso:
  LOGID_PARAM+=" -lso "+lso
 if lcon:
  LOGID_PARAM+=" -lcon "+lcon


 startScript="iasRun -l s "+LOGID_PARAM+" org.eso.ias.supervisor.Supervisor "+oArg+""+jArg
 logger.info(startScript)
 logger.info("Script iasSupervisor start iasRun script")
 iasRun.main(language,id,"",verbose,False,lsoVal,lconVal,"org.eso.ias.supervisor.Supervisor","",0)
 #os.system(startScript)
 #main(language,logfileId,jProp,verbose,enableAssertions,stdoutLevel,consoleLevel,className,param,size)
 #iasRunMain("s","supId","",True,False,lsoVal,lconVal,"org.eso.ias.supervisor.Supervisor","",0)
