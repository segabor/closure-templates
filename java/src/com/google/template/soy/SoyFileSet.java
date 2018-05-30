/*
 * Copyright 2008 Google Inc.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.ByteSink;
import com.google.common.io.CharSource;
import com.google.inject.Guice;
import com.google.protobuf.Descriptors.GenericDescriptor;
import com.google.template.soy.SoyFileSetParser.ParseResult;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.base.internal.VolatileSoyFileSupplier;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.basetree.SyntaxVersion;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyCompilationException;
import com.google.template.soy.error.SoyError;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.jbcsrc.BytecodeCompiler;
import com.google.template.soy.jbcsrc.api.SoySauce;
import com.google.template.soy.jbcsrc.api.SoySauceImpl;
import com.google.template.soy.jbcsrc.shared.CompiledTemplate;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates;
import com.google.template.soy.jssrc.SoyJsSrcOptions;
import com.google.template.soy.jssrc.internal.JsSrcMain;
import com.google.template.soy.logging.LoggingConfig;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.SoyMsgBundleHandler;
import com.google.template.soy.msgs.SoyMsgBundleHandler.OutputFileOptions;
import com.google.template.soy.msgs.SoyMsgPlugin;
import com.google.template.soy.msgs.internal.ExtractMsgsVisitor;
import com.google.template.soy.msgs.restricted.SoyMsg;
import com.google.template.soy.msgs.restricted.SoyMsgBundleImpl;
import com.google.template.soy.parseinfo.passes.GenerateParseInfoVisitor;
import com.google.template.soy.passes.ClearSoyDocStringsVisitor;
import com.google.template.soy.passes.FindIjParamsVisitor;
import com.google.template.soy.passes.FindIjParamsVisitor.IjParamsInfo;
import com.google.template.soy.passes.FindTransitiveDepTemplatesVisitor;
import com.google.template.soy.passes.FindTransitiveDepTemplatesVisitor.TransitiveDepTemplatesInfo;
import com.google.template.soy.passes.PassManager;
import com.google.template.soy.pysrc.SoyPySrcOptions;
import com.google.template.soy.pysrc.internal.PySrcMain;
import com.google.template.soy.shared.SoyAstCache;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.internal.GuiceSimpleScope;
import com.google.template.soy.shared.internal.MainEntryPointUtils;
import com.google.template.soy.shared.restricted.ApiCallScopeBindingAnnotations.ApiCall;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soyparse.PluginResolver;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.soytree.Visibility;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;
import com.google.template.soy.swiftsrc.internal.SwiftSrcMain;
import com.google.template.soy.tofu.SoyTofu;
import com.google.template.soy.tofu.internal.BaseTofu;
import com.google.template.soy.types.SoyTypeRegistry;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Represents a complete set of Soy files for compilation as one bundle. The files may depend on
 * each other but should not have dependencies outside of the set.
 *
 * <p>Note: Soy file (or resource) contents must be encoded in UTF-8.
 */
public final class SoyFileSet {
  private static final Logger logger = Logger.getLogger(SoyFileSet.class.getName());

  /**
   * Creates a builder with the standard set of Soy directives, functions, and types.
   *
   * <p>If you need additional directives, functions, or types, create the Builder instance using
   * Guice. If your project doesn't otherwise use Guice, you can just use Guice.createInjector with
   * only the modules you need, similar to the implementation of this method.
   */
  public static Builder builder() {
    return Guice.createInjector(new SoyModule()).getInstance(Builder.class);
  }

  // Implementation detail of SoyFileSet.Builder.
  // having it as its own 'parameter' class removes a small amount of boilerplate.
  static final class CoreDependencies {
    private final GuiceSimpleScope apiCallScope;
    private final ImmutableMap<String, ? extends SoyFunction> soyFunctionMap;
    private final ImmutableMap<String, ? extends SoyPrintDirective> printDirectives;

    @Inject
    CoreDependencies(
        @ApiCall GuiceSimpleScope apiCallScope,
        ImmutableMap<String, ? extends SoyFunction> soyFunctionMap,
        ImmutableMap<String, ? extends SoyPrintDirective> printDirectives) {
      this.apiCallScope = apiCallScope;
      this.soyFunctionMap = soyFunctionMap;
      this.printDirectives = printDirectives;
    }
  }

  /**
   * Builder for a {@code SoyFileSet}.
   *
   * <p>Instances of this can be obtained by calling {@link #builder()} or by installing {@link
   * SoyModule} and injecting it.
   */
  public static final class Builder {
    /** The SoyFileSuppliers collected so far in added order, as a set to prevent dupes. */
    private final ImmutableMap.Builder<String, SoyFileSupplier> filesBuilder;

    /** Optional AST cache. */
    private SoyAstCache cache;

    /** The general compiler options. */
    private SoyGeneralOptions lazyGeneralOptions;

    private final CoreDependencies coreDependencies;

    /** The SoyProtoTypeProvider builder that will be built for local type registry. */
    private final SoyTypeRegistry.Builder typeRegistryBuilder = new SoyTypeRegistry.Builder();

    @Nullable private Appendable warningSink;

    private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;

    private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;

    Builder(CoreDependencies coreDependencies) {
      this.coreDependencies = coreDependencies;
      this.filesBuilder = ImmutableMap.builder();
      this.cache = null;
      this.lazyGeneralOptions = null;
    }

    /**
     * Sets all Soy general options.
     *
     * <p>This must be called before any other setters.
     */
    public void setGeneralOptions(SoyGeneralOptions generalOptions) {
      Preconditions.checkState(
          lazyGeneralOptions == null,
          "Call SoyFileSet#setGeneralOptions before any other setters.");
      Preconditions.checkNotNull(generalOptions, "Non-null argument expected.");
      lazyGeneralOptions = generalOptions.clone();
    }

