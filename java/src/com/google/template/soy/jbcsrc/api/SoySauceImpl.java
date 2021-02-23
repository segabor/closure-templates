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

package com.google.template.soy.jbcsrc.api;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.template.soy.jbcsrc.api.AppendableAsAdvisingAppendable.asAdvisingAppendable;
import static com.google.template.soy.jbcsrc.shared.Names.rewriteStackTrace;

import com.google.common.base.Ascii;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyTemplate;
import com.google.template.soy.data.SoyTemplateData;
import com.google.template.soy.data.SoyValueConverter;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import com.google.template.soy.data.internal.BasicParamStore;
import com.google.template.soy.data.internal.ParamStore;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.jbcsrc.shared.CompiledTemplate;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates;
import com.google.template.soy.jbcsrc.shared.LegacyFunctionAdapter;
import com.google.template.soy.jbcsrc.shared.RenderContext;
import com.google.template.soy.logging.SoyLogger;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.shared.SoyIdRenamingMap;
import com.google.template.soy.shared.internal.SoyScopedData;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyJavaFunction;
import com.google.template.soy.shared.restricted.SoyJavaPrintDirective;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Main entry point for rendering Soy templates on the server. */
public final class SoySauceImpl implements SoySauce {
  private final CompiledTemplates templates;
  private final SoyScopedData.Enterable apiCallScope;
  private final ImmutableMap<String, Supplier<Object>> pluginInstances;
  private final ImmutableMap<String, SoyJavaPrintDirective> printDirectives;

  public SoySauceImpl(
      CompiledTemplates templates,
      SoyScopedData.Enterable apiCallScope,
      ImmutableList<? extends SoyFunction> functions,
      ImmutableList<? extends SoyPrintDirective> printDirectives,
      ImmutableMap<String, Supplier<Object>> pluginInstances) {
    this.templates = checkNotNull(templates);
    this.apiCallScope = checkNotNull(apiCallScope);
    ImmutableMap.Builder<String, Supplier<Object>> pluginInstanceBuilder = ImmutableMap.builder();
    pluginInstanceBuilder.putAll(pluginInstances);

    for (SoyFunction fn : functions) {
      if (fn instanceof SoyJavaFunction) {
        pluginInstanceBuilder.put(
            fn.getName(), Suppliers.ofInstance(new LegacyFunctionAdapter((SoyJavaFunction) fn)));
      }
    }

    // SoySauce has no need for SoyPrintDirectives that are not SoyJavaPrintDirectives.
    // Filter them out.
    ImmutableMap.Builder<String, SoyJavaPrintDirective> soyJavaPrintDirectives =
        ImmutableMap.builder();
    for (SoyPrintDirective printDirective : printDirectives) {
      if (printDirective instanceof SoyJavaPrintDirective) {
        soyJavaPrintDirectives.put(
            printDirective.getName(), (SoyJavaPrintDirective) printDirective);
      }
    }
    this.printDirectives = soyJavaPrintDirectives.build();
    this.pluginInstances = pluginInstanceBuilder.build();
  }

  @Override
  public ImmutableSortedSet<String> getTransitiveIjParamsForTemplate(String templateName) {
    return templates.getTransitiveIjParamsForTemplate(templateName);
  }

  @Override
  public ImmutableList<String> getAllRequiredCssNamespaces(
      String templateName,
      Predicate<String> enabledDelpackages,
      boolean collectCssFromDelvariants) {
    return templates.getAllRequiredCssNamespaces(
        templateName, enabledDelpackages, collectCssFromDelvariants);
  }

  @Override
  public boolean hasTemplate(String template) {
    try {
      templates.getTemplate(template);
      return true;
    } catch (IllegalArgumentException iae) {
      return false;
    }
  }

  @Override
  public RendererImpl renderTemplate(String template) {
    CompiledTemplates.TemplateData data = templates.getTemplateData(template);
    return new RendererImpl(template, data.template(), data.kind(), /* data=*/ null);
  }

  @Override
  public RendererImpl newRenderer(SoyTemplate params) {
    String template = params.getTemplateName();
    CompiledTemplates.TemplateData data = templates.getTemplateData(template);
    return new RendererImpl(template, data.template(), data.kind(), params.getParamsAsMap());
  }

  final class RendererImpl implements Renderer {
    private final String templateName;
    private final CompiledTemplate template;
    private final ContentKind contentKind;
    private final RenderContext.Builder contextBuilder =
        new RenderContext.Builder(templates, printDirectives, SoySauceImpl.this.pluginInstances);

