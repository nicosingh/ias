#! /usr/bin/env python
'''
Created on Sep 21, 2016

@author: acaproni
'''
import argparse
from IASTools.FileSupport import FileSupport
from logConf import Log
if __name__ == '__main__':
    log=Log()
    logger=log.GetLoggerFile(os.path.basename(__file__).split(".")[0])
    parser = argparse.ArgumentParser(description='Search for a file in the hierarchy of IAS folders.')
    parser.add_argument(
        '-t',
        '--type',
        help='The type of files to search for: '+str(FileSupport.iasFileType),
        dest="fileType",
        action='store',
        default=None)
    parser.add_argument(
        dest='fileName',
        help='The name of the file to search for')
    args = parser.parse_args()

    try:
        if not args.fileType is None:
            fileSupport = FileSupport(args.fileName, args.fileType)
            filePath=fileSupport.findFile()
        else:
            fileSupport = FileSupport(args.fileName)
            filePath=fileSupport.findFile()
        print(filePath)
    except OSError as e:
        logger.error("File not found")
