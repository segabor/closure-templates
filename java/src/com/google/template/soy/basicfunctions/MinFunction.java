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

package com.google.template.soy.basicfunctions;

import com.google.template.soy.data.SoyValue;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptPluginContext;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValue;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValueFactory;
import com.google.template.soy.plugin.javascript.restricted.SoyJavaScriptSourceFunction;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyFunctionExprBuilder;
import com.google.template.soy.pysrc.restricted.SoyPySrcFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.SoyPureFunction;
import com.google.template.soy.shared.restricted.TypedSoyFunction;
import com.google.template.soy.swiftsrc.restricted.SoySwiftSrcFunction;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Soy function that takes the min of two numbers.
 *
 */
@SoyFunctionSignature(
    name = "min",
    value =
        // TODO(b/70946095):these should all be number
        @Signature(
            returnType = "?",
            parameterTypes = {"?", "?"}))
@SoyPureFunction
public final class MinFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJavaScriptSourceFunction, SoyPySrcFunction, SoySwiftSrcFunction {

  @Override
  public JavaScriptValue applyForJavaScriptSource(
      JavaScriptValueFactory factory, List<JavaScriptValue> args, JavaScriptPluginContext context) {
    return factory.global("Math").invokeMethod("min", args.get(0), args.get(1));
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    PyExpr arg0 = args.get(0);
    PyExpr arg1 = args.get(1);

    PyFunctionExprBuilder fnBuilder = new PyFunctionExprBuilder("min");
    return fnBuilder.addArg(arg0).addArg(arg1).asPyExpr();
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    static final Method MIN_FN =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class, "min", SoyValue.class, SoyValue.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return factory.callStaticMethod(Methods.MIN_FN, args.get(0), args.get(1));
  }

  /**
   * Note: this function might require importing Darwin/Glibc
   */
  @Override
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args) {
    SwiftExpr arg0 = args.get(0);
    SwiftExpr arg1 = args.get(1);

    return new SwiftExpr(
        "min(" + arg0.getText() + ", " + arg1.getText() + ")", Integer.MAX_VALUE);
  }
}
