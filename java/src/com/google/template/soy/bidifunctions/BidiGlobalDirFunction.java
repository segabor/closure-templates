/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.bidifunctions;

import com.google.common.base.Supplier;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptPluginContext;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValue;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValueFactory;
import com.google.template.soy.plugin.javascript.restricted.SoyJavaScriptSourceFunction;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
import com.google.template.soy.pysrc.restricted.SoyPySrcFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.TypedSoyFunction;
import com.google.template.soy.swiftsrc.restricted.SoySwiftSrcFunction;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Soy function that returns the current global bidi directionality (1 for LTR or -1 for RTL).
 *
 */
@SoyFunctionSignature(name = "bidiGlobalDir", value = @Signature(returnType = "int"))
final class BidiGlobalDirFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJavaScriptSourceFunction, SoyPySrcFunction, SoySwiftSrcFunction {

  /** Supplier for the current bidi global directionality. */
  private final Supplier<BidiGlobalDir> bidiGlobalDirProvider;

  /** @param bidiGlobalDirProvider Supplier for the current bidi global directionality. */
  BidiGlobalDirFunction(Supplier<BidiGlobalDir> bidiGlobalDirProvider) {
    this.bidiGlobalDirProvider = bidiGlobalDirProvider;
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    static final Method BIDI_GLOBAL_DIR =
        JavaValueFactory.createMethod(
            BidiFunctionsRuntime.class, "bidiGlobalDir", BidiGlobalDir.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return factory.callStaticMethod(Methods.BIDI_GLOBAL_DIR, context.getBidiDir());
  }

  @Override
  public JavaScriptValue applyForJavaScriptSource(
      JavaScriptValueFactory factory, List<JavaScriptValue> args, JavaScriptPluginContext context) {
    return context.getBidiDir();
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    return new PyExpr(
        bidiGlobalDirProvider.get().getCodeSnippet(),
        PyExprUtils.pyPrecedenceForOperator(Operator.CONDITIONAL));
  }
  
  @Override
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args) {
    // TODO
    return new SwiftExpr(
        bidiGlobalDirProvider.get().getCodeSnippet(),
        SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL));
  }
}
