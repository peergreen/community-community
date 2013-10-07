/**
 * Copyright 2013 Peergreen S.A.S.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.peergreen.community.maven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.Archiver;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.deployment.DeployRequest;
import org.sonatype.aether.deployment.DeployResult;
import org.sonatype.aether.deployment.DeploymentException;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.ArtifactRequest;
import org.sonatype.aether.resolution.ArtifactResolutionException;
import org.sonatype.aether.resolution.ArtifactResult;
import org.sonatype.aether.util.artifact.DefaultArtifact;
import org.sonatype.aether.util.artifact.SubArtifact;


/**
 * Plugin for deploying artifacts on the public community instance of our M2 repository.
 * @author Florent Benoit
 */
@Mojo(name = "release",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresProject = true,
        requiresDependencyCollection = ResolutionScope.RUNTIME)
public class ReleaseCommunityMojo extends AbstractMojo {

    @Component
    private MavenProject project;

    @Component(hint = "jar")
    private Archiver archiver;

    @Parameter(defaultValue = "true")
    private boolean addExpiration;

    @Parameter(defaultValue = "${project.build.directory}/jartransformer", readonly=true)
    private File jartransformerDir;

    @Parameter(defaultValue = "${project.build.directory}/generated.pom", readonly=true)
    private File generatedPomFile;

    @Parameter(defaultValue = "https://forge.peergreen.com/repository/content/repositories/releases")
    private String nexusPublicInstance;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "com.peergreen.community")
    private String communityGroupId;

    @Parameter
    private String communityArtifactId;

    @Parameter(required=true)
    private String communityVersion;

    /**
     * Current repository/network configuration of Maven.
     */
    @Parameter(defaultValue="${repositorySystemSession}", readonly=true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.jar")
    private File destFile;

    /**
     * The project's remote repositories.
     */
    @Parameter(defaultValue="${project.remoteProjectRepositories}", readonly=true)
    private List<RemoteRepository> remoteRepos;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        // get direct dependencies of this pom
        List<Dependency> dependencies = project.getDependencies();
        if (dependencies.size() != 1) {
            // Invalid dependencies
            throw new MojoExecutionException("We should have only one dependency. Found '" + dependencies + "'.");
        }

        // only one dependency, get the first one
        Dependency dependency = dependencies.get(0);

        // Collect data of this dependency
        String groupId = dependency.getGroupId();
        String artifactId = dependency.getArtifactId();
        String classifier = dependency.getClassifier();
        String type = dependency.getType();
        String version = dependency.getVersion();

        // create artifact and ask to resolve the artifact in order to get the
        // local file
        final Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, type, version);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepos);
        ArtifactResult result;
        // resolve artifact
        try {
            result = repositorySystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        getLog().info(
                "Resolved artifact " + artifact + " to " + result.getArtifact().getFile() + " from "
                        + result.getRepository());

        // well try to read the artifact model but the result is limited to sub
        // parameters :-/
        // ArtifactDescriptorRequest artifactDescriptorRequest = new
        // ArtifactDescriptorRequest();
        // artifactDescriptorRequest.setArtifact(result.getArtifact());
        // ArtifactDescriptorResult artifactDescriptorResult;
        // try {
        // artifactDescriptorResult =
        // repositorySystem.readArtifactDescriptor(repoSession,
        // artifactDescriptorRequest);
        // } catch (ArtifactDescriptorException e) {
        // throw new MojoExecutionException(e.getMessage(), e);
        // }
        // DefaultArtifactDescriptorReader.loadPom

        // Now, we needs to deploy this artifact with another name in the public
        // community repository
        DeployRequest deployRequest = new DeployRequest();

        if (communityArtifactId == null) {
            communityArtifactId = artifactId;
        }

        Artifact deployArtifact = new DefaultArtifact(communityGroupId, communityArtifactId, type, version);
        deployArtifact = deployArtifact.setVersion(communityVersion).setFile(result.getArtifact().getFile());

        // Generate the pom file
        Artifact pomArtifact = generatePom(deployArtifact);

        // Add artifact and the associated pom
        deployRequest.addArtifact(deployArtifact);
        deployRequest.addArtifact(pomArtifact);

        // Build the Peergreen community repository
        RemoteRepository communityRepository = new RemoteRepository("peergreen.community", "default",
                nexusPublicInstance);
        communityRepository.setAuthentication(repoSession.getAuthenticationSelector().getAuthentication(
                communityRepository));
        communityRepository.setProxy(repoSession.getProxySelector().getProxy(communityRepository));
        deployRequest.setRepository(communityRepository);
        DeployResult deployResult;
        try {
            deployResult = repositorySystem.deploy(repoSession, deployRequest);
        } catch (DeploymentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        // Change the project artifact to be the deployed artifact (and then
        // install it in the local repository too)
        project.setArtifact(RepositoryUtils.toArtifact(deployArtifact));
    }

    /**
     * Generates a new POM file based on the artifact that we want to deploy
     * @param deployArtifact the artifact to deploy
     * @return the POM artifact that is being deployed
     * @throws MojoExecutionException
     */
    protected Artifact generatePom(Artifact deployArtifact) throws MojoExecutionException {

        // create path to the pom file if needed
        generatedPomFile.getParentFile().mkdirs();

        // Create artifact
        Artifact pomArtifact = new SubArtifact(deployArtifact, "", "pom").setFile(generatedPomFile);

        // Generate the file
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(pomArtifact.getGroupId());
        model.setArtifactId(pomArtifact.getArtifactId());
        model.setVersion(pomArtifact.getVersion());
        model.setPackaging(deployArtifact.getExtension());
        model.setDescription("Peergreen Community Version");

        // use of a custom file writer used to write the PG HEADER license
        try (FileWriter fw = new FileHeaderWriter(generatedPomFile)) {
            try {
                new MavenXpp3Writer().write(fw, model);
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to generate the POM file", e);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to generate the POM file", e);

        }

        return pomArtifact;

    }
}