    /**
     * Returns and/or lazily-creates the SoyGeneralOptions for this builder.
     *
     * <p>Laziness is an important feature to ensure that setGeneralOptions can fail if options were
     * already set. Otherwise, it'd be easy to set some options on this builder and overwrite them
     * by calling setGeneralOptions.
     */
    private SoyGeneralOptions getGeneralOptions() {
      if (lazyGeneralOptions == null) {
        lazyGeneralOptions = new SoyGeneralOptions();
      }
      return lazyGeneralOptions;
    }

    /**
     * Builds the new {@code SoyFileSet}.
     *
     * @return The new {@code SoyFileSet}.
     */
    public SoyFileSet build() {
      return new SoyFileSet(
          coreDependencies.apiCallScope,
          typeRegistryBuilder.build(),
          coreDependencies.soyFunctionMap,
          coreDependencies.printDirectives,
          filesBuilder.build(),
          getGeneralOptions(),
          cache,
          conformanceConfig,
          loggingConfig,
          warningSink);
    }

    /**
     * Adds an input Soy file, given a {@code CharSource} for the file content, as well as the
     * desired file path for messages.
     *
     * @param contentSource Source for the Soy file content.
     * @param soyFileKind The kind of this input Soy file.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder addWithKind(CharSource contentSource, SoyFileKind soyFileKind, String filePath) {
      return addFile(SoyFileSupplier.Factory.create(contentSource, soyFileKind, filePath));
    }

    /**
     * Adds an input Soy file, given a {@code File}.
     *
     * @param inputFile The Soy file.
     * @param soyFileKind The kind of this input Soy file.
     * @return This builder.
     */
    public Builder addWithKind(File inputFile, SoyFileKind soyFileKind) {
      return addFile(SoyFileSupplier.Factory.create(inputFile, soyFileKind));
    }

    /**
     * Adds an input Soy file, given a resource {@code URL}, as well as the desired file path for
     * messages.
     *
     * @param inputFileUrl The Soy file.
     * @param soyFileKind The kind of this input Soy file.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder addWithKind(URL inputFileUrl, SoyFileKind soyFileKind, String filePath) {
      return addFile(SoyFileSupplier.Factory.create(inputFileUrl, soyFileKind, filePath));
    }

    /**
     * Adds an input Soy file, given a resource {@code URL}.
     *
     * <p>Important: This function assumes that the desired file path is returned by {@code
     * inputFileUrl.toString()}. If this is not the case, please use {@link #addWithKind(URL,
     * SoyFileKind, String)} instead.
     *
     * @see #addWithKind(URL, SoyFileKind, String)
     * @param inputFileUrl The Soy file.
     * @param soyFileKind The kind of this input Soy file.
     * @return This builder.
     */
    public Builder addWithKind(URL inputFileUrl, SoyFileKind soyFileKind) {
      return addFile(SoyFileSupplier.Factory.create(inputFileUrl, soyFileKind));
    }

    /**
     * Adds an input Soy file, given the file content provided as a string, as well as the desired
     * file path for messages.
     *
     * @param content The Soy file content.
     * @param soyFileKind The kind of this input Soy file.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder addWithKind(CharSequence content, SoyFileKind soyFileKind, String filePath) {
      return addFile(SoyFileSupplier.Factory.create(content, soyFileKind, filePath));
    }

    /**
     * Adds an input Soy file, given a {@code CharSource} for the file content, as well as the
     * desired file path for messages.
     *
     * @param contentSource Source for the Soy file content.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder add(CharSource contentSource, String filePath) {
      return addWithKind(contentSource, SoyFileKind.SRC, filePath);
    }

    /**
     * Adds an input Soy file, given a resource {@code URL}, as well as the desired file path for
     * messages.
     *
     * @param inputFileUrl The Soy file.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder add(URL inputFileUrl, String filePath) {
      return addWithKind(inputFileUrl, SoyFileKind.SRC, filePath);
    }

    /**
     * Adds an input Soy file, given a resource {@code URL}.
     *
     * <p>Important: This function assumes that the desired file path is returned by {@code
     * inputFileUrl.toString()}. If this is not the case, please use {@link #add(URL, String)}
     * instead.
     *
     * @see #add(URL, String)
     * @param inputFileUrl The Soy file.
     * @return This builder.
     */
    public Builder add(URL inputFileUrl) {
      return addWithKind(inputFileUrl, SoyFileKind.SRC);
    }

    /**
     * Adds an input Soy file, given the file content provided as a string, as well as the desired
     * file path for messages.
     *
     * @param content The Soy file content.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder add(CharSequence content, String filePath) {
      return addWithKind(content, SoyFileKind.SRC, filePath);
    }

    /**
     * Adds an input Soy file, given a {@code File}.
     *
     * @param inputFile The Soy file.
     * @return This builder.
     */
    public Builder add(File inputFile) {
      return addWithKind(inputFile, SoyFileKind.SRC);
    }

    /**
     * Adds an input Soy file that supports checking for modifications, given a {@code File}.
     *
     * <p>Note: This does nothing by itself. It should be used in conjunction with a feature that
     * actually checks for volatile files. Currently, that feature is {@link
     * #setSoyAstCache(SoyAstCache)}.
     *
     * @param inputFile The Soy file.
     * @param soyFileKind The kind of this input Soy file.
     * @return This builder.
     */
    public Builder addVolatileWithKind(File inputFile, SoyFileKind soyFileKind) {
      return addFile(new VolatileSoyFileSupplier(inputFile, soyFileKind));
    }

