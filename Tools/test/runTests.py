import IASTools
import os
from IASLogging.logConf import Log

if __name__=="__main__":
 os.system("python -V")
 log=Log()
 logger=log.initLogging(os.path.basename(__file__),"info","info")
 logger.info("Start testModel")

 os.system("testCreateModule")
 os.system("iasRun -l s -v true org.scalatest.run org.eso.ias.utils.test.ISO8601Test") 
