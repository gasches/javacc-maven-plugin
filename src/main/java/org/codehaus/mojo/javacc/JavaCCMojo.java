package org.codehaus.mojo.javacc;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file 
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations 
 * under the License.
 */

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.util.FileUtils;

/**
 * Parses a JavaCC grammar file (<code>*.jj</code>) and transforms it to Java source files. Detailed information
 * about the JavaCC options can be found on the <a href="https://javacc.dev.java.net/">JavaCC website</a>.
 * 
 * @goal javacc
 * @phase generate-sources
 * @since 2.0
 * @author jruiz@exist.com
 * @author jesse <jesse.mcconnell@gmail.com>
 * @version $Id$
 */
public class JavaCCMojo
    extends AbstractJavaCCMojo
{

    /**
     * Package into which the generated classes will be put. Note that this will also be used to create the directory
     * structure where sources will be generated. Defaults to the package name specified in a grammar file.
     * 
     * @parameter expression="${packageName}"
     * @deprecated As of version 2.4 because the plugin extracts the package name from each grammar file.
     */
    private String packageName;

    /**
     * The directory where the JavaCC grammar files (<code>*.jj</code>) are located.
     * 
     * @parameter expression="${sourceDirectory}" default-value="${basedir}/src/main/javacc"
     */
    private File sourceDirectory;

    /**
     * The directory where the output Java files will be located.
     * 
     * @parameter expression="${outputDirectory}" default-value="${project.build.directory}/generated-sources/javacc"
     */
    private File outputDirectory;

    /**
     * The granularity in milliseconds of the last modification date for testing whether a source needs recompilation.
     * 
     * @parameter expression="${lastModGranularityMs}" default-value="0"
     */
    private int staleMillis;

    /**
     * A set of Ant-like inclusion patterns used to select files from the source directory for processing. By default,
     * the patterns <code>**&#47;*.jj</code> and <code>**&#47;*.JJ</code> are used to select grammar files.
     * 
     * @parameter
     */
    private String[] includes;

    /**
     * A set of Ant-like exclusion patterns used to prevent certain files from being processed. By default, this set if
     * empty such that no files are excluded.
     * 
     * @parameter
     */
    private String[] excludes;

    /**
     * {@inheritDoc}
     */
    protected File getSourceDirectory()
    {
        return this.sourceDirectory;
    }

    /**
     * {@inheritDoc}
     */
    protected String[] getIncludes()
    {
        if ( this.includes != null )
        {
            return this.includes;
        }
        else
        {
            return new String[] { "**/*.jj", "**/*.JJ" };
        }
    }

    /**
     * {@inheritDoc}
     */
    protected String[] getExcludes()
    {
        return this.excludes;
    }

    /**
     * {@inheritDoc}
     */
    protected File getOutputDirectory()
    {
        return this.outputDirectory;
    }

    /**
     * {@inheritDoc}
     */
    protected int getStaleMillis()
    {
        return this.staleMillis;
    }

    /**
     * {@inheritDoc}
     */
    protected String getParserPackage()
    {
        return this.packageName;
    }

    /**
     * {@inheritDoc}
     */
    protected void processGrammar( GrammarInfo grammarInfo )
        throws MojoExecutionException, MojoFailureException
    {
        File jjFile = grammarInfo.getGrammarFile();
        File parserDirectory = new File( getOutputDirectory(), grammarInfo.getParserDirectory() );

        // Copy all .java files from sourceDirectory to outputDirectory, in
        // order to prevent regeneration of customized Token.java or similar
        try
        {
            FileUtils.copyDirectory( jjFile.getParentFile(), parserDirectory, "*.java", "*.jj,*.JJ" );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to copy custom source files to output directory:"
                + jjFile.getParent() + " -> " + parserDirectory, e );
        }

        // generate parser file
        JavaCC javacc = newJavaCC();
        javacc.setInputFile( jjFile );
        javacc.setOutputDirectory( parserDirectory );
        javacc.run();
    }

}
