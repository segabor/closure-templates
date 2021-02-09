package com.google.template.soy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.args4j.Option;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;

public class SoyToSwiftSrcCompiler extends AbstractSoyCompiler {
  @Option(
    name = "--namespaceManifestPath",
    usage =
        "A list of paths to a manifest file which provides a map of soy namespaces to"
            + " their Swift paths. If this is provided, direct imports will be used,"
            + " drastically improving runtime performance.",
    handler = SoyCmdLineParser.StringListOptionHandler.class
  )
  private List<String> namespaceManifestPaths = new ArrayList<>();

  private final PerInputOutputFiles outputFiles = new PerInputOutputFiles("swift");

  SoyToSwiftSrcCompiler(PluginLoader loader, SoyInputCache cache) {
    super(loader, cache);
  }

  SoyToSwiftSrcCompiler() {}

  /**
   * Compiles a set of Soy files into corresponding Swift source files.
   *
   * @param args Should contain command-line flags and the list of paths to the Soy files.
   * @throws IOException If there are problems reading the input files or writing the output file.
   */
  public static void main(final String[] args) throws IOException {
    new SoyToSwiftSrcCompiler().runMain(args);
  }

  @Override
  protected void validateFlags() {
    outputFiles.validateFlags();
  }

  @Override
  Iterable<?> extraFlagsObjects() {
    return ImmutableList.of(outputFiles);
  }

  @Override
  protected void compile(SoyFileSet.Builder sfsBuilder) throws IOException {
    // Disallow external call entirely in Swift.
    sfsBuilder.setAllowExternalCalls(false);
    SoyFileSet sfs = sfsBuilder.build();
    // Load the manifest if available.
    // TODO

    /* ImmutableMap<String, String> manifest = loadNamespaceManifest(namespaceManifestPaths);
    if (!manifest.isEmpty() && outputNamespaceManifest == null) {
      exitWithError("Namespace manifests provided without outputting a new manifest.");
    } */

    final String rendererMapFile = namespaceManifestPaths.isEmpty() ? "TemplateRenderers.swift" : namespaceManifestPaths.get(0);

    // Create SoyPySrcOptions.
    SoySwiftSrcOptions swiftSrcOptions =
        new SoySwiftSrcOptions(
            // bidiIsRtlFn,
            // translationClass,
            ImmutableMap.<String, String>of(),
            outputFiles.getOutputFilePathsForInputs(sfs.getSourceFilePaths()),
            outputFiles.getOutputDirectoryFlag(),
            rendererMapFile);

    // Compile.
    outputFiles.writeFiles(srcs, sfs.compileToSwiftSrcFiles(swiftSrcOptions));
  }

}
