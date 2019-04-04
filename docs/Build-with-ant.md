# Build system

`ant` is the tool used to build, &brvbar;AS provides a script to be used while writing of the `build.xml` files of each modules. The purpose of the script is, from one side, to shorten and simplify the build configuration of each module and from the other side, to ensure that all the builds and folders are consistent along the project.

Each `build.xml` in the &brvbar;AS must include the build script, `CommonAnt.xml` and invoke the targets it provides for cleaning, building and installing.

## &brvbar;AS module structure

All the sources files are in the `src` or `test` folder of the module depending on the programming language. &brvbar;AS supports java, scala, python scripts and python modules. 

The structure of a module is fixed. To create a new module, use `iasCreateModule.py` with the name of the module. The script creates all the folders and add the LGPL v3 license file.

The structure of a module is defined in the `FoldersOfAModule.template` template files in that changing the name and position of the folders can be done by customizing the template without any change in the `iasCreateModule.py` script.

The following is the structure of a &brvbar;AS module name `iasmodule`:
```
iasmodule
 +| src
   +| main
      +| scala
      +| java
      +| python
      +| resources
       | build.xml
    +| test
      +| scala
      +| java
      +| python
      +| resources
       | build.xml
   +| cdb
     +| Supervisor
     +| Dasus
     +| Asce
     +| Iasio
 +| extTools
 +| bin
 +| lib
   +| python
   +| ExtTools
 +| classes
 +| config
 +| logs
```

`src` contains the sources of the module in one folder for each supported programming language. Python scripts go in the `python` folder while python modules go in a sub folder of the `python` folder itself.

The `resources` folder contains files to be added to the jar, like xml configuration files. The path the have in `resources` is preserved in the jar file.

`build.xml` is the user provided script for the building. It basically is a customization of the build script provided by the &brvbar;AS.

`test` folders contain source for testing and a [[configuration database|ConfigurationDatabase]] to be used for testing.

`exttools` contains external tools and libraries to be instaled in th eintegration area. They are redistributable third-party jars like junit or slf4j.

The jar generated by the building of the sources in `src` and `test` are installed in the `lib` folder of the module together with the resources, if any, and the java and scala source files. External tools are instead installed in `lib/ExtTools` to be easily identifiable and distinguished by the jars provided by the &brvbar;AS.

Python scripts are installed in the `bin` folder while python modules are installed in `lib/python`.

The `classes` folder contains `*.class` files generated while building the java and scala sources. They can be removed after the building because the classpath is generated from the jars in the `lib` folder only.

`config` contains configuration files while `log` is used to save log files generated at run-time.

## User provided `build.xml`

The user provides a customized `build.xml` for ant in the `src` and `test` folders of the module. It must include the &brvbar;AS build script, `CommonAnt.xml`. That script is fully documented and we kindly suggest to go through its targets to fully understand the building process. The main concept is that all the source files that are in the `src` or `test` folder will be built and finally installed into `lib` and `bin`. At run time only the files in `lib` and `bin` will be used. Customization is done by setting ant properties:

| Property name | Value | Effect | Example |
| ------------- | ----- | ------ | ------- |
| jarName | The name of the jar to build | Build the java and scala sources into the jar with the passed name | JarName=test.jar |
| exttools | Comma separated jars | Install the jars with the given names from `exttools` | exttools=tools.jar,log.jar |

There is no property for python scripts and modules as they are automatically recognized and properly installed by `CommonAnt.xml`.
Other scripts like bash scripts are not supported.


The ant targets are provided by the &brvbar;AS script: `build.xml` usually delegates its target to the ones in `CommonAnt.xml` providing a further customization if needed:
* clean delegates to ias.clean
* build delegates to ias.build
* install delegates to ias.install

The following example shows the easiste case where on customization is needed, taken from the `BasicTypes` module:

```xml
<project name="BasicTypes" default="build" basedir=".">
	
	<property environment="env" />
	<property name="iasRoot" value="${env.IAS_ROOT}" />
	<include file="${iasRoot}/config/CommonAnt.xml" as="ias" />
	
	<property name="jarName" value="ias-basic-types.jar"/>
	
	<target name="clean" depends="ias.clean" />
		
	<target name="install" depends="ias.install" />
	
	<target name="build" depends="ias.build" />
	
</project>
```