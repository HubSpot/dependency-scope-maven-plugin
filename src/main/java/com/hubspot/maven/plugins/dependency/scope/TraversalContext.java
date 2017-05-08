package com.hubspot.maven.plugins.dependency.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyNode;

public class TraversalContext {
  private final DependencyNode node;
  private final List<Artifact> path;
  private final Set<String> testScopedArtifacts;
  private final Set<String> exclusions;

  private TraversalContext(DependencyNode node,
                           List<Artifact> path,
                           Set<String> testScopedArtifacts,
                           Set<String> exclusions) {
    this.node = node;
    this.path = Collections.unmodifiableList(path);
    this.testScopedArtifacts = Collections.unmodifiableSet(testScopedArtifacts);
    this.exclusions = Collections.unmodifiableSet(exclusions);
  }

  public static TraversalContext newContextFor(DependencyNode node) {
    List<Artifact> path = Collections.singletonList(node.getArtifact());

    Set<String> testScopedArtifacts = new HashSet<>();
    for (DependencyNode dependency : node.getChildren()) {
      if (Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope())) {
        testScopedArtifacts.add(dependency.getArtifact().getDependencyConflictId());
      }
    }

    return new TraversalContext(node, path, testScopedArtifacts, Collections.<String>emptySet());
  }

  public TraversalContext stepInto(MavenProject project, DependencyNode node) {
    String artifactKey = node.getArtifact().getDependencyConflictId();

    List<Artifact> path = new ArrayList<>(this.path);
    path.add(node.getArtifact());

    Set<String> exclusions = new HashSet<>(this.exclusions);
    for (Dependency dependency : project.getDependencies()) {
      if (artifactKey.equals(dependency.getManagementKey())) {
        for (Exclusion exclusion : dependency.getExclusions()) {
          exclusions.add(exclusion.getGroupId() + ":" + exclusion.getArtifactId());
        }
      }
    }

    return new TraversalContext(node, path, testScopedArtifacts, exclusions);
  }

  public boolean isOverriddenToTestScope(Dependency dependency) {
    return !excluded(dependency) && testScopedArtifacts.contains(dependency.getManagementKey());
  }

  public Artifact currentArtifact() {
    return node.getArtifact();
  }

  public List<Artifact> path() {
    return path;
  }

  private boolean excluded(Dependency dependency) {
    return exclusions.contains(dependency.getGroupId() + ":" + dependency.getArtifactId());
  }
}
