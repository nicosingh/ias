'''
API python documentation builder.

Created on Jul 7, 2017

@author: acaproni
'''

import sys
import os
import shutil
from subprocess import call
from glob import glob
from IASApiDocs.DocGenerator import DocGenerator
from .logConf import GetLogger



class PydocBuilder(DocGenerator):
    '''
    Builds python API documentation, delegating to pydoc
    '''
    logger=log.GetLogger(__name__)

    def __init__(self,srcFolder,dstFolder,includeTestFolder=False,outFile=sys.stdout):
        """
        Constructor
        @param srcFolder: the folder with python sources to generate their documentation
        @param dstFolder: destination folder for pydocs
        @param includeTestFolder: True if the class must generate documentation for the python
                sources in test folders (defaults to False)
        @param outFile: the file where the output generated by calling pydoc must be sent
        """
        self.includeTestFolder=includeTestFolder
        super(PydocBuilder, self).__init__(srcFolder,dstFolder,outFile)

    def getPythonFilesInFolder(self,folder):
        """
        Get the python sources in the passed folder

        Note that these files are python scripts that do not belong
        to a python module
        """
        ret = []
        files= os.listdir(folder)
        for f in files:
            if f.endswith(".py"):
                ret.append(f)

        return ret

    def buildIndex(self,folder):
        """
        Build the index.thm file needed by github web server.

        The index will simply list the generated html files

        @param folder: the folder containing html files to index
        """
        print("Generating index in",folder)

        htmlFilePaths = glob(folder+"/*.html")
        htmlFiles = []
        for f in htmlFilePaths:
            parts = f.split("/")
            fileName = parts[len(parts)-1]
            htmlFiles.append(fileName)
        htmlFiles.sort()

        # Look for python modules that can be taken from
        # file names having 2 dots like IASApiDocs.DocGenerator.html
        pyModules = []
        for f in htmlFiles:
            parts = f.split(".")
            if (len(parts)>2):
                if (parts[0] not in pyModules):
                    pyModules.append(parts[0])
        pyModules.sort()
        logger.info("Python modules %s", pyModules)

        msg = "<!DOCTYPE html><html>\n<body>\n"
        msg += "\t<h1>IAS python API</h1>\n"
        msg += "\t<h2>Scripts</h2>\n"
        msg += "\t<UL>\n"

        for f in htmlFiles:
            parts = f.split(".")
            if (len(parts)==2):
                if parts[0] not in pyModules:
                    noExtension=os.path.splitext(f)[0]
                    msg+= '\t\t<LI><A href="'+f+'">'+noExtension+'</A>\n'
        msg += "\t</UL>\n"

        msg += "\t<h2>Modules</h2>\n"
        for m in pyModules:
            msg +="\t\t<h3>"+m+"</h3>\n"
            msg += "\t\t<UL>\n"
            for f in htmlFiles:
                parts = f.split(".")
                if (parts[0]==m):
                    noExtension=os.path.splitext(f)[0]
                    msg+= '\t\t\t<LI><A href="'+f+'">'+noExtension+'</A>\n'
            msg += "\t\t</UL>\n"

        msg+= "</body>\n"

        text_file = open(folder+"/index.html", "w")
        text_file.write(msg)
        text_file.close()

        logger.info("/%sindex.html written",folder)

    def buildPydocs(self):
        """
        Build the pydocs

        Generation of pydocs is delegated to pydoc executable

        @return: the code returned by calling pydoc
        """

        folders = self.getSrcPaths(self.srcFolder, False,"python",".py")

        logger.info("Folders %s",folders)
        logger.info("SourceFolder %s",self.srcFolder)

        for folder in folders:
            logger.info("Generating pydoc in %s",folder)

            oldWD = os.getcwd()
            logger.info("Changing folder to %s",folder)

            os.chdir(folder)
            cmd =["pydoc"]
            cmd.append("-w")
            cmd.append("./")
            ret = call(cmd,stdout=self.outFile,stderr=self.outFile)
            logger.info("Moving htmls to %s",self.dstFolder)

            files = os.listdir(".")
            for f in files:
                if (f.endswith(".html")):
                    shutil.move(f, self.dstFolder)
            logger.info("Changing folder back to %s",oldWD)

            os.chdir(oldWD)

        self.buildIndex(self.dstFolder)
        return ret
