package com.google.template.soy.swiftsrc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.shared.internal.MainEntryPointUtils;
import com.google.template.soy.shared.internal.SoyScopedData;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.Visibility;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;
import com.google.template.soy.swiftsrc.internal.GenSwiftExprsVisitor.GenSwiftExprsVisitorFactory;

public class SwiftSrcMain {

  /** The scope object that manages the API call scope. */
  private final SoyScopedData.Enterable apiCallScope;

  public SwiftSrcMain(SoyScopedData.Enterable apiCallScope) {
    this.apiCallScope = apiCallScope;
  }

  /**
   * Generates Swift source code given a Soy parse tree and an options object.
   *
   * @param soyTree The Soy parse tree to generate Swift source code for.
   * @param swiftSrcOptions The compilation options relevant to this backend.
   * @param currentManifest The namespace manifest for current sources.
   * @param errorReporter The Soy error reporter that collects errors during code generation.
   * @return A list of strings where each string represents the Swift source code that belongs in
   *     one Swift file. The generated Swift files correspond one-to-one to the original Soy
   *     source files.
   * @throws SoySyntaxException If a syntax error is found.
   */
  public List<String> genSwiftSource(
      SoyFileSetNode soyTree,
      SoySwiftSrcOptions swiftSrcOptions,
      ImmutableMap<String, String> currentManifest,
      ErrorReporter errorReporter) {

    // FIXME do we need this?
    BidiGlobalDir bidiGlobalDir = BidiGlobalDir.LTR;
    // SoyBidiUtils.decodeBidiGlobalDirFromPyOptions(pySrcOptions.getBidiIsRtlFn());
    try (SoyScopedData.InScope inScope = apiCallScope.enter(/* msgBundle= */ null, bidiGlobalDir)) {
      return createVisitor(swiftSrcOptions, bidiGlobalDir, errorReporter, currentManifest).gen(soyTree, errorReporter);
    }
  }

