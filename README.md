# Introduction

This Maven plugin helps when working with mixed Java/CUDA projects (such as those powered by JCUDA) by compiling any CUDA kernel source files (ie those ending in `.cu` or `.ptx`) found in the project sources directory. The outputs will then be included in the jar file. The plugin will check modification times and avoid re-compiling the sources if it is not necessary. Compilation is multi-threaded. Supports Linux and Windows. (Not tested on Mac OS).

# Installation

```
git clone https://github.com/almson/cuda-compiler-plugin.git
cd cuda-compiler-plugin
mvn install
```

# Usage

In your `pom.xml` add:

        <plugin>
            <groupId>com.almson</groupId>
            <artifactId>cuda-compiler-plugin</artifactId>
            <version>1.1.1</version>
            <executions>
                <execution>
                    <id>fatbin</id>
                    <goals>
                        <goal>compile</goal>
                    </goals>
                    <configuration>
                        <nvccOptions>-fatbin -restrict -use_fast_math -maxrregcount=32 -Xptxas=-v -gencode=arch=compute_20,code="compute_20,sm_21,sm_30,sm_35,sm_50,sm_52,sm_53"</nvccOptions>
                    </configuration>
                </execution>
            </executions>
        </plugin>

Customize the option `nvccOptions` according to your needs. You can build ptx, cubin, and fatbin outputs.

If you're using JCUDA and you have a Java wrapper class `src/main/java/MyClassThatCallsCuda.java` and its kernels are in `src/main/java/MyClassThatCallsCuda.cu`, you can load these kernels with:

        Class currentClass = new Object(){}.getClass().getEnclosingClass(); // Trick to get class in static method
        String fatbinPath = currentClass.getName().replace('.', '/') + ".fatbin";
        try( InputStream ptx = ClassLoader.getSystemResourceAsStream(fatbinPath) ) {
            cuModuleLoadData(module, IOUtils.toByteArray(ptx));
        }

# Options

The plugin supports a number of options. It hasn't been tested with all possible option combinations, but it's a start in case your needs are different from mine. Consult the source code and feel free to contribute pull requests.

  - `sourceBaseDirectory` - Path to search for CUDA kernel sources. Default: `${basedir}/src/main/java`
  - `sourceFilenameExtensions` - A regular expression defining file extensions that are recognized as CUDA kernel sources. Default: `cu|ptx`
  - `outputBaseDirectory` - Base path to save outputs. Each output will be written to the same path as the source file, relative to this directory. Default: `${project.build.outputDirectory}`
  - `nvccOptions` - Custom options to pass to nvcc. Default: `-ptx`