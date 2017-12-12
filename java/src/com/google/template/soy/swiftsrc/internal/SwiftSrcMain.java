package com.google.template.soy.swiftsrc.internal;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.shared.internal.ApiCallScopeUtils;
import com.google.template.soy.shared.internal.GuiceSimpleScope;
import com.google.template.soy.shared.internal.MainEntryPointUtils;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;

public class SwiftSrcMain {

  /** The scope object that manages the API call scope. */
  private final GuiceSimpleScope apiCallScope;

  public SwiftSrcMain(GuiceSimpleScope apiCallScope) {
    this.apiCallScope = apiCallScope;
  }

  public void generateSwiftFiles(
      SoyFileSetNode soyTree,
      SoySwiftSrcOptions pySrcOptions,
      String outputPathFormat,
      String inputPathsPrefix,
      ErrorReporter errorReporter)
      throws IOException {

    ImmutableList<SoyFileNode> srcsToCompile =
        ImmutableList.copyOf(
            Iterables.filter(soyTree.getChildren(), SoyFileNode.MATCH_SRC_FILENODE));

    // Determine the output paths.
    List<String> soyNamespaces = getSoyNamespaces(soyTree);
    Multimap<String, Integer> outputs =
        MainEntryPointUtils.mapOutputsToSrcs(
            null, outputPathFormat, inputPathsPrefix, srcsToCompile);

    // Generate the manifest and add it to the current manifest.
    ImmutableMap<String, String> manifest = generateManifest(soyNamespaces, outputs);

    // Generate the Swift source.
    List<String> pyFileContents = genSwiftSource(soyTree, pySrcOptions, manifest, errorReporter);

    if (srcsToCompile.size() != pyFileContents.size()) {
      throw new AssertionError(
          String.format(
              "Expected to generate %d code chunk(s), got %d",
              srcsToCompile.size(), pyFileContents.size()));
    }

    // Write out the Python outputs.
    /* for (String outputFilePath : outputs.keySet()) {
      try (Writer out = Files.newWriter(new File(outputFilePath), StandardCharsets.UTF_8)) {
        for (int inputFileIndex : outputs.get(outputFilePath)) {
          out.write(pyFileContents.get(inputFileIndex));
        }
      }
    } */

    // Write out the manifest file.
    if (pySrcOptions.namespaceManifestFile() != null) {
      try (Writer out =
          Files.newWriter(new File(pySrcOptions.namespaceManifestFile()), StandardCharsets.UTF_8)) {
        Properties prop = new Properties();
        for (String namespace : manifest.keySet()) {
          prop.put(namespace, manifest.get(namespace));
        }
        prop.store(out, null);
      }
    }
  }

  /**
   * Generates Python source code given a Soy parse tree and an options object.
   *
   * @param soyTree The Soy parse tree to generate Python source code for.
   * @param pySrcOptions The compilation options relevant to this backend.
   * @param currentManifest The namespace manifest for current sources.
   * @param errorReporter The Soy error reporter that collects errors during code generation.
   * @return A list of strings where each string represents the Python source code that belongs in
   *     one Python file. The generated Python files correspond one-to-one to the original Soy
   *     source files.
   * @throws SoySyntaxException If a syntax error is found.
   */
  public List<String> genSwiftSource(
      SoyFileSetNode soyTree,
      SoySwiftSrcOptions pySrcOptions,
      ImmutableMap<String, String> currentManifest,
      ErrorReporter errorReporter) {

    try (GuiceSimpleScope.InScope inScope = apiCallScope.enter()) {
      // Seed the scoped parameters, for plugins
      BidiGlobalDir bidiGlobalDir = null;
          // FIXME do we need this? SoyBidiUtils.decodeBidiGlobalDirFromPyOptions(pySrcOptions.getBidiIsRtlFn());
      ApiCallScopeUtils.seedSharedParams(inScope, null, bidiGlobalDir);
      return createVisitor(pySrcOptions, currentManifest).gen(soyTree, errorReporter);
    }
  }

  private List<String> getSoyNamespaces(SoyFileSetNode soyTree) {
    List<String> namespaces = new ArrayList<>();
    for (SoyFileNode soyFile : soyTree.getChildren()) {
      namespaces.add(soyFile.getNamespace());
    }
    return namespaces;
  }

  /**
   * Generate the manifest file by finding the output file paths and converting them into a Swift
   * import format.
   */
  private static ImmutableMap<String, String> generateManifest(
      List<String> soyNamespaces, Multimap<String, Integer> outputs) {
    ImmutableMap.Builder<String, String> manifest = new ImmutableMap.Builder<>();
    for (String outputFilePath : outputs.keySet()) {
      for (int inputFileIndex : outputs.get(outputFilePath)) {
    	  	// FIXME
        String pythonPath = outputFilePath.replace(".py", "").replace('/', '.');

        manifest.put(soyNamespaces.get(inputFileIndex), pythonPath);
      }
    }
    return manifest.build();
  }

  @VisibleForTesting
  static GenSwiftCodeVisitor createVisitor(
      SoySwiftSrcOptions pySrcOptions, ImmutableMap<String, String> currentManifest) {
    // TODO

    return new GenSwiftCodeVisitor();
  }
}