  /**
   * Generates Swift source files given a Soy parse tree, an options object, and information on
   * where to put the output files.
   *
   * @param soyTree The Soy parse tree to generate Python source code for.
   * @param swiftSrcOptions The compilation options relevant to this backend.
   * @param outputPathFormat The format string defining how to build the output file path
   *     corresponding to an input file path.
   * @param errorReporter The Soy error reporter that collects errors during code generation.
   * @throws SoySyntaxException If a syntax error is found.
   * @throws IOException If there is an error in opening/writing an output Python file.
   */
  public void genSwiftFiles(
      SoyFileSetNode soyTree,
      SoySwiftSrcOptions swiftSrcOptions,
      String outputPathFormat,
      ErrorReporter errorReporter)
      throws IOException {

    ImmutableList<SoyFileNode> srcsToCompile = ImmutableList.copyOf(soyTree.getChildren());

    // Determine the output paths.
    List<String> soyNamespaces = getSoyNamespaces(soyTree);
    Multimap<String, Integer> outputs =
        MainEntryPointUtils.mapOutputsToSrcs(null, outputPathFormat, srcsToCompile);

    // Generate the manifest and add it to the current manifest.
    ImmutableMap<String, String> manifest = generateManifest(soyNamespaces, outputs);

    // Generate the Swift source.
    List<String> swiftFileContents = genSwiftSource(soyTree, swiftSrcOptions, manifest, errorReporter);

    if (srcsToCompile.size() != swiftFileContents.size()) {
      throw new AssertionError(
          String.format(
              "Expected to generate %d code chunk(s), got %d",
              srcsToCompile.size(), swiftFileContents.size()));
    }

    // Write out the Swift outputs.
    for (String outputFilePath : outputs.keySet()) {
      try (Writer out = Files.newWriter(new File(outputFilePath), StandardCharsets.UTF_8)) {
        for (int inputFileIndex : outputs.get(outputFilePath)) {
          out.write(swiftFileContents.get(inputFileIndex));
        }
      }
    }



    // Write out template renderer map
    // [namespace + ] template -> Swift fuction name
    //
    if (swiftSrcOptions.namespaceManifestFile() != null) {
      try (Writer out = Files.newWriter(new File(swiftSrcOptions.namespaceManifestFile()),
          StandardCharsets.UTF_8)) {
        StringBuilder rendererMap = new StringBuilder();

        rendererMap.append("import Soy\n");

        rendererMap.append("\n\n");

        rendererMap.append(
            "public typealias SoyTemplateRenderFunc = ([String, SoyValue], [String: SoyValue]) -> String\n");
        rendererMap.append("\n\n");

        rendererMap.append(
            "fileprivate let SOY_LOOKUP: [String: SoyTemplateRenderFunc] = [\n");

        for (SoyFileNode soyFile : soyTree.getChildren()) {
          for (TemplateNode template : soyFile.getChildren()) {
            if (Visibility.PUBLIC == template.getVisibility()) {
              // write out
              String key = template.getTemplateName();
              String functionName = GenSoyTemplateRendererName.makeFuncName(template);
              rendererMap.append("    ").append("\"").append(key).append("\": ")
                  .append(functionName).append(", \n");
            }
          }
        }
        rendererMap.append("]\n");

        rendererMap.append("\n\n");

        rendererMap.append("public func findSoyRenderer(for templateName: String) -> SoyTemplateRenderFunc? {\n");
        rendererMap.append("    return SOY_LOOKUP[templateName]\n");
        rendererMap.append("}\n");
        
        out.write(rendererMap.toString());
      }
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
  @Deprecated
  private static ImmutableMap<String, String> generateManifest(
      List<String> soyNamespaces, Multimap<String, Integer> outputs) {
    ImmutableMap.Builder<String, String> manifest = new ImmutableMap.Builder<>();
    for (String outputFilePath : outputs.keySet()) {
      for (int inputFileIndex : outputs.get(outputFilePath)) {
        String swiftPath = outputFilePath.replace(".swift", "").replace('/', '.');

        manifest.put(soyNamespaces.get(inputFileIndex), swiftPath);
      }
    }
    return manifest.build();
  }

  @VisibleForTesting
  static GenSwiftCodeVisitor createVisitor(
      SoySwiftSrcOptions swiftSrcOptions,
      BidiGlobalDir bidiGlobalDir,
      ErrorReporter errorReporter,
      ImmutableMap<String, String> currentManifest) {
    final IsComputableAsSwiftExprVisitor isComputableAsSwiftExprsVisitor =
        new IsComputableAsSwiftExprVisitor();
    // TODO
    // There is a circular dependency between the GenPyExprsVisitorFactory and GenPyCallExprVisitor
    // here we resolve it with a mutable field in a custom provider
    final SwiftValueFactoryImpl pluginValueFactory =
        new SwiftValueFactoryImpl(errorReporter, bidiGlobalDir);
    class SwiftCallExprVisitorSupplier implements Supplier<GenSwiftCallExprVisitor> {
      GenSwiftExprsVisitorFactory factory;

      @Override
      public GenSwiftCallExprVisitor get() {
        return new GenSwiftCallExprVisitor(isComputableAsSwiftExprsVisitor, pluginValueFactory, checkNotNull(factory));
      }
    }
    SwiftCallExprVisitorSupplier provider = new SwiftCallExprVisitorSupplier();
    GenSwiftExprsVisitorFactory genSwiftExprsFactory =
        new GenSwiftExprsVisitorFactory(isComputableAsSwiftExprsVisitor, provider, pluginValueFactory);
    provider.factory = genSwiftExprsFactory;

    return new GenSwiftCodeVisitor(
        swiftSrcOptions,
        currentManifest,
        isComputableAsSwiftExprsVisitor,
        genSwiftExprsFactory,
        provider.get(),
        pluginValueFactory);
  }
}
