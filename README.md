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
  <version>0.9</version>
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

If the plugin detects an issue, that means your local POM declares a dependency with `<scope>test</scope>` but one of your dependencies wants it at compile or runtime scope. To fix, you either need to change your scope or to change your dependency's scope (by changing its POM or adding an exclusion). Which option is the right way to go varies case-by-case unfortunately.

Let's look at some examples:

```
[INFO] --- dependency-scope-maven-plugin:0.3:check (check) @ MyApp ---
[ERROR] Found a problem with test-scoped dependency com.google.guava:guava
  Scope compile was expected by artifact: com.google.inject:guice:4.1.0
```

In this case, I have Guava at test scope, but one of my dependencies (Guice) has a compile-scoped dependency on Guava. This means at runtime I'll get `NoClassDefFoundError`s for Guava classes. Since it's totally reasonable for a library to need Guava at runtime, the correct fix is to remove `<scope>test</scope>` from the Guava dependency in my local POM (I can leave a comment in the POM to document this decision if I'm so inclined).

```
[INFO] --- dependency-scope-maven-plugin:0.3:check (check) @ MyApp ---
[ERROR] Found a problem with test-scoped dependency junit:junit
  Scope compile was expected by artifact: jline:jline:0.9.94
```

In this case, I have junit at test scope, but one of my dependencies (jline) has a compile-scoped dependency on junit. junit is a testing library and it's not expected to be used at runtime, so the most likely scenario is that the project owners accidentally forgot to add `<scope>test</scope>` to their junit dependency, causing it to leak out at compile scope as a transitive dependency. So in this case I should keep `<scope>test</scope>` on junit in my local POM and instead fix the jline dependency. If jline was an internal project, I could update the POM to make its junit dependency test-scoped, but since its a 3rd party library the easiest course of action is to add an exclusion so that my jline dependency looks like this:
```xml
<dependency>
  <groupId>jline</groupId>
  <artifactId>jline</artifactId>
  <version>0.9.94</version>
  <exclusions>
    <exclusion>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```
If jline was in dependency management, you'd probably want to add this exclusion there so that it's centralized and doesn't need to be duplicated for everyone using jline.

So to summarize, if the issue is with a testing library (like junit, Mockito, or assertj), you should probably investigate your dependency tree rather than bumping the dependency to compile scope (since these libraries normally aren't used at runtime). Someone might have missed a `<scope>test</scope>` declaration, causing one of these libraries to leak out as a compile-scoped transitive dependency. You can fix by updating the problematic POM or by adding an exclusion.

On the other hand, if the issue is with a normal library that is reasonable to use at runtime (like Guava, Netty, Jackson, Apache HTTP client, etc.), you should probably remove `<scope>test</scope>` from your local dependency declaration (optionally with a comment in your POM to document this decision).
