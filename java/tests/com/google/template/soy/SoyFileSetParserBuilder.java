/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Guice;
import com.google.template.soy.SoyFileSetParser.ParseResult;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.basetree.SyntaxVersion;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.passes.PassManager;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.AutoEscapingType;
import com.google.template.soy.shared.SharedTestUtils;
import com.google.template.soy.shared.SoyAstCache;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.internal.InternalPlugins;
import com.google.template.soy.shared.internal.SharedModule;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soyparse.PluginResolver;
import com.google.template.soy.types.SoyTypeRegistry;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Fluent builder for configuring {@link com.google.template.soy.SoyFileSetParser}s in tests.
 *
 * @author brndn@google.com (Brendan Linn)
 */
public final class SoyFileSetParserBuilder {

  private final ImmutableMap<String, SoyFileSupplier> soyFileSuppliers;
  private SoyTypeRegistry typeRegistry = new SoyTypeRegistry();
  @Nullable private SoyAstCache astCache = null;
  private ErrorReporter errorReporter = ErrorReporter.exploding(); // See #parse for discussion.
  private boolean allowUnboundGlobals;
  @Inject private ImmutableMap<String, ? extends SoyFunction> soyFunctionMap;
  @Inject private ImmutableMap<String, ? extends SoyPrintDirective> soyPrintDirectiveMap;
  private ImmutableMap<String, SoySourceFunction> sourceFunctionMap;
  // disable optimization by default
  private SoyGeneralOptions options = new SoyGeneralOptions().disableOptimizer();
  private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;
  private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;
  private boolean desugarHtmlNodes = true;
  // TODO(lukes): disabled for compatibility with unit tests.  fix tests relying on the
  // escaper not running and enable by default.  This configuration bit only really exists
  // for incrementaldomsrc, not tests
  private boolean runAutoescaper = false;
  // By default, do not modify the AST to add the HTML comments, since many unit tests depend on
  // the order of the nodes in the AST.
  private boolean addHtmlAttributesForDebugging = false;

  /**
   * Returns a builder that gets its Soy inputs from the given strings, treating each string as the
   * full contents of a Soy file.
   */
  public static SoyFileSetParserBuilder forFileContents(String... fileContents) {
    return new SoyFileSetParserBuilder(fileContents);
  }

  /** Returns a builder that gets its Soy inputs from the given {@link SoyFileSupplier}s. */
  public static SoyFileSetParserBuilder forSuppliers(SoyFileSupplier... suppliers) {
    return forSuppliers(Arrays.asList(suppliers));
  }

  public static SoyFileSetParserBuilder forSuppliers(Iterable<SoyFileSupplier> files) {
    return new SoyFileSetParserBuilder(files);
  }

  /**
   * Returns a builder that gets its Soy inputs from the given strings, treating each string as the
   * contents of a Soy template.
   */
  public static SoyFileSetParserBuilder forTemplateContents(String... templateContents) {
    return forTemplateContents(AutoEscapingType.DEPRECATED_NONCONTEXTUAL, templateContents);
  }

  /**
   * Returns a builder that gets its Soy inputs from the given strings, treating each string as the
   * contents of a Soy template, and using the given {@link AutoEscapingType}.
   */
  public static SoyFileSetParserBuilder forTemplateContents(
      AutoEscapingType autoEscapingType, String... templateContents) {
    return forTemplateContents(autoEscapingType, /* strictHtml= */ false, templateContents);
  }

  /**
   * Returns a builder that gets its Soy inputs from the given strings, treating each string as the
   * contents of a Soy template, and using the given {@link AutoEscapingType} and strictHtml mode.
   */
  public static SoyFileSetParserBuilder forTemplateContents(
      AutoEscapingType autoEscapingType, boolean strictHtml, String... templateContents) {
    String[] fileContents = new String[templateContents.length];
    for (int i = 0; i < fileContents.length; ++i) {
      fileContents[i] =
          SharedTestUtils.buildTestSoyFileContent(
              autoEscapingType, strictHtml, /* soyDocParamNames= */ null, templateContents[i]);
    }
    return new SoyFileSetParserBuilder(fileContents);
  }

  private SoyFileSetParserBuilder(String... soyCode) {
    this(ImmutableList.copyOf(buildTestSoyFileSuppliers(soyCode)));
  }

  private SoyFileSetParserBuilder(Iterable<SoyFileSupplier> suppliers) {
    ImmutableMap.Builder<String, SoyFileSupplier> builder = ImmutableMap.builder();
    for (SoyFileSupplier supplier : suppliers) {
      builder.put(supplier.getFilePath(), supplier);
    }
    this.soyFileSuppliers = builder.build();
    // inject our @Inject fields to get the default set of functions and print directives
    Guice.createInjector(new SharedModule()).injectMembers(this);
    this.sourceFunctionMap = InternalPlugins.internalFunctionMap();
  }

  /** Sets the parser's declared syntax version. Returns this object, for chaining. */
  public SoyFileSetParserBuilder declaredSyntaxVersion(SyntaxVersion version) {
    this.options.setDeclaredSyntaxVersionName(version.name);
    return this;
  }

  public SoyFileSetParserBuilder errorReporter(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
    return this;
  }

  public SoyFileSetParserBuilder addSoyFunction(SoyFunction function) {
    return addSoyFunctions(ImmutableList.of(function));
  }

  public SoyFileSetParserBuilder addSoyFunctions(Iterable<? extends SoyFunction> soyFunctions) {
    Map<String, SoyFunction> functions = new LinkedHashMap<>();
    functions.putAll(soyFunctionMap);
    for (SoyFunction function : soyFunctions) {
      functions.put(function.getName(), function);
    }
    this.soyFunctionMap = ImmutableMap.copyOf(functions);
    return this;
  }

