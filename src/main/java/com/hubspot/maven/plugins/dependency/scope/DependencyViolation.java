package com.hubspot.maven.plugins.dependency.scope;

import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;

import com.google.common.collect.ImmutableList;

public class DependencyViolation {
  private final TraversalContext source;
  private final Dependency dependency;

  public DependencyViolation(TraversalContext source, Dependency dependency) {
    this.source = source;
    this.dependency = dependency;
  }

  public TraversalContext getSource() {
    return source;
  }

  public List<String> getPath() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    source.path().stream().map(Artifact::toString).forEach(builder::add);
    builder.add(dependency.getArtifact() + ":" + dependency.getScope());

    return builder.build();
  }

  public Dependency getDependency() {
    return dependency;
  }
}