    /**
     * Adds an input Soy file that supports checking for modifications, given a {@code File}.
     *
     * <p>Note: This does nothing by itself. It should be used in conjunction with a feature that
     * actually checks for volatile files. Currently, that feature is {@link
     * #setSoyAstCache(SoyAstCache)}.
     *
     * @param inputFile The Soy file.
     * @return This builder.
     */
    public Builder addVolatile(File inputFile) {
      return addVolatileWithKind(inputFile, SoyFileKind.SRC);
    }

    /**
     * Configures to use an AST cache to speed up development time.
     *
     * <p>This is undesirable in production mode since it uses strictly more memory, and this only
     * helps if the same templates are going to be recompiled frequently.
     *
     * @param cache The cache to use, which can have a lifecycle independent of the SoyFileSet. Null
     *     indicates not to use a cache.
     * @return This builder.
     */
    public Builder setSoyAstCache(SoyAstCache cache) {
      this.cache = cache;
      return this;
    }

    /**
     * Sets whether to allow external calls (calls to undefined templates).
     *
     * @param allowExternalCalls Whether to allow external calls (calls to undefined templates).
     * @return This builder.
     */
    public Builder setAllowExternalCalls(boolean allowExternalCalls) {
      getGeneralOptions().setAllowExternalCalls(allowExternalCalls);
      return this;
    }

    /**
     * Sets experimental features. These features are unreleased and are not generally available.
     *
     * @param experimentalFeatures
     * @return This builder.
     */
    public Builder setExperimentalFeatures(List<String> experimentalFeatures) {
      getGeneralOptions().setExperimentalFeatures(experimentalFeatures);
      return this;
    }

    /**
     * Disables optimizer. The optimizer tries to simplify the Soy AST by evaluating constant
     * expressions. It generally improves performance and should only be disabled in integration
     * tests.
     *
     * <p>This is public only because we need to set it in {@code SoyFileSetHelper}, that are
     * necessary for integration tests. Normal users should not use this.
     *
     * @return This builder.
     */
    public Builder disableOptimizer() {
      getGeneralOptions().disableOptimizer();
      return this;
    }

    /**
     * Sets whether to force strict autoescaping. Enabling will cause compile time exceptions if
     * non-strict autoescaping is used in namespaces or templates.
     *
     * @param strictAutoescapingRequired Whether strict autoescaping is required.
     * @return This builder.
     */
    public Builder setStrictAutoescapingRequired(boolean strictAutoescapingRequired) {
      getGeneralOptions().setStrictAutoescapingRequired(strictAutoescapingRequired);
      return this;
    }

    /**
     * Sets the map from compile-time global name to value.
     *
     * <p>The values can be any of the Soy primitive types: null, boolean, integer, float (Java
     * double), or string.
     *
     * @param compileTimeGlobalsMap Map from compile-time global name to value. The values can be
     *     any of the Soy primitive types: null, boolean, integer, float (Java double), or string.
     * @return This builder.
     * @throws IllegalArgumentException If one of the values is not a valid Soy primitive type.
     */
    public Builder setCompileTimeGlobals(Map<String, ?> compileTimeGlobalsMap) {
      getGeneralOptions().setCompileTimeGlobals(compileTimeGlobalsMap);
      return this;
    }

    /**
     * Sets the file containing compile-time globals.
     *
     * <p>Each line of the file should have the format
     *
     * <pre>
     *     &lt;global_name&gt; = &lt;primitive_data&gt;
     * </pre>
     *
     * where primitive_data is a valid Soy expression literal for a primitive type (null, boolean,
     * integer, float, or string). Empty lines and lines beginning with "//" are ignored. The file
     * should be encoded in UTF-8.
     *
     * <p>If you need to generate a file in this format from Java, consider using the utility {@code
     * SoyUtils.generateCompileTimeGlobalsFile()}.
     *
     * @param compileTimeGlobalsFile The file containing compile-time globals.
     * @return This builder.
     * @throws IOException If there is an error reading the compile-time globals file.
     */
    public Builder setCompileTimeGlobals(File compileTimeGlobalsFile) throws IOException {
      getGeneralOptions().setCompileTimeGlobals(compileTimeGlobalsFile);
      return this;
    }

    /**
     * Sets the resource file containing compile-time globals.
     *
     * <p>Each line of the file should have the format
     *
     * <pre>
     *     &lt;global_name&gt; = &lt;primitive_data&gt;
     * </pre>
     *
     * where primitive_data is a valid Soy expression literal for a primitive type (null, boolean,
     * integer, float, or string). Empty lines and lines beginning with "//" are ignored. The file
     * should be encoded in UTF-8.
     *
     * <p>If you need to generate a file in this format from Java, consider using the utility {@code
     * SoyUtils.generateCompileTimeGlobalsFile()}.
     *
     * @param compileTimeGlobalsResource The resource containing compile-time globals.
     * @return This builder.
     * @throws IOException If there is an error reading the compile-time globals file.
     */
    public Builder setCompileTimeGlobals(URL compileTimeGlobalsResource) throws IOException {
      getGeneralOptions().setCompileTimeGlobals(compileTimeGlobalsResource);
      return this;
    }

    /**
     * Add all proto descriptors found in the file to the type registry.
     *
     * @param descriptorFile A file containing FileDescriptorSet binary protos. These typically end
     *     in {@code .proto.bin}. Note that this isn't the same as a {@code .proto} source file.
     */
    public Builder addProtoDescriptorsFromFile(File descriptorFile) throws IOException {
      typeRegistryBuilder.addFileDescriptorSetFromFile(descriptorFile);
      return this;
    }