    private SoyRecord data;
    private SoyRecord ij;
    private boolean dataSetInConstructor;

    RendererImpl(
        String templateName,
        CompiledTemplate template,
        ContentKind contentKind,
        @Nullable Map<String, ?> data) {
      this.templateName = templateName;
      this.template = checkNotNull(template);
      this.contentKind = contentKind;
      if (data != null) {
        this.data = soyValueProviderMapAsParamStore(data);
        // TODO(lukes): eliminate this and just use the nullness of data to enforce this.
        this.dataSetInConstructor = true;
      }
    }

    private BasicParamStore soyValueProviderMapAsParamStore(Map<String, ?> source) {
      BasicParamStore dest = new BasicParamStore(source.size());
      for (Map.Entry<String, ?> entry : source.entrySet()) {
        dest.setField(entry.getKey(), (SoyValueProvider) entry.getValue());
      }
      return dest;
    }

    private BasicParamStore mapAsParamStore(Map<String, ?> source) {
      BasicParamStore dest = new BasicParamStore(source.size());
      for (Map.Entry<String, ?> entry : source.entrySet()) {
        String key = entry.getKey();
        SoyValueProvider value;
        try {
          value = SoyValueConverter.INSTANCE.convert(entry.getValue());
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Unable to convert param " + key + " to a SoyValue", e);
        }
        dest.setField(key, value);
      }
      return dest;
    }

    @Override
    public RendererImpl setIj(Map<String, ?> record) {
      this.ij = mapAsParamStore(record);
      return this;
    }

    @Override
    public RendererImpl setIj(SoyTemplateData templateData) {
      this.ij = soyValueProviderMapAsParamStore(templateData.getParamsAsMap());
      return this;
    }

    @Override
    public RendererImpl setPluginInstances(Map<String, Supplier<Object>> pluginInstances) {
      contextBuilder.withPluginInstances(
          ImmutableMap.<String, Supplier<Object>>builderWithExpectedSize(
                  SoySauceImpl.this.pluginInstances.size() + pluginInstances.size())
              .putAll(SoySauceImpl.this.pluginInstances)
              .putAll(pluginInstances)
              .build());
      return this;
    }

    @Override
    public RendererImpl setData(Map<String, ?> record) {
      checkState(
          !dataSetInConstructor,
          "May not call setData on a Renderer created from a TemplateParams");

      this.data = mapAsParamStore(record);
      return this;
    }

    @Override
    public RendererImpl setActiveDelegatePackageSelector(Predicate<String> active) {
      contextBuilder.withActiveDelPackageSelector(checkNotNull(active));
      return this;
    }

    @Override
    public RendererImpl setCssRenamingMap(SoyCssRenamingMap cssRenamingMap) {
      contextBuilder.withCssRenamingMap(cssRenamingMap);
      return this;
    }

    @Override
    public RendererImpl setXidRenamingMap(SoyIdRenamingMap xidRenamingMap) {
      contextBuilder.withXidRenamingMap(xidRenamingMap);
      return this;
    }

    @Override
    public RendererImpl setMsgBundle(SoyMsgBundle msgs) {
      contextBuilder.withMessageBundle(msgs);
      return this;
    }

    @Override
    public RendererImpl setDebugSoyTemplateInfo(boolean debugSoyTemplateInfo) {
      contextBuilder.withDebugSoyTemplateInfo(debugSoyTemplateInfo);
      return this;
    }

    @Override
    public RendererImpl setSoyLogger(SoyLogger logger) {
      contextBuilder.withLogger(logger);
      return this;
    }

    @Override
    public WriteContinuation renderHtml(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.HTML);
    }

    @Override
    public Continuation<SanitizedContent> renderHtml() {
      return renderToSanitizedContent(ContentKind.HTML);
    }

