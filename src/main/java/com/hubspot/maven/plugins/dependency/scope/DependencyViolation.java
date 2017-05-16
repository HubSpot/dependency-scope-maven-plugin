package com.hubspot.maven.plugins.dependency.scope;

import org.eclipse.aether.graph.Dependency;

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

  public Dependency getDependency() {
    return dependency;
  }
}