    /**
     * Registers a collection of protocol buffer descriptors. This makes all the types defined in
     * the provided descriptors available to use in soy.
     */
    public Builder addProtoDescriptors(GenericDescriptor... descriptors) {
      return addProtoDescriptors(Arrays.asList(descriptors));
    }

    /**
     * Registers a collection of protocol buffer descriptors. This makes all the types defined in
     * the provided descriptors available to use in soy.
     */
    public Builder addProtoDescriptors(Iterable<? extends GenericDescriptor> descriptors) {
      typeRegistryBuilder.addDescriptors(descriptors);
      return this;
    }

    /** Registers a conformance config proto. */
    Builder setConformanceConfig(ValidatedConformanceConfig config) {
      checkNotNull(config);
      this.conformanceConfig = config;
      return this;
    }

    private Builder addFile(SoyFileSupplier supplier) {
      filesBuilder.put(supplier.getFilePath(), supplier);
      return this;
    }

    /**
     * Configures a place to write warnings for successful compilations.
     *
     * <p>For compilation failures warnings are reported along with the errors, by throwing an
     * exception. The default is to report warnings to the logger for SoyFileSet.
     */
    Builder setWarningSink(Appendable warningSink) {
      this.warningSink = checkNotNull(warningSink);
      return this;
    }

    /**
     * Sets the logging config to use.
     *
     * @throws IllegalArgumentException if the config proto is invalid. For example, if there are
     *     multiple elements with the same {@code name} or {@code id}, or if the name not a valid
     *     identifier.
     */
    public Builder setLoggingConfig(LoggingConfig config) {
      return setValidatedLoggingConfig(ValidatedLoggingConfig.create(config));
    }

    /** Sets the validated logging config to use. */
    Builder setValidatedLoggingConfig(ValidatedLoggingConfig parseLoggingConfigs) {
      this.loggingConfig = checkNotNull(parseLoggingConfigs);
      return this;
    }
  }

  private final GuiceSimpleScope apiCallScopeProvider;

  private final SoyTypeRegistry typeRegistry;
  private final ImmutableMap<String, SoyFileSupplier> soyFileSuppliers;

  /** Optional soy tree cache for faster recompile times. */
  @Nullable private final SoyAstCache cache;

  private final SoyGeneralOptions generalOptions;

  private final ValidatedConformanceConfig conformanceConfig;
  private final ValidatedLoggingConfig loggingConfig;

  /** For private use by pruneTranslatedMsgs(). */
  private ImmutableSet<Long> memoizedExtractedMsgIdsForPruning;

  private final ImmutableMap<String, ? extends SoyFunction> soyFunctionMap;
  private final ImmutableMap<String, ? extends SoyPrintDirective> printDirectives;

  /** For reporting errors during parsing. */
  private ErrorReporter errorReporter;

  @Nullable private final Appendable warningSink;

  SoyFileSet(
      GuiceSimpleScope apiCallScopeProvider,
      SoyTypeRegistry typeRegistry,
      ImmutableMap<String, ? extends SoyFunction> soyFunctionMap,
      ImmutableMap<String, ? extends SoyPrintDirective> printDirectives,
      ImmutableMap<String, SoyFileSupplier> soyFileSuppliers,
      SoyGeneralOptions generalOptions,
      @Nullable SoyAstCache cache,
      ValidatedConformanceConfig conformanceConfig,
      ValidatedLoggingConfig loggingConfig,
      @Nullable Appendable warningSink) {
    this.apiCallScopeProvider = apiCallScopeProvider;

    Preconditions.checkArgument(
        !soyFileSuppliers.isEmpty(), "Must have non-zero number of input Soy files.");
    this.typeRegistry = typeRegistry;
    this.soyFileSuppliers = soyFileSuppliers;
    this.cache = cache;
    this.generalOptions = generalOptions.clone();
    this.soyFunctionMap = soyFunctionMap;
    this.printDirectives = printDirectives;
    this.conformanceConfig = checkNotNull(conformanceConfig);
    this.loggingConfig = checkNotNull(loggingConfig);
    this.warningSink = warningSink;
  }

  /** Returns the list of suppliers for the input Soy files. For testing use only! */
  @VisibleForTesting
  ImmutableMap<String, SoyFileSupplier> getSoyFileSuppliersForTesting() {
    return soyFileSuppliers;
  }

  /**
   * Generates Java classes containing parse info (param names, template names, meta info). There
   * will be one Java class per Soy file.
   *
   * @param javaPackage The Java package for the generated classes.
   * @param javaClassNameSource Source of the generated class names. Must be one of "filename",
   *     "namespace", or "generic".
   * @return A map from generated file name (of the form "<*>SoyInfo.java") to generated file
   *     content.
   * @throws SoyCompilationException If compilation fails.
   */
  ImmutableMap<String, String> generateParseInfo(String javaPackage, String javaClassNameSource) {
    resetErrorReporter();
    // TODO(lukes): see if we can enforce that globals are provided at compile time here. given that
    // types have to be, this should be possible.  Currently it is disabled for backwards
    // compatibility
    // N.B. we do not run the optimizer here for 2 reasons:
    // 1. it would just waste time, since we are not running code generation the optimization work
    //    doesn't help anything
    // 2. it potentially removes metadata from the tree by precalculating expressions. For example,
    //    trivial print nodes are evaluated, which can remove globals from the tree, but the
    //    generator requires data about globals to generate accurate proto descriptors.  Also, the
    //    ChangeCallsToPassAllData pass will change the params of templates.
    ParseResult result =
        parse(
            passManagerBuilder().allowUnknownGlobals().optimize(false),
            typeRegistry,
            new PluginResolver(
                // we allow undefined plugins since they typically aren't provided :(
                PluginResolver.Mode.ALLOW_UNDEFINED,
                printDirectives,
                soyFunctionMap,
                errorReporter));
    throwIfErrorsPresent();

    SoyFileSetNode soyTree = result.fileSet();
    TemplateRegistry registry = result.registry();

    // Do renaming of package-relative class names.
    ImmutableMap<String, String> parseInfo =
        new GenerateParseInfoVisitor(javaPackage, javaClassNameSource, registry).exec(soyTree);
    throwIfErrorsPresent();
    reportWarnings();
    return parseInfo;
  }

