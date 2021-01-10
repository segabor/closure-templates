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
            + " their Python paths. If this is provided, direct imports will be used,"
            + " drastically improving runtime performance.",
    handler = SoyCmdLineParser.StringListOptionHandler.class
  )
  private List<String> namespaceManifestPaths = new ArrayList<>();

  private final PerInputOutputFiles outputFiles = new PerInputOutputFiles("swift");

  public static void main(String[] args) {
    new SoyToSwiftSrcCompiler().runMain(args);
  }

  SoyToSwiftSrcCompiler(PluginLoader loader, SoyInputCache cache) {
    super(loader, cache);
  }

  SoyToSwiftSrcCompiler() {}

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
    SoySwiftSrcOptions pySrcOptions =
        new SoySwiftSrcOptions(
            // bidiIsRtlFn,
            // translationClass,
            ImmutableMap.<String, String>of(),
            outputFiles.getOutputFilePathsForInputs(sfs.getSourceFilePaths()),
            outputFiles.getOutputDirectoryFlag(),
            rendererMapFile);

    // Compile.
    outputFiles.writeFiles(srcs, sfs.compileToSwiftSrcFiles(pySrcOptions));
  }

}
