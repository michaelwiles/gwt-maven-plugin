package net.ltgt.gwt.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

@Mojo(name = "codeserver", requiresDependencyResolution = ResolutionScope.COMPILE, requiresDirectInvocation = true, threadSafe = true, aggregator = true)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class CodeServerMojo extends AbstractMojo {
  /**
   * Sets the level of logging detail. Defaults to Maven's own log level.
   */
  @Parameter(property = "gwt.logLevel")
  private GwtOptions.LogLevel logLevel;

  /**
   * Comma-delimited list of the modules to run.
   * <p>
   * Defaults to the discovered module names from {@code gwt-app} projects.
   */
  @Parameter(property = "gwt.modules")
  private String modules;

  /**
   * Comma-delimited list of the reactor projects to run.
   * <p>
   * Defaults to all the {@code gwt-app} projects in the reactor.
   */
  @Parameter(property = "gwt.projects")
  private String projects;

  /**
   * Specifies Java source level.
   */
  @Parameter(property = "maven.compiler.source")
  private String sourceLevel;

  /**
   * Only succeed if no input files have errors.
   */
  @Parameter(property = "gwt.strict", defaultValue = "false")
  private boolean strict;

  /**
   * The compiler work directory (must be writeable).
   */
  @Parameter(defaultValue = "${project.build.directory}/gwt/codeserver/work")
  private File codeserverWorkDir;

  @Parameter(property = "launcherDir")
  private File launcherDir;

  /**
   * Additional arguments to be passed to the GWT compiler.
   */
  @Parameter
  private List<String> codeserverArgs;

  /**
   * Arguments to be passed to the forked JVM (e.g. {@code -Xmx})
   */
  @Parameter
  private List<String> jvmArgs;

  /**
   * List of system properties to pass to the GWT compiler.
   */
  @Parameter
  private Map<String, String> systemProperties;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private MavenProject project;

  @Parameter(defaultValue = "${plugin}", required = true, readonly = true)
  PluginDescriptor pluginDescriptor;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<MavenProject> projectList = new ArrayList<>();
    if (StringUtils.isBlank(projects)) {
      for (MavenProject p : project.getCollectedProjects()) {
        if (p.getPackaging().equals("gwt-app")) {
          projectList.add(p);
        }
      }
    } else {
      Map<String, MavenProject> projectMap = new HashMap<>();
      for (MavenProject p : project.getCollectedProjects()) {
        // XXX: how about duplicates?
        String key = p.getArtifactId();
        projectMap.put(key, p);
        key = ":" + key;
        projectMap.put(key, p);
        key = p.getGroupId() + key;
        projectMap.put(key, p);
      }
      for (String key : StringUtils.split(projects, ",")) {
        MavenProject p = projectMap.get(key);
        if (p == null) {
          throw new MojoExecutionException("Could not find the selected project in the reactor: " + key);
        }
        // XXX: check module packaging for known illegal values? (e.g. war, ear, etc.)
        projectList.add(p);
      }
    }

    List<String> moduleList = new ArrayList<>();
    if (StringUtils.isBlank(modules)) {
      List<String> nonGwtAppProjects = new ArrayList<>();
      for (MavenProject p : projectList) {
        if (!"gwt-app".equals(p.getPackaging())) {
          nonGwtAppProjects.add(ArtifactUtils.versionlessKey(p.getGroupId(), p.getArtifactId()));
          continue;
        }
        moduleList.add(p.getGoalConfiguration(pluginDescriptor.getGroupId(), pluginDescriptor.getArtifactId(), null, null).getChild("moduleName").getValue());
      }
      if (!nonGwtAppProjects.isEmpty()) {
        getLog().warn("Found projects with packaging different form gwt-app when discovering GWT modules; they've been ignored: "
            + StringUtils.join(nonGwtAppProjects.iterator(), ", "));
      }
    } else {
      moduleList.addAll(Arrays.asList(StringUtils.split(modules, ",")));
    }

    final Path baseDir = project.getBasedir().toPath();
    final Path workingDir = baseDir.resolve(project.getBuild().getDirectory());

    List<String> args = new ArrayList<>();
    if (jvmArgs != null) {
      args.addAll(jvmArgs);
    }
    if (systemProperties != null) {
      for (Map.Entry<String, String> entry : systemProperties.entrySet()) {
        args.add("-D" + entry.getKey() + "=" + entry.getValue());
      }
    }
    args.add("com.google.gwt.dev.codeserver.CodeServer");
    args.add("-logLevel");
    args.add((logLevel == null ? GwtOptions.LogLevel.getLogLevel(getLog()) : logLevel).name());
    args.add("-workDir");
    args.add(workingDir.relativize(codeserverWorkDir.toPath()).toString());
    if (sourceLevel != null) {
      args.add("-sourceLevel");
      args.add(sourceLevel);
    }
    if (strict) {
      args.add("-strict");
    }
    if (launcherDir != null) {
      args.add("-launcherDir");
      args.add(workingDir.relativize(launcherDir.toPath()).toString());
    }
    if (codeserverArgs != null) {
      args.addAll(codeserverArgs);
    }
    args.addAll(moduleList);

    LinkedHashSet<String> cp = new LinkedHashSet<>();
    try {
      for (MavenProject p : projectList) {
        // TODO: add sources (incl. source dependencies from referenced projects, as in import-sources mojo)
        for (String elt : p.getCompileClasspathElements()) {
          cp.add(workingDir.relativize(baseDir.resolve(elt)).toString());
        }
      }
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    try {
      FileUtils.forceMkdir(workingDir.toFile());
      FileUtils.forceMkdir(codeserverWorkDir);
    } catch (IOException ioe) {
      throw new MojoFailureException(ioe.getMessage(), ioe);
    }

    Commandline commandline = new Commandline();
    commandline.setWorkingDirectory(workingDir.toFile());
    commandline.setExecutable(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    commandline.addEnvironment("CLASSPATH", org.codehaus.plexus.util.StringUtils.join(cp.iterator(), File.pathSeparator));
    commandline.addArguments(args.toArray(new String[args.size()]));

    int result;
    try {
      result = CommandLineUtils.executeCommandLine(commandline,
          new StreamConsumer() {
            @Override
            public void consumeLine(String s) {
              getLog().info(s);
            }
          },
          new StreamConsumer() {
            @Override
            public void consumeLine(String s) {
              getLog().warn(s);
            }
          });
    } catch (CommandLineException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
    if (result != 0) {
      throw new MojoExecutionException("GWT Compiler exited with status " + result);
    }
  }
}