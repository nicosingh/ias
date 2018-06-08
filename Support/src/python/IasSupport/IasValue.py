'''
Created on Jun 7, 2018

@author: acaproni
'''
import json
from IasSupport.Iso8601TStamp import Iso8601TStamp
from IasPlugin.OperationalMode import OperationalMode
from IasSupport.Validity import Validity
from IasPlugin.IasType import IASType

class IasValue(object):
    '''
    The equivalent of IASValue.java in python
    
    Each parameter is present as string or as native type
    for example a timestamp is present as ISO8601 string and datatime,
    the mode is present as string or as OperationalMode
    '''
    
    # The value
    value = None
    
    # The point in time when the plugin produced this value
    # as ISO 8601 string
    pluginProductionTStampStr = None
    
    # The point in time when the plugin produced this value
    # as datetime
    pluginProductionTStamp = None
    
    # The point in time when the plugin sent the 
    # value to the converter
    # as ISO 8601 string
    sentToConverterTStampStr = None
    
    # The point in time when the plugin sent the 
    # value to the converter
    # as datetime
    sentToConverterTStamp = None
    
    # The point in time when the converter received the  
    # value from the plugin
    # as ISO 8601 string
    receivedFromPluginTStampStr = None
    
    # The point in time when the converter received the  
    # value from the plugin
    # as datetime
    receivedFromPluginTStamp = None
    
    # The point in time when the converter generated
    # the value from the data structure received by the plugin
    # as ISO 8601 string
    convertedProductionTStampStr = None
    
    # The point in time when the converter generated
    # the value from the data structure received by the plugin
    # as datetime
    convertedProductionTStamp = None
    
    # The point in time when the value has been sent to the BSDB
    # as ISO 8601 string
    sentToBsdbTStampStr = None
    
    # The point in time when the value has been sent to the BSDB
    # as datetime
    sentToBsdbTStamp = None
    
    # The point in time when the value has been read from the BSDB
    # as ISO 8601 string
    readFromBsdbTStampStr = None
    
    # The point in time when the value has been read from the BSDB
    # as datetime
    readFromBsdbTStamp = None
    
    # The point in time when the value has been
    # generated by the DASU
    # as ISO 8601 string
    dasuProductionTStampStr = None
    
    # The point in time when the value has been
    # generated by the DASU
    # as datetime
    dasuProductionTStamp= None
    
    # The string representation of the mode of the input
    modeStr = None
    
    # The OperationalMode of the input
    mode = None
    
    # The validity
    iasValidityStr = None
    
    # The validity
    iasValidity = None
    
    # The identifier of the input
    id = None
    
    # The full identifier of the input concatenated with
    # that of its parents.
    fullRunningId = None
    
    # The string representation of the IAS type of this input.
    valueTypeStr = None
    
    # The IAS type of this input.
    valueType = None
    
    # The full running identifiers of the dependent
    # monitor point 
    dependentsFullRuningIds = None
    
    # The additional properties
    props = None
    
    # The dictonary produced by reading the json string of the IAasValue
    fromJson = None

    def __init__(self, jsonStr):
        '''
        Constructor
        
        @param jsonStr the json string representing the IASValue
        '''
        self.fromJson = json.loads(jsonStr)
        
        self.value = self.fromJson["value"]
        self.valueTypeStr = self.fromJson["valueType"]
        self.valueType = IASType.fromString(self.valueTypeStr)
        self.fullRunningId = self.fromJson["fullRunningId"]
        self.id = self.getIdFromFullRunningId(self.fullRunningId)
        self.dependentsFullRuningIds = self.getValue(self.fromJson,"depsFullRunningIds")
        self.modeStr = self.fromJson["mode"]
        self.mode = OperationalMode.fromString(self.modeStr)
        self.iasValidityStr = self.fromJson["iasValidity"]
        self.iasValidity = Validity.fromString(self.iasValidityStr)
        self.props = self.getValue(self.fromJson,"props")
    
        self.pluginProductionTStampStr = self.getValue(self.fromJson,"pluginProductionTStamp")
        if self.pluginProductionTStampStr is not None:
            self.pluginProductionTStamp=Iso8601TStamp.Iso8601ToDatetime(self.pluginProductionTStampStr)
            
        self.sentToConverterTStampStr = self.getValue(self.fromJson,"sentToConverterTStamp")
        if self.sentToConverterTStampStr is not None:
            self.sentToConverterTStamp=Iso8601TStamp.Iso8601ToDatetime(self.sentToConverterTStampStr)
            
        self.receivedFromPluginTStampStr = self.getValue(self.fromJson,"receivedFromPluginTStamp")
        if self.receivedFromPluginTStampStr is not None:
            self.receivedFromPluginTStamp=Iso8601TStamp.Iso8601ToDatetime(self.receivedFromPluginTStampStr)
            
        self.convertedProductionTStampStr = self.getValue(self.fromJson,"convertedProductionTStamp")
        if self.convertedProductionTStampStr is not None:
            self.convertedProductionTStamp=Iso8601TStamp.Iso8601ToDatetime(self.convertedProductionTStampStr)
        
        self.sentToBsdbTStampStr = self.getValue(self.fromJson,"sentToBsdbTStamp")
        if self.sentToBsdbTStampStr is not None:
            self.sentToBsdbTStamp=Iso8601TStamp.Iso8601ToDatetime(self.sentToBsdbTStampStr)
        
        self.readFromBsdbTStampStr = self.getValue(self.fromJson,"readFromBsdbTStamp")
        if self.readFromBsdbTStampStr is not None:
            self.readFromBsdbTStamp=Iso8601TStamp.Iso8601ToDatetime(self.readFromBsdbTStampStr)
        
        self.dasuProductionTStampStr = self.getValue(self.fromJson,"dasuProductionTStamp")
        if self.dasuProductionTStampStr is not None:
            self.dasuProductionTStamp=Iso8601TStamp.Iso8601ToDatetime(self.dasuProductionTStampStr)
        
    def getIdFromFullRunningId(self, frid):
        """
        Extract the id from the fullRunningId
        """
        if frid is None or frid is "":
            raise ValueError("The FullRuning ID cannot be empty")
        
        parts = frid.split("@")
        iasioIdList = [t for t in parts if t.count("IASIO")]
        if len(iasioIdList)!=1:
            raise ValueError("Invalid format of fullRunningId"+frid)
        iasioId=iasioIdList[0]
        if iasioId[0] is not '(' or iasioId[len(iasioId)-1] is not ')' or iasioId.count(':') is not 1:
            raise ValueError("Invalid format of fullRunningId"+frid)
        return iasioId[1:-1].split(":")[0]
    
      
    def getValue(self, jsonDict,key):
        """
        Get a value from the dictionary, if it exists
        
        @param jsonDict: the dictionary
        @param key: the key
        @return the value of the key if exists, None otherwise
        """
        try:
            ret = jsonDict[key]
        except:
            ret = None
        return ret
    
    def toJSonString(self):
        """
        @return the JSON representation of the IasValue
        """
        temp = {
             "value":self.value,
             "valueType":self.valueTypeStr,
             "fullRunningId":self.fullRunningId,
             "mode":self.modeStr,
             "iasValidity":self.iasValidityStr
             }
        
        if self.dependentsFullRuningIds is not None:
            temp["depsFullRunningIds"]=self.dependentsFullRuningIds
        if self.props is not None:
            temp["props"]=self.props
        if self.pluginProductionTStampStr is not None:
            temp["pluginProductionTStamp"]=self.pluginProductionTStampStr
        if self.sentToConverterTStampStr is not None:
            temp["sentToConverterTStamp"]=self.sentToConverterTStampStr
        if self.receivedFromPluginTStampStr is not None:
            temp["receivedFromPluginTStamp"]=self.receivedFromPluginTStampStr
        if self.convertedProductionTStampStr is not None:
            temp["convertedProductionTStamp"]=self.convertedProductionTStampStr
        if self.sentToBsdbTStampStr is not None:
            temp["sentToBsdbTStamp"]=self.sentToBsdbTStampStr
        if self.readFromBsdbTStampStr is not None:
            temp["readFromBsdbTStamp"]=self.readFromBsdbTStampStr
        if self.dasuProductionTStampStr is not None:
            temp["dasuProductionTStamp"]=self.dasuProductionTStampStr
             
        return json.dumps(temp)
    