  /**
   * Extracts all messages from this Soy file set into a SoyMsgBundle (which can then be turned into
   * an extracted messages file with the help of a SoyMsgBundleHandler).
   *
   * @return A SoyMsgBundle containing all the extracted messages (locale "en").
   * @throws SoyCompilationException If compilation fails.
   */
  public SoyMsgBundle extractMsgs() {
    resetErrorReporter();
    SoyMsgBundle bundle = doExtractMsgs();
    reportWarnings();
    return bundle;
  }

  /**
   * Extracts all messages from this Soy file set and writes the messages to an output sink.
   *
   * @param msgBundleHandler Handler to write the messages.
   * @param options Options to configure how to write the extracted messages.
   * @param output Where to write the extracted messages.
   * @throws IOException If there are errors writing to the output.
   */
  public void extractAndWriteMsgs(
      SoyMsgBundleHandler msgBundleHandler, OutputFileOptions options, ByteSink output)
      throws IOException {
    resetErrorReporter();
    SoyMsgBundle bundle = doExtractMsgs();
    msgBundleHandler.writeExtractedMsgs(bundle, options, output, errorReporter);
    throwIfErrorsPresent();
    reportWarnings();
  }

  /** Performs the parsing and extraction logic. */
  private SoyMsgBundle doExtractMsgs() {
    // extractMsgs disables a bunch of passes since it is typically not configured with things
    // like global definitions, type definitions, etc.
    // TODO(b/32091399): Message Extraction doesn't have a way to configure the version and it needs
    // to support all soy files so we assume the worst and configure v1.0.  This can go away when
    // jssrc no longer supports v1.0
    generalOptions.setDeclaredSyntaxVersionName("1.0");
    SoyFileSetNode soyTree =
        parse(
                passManagerBuilder()
                    .allowUnknownGlobals()
                    .setTypeRegistry(SoyTypeRegistry.DEFAULT_UNKNOWN)
                    .disableAllTypeChecking(),
                // override the type registry so that the parser doesn't report errors when it
                // can't resolve strict types
                SoyTypeRegistry.DEFAULT_UNKNOWN,
                new PluginResolver(
                    PluginResolver.Mode.ALLOW_UNDEFINED,
                    printDirectives,
                    soyFunctionMap,
                    errorReporter))
            .fileSet();
    throwIfErrorsPresent();
    SoyMsgBundle bundle = new ExtractMsgsVisitor().exec(soyTree);
    throwIfErrorsPresent();
    return bundle;
  }

  /**
   * Prunes messages from a given message bundle, keeping only messages used in this Soy file set.
   *
   * <p>Important: Do not use directly. This is subject to change and your code will break.
   *
   * <p>Note: This method memoizes intermediate results to improve efficiency in the case that it is
   * called multiple times (which is a common case). Thus, this method will not work correctly if
   * the underlying Soy files are modified between calls to this method.
   *
   * @param origTransMsgBundle The message bundle to prune.
   * @return The pruned message bundle.
   * @throws SoyCompilationException If compilation fails.
   */
  public SoyMsgBundle pruneTranslatedMsgs(SoyMsgBundle origTransMsgBundle) {
    resetErrorReporter();
    // ------ Extract msgs from all the templates reachable from public templates. ------
    // Note: In the future, instead of using all public templates as the root set, we can allow the
    // user to provide a root set.
    // TODO(b/32091399): Message Extraction doesn't have a way to configure the version and it needs
    // to support all soy files so we assume the worst and configure v1.0.  This can go away when
    // jssrc no longer supports v1.0
    generalOptions.setDeclaredSyntaxVersionName("1.0");
    if (memoizedExtractedMsgIdsForPruning == null) {
      ParseResult result =
          parse(
              passManagerBuilder().allowUnknownGlobals().disableAllTypeChecking(),
              // override the type registry so that the parser doesn't report errors when it
              // can't resolve strict types
              SoyTypeRegistry.DEFAULT_UNKNOWN,
              new PluginResolver(
                  PluginResolver.Mode.ALLOW_UNDEFINED,
                  printDirectives,
                  soyFunctionMap,
                  errorReporter));

      throwIfErrorsPresent();
      SoyFileSetNode soyTree = result.fileSet();
      TemplateRegistry registry = result.registry();

      List<TemplateNode> allPublicTemplates = Lists.newArrayList();
      for (SoyFileNode soyFile : soyTree.getChildren()) {
        for (TemplateNode template : soyFile.getChildren()) {
          if (template.getVisibility() == Visibility.PUBLIC) {
            allPublicTemplates.add(template);
          }
        }
      }
      Map<TemplateNode, TransitiveDepTemplatesInfo> depsInfoMap =
          new FindTransitiveDepTemplatesVisitor(registry)
              .execOnMultipleTemplates(allPublicTemplates);
      TransitiveDepTemplatesInfo mergedDepsInfo =
          TransitiveDepTemplatesInfo.merge(depsInfoMap.values());

      SoyMsgBundle extractedMsgBundle =
          new ExtractMsgsVisitor().execOnMultipleNodes(mergedDepsInfo.depTemplateSet);

      ImmutableSet.Builder<Long> extractedMsgIdsBuilder = ImmutableSet.builder();
      for (SoyMsg extractedMsg : extractedMsgBundle) {
        extractedMsgIdsBuilder.add(extractedMsg.getId());
      }
      throwIfErrorsPresent();
      memoizedExtractedMsgIdsForPruning = extractedMsgIdsBuilder.build();
    }

    // ------ Prune. ------

    ImmutableList.Builder<SoyMsg> prunedTransMsgsBuilder = ImmutableList.builder();
    for (SoyMsg transMsg : origTransMsgBundle) {
      if (memoizedExtractedMsgIdsForPruning.contains(transMsg.getId())) {
        prunedTransMsgsBuilder.add(transMsg);
      }
    }
    throwIfErrorsPresent();
    return new SoyMsgBundleImpl(
        origTransMsgBundle.getLocaleString(), prunedTransMsgsBuilder.build());
  }

