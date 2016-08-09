package com.almson.cuda.compiler.plugin;

import java.io.ByteArrayOutputStream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.codehaus.plexus.util.StringUtils;

@Mojo( name = "compile", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true )
public class CompileMojo
    extends AbstractMojo
{    
    @Parameter( defaultValue = "${basedir}/src/main/java", property = "sourceBaseDirectory" )
    private File sourceBaseDirectory;
    
    @Parameter( defaultValue = "cu|ptx", property = "sourceFilenameExtensions" )
    private String sourceFilenameExtensions;
    
    @Parameter( defaultValue = "${project.build.outputDirectory}", property = "outputBaseDirectory" )
    private File outputBaseDirectory;
    
    @Parameter( defaultValue = "-ptx", property = "nvccOptions" )
    private String nvccOptions;
    
    public void execute() throws MojoExecutionException
    {
        if( !sourceBaseDirectory.exists() )
            throw new MojoExecutionException("Input directory " + sourceBaseDirectory.getAbsolutePath() + " does not exist");
        
        Pattern compilationTypePattern = Pattern.compile("( |^|\\-)\\-(ptx|cubin|fatbin)( |$)");
        Matcher compilationTypeMatcher = compilationTypePattern.matcher(nvccOptions);
        String compilationType;
        
        if( compilationTypeMatcher.find() )
        {
            compilationType = compilationTypeMatcher.group(2);
            
            if( compilationTypeMatcher.find() ) throw new MojoExecutionException("Property nvccOptions must contain only one of -ptx, -cubin, or -fatbin");
        }
        else throw new MojoExecutionException("Property nvccOptions must contain one of -ptx, -cubin, or -fatbin");
        
        
        ExecutorService threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        final AtomicBoolean error = new AtomicBoolean(false);
        
        for(final File source : FileUtils.listFiles(sourceBaseDirectory, sourceFilenameExtensions.split("\\|"), true))
        {
            File outputDirectory = new File( outputBaseDirectory, sourceBaseDirectory.toPath().relativize(source.toPath().getParent()).toString() );
            outputDirectory.mkdirs();
            final File target = new File( outputDirectory, source.getName().replaceAll("\\.(" + sourceFilenameExtensions + ")$", "." + compilationType) );
            final Log log = super.getLog();
            
            if( !target.exists() ||
                target.lastModified() < source.lastModified() )
            {                
                threadPool.submit(new Runnable() {
                    public void run()
                    {
                        boolean myError = false;
                        StringBuilder myLog = new StringBuilder();
                        myLog.append("Compiling " + target.toString() + "\n");
                        
                        // Retarded commons-exec is retarded when it comes to quotes and spaces.
                        // No guarantees this will reliably work, but it's better than CommandLine.parse()
                        CommandLine command = new CommandLine("nvcc");
                        for(String argument : nvccOptions.split(" "))
                        {
                            if (System.getProperty("os.name").startsWith("Windows"))
                                argument = argument.replace("\"", "\\\"");
                            command.addArgument (argument, false);
                        }
                        command.addArgument(source.getAbsolutePath(), false);
                        command.addArgument("-o", false);
                        command.addArgument(target.getAbsolutePath(), false);
                        
                        OutputStream stdout = new ByteArrayOutputStream();
                        OutputStream stderr = new ByteArrayOutputStream();
                                                
                        ExecuteStreamHandler streamHandler = new PumpStreamHandler(stdout, stderr, null);
                        ExecuteWatchdog watchdog = new ExecuteWatchdog (300000);
                        
                        try
                        {
                            DefaultExecutor executor = new DefaultExecutor();
                            executor.setStreamHandler(streamHandler);
                            executor.setWatchdog(watchdog);

                            executor.execute(command);
                        
                            if( StringUtils.isNotBlank (stdout.toString()) )
                                myLog.append( stdout.toString().trim() + "\n");
                            if( StringUtils.isNotBlank (stderr.toString()) )
                                myLog.append( stderr.toString().trim() + "\n");
                        }
                        catch(ExecuteException e)
                        {
                            if (watchdog.killedProcess())
                                myLog.append("nvcc timed out after 5 minutes.\n");
                            else
                                myLog.append( stdout.toString() + stderr.toString() );                   
                            myError = true;
                        }
                        catch(IOException e)
                        {
                            myLog.append("nvcc not found. Include it in the path and try again.\n");                            
                            myError = true;
                        }
                        finally
                        {
                            try
                            {
                                streamHandler.stop();
                            }
                            catch (Exception e)
                            {
                                myLog.append ("StreamHandler could not be stopped.\n");
                                myError = true;
                            }
                        }
                        
                        if( myError )
                        {
                            log.error(myLog.toString().trim());
                            error.set(true);
                        }
                        else
                            log.info(myLog.toString().trim());
                    }
                });
            }
        }
        
        threadPool.shutdown();        
        try {
            threadPool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException ex) {
            throw new Error(ex);
        }
        
        if( error.get() )
            throw new MojoExecutionException("Compilation of cuda code failed");
    }
}
