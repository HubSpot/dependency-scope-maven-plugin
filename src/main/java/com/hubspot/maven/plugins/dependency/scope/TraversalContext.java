package com.hubspot.maven.plugins.dependency.scope;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class TraversalContext {
  private static final String WILDCARD = "*";

  private final Artifact artifact;
  private final ImmutableList<Artifact> path;
  private final ImmutableSet<String> testScopedArtifacts;
  private final ImmutableMap<String, String> dependencyVersions;
  private final ImmutableSet<Exclusion> exclusions;
  private final ImmutableMap<String, ImmutableSet<Exclusion>> dependencyManagementExclusions;

  private TraversalContext(Artifact artifact,
                           ImmutableList<Artifact> path,
                           ImmutableSet<String> testScopedArtifacts,
                           ImmutableMap<String, String> dependencyVersions,
                           ImmutableSet<Exclusion> exclusions,
                           ImmutableMap<String, ImmutableSet<Exclusion>> dependencyManagementExclusions) {
    this.artifact = artifact;
    this.path = path;
    this.testScopedArtifacts = testScopedArtifacts;
    this.dependencyVersions = dependencyVersions;
    this.exclusions = exclusions;
    this.dependencyManagementExclusions = dependencyManagementExclusions;
  }

  public static TraversalContext newContextFor(MavenProject project, DependencyNode node) {
    ImmutableSet<String> testScopedArtifacts = node.getChildren()
        .stream()
        .filter(dependency -> Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope()))
        .map(dependency -> dependency.getArtifact().getDependencyConflictId())
        .collect(ImmutableSet.toImmutableSet());

    ImmutableMap<String, String> dependencyVersions = project.getArtifacts()
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                Artifact::getDependencyConflictId,
                Artifact::getBaseVersion
            )
        );

    final ImmutableMap<String, ImmutableSet<Exclusion>> dependencyManagementExclusions;
    if (project.getDependencyManagement() == null) {
      dependencyManagementExclusions = ImmutableMap.of();
    } else {
      dependencyManagementExclusions = project.getDependencyManagement()
          .getDependencies()
          .stream()
          .collect(
              ImmutableMap.toImmutableMap(
                  org.apache.maven.model.Dependency::getManagementKey,
                  TraversalContext::exclusions
              )
          );
    }

    return new TraversalContext(
        node.getArtifact(),
        ImmutableList.of(node.getArtifact()),
        testScopedArtifacts,
        dependencyVersions,
        ImmutableSet.of(),
        dependencyManagementExclusions
    );
  }

  public TraversalContext extendManagedDependencyExclusions(List<Dependency> dependencies) {
    if (dependencies.isEmpty()) {
      return this;
    }

    ImmutableMap<String, ImmutableSet<Exclusion>> newExclusions = dependencies.stream()
        .filter(dependency -> !dependency.getExclusions().isEmpty())
        .collect(
            ImmutableMap.toImmutableMap(
                TraversalContext::computeDependencyKey,
                dependency -> ImmutableSet.copyOf(dependency.getExclusions())
            )
        );

    ImmutableMap<String, ImmutableSet<Exclusion>> mergedExclusions =
        merge(dependencyManagementExclusions, newExclusions);

    return new TraversalContext(
        artifact,
        path,
        testScopedArtifacts,
        dependencyVersions,
        exclusions,
        mergedExclusions
    );
  }

  public TraversalContext stepInto(MavenProject project, DependencyNode node) {
    String artifactKey = node.getArtifact().getDependencyConflictId();

    ImmutableList<Artifact> path = ImmutableList.<Artifact>builderWithExpectedSize(this.path.size() + 1)
        .addAll(this.path)
        .add(node.getArtifact())
        .build();

    Set<Exclusion> exclusions = this.exclusions;
    if (dependencyManagementExclusions.containsKey(artifactKey)) {
      Set<Exclusion> toAdd = dependencyManagementExclusions.get(artifactKey);

      exclusions = Sets.union(exclusions, toAdd);
    }

    for (org.apache.maven.model.Dependency dependency : project.getDependencies()) {
      if (artifactKey.equals(dependency.getManagementKey())) {
        if (!dependency.getExclusions().isEmpty()) {
          exclusions = Sets.union(exclusions, exclusions(dependency));
        }
      }
    }

    return new TraversalContext(
        node.getArtifact(),
        path,
        testScopedArtifacts,
        dependencyVersions,
        ImmutableSet.copyOf(exclusions),
        dependencyManagementExclusions
    );
  }

  public Optional<TraversalContext> stepInto(Dependency dependency) {
    Artifact artifact = fromAether(dependency);
    String projectVersion = dependencyVersions.get(artifact.getDependencyConflictId());

    if (projectVersion == null) {
      /*
      probably something is off with our exclusion logic
       */
      return Optional.empty();
    }

    artifact = withVersion(artifact, projectVersion);

    ImmutableList<Artifact> path = ImmutableList.<Artifact>builderWithExpectedSize(this.path.size() + 1)
        .addAll(this.path)
        .add(artifact)
        .build();

    ImmutableSet<Exclusion> exclusions = this.exclusions;
    if (dependencyManagementExclusions.containsKey(artifact.getDependencyConflictId())) {
      Set<Exclusion> toAdd = dependencyManagementExclusions.get(artifact.getDependencyConflictId());

      exclusions = Sets.union(exclusions, toAdd).immutableCopy();
    }

    return Optional.of(
        new TraversalContext(
            artifact,
            path,
            testScopedArtifacts,
            dependencyVersions,
            exclusions,
            dependencyManagementExclusions
        )
    );
  }

  public boolean isExcluded(Dependency dependency) {
    for (Exclusion exclusion : exclusions) {
      if (matches(dependency, exclusion)) {
        return true;
      }
    }

    return false;
  }

  public boolean isOverriddenToTestScope(Dependency dependency) {
    return testScopedArtifacts.contains(computeDependencyKey(dependency));
  }

  public Artifact currentArtifact() {
    return artifact;
  }

  public List<Artifact> path() {
    return path;
  }

  private static boolean matches(Dependency dependency, Exclusion exclusion) {
    org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();

    return artifact.getGroupId().equals(exclusion.getGroupId()) &&
        artifact.getArtifactId().equals(exclusion.getArtifactId()) &&
        (WILDCARD.equals(exclusion.getClassifier()) || artifact.getClassifier().equals(exclusion.getClassifier())) &&
        (WILDCARD.equals(exclusion.getExtension()) || artifact.getExtension().equals(exclusion.getExtension()));
  }

  private static ImmutableMap<String, ImmutableSet<Exclusion>> merge(
      ImmutableMap<String, ImmutableSet<Exclusion>> a,
      ImmutableMap<String, ImmutableSet<Exclusion>> b) {

    MapDifference<String, ImmutableSet<Exclusion>> diff =
        Maps.difference(a, b);

    ImmutableMap.Builder<String, ImmutableSet<Exclusion>> merged = ImmutableMap.builder();
    merged.putAll(diff.entriesOnlyOnLeft());
    merged.putAll(diff.entriesOnlyOnRight());
    merged.putAll(diff.entriesInCommon());
    diff.entriesDiffering().forEach((key, valueDifference) -> {
      ImmutableSet<Exclusion> mergedValue = Sets.union(
          valueDifference.leftValue(),
          valueDifference.rightValue()
      ).immutableCopy();

      merged.put(key, mergedValue);
    });

    return merged.build();
  }

  private static ImmutableSet<Exclusion> exclusions(org.apache.maven.model.Dependency dependency) {
    return dependency.getExclusions()
        .stream()
        .map(exclusion -> new Exclusion(exclusion.getGroupId(), exclusion.getArtifactId(), WILDCARD, WILDCARD))
        .collect(ImmutableSet.toImmutableSet());
  }

  private static Artifact fromAether(Dependency dependency) {
    org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();

    return new DefaultArtifact(
        artifact.getGroupId(),
        artifact.getArtifactId(),
        VersionRange.createFromVersion(artifact.getVersion()),
        dependency.getScope(),
        artifact.getExtension(),
        artifact.getClassifier().isEmpty() ? null : artifact.getClassifier(),
        new DefaultArtifactHandler(artifact.getExtension()),
        dependency.isOptional()
    );
  }

  private static Artifact withVersion(Artifact artifact, String version) {
    return new DefaultArtifact(
        artifact.getGroupId(),
        artifact.getArtifactId(),
        VersionRange.createFromVersion(version),
        artifact.getScope(),
        artifact.getType(),
        artifact.getClassifier(),
        artifact.getArtifactHandler(),
        artifact.isOptional()
    );
  }

  private static String computeDependencyKey(Dependency dependency) {
    org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();

    StringBuilder builder = new StringBuilder()
        .append(artifact.getGroupId())
        .append(":")
        .append(artifact.getArtifactId())
        .append(":")
        .append(artifact.getExtension());

    if (!artifact.getClassifier().isEmpty()) {
      builder.append(":").append(artifact.getClassifier());
    }

    return builder.toString();
  }
}
