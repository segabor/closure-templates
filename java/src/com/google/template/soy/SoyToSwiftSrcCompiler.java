package com.google.template.soy;

import java.io.IOException;

import org.kohsuke.args4j.Option;

import com.google.common.collect.ImmutableMap;
import com.google.template.soy.basetree.SyntaxVersion;
import com.google.template.soy.pysrc.SoyPySrcOptions;

public class SoyToSwiftSrcCompiler extends AbstractSoyCompiler {

  @Option(
    name = "--syntaxVersion",
    usage = "User-declared syntax version for the Soy file bundle (e.g. 2.2, 2.3)."
  )
  private String syntaxVersion = "";

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
    // Disallow external call entirely in Python.
    sfsBuilder.setAllowExternalCalls(false);
    // Require strict templates in Python.
    sfsBuilder.setStrictAutoescapingRequired(true);
    SoyFileSet sfs = sfsBuilder.build();
    // Load the manifest if available.
    // TODO
    
    // Create SoyPySrcOptions.
    // TODO

    // Compile.
    sfs.compileToSwiftSrcFiles();
  }

}