  /** Warning: This SoySourceFunctions are not yet working completely. Do not use this method. */
  @Deprecated
  public SoyFileSetParserBuilder addSoySourceFunction(SoySourceFunction function) {
    return addSoySourceFunction(ImmutableList.of(function));
  }

  /** Warning: This SoySourceFunctions are not yet working completely. Do not use this method. */
  @Deprecated
  public SoyFileSetParserBuilder addSoySourceFunction(
      Iterable<? extends SoySourceFunction> sourceFunctions) {
    Map<String, SoySourceFunction> functions = new LinkedHashMap<>();
    functions.putAll(sourceFunctionMap);
    for (Map.Entry<String, SoySourceFunction> entry :
        InternalPlugins.fromFunctions(sourceFunctions).entrySet()) {
      functions.put(entry.getKey(), entry.getValue());
    }
    this.sourceFunctionMap = ImmutableMap.copyOf(functions);
    return this;
  }

  public SoyFileSetParserBuilder addPrintDirectives(
      Iterable<? extends SoyPrintDirective> soyPrintDirectives) {
    Map<String, SoyPrintDirective> directives = new LinkedHashMap<>();
    directives.putAll(soyPrintDirectiveMap);
    for (SoyPrintDirective printDirective : soyPrintDirectives) {
      directives.put(printDirective.getName(), printDirective);
    }
    this.soyPrintDirectiveMap = ImmutableMap.copyOf(directives);
    return this;
  }

  public SoyFileSetParserBuilder addPrintDirective(SoyPrintDirective printDirective) {
    return addPrintDirectives(ImmutableList.of(printDirective));
  }

  public SoyFileSetParserBuilder options(SoyGeneralOptions options) {
    this.options = checkNotNull(options);
    return this;
  }

  public SoyFileSetParserBuilder typeRegistry(SoyTypeRegistry typeRegistry) {
    this.typeRegistry = typeRegistry;
    return this;
  }

  public SoyFileSetParserBuilder allowUnboundGlobals(boolean allowUnboundGlobals) {
    this.allowUnboundGlobals = allowUnboundGlobals;
    return this;
  }

  public SoyFileSetParserBuilder setConformanceConfig(ValidatedConformanceConfig config) {
    this.conformanceConfig = checkNotNull(config);
    return this;
  }

  public SoyFileSetParserBuilder setLoggingConfig(ValidatedLoggingConfig loggingConfig) {
    this.loggingConfig = checkNotNull(loggingConfig);
    return this;
  }

  public SoyFileSetParserBuilder desugarHtmlNodes(boolean desugarHtmlNodes) {
    this.desugarHtmlNodes = desugarHtmlNodes;
    return this;
  }

  public SoyFileSetParserBuilder runAutoescaper(boolean runAutoescaper) {
    this.runAutoescaper = runAutoescaper;
    return this;
  }

  /**
   * Tests can use this method to force running {@code AddDebugAttributesPass}. By default, this
   * compiler pass is disabled for tests, since it modifies the AST structure and will break a lot
   * of unit tests that rely on particular structure.
   */
  public SoyFileSetParserBuilder addHtmlAttributesForDebugging(
      boolean addHtmlAttributesForDebugging) {
    this.addHtmlAttributesForDebugging = addHtmlAttributesForDebugging;
    return this;
  }

  private static List<SoyFileSupplier> buildTestSoyFileSuppliers(String... soyFileContents) {

    List<SoyFileSupplier> soyFileSuppliers = Lists.newArrayList();
    for (int i = 0; i < soyFileContents.length; i++) {
      String soyFileContent = soyFileContents[i];
      // Names are now required to be unique in a SoyFileSet. Use one-based indexing.
      String filePath = (i == 0) ? "no-path" : ("no-path-" + (i + 1));
      soyFileSuppliers.add(
          SoyFileSupplier.Factory.create(soyFileContent, SoyFileKind.SRC, filePath));
    }
    return soyFileSuppliers;
  }

  /**
   * Constructs a parse tree from the builder's state, returning the root of the tree.
   *
   * <p>Note: since {@link SoyFileSetParserBuilder} can only be used in tests, this method will
   * throw an {@link AssertionError} if any error is encountered during parsing. For tests that
   * require different behavior (for example, tests that need to inspect the full list of errors
   * encountered during compilation), pass a different {@link ErrorReporter} implementation to
   * {@link #errorReporter}.
   */
  public ParseResult parse() {
    PassManager.Builder passManager =
        new PassManager.Builder()
            .setSoyPrintDirectiveMap(soyPrintDirectiveMap)
            .setErrorReporter(errorReporter)
            .setTypeRegistry(typeRegistry)
            .desugarHtmlNodes(desugarHtmlNodes)
            .setGeneralOptions(options)
            .setConformanceConfig(conformanceConfig)
            .setAutoescaperEnabled(runAutoescaper)
            .addHtmlAttributesForDebugging(addHtmlAttributesForDebugging)
            .setLoggingConfig(loggingConfig);
    if (allowUnboundGlobals) {
      passManager.allowUnknownGlobals();
    }
    return SoyFileSetParser.newBuilder()
        .setCache(astCache)
        .setSoyFileSuppliers(soyFileSuppliers)
        .setTypeRegistry(typeRegistry)
        .setPluginResolver(
            new PluginResolver(
                PluginResolver.Mode.REQUIRE_DEFINITIONS,
                ImmutableMap.copyOf(soyPrintDirectiveMap),
                ImmutableMap.copyOf(soyFunctionMap),
                sourceFunctionMap,
                errorReporter))
        .setPassManager(passManager.build())
        .setErrorReporter(errorReporter)
        .setGeneralOptions(options)
        .build()
        .parse();
  }
}
