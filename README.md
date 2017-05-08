# dependency-scope-maven-plugin

## Motivation

This plugin aims to mitigate a particular pesky problem with Maven which is that if you declare a dependency with test scope, that will take precedence over a transitive dependency with compile scope. For example, let's say I only use Guava in test code for my project. So it is natural to define a dependency on Guava with `<scope>test</scope>`. But if any of my dependencies rely on Guava at runtime, my app will now fail at runtime with confusing `NoClassDefFoundError`s for Guava classes. So I should just use compile scope for Guava then? Well, if none of your dependencies use Guava at runtime then you've just unnecessarily bloated your app. So I should check my dependency tree for a transitive dependency on Guava and use compile scope if that exists and test scope otherwise? Sure, but you need to repeat this process every time you want to add a test-scoped dependency, and need to reevaluate all previous decisions any time your dependency tree changes. And to make matters worse, the dependency is on the classpath at test time so no amount of unit testing can catch this class of errors. It was suggested to change this behavior within Maven in [2009](https://issues.apache.org/jira/browse/MNG-4156) but that never went anywhere.

## What is it

This plugin attempts to mitigate this problem by verifying that test-scoped dependencies don't appear elsewhere in your dependency tree at scope runtime or compile. This allows you to use `<scope>test</scope>` where it makes sense and not worry about causing runtime issues. 

## How to use it

Add the following snippet to the plugins section of your POM:

```xml
<plugin>
  <groupId>com.hubspot.maven.plugins</groupId>
  <artifactId>dependency-scope-maven-plugin</artifactId>
  <version>0.1</version>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
      </goals>
      <configuration>
        <fail>true</fail>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The `fail` configuration option controls whether your build will fail in the presence of test-scoped dependency issues (default `false`). There is also a `skip` option (also defaults to `false`).

## How to fix issues

If the plugin detects an issue, there are two mains way you can fix it. You can either change your local project by removing the test scope from the dependency, or you can change your dependency tree (so that this dependency doesn't appear elsewhere at scope runtime or compile). Which option is the right way to go varies case-by-case unfortunately. 

If the issue is with a testing library (like junit, Mockito, or assertj), you should probably investigate your dependency tree rather than bumping the dependency to compile scope (since these libraries normally aren't used at runtime). Someone might have missed a `<scope>test</scope>` declaration, causing junit to leak out as a compile-scoped transitive dependency. If that's the case, the best fix is to update that dependency's POM, adding `<scope>test</scope>` to its junit declaration. Or as a quick fix, you can add a junit exclusion to the problematic dependency.

On the other hand, if the issue is with a normal library that is reasonable to use at runtime (like Guava, Netty, Jackson, Apache HTTP client, etc.), you should probably remove `<scope>test</scope>` from your local dependency declaration (you can leave a comment in your POM if you want to document this decision).
