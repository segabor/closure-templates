/*
 * Copyright 2012 Google Inc.
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

import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.jssrc.restricted.JsExprUtils;
import com.google.template.soy.jssrc.restricted.SoyJsSrcFunction;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
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
 * A function that determines if a given string contains another given string.
 *
 * <p><code>strContains(expr1, expr2)</code> requires <code>expr1</code> and <code>expr2</code> to
 * be of type string or {@link SanitizedContent}. It evaluates to <code>true</code> iff <code>expr1
 * </code> contains <code>expr2</code>. <code>strContains</code> is case sensitive.
 *
 */
@SoyFunctionSignature(
    name = "strContains",
    value = {
      @Signature(
          // TODO(b/62134073): should be string, string and return a bool
          returnType = "?",
          parameterTypes = {"?", "?"}),
    })
@SoyPureFunction
final class StrContainsFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJsSrcFunction, SoyPySrcFunction, SoySwiftSrcFunction {

  @Override
  public JsExpr computeForJsSrc(List<JsExpr> args) {
    // Coerce SanitizedContent args to strings.
    String arg0 = JsExprUtils.toString(args.get(0)).getText();
    String arg1 = JsExprUtils.toString(args.get(1)).getText();

    String exprText = "(" + arg0 + ").indexOf(" + arg1 + ") != -1";

    return new JsExpr(exprText, Operator.NOT_EQUAL.getPrecedence());
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    // Coerce SanitizedContent args to strings.
    String arg0 = args.get(0).toPyString().getText();
    String arg1 = args.get(1).toPyString().getText();

    String exprText = "(" + arg0 + ").find(" + arg1 + ") != -1";
    return new PyExpr(exprText, PyExprUtils.pyPrecedenceForOperator(Operator.NOT_EQUAL));
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    static final Method STR_CONTAINS =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class, "strContains", SoyValue.class, String.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return factory.callStaticMethod(
        Methods.STR_CONTAINS, args.get(0), args.get(1).coerceToSoyString());
  }

  @Override
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args) {
    SwiftExpr arg0 = args.get(0);
    SwiftExpr arg1 = args.get(1);
    return new SwiftExpr(String.format("%s.contains(%s)", arg0.getText(), arg1.getText()), Integer.MAX_VALUE);
  }
}