  /**
   * Compiles this Soy file set into a Java object (type {@code SoyTofu}) capable of rendering the
   * compiled templates.
   *
   * @return The resulting {@code SoyTofu} object.
   * @throws SoyCompilationException If compilation fails.
   */
  public SoyTofu compileToTofu() {
    resetErrorReporter();
    ServerCompilationPrimitives primitives = compileForServerRendering();
    throwIfErrorsPresent();
    SoyTofu tofu = doCompileToTofu(primitives);

    reportWarnings();
    return tofu;
  }

  /** Helper method to compile SoyTofu from {@link ServerCompilationPrimitives} */
  private SoyTofu doCompileToTofu(ServerCompilationPrimitives primitives) {
    return new BaseTofu(
        apiCallScopeProvider,
        primitives.registry,
        getTransitiveIjs(primitives.soyTree, primitives.registry));
  }

  /**
   * Compiles this Soy file set into a set of java classes implementing the {@link SoySauce}
   * interface.
   *
   * <p>This is useful for implementing 'edit refresh' workflows. Most production usecases should
   * use the command line interface to 'ahead of time' compile templates to jar files and then use
   * {@code PrecompiledSoyModule} to get access to a {@link SoySauce} object without invoking the
   * compiler. This will allow applications to avoid invoking the soy compiler at runtime which can
   * be relatively slow.
   *
   * @return A set of compiled templates
   * @throws SoyCompilationException If compilation fails.
   */
  public SoySauce compileTemplates() {
    resetErrorReporter();
    disallowExternalCalls();
    ServerCompilationPrimitives primitives = compileForServerRendering();
    throwIfErrorsPresent();
    SoySauce sauce = doCompileSoySauce(primitives);

    reportWarnings();
    return sauce;
  }

  /**
   * Compiles this Soy file set into a set of java classes implementing the {@link CompiledTemplate}
   * interface and writes them out to the given ByteSink as a JAR file.
   *
   * @throws SoyCompilationException If compilation fails.
   */
  void compileToJar(ByteSink jarTarget, Optional<ByteSink> srcJarTarget) throws IOException {
    resetErrorReporter();
    disallowExternalCalls();
    ServerCompilationPrimitives primitives = compileForServerRendering();
    BytecodeCompiler.compileToJar(primitives.registry, errorReporter, jarTarget);
    if (srcJarTarget.isPresent()) {
      BytecodeCompiler.writeSrcJar(primitives.registry, soyFileSuppliers, srcJarTarget.get());
    }
    throwIfErrorsPresent();
    reportWarnings();
  }

  /** Helper method to compile SoySauce from {@link ServerCompilationPrimitives} */
  private SoySauce doCompileSoySauce(ServerCompilationPrimitives primitives) {
    Optional<CompiledTemplates> templates =
        BytecodeCompiler.compile(
            primitives.registry,
            // if there is an AST cache, assume we are in 'dev mode' and trigger lazy compilation.
            cache != null,
            errorReporter);

    throwIfErrorsPresent();

    return new SoySauceImpl(templates.get(), apiCallScopeProvider, soyFunctionMap, printDirectives);
  }

  /**
   * A tuple of the outputs of shared compiler passes that are needed to produce SoyTofu or
   * SoySauce.
   */
  private static final class ServerCompilationPrimitives {
    final SoyFileSetNode soyTree;
    final TemplateRegistry registry;

    ServerCompilationPrimitives(TemplateRegistry registry, SoyFileSetNode soyTree) {
      this.registry = registry;
      this.soyTree = soyTree;
    }
  }

  /** Runs common compiler logic shared by tofu and jbcsrc backends. */
  private ServerCompilationPrimitives compileForServerRendering() {
    ParseResult result = parse();
    throwIfErrorsPresent();

    SoyFileSetNode soyTree = result.fileSet();
    TemplateRegistry registry = result.registry();
    // Clear the SoyDoc strings because they use unnecessary memory, unless we have a cache, in
    // which case it is pointless.
    if (cache == null) {
      new ClearSoyDocStringsVisitor().exec(soyTree);
    }

    throwIfErrorsPresent();
    return new ServerCompilationPrimitives(registry, soyTree);
  }