    @Override
    public WriteContinuation renderJs(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.JS);
    }

    @Override
    public Continuation<SanitizedContent> renderJs() {
      return renderToSanitizedContent(ContentKind.JS);
    }

    @Override
    public WriteContinuation renderUri(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.URI);
    }

    @Override
    public Continuation<SanitizedContent> renderUri() {
      return renderToSanitizedContent(ContentKind.URI);
    }

    @Override
    public WriteContinuation renderTrustedResourceUri(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.TRUSTED_RESOURCE_URI);
    }

    @Override
    public Continuation<SanitizedContent> renderTrustedResourceUri() {
      return renderToSanitizedContent(ContentKind.TRUSTED_RESOURCE_URI);
    }

    @Override
    public WriteContinuation renderAttributes(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.ATTRIBUTES);
    }

    @Override
    public Continuation<SanitizedContent> renderAttributes() {
      return renderToSanitizedContent(ContentKind.ATTRIBUTES);
    }

    @Override
    public WriteContinuation renderCss(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.CSS);
    }

    @Override
    public Continuation<SanitizedContent> renderCss() {
      return renderToSanitizedContent(ContentKind.CSS);
    }

    @Override
    public WriteContinuation renderText(AdvisingAppendable out) throws IOException {
      return startRender(out, ContentKind.TEXT);
    }

    @Override
    public Continuation<String> renderText() {
      return renderToValue(Function.identity());
    }

    private Continuation<SanitizedContent> renderToSanitizedContent(ContentKind kind) {
      enforceContentKind(kind);
      return renderToValue(s -> UnsafeSanitizedContentOrdainer.ordainAsSafe(s, kind));
    }

    private < T>
        Continuation<T> renderToValue(Function<String, T> factory) {
      StringBuilder sb = new StringBuilder();
      try {
        return Continuations.valueContinuation(
            startRender(asAdvisingAppendable(sb), contentKind), () -> factory.apply(sb.toString()));
      } catch (IOException e) {
        throw new AssertionError("impossible", e);
      }
    }

    private <T> WriteContinuation startRender(AdvisingAppendable out, ContentKind contentKind)
        throws IOException {
      enforceContentKind(contentKind);

      SoyRecord params = data == null ? ParamStore.EMPTY_INSTANCE : data;
      SoyRecord injectedParams = ij == null ? ParamStore.EMPTY_INSTANCE : ij;
      RenderContext context = contextBuilder.build();
      OutputAppendable output = OutputAppendable.create(out, context.getLogger());
      RendererClosure renderer = () -> template.render(params, injectedParams, output, context);

      return doRender(renderer, new Scoper(apiCallScope, context.getBidiGlobalDir()));
    }

    private void enforceContentKind(ContentKind expectedContentKind) {
      if (expectedContentKind == ContentKind.TEXT) {
        // Allow any template to be called as text.
        return;
      }
      if (expectedContentKind != contentKind) {
        throw new IllegalStateException(
            "Expected template '"
                + templateName
                + "' to be kind=\""
                + Ascii.toLowerCase(expectedContentKind.name())
                + "\" but was kind=\""
                + Ascii.toLowerCase(contentKind.name())
                + "\"");
      }
    }
  }

  @FunctionalInterface
  private interface RendererClosure {
    RenderResult render() throws IOException;
  }

  private static WriteContinuation doRender(RendererClosure renderer, Scoper scoper)
      throws IOException {
    RenderResult result;
    try (SoyScopedData.InScope scope = scoper.enter()) {
      result = renderer.render();
    } catch (Throwable t) {
      rewriteStackTrace(t);
      Throwables.throwIfInstanceOf(t, IOException.class);
      throw t;
    }
    if (result.isDone()) {
      return Continuations.done();
    }
    return new WriteContinuationImpl(result, renderer, scoper);
  }

  private static final class WriteContinuationImpl implements WriteContinuation {
    final RenderResult result;
    final Object lock = new Object();

    @GuardedBy("lock")
    final Scoper scoper;

    @GuardedBy("lock")
    final RendererClosure renderer;

    @GuardedBy("lock")
    boolean hasContinueBeenCalled;

    WriteContinuationImpl(RenderResult result, RendererClosure renderer, Scoper scoper) {
      checkArgument(!result.isDone());
      this.result = checkNotNull(result);
      this.renderer = checkNotNull(renderer);
      this.scoper = checkNotNull(scoper);
    }

    @Override
    public RenderResult result() {
      return result;
    }

    @Override
    public WriteContinuation continueRender() throws IOException {
      synchronized (lock) {
        if (hasContinueBeenCalled) {
          throw new IllegalStateException("continueRender() has already been called.");
        }
        hasContinueBeenCalled = true;
        return doRender(renderer, scoper);
      }
    }
  }

  private static final class Scoper {
    final SoyScopedData.Enterable scope;
    final BidiGlobalDir dir;

    Scoper(SoyScopedData.Enterable scope, BidiGlobalDir dir) {
      this.scope = scope;
      this.dir = dir;
    }

    SoyScopedData.InScope enter() {
      return scope.enter(dir);
    }
  }
}
