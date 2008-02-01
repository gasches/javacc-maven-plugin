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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.javacc.jjtree.JJTree;

/**
 * Parses a JJTree grammar file (<code>*.jjt</code>) and transforms it to Java source files and a JavaCC grammar
 * file. Please see the <a href="https://javacc.dev.java.net/doc/JJTree.html">JJTree Reference Documentation</a> for
 * more information.
 * 
 * @goal jjtree
 * @phase generate-sources
 * @author jesse <jesse.mcconnell@gmail.com>
 * @version $Id$
 */
public class JJTreeMojo
    extends AbstractMojo
{

    /**
     * The Java version for which to generate source code. Default value is <code>"1.4"</code>.
     * 
     * @parameter expression="${jdkVersion}"
     * @since 2.4
     */
    private String jdkVersion;

    /**
     * A flag whether to generate sample implementations for <code>SimpleNode</code> and any other nodes used in the
     * grammar. Default value is <code>true</code>.
     * 
     * @parameter expression="${buildNodeFiles}"
     */
    private Boolean buildNodeFiles;

    /**
     * A flag whether to generate a multi mode parse tree or a single mode parse tree. Default value is
     * <code>false</code>.
     * 
     * @parameter expression="${multi}"
     */
    private Boolean multi;

    /**
     * A flag whether to make each non-decorated production void instead of an indefinite node. Default value is
     * <code>false</code>.
     * 
     * @parameter expression="${nodeDefaultVoid}"
     */
    private Boolean nodeDefaultVoid;

    /**
     * The name of a custom factory class to create <code>Node</code> objects. Default value is <code>""</code>.
     * 
     * @parameter expression="${nodeFactory}"
     */
    private Boolean nodeFactory;

    /**
     * The package to generate the node classes into. Default value is <code>""</code> meaning to use the package of
     * the corresponding parser.
     * 
     * @parameter expression="${nodePackage}"
     */
    private String nodePackage;

    /**
     * The prefix used to construct node class names from node identifiers in multi mode. Default value is
     * <code>"AST"</code>.
     * 
     * @parameter expression="${nodePrefix}"
     */
    private String nodePrefix;

    /**
     * A flag whether user-defined parser methods should be called on entry and exit of every node scope. Default value
     * is <code>false</code>.
     * 
     * @parameter expression="${nodeScopeHook}"
     */
    private Boolean nodeScopeHook;

    /**
     * A flag whether the node construction routines need an additional method parameter to receive the parser object.
     * Default value is <code>false</code>.
     * 
     * @parameter expression="${nodeUsesParser}"
     */
    private Boolean nodeUsesParser;

    /**
     * A flag whether to generate code for a static parser. Note that this setting must match the corresponding option
     * for the <code>javacc</code> mojo. Default value is <code>true</code>.
     * 
     * @parameter expression="${isStatic}" alias="staticOption"
     */
    private Boolean isStatic;

    /**
     * A flag whether to insert a <code>jjtAccept()</code> method in the node classes and to generate a visitor
     * implementation with an entry for every node type used in the grammar. Default value is <code>false</code>.
     * 
     * @parameter expression="${visitor}"
     */
    private Boolean visitor;

    /**
     * The name of an exception class to include in the signature of the generated <code>jjtAccept()</code> and
     * <code>visit()</code> methods. Default value is <code>""</code>.
     * 
     * @parameter expression="${visitorException}"
     */
    private String visitorException;

    /**
     * Directory where the input JJTree files (<code>*.jjt</code>) are located.
     * 
     * @parameter expression="${sourceDirectory}" default-value="${basedir}/src/main/jjtree"
     */
    private File sourceDirectory;

    /**
     * Directory where the output Java files for the node classes and the JavaCC grammar file will be located.
     * 
     * @parameter expression="${outputDirectory}" default-value="${project.build.directory}/generated-sources/jjtree"
     */
    private File outputDirectory;

    /**
     * The directory to store the processed input files for later detection of stale sources.
     * 
     * @parameter expression="${timestampDirectory}"
     *            default-value="${project.build.directory}/generated-sources/jjtree-timestamp"
     */
    private File timestampDirectory;

    /**
     * The granularity in milliseconds of the last modification date for testing whether a source needs recompilation.
     * 
     * @parameter expression="${lastModGranularityMs}" default-value="0"
     */
    private int staleMillis;

    /**
     * A set of Ant-like inclusion patterns for the compiler.
     * 
     * @parameter
     */
    private Set includes;

    /**
     * A set of Ant-like exclusion patterns for the compiler.
     * 
     * @parameter
     */
    private Set excludes;

    /**
     * Contains the package name to use for the generated code.
     */
    private String packageName;

    /**
     * Execute the JJTree preprocessor.
     * 
     * @throws MojoExecutionException If the compilation fails.
     */
    public void execute()
        throws MojoExecutionException
    {
        if ( !this.sourceDirectory.isDirectory() )
        {
            getLog().info( "Skipping non-existing source directory: " + this.sourceDirectory );
            return;
        }

        if ( this.nodePackage != null )
        {
            this.packageName = StringUtils.replace( this.nodePackage, '.', File.separatorChar );
        }

        if ( !this.outputDirectory.exists() )
        {
            this.outputDirectory.mkdirs();
        }

        if ( !this.timestampDirectory.exists() )
        {
            this.timestampDirectory.mkdirs();
        }

        if ( this.includes == null )
        {
            this.includes = Collections.singleton( "**/*" );
        }

        if ( this.excludes == null )
        {
            this.excludes = Collections.EMPTY_SET;
        }

        Set staleGrammars = computeStaleGrammars();

        if ( staleGrammars.isEmpty() )
        {
            getLog().info( "Skipping - all grammars up to date: " + this.sourceDirectory );
        }
        else
        {
            for ( Iterator i = staleGrammars.iterator(); i.hasNext(); )
            {
                File jjtFile = (File) i.next();
                File outputDir = getOutputDirectory( jjtFile );
                try
                {
                    JJTree jjtree = new JJTree();
                    int exitCode = jjtree.main( generateArgumentList( jjtFile, outputDir ) );
                    if ( exitCode != 0 )
                    {
                        throw new MojoExecutionException( "JJTree reported non-zero exit code: " + exitCode );
                    }

                    URI relativeURI = this.sourceDirectory.toURI().relativize( jjtFile.toURI() );
                    File timestampFile = new File( this.timestampDirectory.toURI().resolve( relativeURI ) );
                    FileUtils.copyFile( jjtFile, timestampFile );
                }
                catch ( Exception e )
                {
                    throw new MojoExecutionException( "JJTree execution failed", e );
                }
            }
        }
    }

    /**
     * Get the output directory for the JavaCC files.
     * 
     * @param jjtFile The JJTree input file.
     * @return The directory that will contain the generated code.
     * @throws MojoExecutionException If there is a problem getting the package name.
     */
    private File getOutputDirectory( File jjtFile )
        throws MojoExecutionException
    {
        try
        {
            GrammarInfo info = new GrammarInfo( jjtFile, this.packageName );
            return new File( this.outputDirectory, info.getPackageDirectory().getPath() );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to retrieve package name from grammar file", e );
        }
    }

    /**
     * Create the argument list to be passed to JJTree on the command line.
     * 
     * @param jjtFile The path of the file to compile.
     * @param outputDir The output directory for the generated Java files. This path should already contain the package
     *            hierarchy.
     * @return A string array that represents the arguments to use for JJTree.
     */
    private String[] generateArgumentList( File jjtFile, File outputDir )
    {
        List argsList = new ArrayList();

        if ( this.jdkVersion != null )
        {
            argsList.add( "-JDK_VERSION=" + this.jdkVersion );
        }

        if ( this.buildNodeFiles != null )
        {
            argsList.add( "-BUILD_NODE_FILES=" + this.buildNodeFiles );
        }

        if ( this.multi != null )
        {
            argsList.add( "-MULTI=" + this.multi );
        }

        if ( this.nodeDefaultVoid != null )
        {
            argsList.add( "-NODE_DEFAULT_VOID=" + this.nodeDefaultVoid );
        }

        if ( this.nodeFactory != null )
        {
            argsList.add( "-NODE_FACTORY=" + this.nodeFactory );
        }

        if ( this.nodePackage != null )
        {
            argsList.add( "-NODE_PACKAGE=" + this.nodePackage );
        }

        if ( this.nodePrefix != null )
        {
            argsList.add( "-NODE_PREFIX=" + this.nodePrefix );
        }

        if ( this.nodeScopeHook != null )
        {
            argsList.add( "-NODE_SCOPE_HOOK=" + this.nodeScopeHook );
        }

        if ( this.nodeUsesParser != null )
        {
            argsList.add( "-NODE_USES_PARSER=" + this.nodeUsesParser );
        }

        if ( this.isStatic != null )
        {
            argsList.add( "-STATIC=" + this.isStatic );
        }

        if ( this.visitor != null )
        {
            argsList.add( "-VISITOR=" + this.visitor );
        }

        if ( this.visitorException != null )
        {
            argsList.add( "-VISITOR_EXCEPTION=\'" + this.visitorException + "\'" );
        }

        argsList.add( "-OUTPUT_DIRECTORY=" + outputDir );

        argsList.add( jjtFile.getAbsolutePath() );

        getLog().debug( "argslist: " + argsList.toString() );

        return (String[]) argsList.toArray( new String[argsList.size()] );
    }

    /**
     * @return A set of <code>File</code> objects to compile.
     * @throws MojoExecutionException If it fails.
     */
    private Set computeStaleGrammars()
        throws MojoExecutionException
    {
        SuffixMapping mapping = new SuffixMapping( ".jjt", ".jjt" );
        SuffixMapping mappingCAP = new SuffixMapping( ".JJT", ".JJT" );

        SourceInclusionScanner scanner = new StaleSourceScanner( this.staleMillis, this.includes, this.excludes );

        scanner.addSourceMapping( mapping );
        scanner.addSourceMapping( mappingCAP );

        Set staleSources = new HashSet();

        try
        {
            staleSources.addAll( scanner.getIncludedSources( this.sourceDirectory, this.timestampDirectory ) );
        }
        catch ( InclusionScanException e )
        {
            throw new MojoExecutionException( "Error scanning source root: \'" + this.sourceDirectory
                + "\' for stale grammars to reprocess.", e );
        }

        return staleSources;
    }

}
