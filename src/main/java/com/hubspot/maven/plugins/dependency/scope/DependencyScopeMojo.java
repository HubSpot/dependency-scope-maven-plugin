package com.hubspot.maven.plugins.dependency.scope;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
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

@Mojo(name = "check", defaultPhase = LifecyclePhase.VALIDATE, requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
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

    Set<DependencyViolation> violations = new HashSet<>();
    for (DependencyNode dependency : node.getChildren()) {
      if (!Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope())) {
        TraversalContext subcontext = context.stepInto(project, dependency);

        violations.addAll(findViolations(dependency, subcontext));
      }
    }

    if (!violations.isEmpty()) {
      printViolations(violations);

      if (fail) {
        throw new MojoFailureException("Test dependency scope issues found");
      }
    } else {
      getLog().info("No test dependency scope issues found");
    }
  }

  private Set<DependencyViolation> findViolations(DependencyNode node, TraversalContext context) throws MojoExecutionException {
    MavenProject dependencyProject = buildDependencyProject(node);

    Set<DependencyViolation> violations = new HashSet<>();
    for (Dependency dependency : dependencyProject.getDependencies()) {
      if (RUNTIME_SCOPES.contains(dependency.getScope()) && context.isOverriddenToTestScope(dependency)) {
        violations.add(new DependencyViolation(context, dependency));
      }
    }

    for (DependencyNode child : node.getChildren()) {
      TraversalContext subcontext = context.stepInto(dependencyProject, child);

      violations.addAll(findViolations(child, subcontext));
    }

    return violations;
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

  private MavenProject buildDependencyProject(DependencyNode node) throws MojoExecutionException {
    try {
      return projectBuilder.buildFromRepository(
          node.getArtifact(),
          project.getRemoteArtifactRepositories(),
          localRepository
      );
    } catch (ProjectBuildingException e) {
      throw new MojoExecutionException("Error building dependency project", e);
    }
  }

  private void printViolations(Set<DependencyViolation> violations) {
    Map<String, Set<DependencyViolation>> violationsByDependency = new HashMap<>();
    for (DependencyViolation violation : violations) {
      String key = readableGATC(violation.getDependency());

      if (!violationsByDependency.containsKey(key)) {
        violationsByDependency.put(key, new HashSet<DependencyViolation>());
      }

      violationsByDependency.get(key).add(violation);
    }

    for (Entry<String, Set<DependencyViolation>> dependencyViolation : violationsByDependency.entrySet()) {
      StringBuilder message = new StringBuilder();
      message.append("Found a problem with test-scoped dependency ").append(dependencyViolation.getKey());
      for (DependencyViolation violation : dependencyViolation.getValue()) {
        message.append("\n")
            .append("  ")
            .append("Scope ")
            .append(violation.getDependency().getScope())
            .append(" was expected by artifact: ")
            .append(readableGATCV(violation.getSource().currentArtifact()));
      }

      if (fail) {
        getLog().error(message);
      } else {
        getLog().warn(message);
      }
    }
  }

  private static String readableGATC(Dependency dependency) {
    String name = dependency.getGroupId() + ":" + dependency.getArtifactId();

    if (dependency.getType() != null && !"jar".equals(dependency.getType())) {
      name += ":" + dependency.getType();
    }

    if (dependency.getClassifier() != null) {
      name += ":" + dependency.getClassifier();
    }

    return name;
  }

  private static String readableGATCV(Artifact artifact) {
    String name = artifact.getGroupId() + ":" + artifact.getArtifactId();

    if (artifact.getType() != null && !"jar".equals(artifact.getType())) {
      name += ":" + artifact.getType();
    }

    if (artifact.getClassifier() != null) {
      name += ":" + artifact.getClassifier();
    }

    // this modifies the artifact internal state :/
    artifact.isSnapshot();
    name += ":" + artifact.getBaseVersion();

    return name;
  }
}
