package com.google.template.soy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.args4j.Option;

import com.google.common.collect.ImmutableMap;
import com.google.template.soy.basetree.SyntaxVersion;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;

public class SoyToSwiftSrcCompiler extends AbstractSoyCompiler {
  @Option(
    name = "--outputPathFormat",
    required = true,
    usage =
        "[Required] A format string that specifies how to build the path to each"
            + " output file. There will be one output Python file (UTF-8) for each input Soy"
            + " file. The format string can include literal characters as well as the"
            + " placeholders {INPUT_PREFIX}, {INPUT_DIRECTORY}, {INPUT_FILE_NAME}, and"
            + " {INPUT_FILE_NAME_NO_EXT}. Additionally periods are not allowed in the"
            + " outputted filename outside of the final py extension."
  )
  private String outputPathFormat = "";


  @Option(
    name = "--syntaxVersion",
    usage = "User-declared syntax version for the Soy file bundle (e.g. 2.2, 2.3)."
  )
  private String syntaxVersion = "";

  @Option(
    name = "--namespaceManifestPath",
    usage =
        "A list of paths to a manifest file which provides a map of soy namespaces to"
            + " their Python paths. If this is provided, direct imports will be used,"
            + " drastically improving runtime performance.",
    handler = SoyCmdLineParser.StringListOptionHandler.class
  )
  private List<String> namespaceManifestPaths = new ArrayList<>();

  public static void main(String[] args) {
    new SoyToSwiftSrcCompiler().runMain(args);
  }

  @Override
  void compile(SoyFileSet.Builder sfsBuilder) throws IOException {
    if (syntaxVersion.length() > 0) {
      SyntaxVersion parsedVersion = SyntaxVersion.forName(syntaxVersion);
      if (parsedVersion.num < SyntaxVersion.V2_0.num) {
        exitWithError("Declared syntax version must be 2.0 or greater.");
      }
      sfsBuilder.setDeclaredSyntaxVersionName(syntaxVersion);
    }
    // Allow external call entirely in Swift.
    sfsBuilder.setAllowExternalCalls(true);
    // Require strict templates in Python.
    sfsBuilder.setStrictAutoescapingRequired(true);
    SoyFileSet sfs = sfsBuilder.build();
    // Load the manifest if available.
    // TODO
    
    // Create SoyPySrcOptions.
    SoySwiftSrcOptions pySrcOptions =
            new SoySwiftSrcOptions(
                // runtimePath,
                // environmentModulePath,
                // bidiIsRtlFn,
                // translationClass,
            		ImmutableMap.<String, String>of(),
                "outputNamespaceManifest");

    // Compile.
    sfs.compileToSwiftSrcFiles(outputPathFormat, "", pySrcOptions);
  }

}