  private ImmutableMap<String, ImmutableSortedSet<String>> getTransitiveIjs(
      SoyFileSetNode soyTree, TemplateRegistry registry) {
    ImmutableMap<TemplateNode, IjParamsInfo> templateToIjParamsInfoMap =
        new FindIjParamsVisitor(registry).execOnAllTemplates(soyTree);
    ImmutableMap.Builder<String, ImmutableSortedSet<String>> templateToTransitiveIjParams =
        ImmutableMap.builder();
    for (Map.Entry<TemplateNode, IjParamsInfo> entry : templateToIjParamsInfoMap.entrySet()) {
      templateToTransitiveIjParams.put(
          entry.getKey().getTemplateName(), entry.getValue().ijParamSet);
    }
    return templateToTransitiveIjParams.build();
  }

  private void disallowExternalCalls() {
    TriState allowExternalCalls = generalOptions.allowExternalCalls();
    if (allowExternalCalls == TriState.UNSET) {
      generalOptions.setAllowExternalCalls(false);
    } else if (allowExternalCalls == TriState.ENABLED) {
      throw new IllegalStateException(
          "SoyGeneralOptions.setAllowExternalCalls(true) is not supported with this method");
    }
    // otherwise, it was already explicitly set to false which is what we want.
  }

  private void requireStrictAutoescaping() {
    TriState strictAutoescapingRequired = generalOptions.isStrictAutoescapingRequired();
    if (strictAutoescapingRequired == TriState.UNSET) {
      generalOptions.setStrictAutoescapingRequired(true);
    } else if (strictAutoescapingRequired == TriState.DISABLED) {
      throw new IllegalStateException(
          "SoyGeneralOptions.isStrictAutoescapingRequired(false) is not supported with this"
              + " method");
    }
    // otherwise, it was already explicitly set to true which is what we want.
  }

  /**
   * Compiles this Soy file set into JS source code files and returns these JS files as a list of
   * strings, one per file.
   *
   * @param jsSrcOptions The compilation options for the JS Src output target.
   * @param msgBundle The bundle of translated messages, or null to use the messages from the Soy
   *     source.
   * @return A list of strings where each string represents the JS source code that belongs in one
   *     JS file. The generated JS files correspond one-to-one to the original Soy source files.
   * @throws SoyCompilationException If compilation fails.
   */
  @SuppressWarnings("deprecation")
  public List<String> compileToJsSrc(
      SoyJsSrcOptions jsSrcOptions, @Nullable SoyMsgBundle msgBundle) {
    ParseResult result = preprocessJsSrcResults(jsSrcOptions);
    TemplateRegistry registry = result.registry();
    SoyFileSetNode fileSet = result.fileSet();
    List<String> generatedSrcs =
        new JsSrcMain(apiCallScopeProvider, typeRegistry)
            .genJsSrc(fileSet, registry, jsSrcOptions, msgBundle, errorReporter);
    throwIfErrorsPresent();
    reportWarnings();
    return generatedSrcs;
  }

  /**
   * Compiles this Soy file set into JS source code files and writes these JS files to disk.
   *
   * @param outputPathFormat The format string defining how to build the output file path
   *     corresponding to an input file path.
   * @param jsSrcOptions The compilation options for the JS Src output target.
   * @param locales The list of locales. Can be an empty list if not applicable.
   * @param msgPlugin The {@link SoyMsgPlugin} to use, or null if not applicable
   * @param messageFilePathFormat The message file path format, or null if not applicable.
   * @throws SoyCompilationException If compilation fails.
   * @throws IOException If there is an error in opening/reading a message file or opening/writing
   *     an output JS file.
   */
  @SuppressWarnings("deprecation")
  void compileToJsSrcFiles(
      String outputPathFormat,
      SoyJsSrcOptions jsSrcOptions,
      List<String> locales,
      @Nullable SoyMsgPlugin msgPlugin,
      @Nullable String messageFilePathFormat)
      throws IOException {
    ParseResult result = preprocessJsSrcResults(jsSrcOptions);

    SoyFileSetNode soyTree = result.fileSet();
    TemplateRegistry registry = result.registry();
    if (locales.isEmpty()) {
      // Not generating localized JS.
      new JsSrcMain(apiCallScopeProvider, typeRegistry)
          .genJsFiles(
              soyTree,
              registry,
              jsSrcOptions,
              null,
              null,
              outputPathFormat,
              errorReporter);

    } else {
      checkArgument(
          msgPlugin != null, "a message plugin must be provided when generating localized sources");
      checkArgument(
          messageFilePathFormat != null,
          "a messageFilePathFormat must be provided when generating localized sources");
      // Generating localized JS.
      for (String locale : locales) {

        SoyFileSetNode soyTreeClone = soyTree.copy(new CopyState());

        String msgFilePath = MainEntryPointUtils.buildFilePath(messageFilePathFormat, locale, null);

        SoyMsgBundle msgBundle =
            new SoyMsgBundleHandler(msgPlugin).createFromFile(new File(msgFilePath));
        if (msgBundle.getLocaleString() == null) {
          // TODO: Remove this check (but make sure no projects depend on this behavior).
          // There was an error reading the message file. We continue processing only if the locale
          // begins with "en", because falling back to the Soy source will probably be fine.
          if (!locale.startsWith("en")) {
            throw new IOException("Error opening or reading message file " + msgFilePath);
          }
        }

        new JsSrcMain(apiCallScopeProvider, typeRegistry)
            .genJsFiles(
                soyTreeClone,
                registry,
                jsSrcOptions,
                locale,
                msgBundle,
                outputPathFormat,
                errorReporter);
      }
    }
    throwIfErrorsPresent();
    reportWarnings();
  }

