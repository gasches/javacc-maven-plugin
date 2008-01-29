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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * <a href="https://javacc.dev.java.net/doc/JJDoc.html">JJDoc</a> takes a JavaCC parser specification and produces
 * documentation for the BNF grammar. This mojo will search the source directory for all <code>*.jj</code> files and
 * run JJDoc once for each file it finds. Each of these output files, along with an <code>index.html</code> file will
 * be placed in the site directory (<code>target/site/jjdoc</code>), and a link will be created in the "Project
 * Reports" menu of the generated site.
 * 
 * @goal jjdoc
 * @execute phase=generate-sources
 * @author <a href="mailto:pgier@redhat.com">Paul Gier</a>
 * @version $Id$
 * @see <a href="https://javacc.dev.java.net/doc/JJDoc.html">JJDoc Documentation</a>
 */
public class JJDocMojo
    extends AbstractMavenReport
{

    /**
     * The jjdoc classname that is used to call JJDoc from the command line.
     */
    private static final String JJDOC_CLASSNAME = "jjdoc";

    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------

    /**
     * The current Maven project.
     * 
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The site renderer.
     * 
     * @component
     */
    private Renderer siteRenderer;

    /**
     * The plugin dependencies.
     * 
     * @parameter expression="${plugin.artifacts}"
     * @required
     * @readonly
     */
    private List pluginArtifacts;

    /**
     * Directories where the JavaCC grammar files (<code>*.jj</code>) are located. By default, the directories
     * <code>${basedir}/src/main/javacc</code> and <code>${project.build.directory}/generated-sources/jjtree</code>
     * are scanned for grammar files to document.
     * 
     * @parameter
     */
    private File[] sourceDirectories;

    /**
     * The default source directory for grammar files.
     * 
     * @parameter expression="${basedir}/src/main/javacc"
     * @readonly
     */
    private File defaultSourceDirectory;

    /**
     * The default source directory for generated grammar files.
     * 
     * @parameter expression="${project.build.directory}/generated-sources/jjtree"
     * @readonly
     */
    private File defaultGeneratedSourceDirectory;

    /**
     * The relative path of the JJDoc reports in the output directory. This path will be appended to the output
     * directory.
     * 
     * @parameter default-value="jjdoc";
     */
    private String jjdocDirectory;

    /**
     * The destination directory where JJDoc saves the generated documentation files. Note that this parameter is only
     * relevant if the goal is run from the command line or from the default build lifecycle. If the goal is run
     * indirectly as part of a site generation, the output directory configured in the Maven Site Plugin is used
     * instead.
     * 
     * @parameter expression="${project.reporting.outputDirectory}"
     * @required
     */
    private File outputDirectory;

    /**
     * A flag to specify the output format for the generated documentation. If set to <code>true</code>, JJDoc will
     * generate a plain text description of the BNF. Some formatting is done via tab characters, but the intention is to
     * leave it as plain as possible. Specifying <code>false</code> causes JJDoc to generate a hyperlinked HTML
     * document.
     * 
     * @parameter expression="${text}" default-value=false
     */
    private boolean text;

    /**
     * This option controls the structure of the generated HTML output. If set to <code>true</code>, a single HTML
     * table for the entire BNF is generated. Setting it to <code>false</code> will produce one table for every
     * production in the grammar.
     * 
     * @parameter expression="${oneTable}" default-value=true
     */
    private boolean oneTable;

    /**
     * Get the maven project.
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#getProject()
     * @return The current Maven project.
     */
    protected MavenProject getProject()
    {
        return project;
    }

    /**
     * Get the site renderer.
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#getSiteRenderer()
     * @return The site renderer.
     */
    protected Renderer getSiteRenderer()
    {
        return siteRenderer;
    }

    /**
     * Get the output directory of the report if run directly from the command line.
     * 
     * @see org.apache.maven.reporting.AbstractMavenReport#getOutputDirectory()
     * @return The report output directory.
     */
    protected String getOutputDirectory()
    {
        return outputDirectory.toString();
    }

    /**
     * Get the output directory of the JJDoc files, i.e. the sub directory in the report output directory as specified
     * by the {@link #jjdocDirectory} parameter.
     * 
     * @return The report output directory of the JJDoc files.
     */
    private File getJJDocOutputDirectory()
    {
        return new File( getReportOutputDirectory(), jjdocDirectory );
    }

    /**
     * Get the source directories that should be scanned for grammar files.
     * 
     * @return The source directories that should be scanned for grammar files.
     */
    private List getSourceDirectories()
    {
        List directories = new ArrayList();
        if ( sourceDirectories != null && sourceDirectories.length > 0 )
        {
            directories.addAll( Arrays.asList( sourceDirectories ) );
        }
        else
        {
            if ( defaultSourceDirectory != null )
            {
                directories.add( defaultSourceDirectory );
            }
            if ( defaultGeneratedSourceDirectory != null )
            {
                directories.add( defaultGeneratedSourceDirectory );
            }
        }
        return directories;
    }

    // ----------------------------------------------------------------------
    // public methods
    // ----------------------------------------------------------------------

    /**
     * @see org.apache.maven.reporting.MavenReport#getName(java.util.Locale)
     * @param locale The locale to use for this report.
     * @return The name of this report.
     */
    public String getName( Locale locale )
    {
        return this.getBundle( locale ).getString( "report.jjdoc.name" );
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#getDescription(java.util.Locale)
     * @param locale The locale to use for this report.
     * @return The description of this report.
     */
    public String getDescription( Locale locale )
    {
        return this.getBundle( locale ).getString( "report.jjdoc.short.description" );
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#getOutputName()
     * @return The name of the main report file.
     */
    public String getOutputName()
    {
        return jjdocDirectory + "/index";
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#isExternalReport()
     * @return Determines if the report is using a sink. This always returns false.
     */
    public boolean isExternalReport()
    {
        return false;
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#canGenerateReport()
     * @return Always returns true because this mojo can generate a report.
     */
    public boolean canGenerateReport()
    {
        return true;
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#getCategoryName()
     * @return The category where this report is located.
     */
    public String getCategoryName()
    {
        return CATEGORY_PROJECT_REPORTS;
    }

    /**
     * Run the actual report.
     * 
     * @param locale The locale to use for this report.
     */
    public void executeReport( Locale locale )
    {
        Sink sink = getSink();

        createReportHeader( getBundle( locale ), sink );

        try
        {
            for ( Iterator it = getSourceDirectories().iterator(); it.hasNext(); )
            {
                File sourceDirectory = (File) it.next();
                if ( !sourceDirectory.isDirectory() )
                {
                    getLog().debug( "Skipping non-existing source directory: " + sourceDirectory );
                    continue;
                }
                else
                {
                    getLog().debug( "Scanning source directory: " + sourceDirectory );
                }

                Set grammarFiles = scanForGrammarFiles( sourceDirectory );

                for ( Iterator i = grammarFiles.iterator(); i.hasNext(); )
                {
                    File grammarFile = (File) i.next();

                    URI relativeOutputFileURI = sourceDirectory.toURI().relativize( grammarFile.toURI() );
                    String relativeOutputFileName =
                        relativeOutputFileURI.toString().replaceAll( "(.jj|.JJ)$", getOutputFileExtension() );

                    File jjdocOutputFile = new File( getJJDocOutputDirectory(), relativeOutputFileName );
                    jjdocOutputFile.getParentFile().mkdirs();

                    String[] jjdocArgs = generateArgumentList( grammarFile, jjdocOutputFile );

                    // Fork jjdoc because of calls to System.exit().
                    forkJJDoc( jjdocArgs );

                    this.createReportLink( sink, sourceDirectory, grammarFile, relativeOutputFileName );
                }
            }
        }
        catch ( MojoExecutionException e )
        {
            e.printStackTrace();
        }

        createReportFooter( sink );
        sink.flush();
        sink.close();

    }

    /**
     * The JJDoc output file will have a <code>.html</code> or <code>.txt</code> extension depending on the value
     * of the parameter {@link #text}.
     * 
     * @return The file extension to be used for the JJDoc output files.
     */
    private String getOutputFileExtension()
    {
        if ( text )
        {
            return ".txt";
        }
        else
        {
            return ".html";
        }
    }

    /**
     * Create the header and title for the HTML report page.
     * 
     * @param bundle The resource bundle with the text.
     * @param sink The sink for writing to the main report file.
     */
    private void createReportHeader( ResourceBundle bundle, Sink sink )
    {
        sink.head();
        sink.title();
        sink.text( bundle.getString( "report.jjdoc.title" ) );
        sink.title_();
        sink.head_();

        sink.body();

        sink.section1();
        sink.sectionTitle1();
        sink.text( bundle.getString( "report.jjdoc.title" ) );
        sink.sectionTitle1_();
        sink.text( bundle.getString( "report.jjdoc.description" ) );
        sink.section1_();

        sink.lineBreak();
        sink.table();
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( bundle.getString( "report.jjdoc.table.heading" ) );
        sink.tableHeaderCell_();
        sink.tableRow_();

    }

    /**
     * Create a table row containing a link to the JJDoc report for a grammar file.
     * 
     * @param sink The sink to write the report
     * @param sourceDirectory The source directory of the grammar file.
     * @param grammarFile The JavaCC grammar file.
     * @param linkPath The path to the JJDoc output.
     */
    private void createReportLink( Sink sink, File sourceDirectory, File grammarFile, String linkPath )
    {
        sink.tableRow();
        sink.tableCell();
        if ( linkPath.startsWith( "/" ) )
        {
            linkPath = linkPath.substring( 1 );
        }
        sink.link( linkPath );
        String grammarFileRelativePath = sourceDirectory.toURI().relativize( grammarFile.toURI() ).toString();
        if ( grammarFileRelativePath.startsWith( "/" ) )
        {
            grammarFileRelativePath = grammarFileRelativePath.substring( 1 );
        }
        sink.text( grammarFileRelativePath );
        sink.link_();
        sink.tableCell_();
        sink.tableRow_();
    }

    /**
     * Create the HTML footer for the report page.
     * 
     * @param sink The sink to write the HTML report page.
     */
    private void createReportFooter( Sink sink )
    {
        sink.table_();
        sink.body_();
    }

    /**
     * Generate the command line arguments for calling JJDoc.
     * 
     * @param javaccFile The grammar file to be documented.
     * @param outputFile The path to the report output.
     * @return An array of the parameters.
     */
    private String[] generateArgumentList( File javaccFile, File outputFile )
    {
        List argsList = new ArrayList();

        argsList.add( "-OUTPUT_FILE=" + outputFile );

        if ( this.text )
        {
            argsList.add( "-TEXT=true" );
        }

        if ( !this.oneTable )
        {
            argsList.add( "-ONE_TABLE=false" );
        }

        argsList.add( javaccFile.getPath() );

        return (String[]) argsList.toArray( new String[argsList.size()] );
    }

    /**
     * Searches the specified source directory to find grammar files that can be documented.
     * 
     * @param sourceDirectory The source directory to scan for grammar files.
     * @return A set of the JavaCC grammar files.
     * @throws MojoExecutionException If there is a problem while scanning for .jj files.
     */
    private Set scanForGrammarFiles( File sourceDirectory )
        throws MojoExecutionException
    {

        SuffixMapping mapping = new SuffixMapping( ".jj", getOutputFileExtension() );
        SuffixMapping mappingCAP = new SuffixMapping( ".JJ", getOutputFileExtension() );

        Set includes = new HashSet( Arrays.asList( new String[] { "**/*.jj", "**/*.JJ" } ) );
        Set excludes = Collections.EMPTY_SET;
        SourceInclusionScanner scanner = new SimpleSourceInclusionScanner( includes, excludes );

        scanner.addSourceMapping( mapping );
        scanner.addSourceMapping( mappingCAP );

        Set grammarFiles = new HashSet();

        try
        {
            grammarFiles.addAll( scanner.getIncludedSources( sourceDirectory, getJJDocOutputDirectory() ) );
        }
        catch ( InclusionScanException e )
        {
            throw new MojoExecutionException( "Error scanning source root: \'" + sourceDirectory
                + "\' for stale grammars to reprocess.", e );
        }

        return grammarFiles;

    }

    /**
     * Create a classpath that contains the JavaCC JAR file with JJDoc.
     * 
     * @return The classpath string.
     */
    private String createJJDocClasspath()
    {
        StringBuffer classpath = new StringBuffer();

        for ( Iterator i = pluginArtifacts.iterator(); i.hasNext(); )
        {
            Artifact artifact = (Artifact) i.next();
            if ( artifact.getArtifactId().contains( "javacc" ) )
            {
                try
                {
                    classpath.append( artifact.getFile().getCanonicalPath() );
                    classpath.append( File.pathSeparatorChar );
                }
                catch ( IOException e )
                {
                    getLog().warn( "Unable to get path to artifact: " + artifact.getFile(), e );
                }
            }
        }
        return classpath.toString();
    }

    /**
     * Runs JJDoc in a forked JVM. This must be done because of the calls to <code>System.exit()</code> in JJDoc.
     * 
     * @param jjdocArgs The arguments to pass to JJDoc.
     * @throws MojoExecutionException If there is a problem while running JJDoc.
     */
    private void forkJJDoc( String[] jjdocArgs )
        throws MojoExecutionException
    {
        Commandline cli = new Commandline();

        // use the same JVM as the one used to run Maven (the "java.home" one)
        String jvm = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
        cli.setExecutable( jvm );

        String[] jvmArgs = { "-cp", createJJDocClasspath(), JJDOC_CLASSNAME };

        cli.addArguments( jvmArgs );

        cli.addArguments( jjdocArgs );

        StreamConsumer out = new MojoLogStreamConsumer();
        StreamConsumer err = new MojoLogStreamConsumer( true );

        getLog().debug( "Forking Command Line: " );
        getLog().debug( cli.toString() );
        getLog().debug( "" );

        try
        {
            int returnCode = CommandLineUtils.executeCommandLine( cli, out, err );
            if ( returnCode != 0 )
            {
                throw new MojoExecutionException( "There were errors while generating the jjdoc" );
            }
        }
        catch ( CommandLineException e )
        {
            throw new MojoExecutionException( "Error while executing forked tests.", e );
        }

    }

    /**
     * Get the resource bundle for the report text.
     * 
     * @param locale The locale to use for this report.
     * @return The resource bundle.
     */
    private ResourceBundle getBundle( Locale locale )
    {
        return ResourceBundle.getBundle( "jjdoc-report", locale, this.getClass().getClassLoader() );
    }

    /**
     * Consume and log command line output from the JJDoc process.
     */
    class MojoLogStreamConsumer
        implements StreamConsumer
    {

        /**
         * The line prefix used by JJDoc to report errors.
         */
        private static final String ERROR_PREFIX = "Error: ";

        /**
         * The line prefix used by JJDoc to report warnings.
         */
        private static final String WARN_PREFIX = "Warning: ";

        /**
         * Determines if the stream consumer is being used for <code>System.out</code> or <code>System.err</code>.
         */
        private boolean err;

        /**
         * Default constructor with err set to false. All consumed lines will be logged at the debug level.
         */
        public MojoLogStreamConsumer()
        {
            this( false );
        }

        /**
         * Single param constructor.
         * 
         * @param error If set to true, all consumed lines will be logged at the info level.
         */
        public MojoLogStreamConsumer( boolean error )
        {
            this.err = error;
        }

        /**
         * Consume a line of text.
         * 
         * @param line The line to consume.
         */
        public void consumeLine( String line )
        {
            if ( line.startsWith( ERROR_PREFIX ) )
            {
                getLog().error( line.substring( ERROR_PREFIX.length() ) );
            }
            else if ( line.startsWith( WARN_PREFIX ) )
            {
                getLog().warn( line.substring( WARN_PREFIX.length() ) );
            }
            else if ( err )
            {
                getLog().error( line );
            }
            else
            {
                getLog().debug( line );
            }
        }
    }

}
