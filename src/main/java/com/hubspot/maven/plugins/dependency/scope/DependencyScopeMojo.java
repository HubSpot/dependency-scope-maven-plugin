package com.hubspot.maven.plugins.dependency.scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

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

import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

@Mojo(name = "check", defaultPhase = LifecyclePhase.VALIDATE, requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
public class DependencyScopeMojo extends AbstractMojo {
  private static final Set<String> RUNTIME_SCOPES = new HashSet<>(Arrays.asList(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME));

  @Parameter( defaultValue = "${session}", required = true, readonly = true )
  protected MavenSession session;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
  private ArtifactRepository localRepository;

  @Parameter(property = "useParallelDependencyResolution", defaultValue = "true")
  private boolean useParallelDependencyResolution;

  @Parameter(defaultValue = "false")
  private boolean linkToDocumentation;

  @Parameter(defaultValue = "false")
  private boolean fail;

  @Parameter(defaultValue = "false")
  private boolean skip;

  @Component
  private DependencyGraphBuilder dependencyGraphBuilder;

  @Component
  private MavenProjectBuilder projectBuilder;

  private ListeningExecutorService executorService;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping plugin execution");
      return;
    }

    executorService = newExecutorService();

    DependencyNode node = buildDependencyNode();
    TraversalContext context = TraversalContext.newContextFor(node);

    List<ListenableFuture<Set<DependencyViolation>>> futures = new ArrayList<>();
    for (DependencyNode dependency : node.getChildren()) {
      if (!Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope())) {
        TraversalContext subcontext = context.stepInto(project, dependency);

        futures.add(findViolations(dependency, subcontext));
      }
    }

    Set<DependencyViolation> violations = resolve(Futures.allAsList(futures));

    if (!violations.isEmpty()) {
      printViolations(violations);

      if (fail) {
        throw new MojoFailureException("Test dependency scope issues found");
      }
    } else {
      getLog().info("No test dependency scope issues found");
    }
  }

  private ListenableFuture<Set<DependencyViolation>> findViolations(final DependencyNode node,
                                                                    final TraversalContext context) {
    final SettableFuture<Set<DependencyViolation>> future = SettableFuture.create();

    Futures.addCallback(buildDependencyProject(node), new FutureCallback<MavenProject>() {

      @Override
      public void onSuccess(MavenProject dependencyProject) {
        try {
          final Set<DependencyViolation> violations = Sets.newConcurrentHashSet();
          for (Dependency dependency : dependencyProject.getDependencies()) {
            if (RUNTIME_SCOPES.contains(dependency.getScope()) && context.isOverriddenToTestScope(dependency)) {
              violations.add(new DependencyViolation(context, dependency));
            }
          }

          if (node.getChildren().isEmpty()) {
            future.set(violations);
            return;
          }

          final CountDownLatch latch = new CountDownLatch(node.getChildren().size());
          for (DependencyNode child : node.getChildren()) {
            TraversalContext subcontext = context.stepInto(dependencyProject, child);

            Futures.addCallback(findViolations(child, subcontext), new FutureCallback<Set<DependencyViolation>>() {

              @Override
              public void onSuccess(Set<DependencyViolation> result) {
                try {
                  violations.addAll(result);
                } finally {
                  latch.countDown();
                  if (latch.getCount() == 0) {
                    future.set(violations);
                  }
                }
              }

              @Override
              public void onFailure(Throwable t) {
                future.setException(t);
              }
            });
          }
        } catch (Throwable t) {
          future.setException(t);
        }
      }

      @Override
      public void onFailure(Throwable t) {
        future.setException(t);
      }
    });

    return future;
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

  private ListenableFuture<MavenProject> buildDependencyProject(final DependencyNode node) {
    return executorService.submit(new Callable<MavenProject>() {

      @Override
      public MavenProject call() throws Exception {
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
    });
  }

  private void printViolations(Set<DependencyViolation> violations) {
    Map<String, Set<DependencyViolation>> violationsByDependency = new HashMap<>();
    for (DependencyViolation violation : violations) {
      String key = readableGATC(violation.getDependency());

      if (!violationsByDependency.containsKey(key)) {
        violationsByDependency.put(key, new TreeSet<>(artifactNameComparator()));
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

    if (linkToDocumentation) {
      StringBuilder message = new StringBuilder("For information on how to fix these issues, see here:")
          .append("\n  ")
          .append("https://github.com/HubSpot/dependency-scope-maven-plugin#how-to-fix-issues");
      getLog().info(message);
    }
  }

  private ListeningExecutorService newExecutorService() {
    if (useParallelDependencyResolution) {
      getLog().debug("Using parallel dependency resolution");
      return MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(
          Math.min(Runtime.getRuntime().availableProcessors() * 5, 20),
          new ThreadFactoryBuilder().setNameFormat("dependency-project-builder-%s")
              .setDaemon(true)
              .build()
      ));
    } else {
      getLog().debug("Using single-threaded dependency resolution");
      return MoreExecutors.newDirectExecutorService();
    }
  }

  private static Set<DependencyViolation> resolve(ListenableFuture<List<Set<DependencyViolation>>> future)
      throws MojoExecutionException {
    try {
      return Sets.newHashSet(Iterables.concat(future.get()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("Interrupted while checking dependency scopes", e);
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), MojoExecutionException.class);
      throw new MojoExecutionException("Error while checking dependency scopes", e.getCause());
    }
  }

  private static Comparator<DependencyViolation> artifactNameComparator() {
    return new Comparator<DependencyViolation>() {

      @Override
      public int compare(DependencyViolation a, DependencyViolation b) {
        return readableGATCV(a.getSource().currentArtifact()).compareTo(readableGATCV(b.getSource().currentArtifact()));
      }
    };
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