  @SuppressWarnings("deprecation")
  private ParseResult preprocessJsSrcResults(SoyJsSrcOptions jsSrcOptions) {
    resetErrorReporter();

    // Synchronize old and new ways to declare syntax version V1.
    if (jsSrcOptions.shouldAllowDeprecatedSyntax()) {
      generalOptions.setDeclaredSyntaxVersionName("1.0");
    }
    // JS has traditionally allowed unknown globals, as a way for soy to reference normal js enums
    // and constants. For consistency/reusability of templates it would be nice to not allow that
    // but the cat is out of the bag.
    PassManager.Builder builder =
        passManagerBuilder().allowUnknownGlobals().desugarHtmlNodes(false);
    ParseResult parseResult = parse(builder);
    throwIfErrorsPresent();
    return parseResult;
  }

  /** Prepares the parsed result for use in generating Incremental DOM source code. */
  @SuppressWarnings("deprecation")
  private ParseResult preprocessIncrementalDOMResults() {
    SyntaxVersion declaredSyntaxVersion = generalOptions.getDeclaredSyntaxVersion();

    Preconditions.checkState(
        declaredSyntaxVersion == SyntaxVersion.V2_0,
        "Incremental DOM code generation only supports syntax version of V2");
    requireStrictAutoescaping();
    // For incremental dom backend, we don't desugar HTML nodes since it requires HTML context.
    ParseResult result = parse(passManagerBuilder().desugarHtmlNodes(false));
    throwIfErrorsPresent();
    return result;
  }

  /**
   * Compiles this Soy file set into Python source code files and writes these Python files to disk.
   *
   * @param outputPathFormat The format string defining how to build the output file path
   *     corresponding to an input file path.
   * @param pySrcOptions The compilation options for the Python Src output target.
   * @throws SoyCompilationException If compilation fails.
   * @throws IOException If there is an error in opening/reading a message file or opening/writing
   *     an output JS file.
   */
  void compileToPySrcFiles(String outputPathFormat, SoyPySrcOptions pySrcOptions)
      throws IOException {
    resetErrorReporter();
    requireStrictAutoescaping();
    ParseResult result = parse();
    throwIfErrorsPresent();
    new PySrcMain(apiCallScopeProvider)
        .genPyFiles(result.fileSet(), pySrcOptions, outputPathFormat, errorReporter);

    throwIfErrorsPresent();
    reportWarnings();
  }

  public void compileToSwiftSrcFiles(
      String outputPathFormat, SoySwiftSrcOptions swiftSrcOptions)
      throws IOException {
    resetErrorReporter();
    requireStrictAutoescaping();
    ParseResult result = parse();
    throwIfErrorsPresent();
    new SwiftSrcMain(apiCallScopeProvider)
        .genSwiftFiles(
            result.fileSet(),
            swiftSrcOptions,
            outputPathFormat,
            errorReporter);

    throwIfErrorsPresent();
    reportWarnings();
  }

  // Parse the current file set with the given default syntax version.
  private ParseResult parse() {
    return parse(passManagerBuilder());
  }

  private ParseResult parse(PassManager.Builder builder) {
    return parse(
        builder,
        typeRegistry,
        new PluginResolver(
            generalOptions.getDeclaredSyntaxVersion() == SyntaxVersion.V1_0
                ? PluginResolver.Mode.ALLOW_UNDEFINED_FUNCTIONS_FOR_V1_SUPPORT
                : PluginResolver.Mode.REQUIRE_DEFINITIONS,
            printDirectives,
            soyFunctionMap,
            errorReporter));
  }

  private ParseResult parse(
      PassManager.Builder builder, SoyTypeRegistry typeRegistry, PluginResolver resolver) {
    return SoyFileSetParser.newBuilder()
        .setCache(cache)
        .setSoyFileSuppliers(soyFileSuppliers)
        .setTypeRegistry(typeRegistry)
        .setPluginResolver(resolver)
        .setPassManager(builder.setTypeRegistry(typeRegistry).build())
        .setErrorReporter(errorReporter)
        .setGeneralOptions(generalOptions)
        .build()
        .parse();
  }

  private PassManager.Builder passManagerBuilder() {
    return new PassManager.Builder()
        .setGeneralOptions(generalOptions)
        .setSoyPrintDirectiveMap(printDirectives)
        .setErrorReporter(errorReporter)
        .setConformanceConfig(conformanceConfig)
        .setLoggingConfig(loggingConfig);
  }

  /**
   * This method resets the error reporter field in preparation to a new compiler invocation.
   *
   * <p>This method should be called at the beginning of every entry point into SoyFileSet.
   */
  private void resetErrorReporter() {
    errorReporter = ErrorReporter.create(soyFileSuppliers);
  }

  private void throwIfErrorsPresent() {
    if (errorReporter.hasErrors()) {
      // if we are reporting errors we should report warnings at the same time.
      Iterable<SoyError> errors =
          Iterables.concat(errorReporter.getErrors(), errorReporter.getWarnings());
      // clear the field to ensure that error reporters can't leak between compilations
      errorReporter = null;
      throw new SoyCompilationException(errors);
    }
  }

  /**
   * Reports warnings ot the user configured warning sink. Should be called at the end of successful
   * compiles.
   */
  private void reportWarnings() {
    ImmutableList<SoyError> warnings = errorReporter.getWarnings();
    if (warnings.isEmpty()) {
      return;
    }
    // this is a custom feature used by the integration test suite.
    if (generalOptions.getExperimentalFeatures().contains("testonly_throw_on_warnings")) {
      errorReporter = null;
      throw new SoyCompilationException(warnings);
    }
    String formatted = SoyErrors.formatErrors(warnings);
    if (warningSink != null) {
      try {
        warningSink.append(formatted);
      } catch (IOException ioe) {
        System.err.println("error while printing warnings");
        ioe.printStackTrace();
      }
    } else {
      logger.warning(formatted);
    }
  }
}
