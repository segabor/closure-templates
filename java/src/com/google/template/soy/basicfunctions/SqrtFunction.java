/*
 * Copyright 2018 Google Inc.
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

import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptPluginContext;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValue;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValueFactory;
import com.google.template.soy.plugin.javascript.restricted.SoyJavaScriptSourceFunction;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.SoyPySrcFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.SoyPureFunction;
import com.google.template.soy.shared.restricted.TypedSoyFunction;
import com.google.template.soy.swiftsrc.restricted.SoySwiftSrcFunction;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import java.lang.reflect.Method;
import java.util.List;

/** Computes the sqrt of a Number expression. */
@SoyFunctionSignature(
    name = "sqrt",
    value =
        @Signature(
            returnType = "number",
            parameterTypes = {"number"}))
@SoyPureFunction
public class SqrtFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJavaScriptSourceFunction, SoyPySrcFunction, SoySwiftSrcFunction {

  @Override
  public JavaScriptValue applyForJavaScriptSource(
      JavaScriptValueFactory factory, List<JavaScriptValue> args, JavaScriptPluginContext context) {
    return factory.global("Math").invokeMethod("sqrt", args.get(0));
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    return new PyExpr(String.format("runtime.sqrt(%s)", args.get(0).getText()), Integer.MAX_VALUE);
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    static final Method MATH_SQRT = JavaValueFactory.createMethod(Math.class, "sqrt", double.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return factory.callStaticMethod(Methods.MATH_SQRT, args.get(0));
  }

  @Override
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args) {
    return new SwiftExpr(String.format("sqrt(%s)", args.get(0).getText()), Integer.MAX_VALUE);
  }
}
