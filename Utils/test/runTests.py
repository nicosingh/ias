import IASTools
import os
from IASLogging.logConf import Log
from IASScript.iasGetClasspath import getClasspath

if __name__=="__main__":
 os.system("pwd")
 log=Log()
 logger=log.initLogging(os.path.basename(__file__),"info","info")
 logger.info("Start testModel")
 getClass=getClasspath()
 getClass.iasGetClasspath()
