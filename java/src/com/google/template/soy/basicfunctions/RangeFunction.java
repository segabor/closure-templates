/*
 * Copyright 2017 Google Inc.
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
import com.google.template.soy.pysrc.restricted.PyFunctionExprBuilder;
import com.google.template.soy.pysrc.restricted.SoyPySrcFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.TypedSoyFunction;
import com.google.template.soy.swiftsrc.restricted.SoySwiftSrcFunction;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The Range function takes 1-3 arguments and generates a sequence of integers, like the python
 * {@code range} function.
 *
 * <ul>
 *   <li>The first argument is the end of the range if it is the only argument (in which case the
 *       start is assumed to be 0), otherwise it is the start of the range
 *   <li>The second argument is the end of the range.
 *   <li>The third argument is the 'step', which defaults to 1.
 * </ul>
 */
@SoyFunctionSignature(
    name = "range",
    // TODO(b/70946095): params should be an 'int', not a 'number'
    value = {
      @Signature(
          parameterTypes = {"number"},
          returnType = "list<int>"),
      @Signature(
          parameterTypes = {"number", "number"},
          returnType = "list<int>"),
      @Signature(
          parameterTypes = {"number", "number", "number"},
          returnType = "list<int>")
    })
public final class RangeFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJavaScriptSourceFunction, SoyPySrcFunction, SoySwiftSrcFunction {

  private static final class Methods {
    static final Method RANGE =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class, "range", int.class, int.class, int.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    JavaValue start;
    JavaValue end;
    JavaValue step;
    switch (args.size()) {
      case 1:
        start = factory.constant(0);
        end = args.get(0).asSoyInt();
        step = factory.constant(1);
        break;
      case 2:
        start = args.get(0).asSoyInt();
        end = args.get(1).asSoyInt();
        step = factory.constant(1);
        break;
      case 3:
        start = args.get(0).asSoyInt();
        end = args.get(1).asSoyInt();
        step = args.get(2).asSoyInt();
        break;
      default:
        throw new AssertionError();
    }
    return factory.callStaticMethod(Methods.RANGE, start, end, step);
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    // Coincidentally, soy range is identical to python 2 xrange and python 3 range
    // Use range which is guaranteed to produce either a list (python 2) or a lazy iterable
    // (python 3) -- both of which are enumerable -- because xrange does not exist in python 3.
    return new PyFunctionExprBuilder("range").addArgs(args).asPyExpr();
  }

  @Override
  public JavaScriptValue applyForJavaScriptSource(
      JavaScriptValueFactory factory, List<JavaScriptValue> args, JavaScriptPluginContext context) {
    return factory.callNamespaceFunction(
        "goog.array", "goog.array.range", args.toArray(new JavaScriptValue[0]));
  }

  /**
   * FIXME Swift does not support ranges with custom steps
   */
  @Override
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args) {
    switch (args.size()) {
      case 1:
        return new SwiftExpr(String.format("0..<%s", args.get(0).getText()), Integer.MAX_VALUE);
      case 2:
        return new SwiftExpr(String.format("%s..<%s", args.get(0).getText(), args.get(1).getText()), Integer.MAX_VALUE);
      default:
        throw new AssertionError();
    }
  }
}
