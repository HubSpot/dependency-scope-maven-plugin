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

    ImmutableMap.Builder<String, String> dependencyVersions = ImmutableMap.builder();
    for (Artifact artifact : project.getArtifacts()) {
      dependencyVersions.put(artifact.getDependencyConflictId(), artifact.getBaseVersion());
    }

    ImmutableMap.Builder<String, ImmutableSet<Exclusion>> dependencyManagementExclusions = ImmutableMap.builder();
    if (project.getDependencyManagement() != null) {
      for (org.apache.maven.model.Dependency dependency : project.getDependencyManagement().getDependencies()) {
        dependencyManagementExclusions.put(dependency.getManagementKey(), exclusions(dependency));
      }
    }

    return new TraversalContext(
        node.getArtifact(),
        ImmutableList.of(node.getArtifact()),
        testScopedArtifacts,
        dependencyVersions.build(),
        ImmutableSet.of(),
        dependencyManagementExclusions.build()
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
      this usually means the artifact is excluded and our exclusion logic is slightly off
      this can happen because we're not climbing the parent hierarchy for every artifact
      so we can miss some exclusions
       */
      return Optional.empty();
    }

    artifact = withVersion(artifact, projectVersion);

    ImmutableList<Artifact> path = ImmutableList.<Artifact>builderWithExpectedSize(this.path.size() + 1)
        .addAll(this.path)
        .add(artifact)
        .build();

    Set<Exclusion> exclusions = this.exclusions;
    if (dependencyManagementExclusions.containsKey(artifact.getDependencyConflictId())) {
      Set<Exclusion> toAdd = dependencyManagementExclusions.get(artifact.getDependencyConflictId());

      exclusions = Sets.union(exclusions, toAdd);
    }

    return Optional.of(
        new TraversalContext(
            artifact,
            path,
            testScopedArtifacts,
            dependencyVersions,
            ImmutableSet.copyOf(exclusions),
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
    return testScopedArtifacts.contains(fromAether(dependency).getDependencyConflictId());
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
        artifact.getClassifier(),
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
}
