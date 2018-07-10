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

import static com.google.template.soy.types.SoyTypes.NUMBER_TYPE;

import com.google.template.soy.data.SoyValue;
import com.google.template.soy.jbcsrc.restricted.JbcSrcPluginContext;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.SoyExpression;
import com.google.template.soy.jbcsrc.restricted.SoyJbcSrcFunction;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.jssrc.restricted.SoyJsSrcFunction;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyFunctionExprBuilder;
import com.google.template.soy.pysrc.restricted.SoyPySrcFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.SoyPureFunction;
import com.google.template.soy.shared.restricted.TypedSoyFunction;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Soy function that takes the max of two numbers.
 *
 */
@SoyFunctionSignature(
    name = "max",
    value =
        // TODO(b/70946095): these should all be number, or maybe something more specific
        @Signature(
            returnType = "?",
            parameterTypes = {"?", "?"}))
@SoyPureFunction
public final class MaxFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJsSrcFunction, SoyPySrcFunction, SoyJbcSrcFunction {

  @Override
  public JsExpr computeForJsSrc(List<JsExpr> args) {
    JsExpr arg0 = args.get(0);
    JsExpr arg1 = args.get(1);

    return new JsExpr(
        "Math.max(" + arg0.getText() + ", " + arg1.getText() + ")", Integer.MAX_VALUE);
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    PyExpr arg0 = args.get(0);
    PyExpr arg1 = args.get(1);

    PyFunctionExprBuilder fnBuilder = new PyFunctionExprBuilder("max");
    return fnBuilder.addArg(arg0).addArg(arg1).asPyExpr();
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    private static final Method MAX_FN =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class, "max", SoyValue.class, SoyValue.class);
    private static final MethodRef MAX_FN_REF = MethodRef.create(MAX_FN).asNonNullable();

    private static final MethodRef MATH_MAX_DOUBLE_REF =
        MethodRef.create(Math.class, "max", double.class, double.class).asCheap();

    private static final MethodRef MATH_MAX_LONG_REF =
        MethodRef.create(Math.class, "max", long.class, long.class).asCheap();
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return factory.callStaticMethod(Methods.MAX_FN, args.get(0), args.get(1));
  }

  @Override
  public SoyExpression computeForJbcSrc(JbcSrcPluginContext context, List<SoyExpression> args) {
    SoyExpression left = args.get(0);
    SoyExpression right = args.get(1);
    if (left.assignableToNullableInt() && right.assignableToNullableInt()) {
      return SoyExpression.forInt(
          Methods.MATH_MAX_LONG_REF.invoke(left.unboxAs(long.class), right.unboxAs(long.class)));
    } else if (left.assignableToNullableFloat() && right.assignableToNullableFloat()) {
      return SoyExpression.forFloat(
          Methods.MATH_MAX_DOUBLE_REF.invoke(
              left.unboxAs(double.class), right.unboxAs(double.class)));
    } else {
      return SoyExpression.forSoyValue(
          NUMBER_TYPE, Methods.MAX_FN_REF.invoke(left.box(), right.box()));
    }
  }
}
