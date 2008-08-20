package info.flexmojos.install;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

public abstract class AbstractInstallMojo
    extends AbstractMojo
{

    private static final String ADOBE_GROUP_ID = "com.adobe.flex";

    private static final String COMPILER_GROUP_ID = ADOBE_GROUP_ID + ".compiler";

    private static final String FRAMEWORK_GROUP_ID = ADOBE_GROUP_ID + ".framework";

    private static final String[] JARS = new String[] { "jar" };

    private static final String[] RSLS = new String[] { "swf", "swz" };

    private static final String[] SWCS = new String[] { "swc" };

    /**
     * @component
     */
    private ArtifactFactory artifactFactory;

    /**
     * File location where targeted Flex SDK is located
     * 
     * @parameter expression="${flex.sdk.folder}"
     */
    private File sdkFolder;

    /**
     * Flex SDK version. Recommend pattern:
     * <ul>
     * Append -MPL suffix on MPL sdks
     * </ul>
     * <ul>
     * Append -FB3 suffix on Flexbuilder sdks
     * </ul>
     * <ul>
     * Append -LCDS suffix on LCDS sdks
     * </ul>
     * <BR>
     * Samples:
     * <ul>
     * 3.0.0.477
     * </ul>
     * <ul>
     * 3.0.0.477-MPL
     * </ul>
     * <ul>
     * 3.0.0.477-FB3
     * </ul>
     * <ul>
     * 3.0.0.477-LCDS
     * </ul>
     * 
     * @parameter expression="${version}"
     */
    private String version;

    public AbstractInstallMojo()
    {
        super();
    }

    /**
     * @param file
     * @param artifact
     */
    public abstract void installArtifact( File file, Artifact artifact );

    private Artifact createArtifact( File file, String groupId )
    {
        String artifactName = getArtifactName( file );
        String type = getExtension( file );
        Artifact artifact = artifactFactory.createArtifact( groupId, artifactName, version, "compile", type );
        return artifact;
    }

    private void installFlexFrameworkArtifacts()
        throws MojoExecutionException
    {
        getLog().info( "Installing flex framework swcs" );
        Collection<Artifact> swcArtifacts = new ArrayList<Artifact>();

        File swcLibFolder = new File( sdkFolder, "frameworks/libs" );
        Collection<File> swcs = listFiles( swcLibFolder, SWCS, true );
        for ( File swc : swcs )
        {
            Artifact artifact = createArtifact( swc, FRAMEWORK_GROUP_ID );
            swcArtifacts.add( artifact );
            installArtifact( swc, artifact );
            Artifact pomArtifact = createPomArtifact( swc, FRAMEWORK_GROUP_ID );
            generatePom( pomArtifact );
        }

        installResourceBundleArtifacts( swcArtifacts );
        installRslArtifacts();

        Collection<Artifact> flexArtifacts = filter( swcArtifacts, null, new String[] { "air*" } );
        Artifact flexSdk =
            artifactFactory.createArtifact( FRAMEWORK_GROUP_ID, "flex-framework", version, "compile", "pom" );
        generatePom( flexSdk, flexArtifacts );

        Collection<Artifact> airArtifacts = filter( swcArtifacts, null, new String[] { "player*" } );
        Artifact airSdk =
            artifactFactory.createArtifact( FRAMEWORK_GROUP_ID, "air-framework", version, "compile", "pom" );
        generatePom( airSdk, airArtifacts );

    }

    private Collection<Artifact> filter( Collection<Artifact> swcArtifacts, String[] include, String[] exclude )
    {
        if ( include == null )
        {
            include = new String[] { "*" };
        }

        if ( exclude == null )
        {
            exclude = new String[0];
        }

        List<Artifact> filtered = new ArrayList<Artifact>();
        for ( Artifact artifact : swcArtifacts )
        {
            for ( String wildcard : include )
            {
                if ( FilenameUtils.wildcardMatch( artifact.getArtifactId(), wildcard ) )
                {
                    filtered.add( artifact );
                    break;
                }
            }
        }

        for ( int i = filtered.size() - 1; i >= 0; i-- )
        {

            Artifact artifact = filtered.get( i );

            for ( String wildcard : exclude )
            {
                if ( FilenameUtils.wildcardMatch( artifact.getArtifactId(), wildcard ) )
                {
                    filtered.remove( artifact );
                    break;
                }
            }
        }
        return filtered;
    }

    private void installCompilerArtifacts()
        throws MojoExecutionException
    {
        getLog().info( "Installing flex compiler jars" );
        Set<Artifact> javaArtifacts = new HashSet<Artifact>();

        File libFolder = new File( sdkFolder, "lib" );
        if ( !libFolder.exists() )
        {
            throw new MojoExecutionException( "Java lib folder not fould: " + libFolder.getAbsolutePath() );
        }

        Collection<File> jars = listFiles( libFolder, JARS, false );

        for ( File jar : jars )
        {
            Artifact artifact = createArtifact( jar, COMPILER_GROUP_ID );
            javaArtifacts.add( artifact );
            installArtifact( jar, artifact );
            Artifact pomArtifact = createPomArtifact( jar, COMPILER_GROUP_ID );
            generatePom( pomArtifact );
        }

        Artifact flexSdkLibs = artifactFactory.createArtifact( ADOBE_GROUP_ID, "compiler", version, "compile", "pom" );
        generatePom( flexSdkLibs, javaArtifacts );
    }

    private Artifact createPomArtifact( File file, String groupId )
    {
        String artifactName = getArtifactName( file );
        String type = "pom";
        Artifact artifact = artifactFactory.createArtifact( groupId, artifactName, version, "compile", type );
        return artifact;
    }

    private void installResourceBundleArtifacts( Collection<Artifact> swcArtifacts )
        throws MojoExecutionException
    {
        getLog().info( "Installing flex-sdk locale swcs" );
        File swcLocalesFolder = new File( sdkFolder, "frameworks/locale" );

        // create resource-bundle beacon
        installResourceBundleBeacon( swcLocalesFolder, swcArtifacts );

        File[] locales = swcLocalesFolder.listFiles();
        for ( File localeFolder : locales )
        {
            Collection<File> localeSwcs = listFiles( localeFolder, SWCS, true );
            for ( File localeSwc : localeSwcs )
            {
                String artifactName = getResourceName( localeSwc );
                Artifact artifact =
                    artifactFactory.createArtifactWithClassifier( FRAMEWORK_GROUP_ID, artifactName, version, "rb.swc",
                                                                  localeFolder.getName() );
                installArtifact( localeSwc, artifact );
            }
        }
    }

    private void installResourceBundleBeacon( File swcLocalesFolder, Collection<Artifact> flexArtifacts )
        throws MojoExecutionException
    {
        Collection<File> localizedSwcs = listFiles( swcLocalesFolder, SWCS, true );
        Set<String> localizedSwcsNames = new HashSet<String>();
        for ( File localizedSwc : localizedSwcs )
        {
            String name = getResourceName( localizedSwc );
            localizedSwcsNames.add( name );
        }

        for ( String swcName : localizedSwcsNames )
        {
            Artifact artifact =
                artifactFactory.createArtifactWithClassifier( FRAMEWORK_GROUP_ID, swcName, version, "rb.swc", "" );
            File tempFile;
            try
            {
                tempFile = File.createTempFile( swcName, ".swc" );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Error beacon locale SWC " + e.getMessage(), e );
            }
            tempFile.deleteOnExit();
            installArtifact( tempFile, artifact );
            flexArtifacts.add( artifact );
        }
    }

    private void installRslArtifacts()
        throws MojoExecutionException
    {
        File rslsFolder = new File( sdkFolder, "frameworks/rsls" );
        if ( rslsFolder.exists() )
        {
            getLog().info( "Installing flex-sdk rsls" );
            Collection<File> rsls = listFiles( rslsFolder, RSLS, true );
            for ( File rsl : rsls )
            {
                String artifactName = getResourceName( rsl );
                String type = getExtension( rsl );
                Artifact artifact =
                    artifactFactory.createArtifactWithClassifier( FRAMEWORK_GROUP_ID, artifactName, version, type, "" );
                installArtifact( rsl, artifact );
            }
        }
        else
        {
            getLog().warn( "Rsls folder not found: " + rslsFolder );
        }
    }

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( sdkFolder == null )
        {
            throw new MojoExecutionException( "Flex SDK folder not defined." );
        }

        if ( !sdkFolder.exists() || !sdkFolder.isDirectory() )
        {
            throw new MojoExecutionException( "Flex SDK folder not found: " + sdkFolder.getAbsolutePath() );
        }

        installCompilerArtifacts();
        installAsdocTemplateArtifact();

        installFlexFrameworkArtifacts();

    }

    private void installAsdocTemplateArtifact()
    {
        // <groupId>com.adobe.flex</groupId>
        // <artifactId>asdoc</artifactId>
        // <classifier>template</classifier>
        // <type>zip</type>
        // TODO
    }

    private void generatePom( Artifact artifact )
        throws MojoExecutionException
    {
        generatePom( artifact, new HashSet<Artifact>() );
    }

    private void generatePom( Artifact artifact, Collection<Artifact> artifacts )
        throws MojoExecutionException
    {
        Model model = new Model();
        model.setModelVersion( "4.0.0" );
        model.setGroupId( artifact.getGroupId() );
        model.setArtifactId( artifact.getArtifactId() );
        model.setVersion( artifact.getVersion() );
        model.setPackaging( artifact.getType() );
        model.setDescription( "POM was created from flex-mojos:install-sdk" );

        for ( Artifact artifactDependency : artifacts )
        {
            Dependency dep = new Dependency();
            dep.setGroupId( artifactDependency.getGroupId() );
            dep.setArtifactId( artifactDependency.getArtifactId() );
            dep.setVersion( artifactDependency.getVersion() );
            dep.setType( artifactDependency.getType() );
            model.addDependency( dep );
        }

        try
        {
            File tempFile = File.createTempFile( artifact.getArtifactId(), ".pom" );
            tempFile.deleteOnExit();

            FileWriter fw = new FileWriter( tempFile );
            tempFile.deleteOnExit();
            new MavenXpp3Writer().write( fw, model );
            fw.flush();
            fw.close();
            installArtifact( tempFile, artifact );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error generating pom file: " + e.getMessage(), e );
        }

    }

    private String getArtifactName( File file )
    {
        String name = file.getName();
        name = name.substring( 0, name.lastIndexOf( '.' ) );
        return name;
    }

    private String getExtension( File file )
    {
        String name = file.getName();
        name = name.substring( name.lastIndexOf( '.' ) + 1 );
        return name;
    }

    private String getResourceName( File file )
    {
        String artifactName = getArtifactName( file );
        artifactName = artifactName.substring( 0, artifactName.lastIndexOf( '_' ) );
        return artifactName;
    }

    @SuppressWarnings( "unchecked" )
    private Collection<File> listFiles( File folder, String[] extensions, boolean recusive )
    {
        // Just a facade to avoid unchecked warnings
        return FileUtils.listFiles( folder, extensions, recusive );
    }

}