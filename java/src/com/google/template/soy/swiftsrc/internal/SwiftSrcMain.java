package com.google.template.soy.swiftsrc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.shared.internal.SoyScopedData;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.Visibility;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;
import com.google.template.soy.swiftsrc.internal.GenSwiftExprsVisitor.GenSwiftExprsVisitorFactory;

public class SwiftSrcMain {

  private static final SoyErrorKind DUPLICATE_NAMESPACE_ERROR =
      SoyErrorKind.of(
          "Multiple files are providing the same namespace: {0}. Soy namespaces must be unique.");

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
   * @param errorReporter The Soy error reporter that collects errors during code generation.
   * @return A list of strings where each string represents the Swift source code that belongs in
   *     one Swift file. The generated Swift files correspond one-to-one to the original Soy
   *     source files.
   * @throws IOException If a syntax error is found.
   */
  public List<String> genSwiftSource(
      SoyFileSetNode soyTree,
      SoySwiftSrcOptions swiftSrcOptions,
      ErrorReporter errorReporter) {

    // Generate the manifest and add it to the current manifest.
    ImmutableMap<String, String> manifest =
            generateManifest(
                    getSoyNamespaces(soyTree),
                    swiftSrcOptions.getInputToOutputFilePaths(),
                    swiftSrcOptions.getOutputDirectoryFlag(),
                    errorReporter);

    // FIXME do we need this?
    BidiGlobalDir bidiGlobalDir = BidiGlobalDir.LTR;
//    BidiGlobalDir bidiGlobalDir =
//        SoyBidiUtils.decodeBidiGlobalDirFromPyOptions(swiftSrcOptions.getBidiIsRtlFn());
    try (SoyScopedData.InScope inScope = apiCallScope.enter(/* msgBundle= */ null, bidiGlobalDir)) {
      return createVisitor(swiftSrcOptions, inScope.getBidiGlobalDir(), errorReporter, manifest)
          .gen(soyTree, errorReporter);
    }
  }

  /**
   * Generates Swift source files given a Soy parse tree, an options object, and information on
   * where to put the output files.
   *
   * @param soyTree The Soy parse tree to generate Python source code for.
   * @param swiftSrcOptions The compilation options relevant to this backend.
   * @param errorReporter The Soy error reporter that collects errors during code generation.
   * @throws IOException If there is an error in opening/writing an output Python file.
   */
  public List<String> genSwiftFiles(
      SoyFileSetNode soyTree, SoySwiftSrcOptions swiftSrcOptions, ErrorReporter errorReporter)
      throws IOException {

    ImmutableList<SoyFileNode> srcsToCompile = ImmutableList.copyOf(soyTree.getChildren());

    // Generate the Swift source.
    List<String> swiftFileContents = genSwiftSource(soyTree, swiftSrcOptions, errorReporter);

    if (srcsToCompile.size() != swiftFileContents.size()) {
      throw new AssertionError(
          String.format(
              "Expected to generate %d code chunk(s), got %d",
              srcsToCompile.size(), swiftFileContents.size()));
    }


    // Write out template renderer map
    // [namespace + ] template -> Swift fuction name
    //
    if (swiftSrcOptions.namespaceManifestFile() != null) {
      try (Writer out = Files.newWriter(new File(swiftSrcOptions.namespaceManifestFile()),
          StandardCharsets.UTF_8)) {
        StringBuilder rendererMap = new StringBuilder();

        rendererMap.append("import SoyKit\n");

        rendererMap.append("\n\n");

        rendererMap.append(
            "public typealias SoyTemplateRenderFunc = (SoyValue, SoyValue) throws -> String\n");
        rendererMap.append("\n\n");

        rendererMap.append(
            "fileprivate let SOY_LOOKUP: [String: SoyTemplateRenderFunc] = [\n");

        for (SoyFileNode soyFile : soyTree.getChildren()) {
          for (TemplateNode template : soyFile.getTemplates()) {
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

    return swiftFileContents;
  }

  private static ImmutableMap<SourceFilePath, String> getSoyNamespaces(SoyFileSetNode soyTree) {
    ImmutableMap.Builder<SourceFilePath, String> namespaces = new ImmutableMap.Builder<>();
    for (SoyFileNode soyFile : soyTree.getChildren()) {
      namespaces.put(soyFile.getFilePath(), soyFile.getNamespace());
    }
    return namespaces.build();
  }

  /**
   * Generate the manifest file by finding the output file paths and converting them into a Swift
   * import format.
   */
  @Deprecated
  private static ImmutableMap<String, String> generateManifest(
      Map<SourceFilePath, String> soyNamespaces,
      Map<SourceFilePath, Path> inputToOutputPaths,
      Optional<Path> outputDirectoryFlag,
      ErrorReporter errorReporter) {

    Map<String, String> manifest = new HashMap<>();

    for (SourceFilePath inputFilePath : inputToOutputPaths.keySet()) {
      String outputFilePath = inputToOutputPaths.get(inputFilePath).toString();
      String swiftPath = outputFilePath.replace(".swift", "").replace('/', '.');

      String namespace = soyNamespaces.get(inputFilePath);
      if (manifest.containsKey(namespace)) {
        errorReporter.report(SourceLocation.UNKNOWN, DUPLICATE_NAMESPACE_ERROR, namespace);
      }

      manifest.put(namespace, swiftPath);
    }

    return ImmutableMap.copyOf(manifest);
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
