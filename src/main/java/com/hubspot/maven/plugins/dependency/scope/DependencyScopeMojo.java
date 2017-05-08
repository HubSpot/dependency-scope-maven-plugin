package com.hubspot.maven.plugins.dependency.scope;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

@Mojo(name = "check", requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
public class DependencyScopeMojo extends AbstractMojo {
  private static final Set<String> RUNTIME_SCOPES = new HashSet<>(Arrays.asList(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME));

  @Parameter( defaultValue = "${session}", required = true, readonly = true )
  protected MavenSession session;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
  private ArtifactRepository localRepository;

  @Parameter(defaultValue = "false")
  private boolean fail;

  @Parameter(defaultValue = "false")
  private boolean skip;

  @Component
  private DependencyGraphBuilder dependencyGraphBuilder;

  @Component
  private MavenProjectBuilder projectBuilder;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    DependencyNode node = buildDependencyNode();
    TraversalContext context = TraversalContext.newContextFor(node);

    for (DependencyNode dependency : node.getChildren()) {
      if (!Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope())) {
        TraversalContext subcontext = context.stepInto(project, dependency);

        checkForArtifacts(dependency, subcontext);
      }
    }

    print(node, "");
  }

  private DependencyNode buildDependencyNode() throws MojoExecutionException {
    try {
      ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
      buildingRequest.setProject(project);

      return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
    } catch (DependencyGraphBuilderException e) {
      throw new MojoExecutionException("Error building dependency graph", e);
    }
  }

  private void checkForArtifacts(DependencyNode node, TraversalContext context) throws MojoExecutionException {
    final MavenProject dependencyProject;
    try {
      dependencyProject = projectBuilder.buildFromRepository(
          node.getArtifact(),
          project.getRemoteArtifactRepositories(),
          localRepository
      );
    } catch (ProjectBuildingException e) {
      throw new MojoExecutionException("Error building dependency project", e);
    }

    for (Dependency dependency : dependencyProject.getDependencies()) {
      if (RUNTIME_SCOPES.contains(dependency.getScope()) && context.isOverriddenToTestScope(dependency)) {
        getLog().warn("Artifact scope changed from " + dependency.getScope() + " to test: " + dependency.getManagementKey());
        getLog().warn("Path to dependency:");
        print(context.path(), dependency.getManagementKey());
      }
    }

    for (DependencyNode child : node.getChildren()) {
      TraversalContext subcontext = context.stepInto(dependencyProject, child);

      checkForArtifacts(child, subcontext);
    }
  }

  private void print(DependencyNode node, String prefix) {
    //getLog().info(prefix + " - " + node.getArtifact().toString());
    for (DependencyNode child : node.getChildren()) {
      print(child, prefix + "  ");
    }
  }

  private void print(List<Artifact> path, String dependency) {
    StringBuilder prefix = new StringBuilder("");
    for (Artifact artifact : path) {
      getLog().warn(prefix + "- " + artifact.toString());
      prefix.append("  ");
    }

    getLog().warn(prefix + "- " + dependency);
  }
}
