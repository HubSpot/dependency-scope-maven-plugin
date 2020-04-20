package com.hubspot.maven.plugins.dependency.scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
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
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
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

  @Parameter( defaultValue = "${session}", required = true, readonly = true )
  protected MavenSession session;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue="${repositorySystemSession}", required = true, readonly = true)
  private RepositorySystemSession repositorySystemSession;

  @Parameter(property = "useParallelDependencyResolution", defaultValue = "true")
  private boolean useParallelDependencyResolution;

  @Parameter(defaultValue = "false")
  private boolean linkToDocumentation;

  @Parameter(defaultValue = "false")
  private boolean fail;

  @Parameter(defaultValue = "false")
  private boolean skip;

  @Parameter(property = "verbose", defaultValue = "true")
  private boolean verbose;

  @Component
  private RepositorySystem repositorySystem;

  @Component
  private DependencyGraphBuilder dependencyGraphBuilder;

  private ListeningExecutorService executorService;
  private Set<String> checkedArtifacts;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping plugin execution");
      return;
    }

    executorService = newExecutorService();
    checkedArtifacts = Sets.newConcurrentHashSet();

    DependencyNode node = buildDependencyNode();
    TraversalContext context = TraversalContext.newContextFor(project, node);

    List<ListenableFuture<Set<DependencyViolation>>> futures = new ArrayList<>();
    for (DependencyNode dependency : node.getChildren()) {
      if (!Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope())) {
        TraversalContext subcontext = context.stepInto(project, dependency);

        futures.add(findViolations(subcontext));
      }
    }

    Set<DependencyViolation> violations = resolve(Futures.allAsList(futures));
    executorService.shutdown();

    if (!violations.isEmpty()) {
      printViolations(violations);

      if (fail) {
        throw new MojoFailureException("Test dependency scope issues found");
      }
    } else {
      getLog().info("No test dependency scope issues found");
    }
  }

  private ListenableFuture<Set<DependencyViolation>> findViolations(final TraversalContext context) {
    final SettableFuture<Set<DependencyViolation>> future = SettableFuture.create();

    if (!checkedArtifacts.add(context.currentArtifact().getId())) {
      future.set(ImmutableSet.of());
      return future;
    }

    Futures.addCallback(resolveArtifactDescriptor(context.currentArtifact()), new FutureCallback<ArtifactDescriptorResult>() {

      @Override
      public void onSuccess(ArtifactDescriptorResult artifactDescriptor) {
        if (artifactDescriptor == null) {
          onFailure(new NullPointerException("artifactDescriptor"));
          return;
        }

        try {
          Set<Dependency> runtimeDependencies = artifactDescriptor.getDependencies()
              .stream()
              .filter(DependencyScopeMojo::dependencyRequiredAtRuntime)
              .filter(dependency -> !context.isExcluded(dependency))
              .collect(ImmutableSet.toImmutableSet());

          if (runtimeDependencies.isEmpty()) {
            future.set(ImmutableSet.of());
            return;
          }

          final Set<DependencyViolation> violations = Sets.newConcurrentHashSet();
          final CountDownLatch latch = new CountDownLatch(runtimeDependencies.size());
          for (Dependency dependency : runtimeDependencies) {
            if (context.isOverriddenToTestScope(dependency)) {
              violations.add(new DependencyViolation(context, dependency));
            }

            Optional<TraversalContext> subcontext = context.stepInto(dependency);
            ListenableFuture<Set<DependencyViolation>> subfuture = subcontext.isPresent()
                ? findViolations(subcontext.get())
                : Futures.immediateFuture(ImmutableSet.of());

            Futures.addCallback(subfuture, new FutureCallback<Set<DependencyViolation>>() {

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
            }, MoreExecutors.directExecutor());
          }
        } catch (Throwable t) {
          future.setException(t);
        }
      }

      @Override
      public void onFailure(Throwable t) {
        future.setException(t);
      }
    }, MoreExecutors.directExecutor());

    return future;
  }

  private DependencyNode buildDependencyNode() throws MojoExecutionException {
    try {
      ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
      buildingRequest.setProject(project);

      return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifact -> {
          return !Artifact.SCOPE_PROVIDED.equals(artifact.getScope())
              && !Artifact.SCOPE_SYSTEM.equals(artifact.getScope());
      });
    } catch (DependencyGraphBuilderException e) {
      throw new MojoExecutionException("Error building dependency graph", e);
    }
  }

  private ListenableFuture<ArtifactDescriptorResult> resolveArtifactDescriptor(final Artifact artifact) {
    return executorService.submit(() -> {
      ArtifactDescriptorRequest request = new ArtifactDescriptorRequest(
          toAether(artifact),
          project.getRemoteProjectRepositories(),
          null
      );

      try {
        return repositorySystem.readArtifactDescriptor(repositorySystemSession, request);
      } catch (ArtifactDescriptorException e) {
        String message = "Error resolving descriptor for artifact " + readableGATCV(artifact);
        throw new MojoExecutionException(message, e);
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

        if (verbose) {
          message.append("\n  Full dependency chain:");
          StringBuilder prefix = new StringBuilder("  ");
          boolean first = true;
          for (String artifact : violation.getPath()) {
            if (first) {
              message.append("\n").append(prefix).append(artifact);
              first = false;
            } else {
              message.append("\n").append(prefix).append("\\- ").append(artifact);
              prefix.append("  ");
            }
          }
        }
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

  private static boolean dependencyRequiredAtRuntime(Dependency dependency) {
    if (dependency.isOptional()) {
      return false;
    } else {
      String scope = dependency.getScope();
      return Artifact.SCOPE_COMPILE.equals(scope) || Artifact.SCOPE_RUNTIME.equals(scope);
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
      if (e.getCause() != null) {
        Throwables.throwIfInstanceOf(e.getCause(), MojoExecutionException.class);
      }
      throw new MojoExecutionException("Error while checking dependency scopes", e.getCause());
    }
  }

  private static Comparator<DependencyViolation> artifactNameComparator() {
    return Comparator.comparing(violation -> readableGATCV(violation.getSource().currentArtifact()));
  }

  private static String readableGATC(Dependency dependency) {
    org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();

    String name = artifact.getGroupId() + ":" + artifact.getArtifactId();

    if (!"jar".equals(artifact.getExtension())) {
      name += ":" + artifact.getExtension();
    }

    if (!artifact.getClassifier().isEmpty()) {
      name += ":" + artifact.getClassifier();
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

  private static org.eclipse.aether.artifact.Artifact toAether(Artifact artifact) {
    return new DefaultArtifact(
        artifact.getGroupId(),
        artifact.getArtifactId(),
        artifact.getClassifier(),
        artifact.getType(),
        artifact.getVersion(),
        null,
        artifact.getFile()
    );
  }
}
