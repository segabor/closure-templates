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

import com.google.common.collect.Lists;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.internal.targetexpr.TargetExpr;
import com.google.template.soy.jssrc.dsl.SoyJsPluginUtils;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.jssrc.restricted.SoyJsSrcFunction;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
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

/**
 * Soy function that rounds a number to a specified number of digits before or after the decimal
 * point.
 *
 */
@SoyFunctionSignature(
    name = "round",
    value = {
      // TODO(b/70946095): these should take number values and return either an int or a number
      @Signature(returnType = "?", parameterTypes = "?"),
      @Signature(
          returnType = "?",
          parameterTypes = {"?", "?"}),
    })
@SoyPureFunction
public final class RoundFunction extends TypedSoyFunction
    implements SoyJavaSourceFunction, SoyJsSrcFunction, SoyPySrcFunction, SoySwiftSrcFunction {

  @Override
  public JsExpr computeForJsSrc(List<JsExpr> args) {
    JsExpr value = args.get(0);
    JsExpr numDigitsAfterPt = (args.size() == 2) ? args.get(1) : null;

    int numDigitsAfterPtAsInt = convertNumDigits(numDigitsAfterPt);

    if (numDigitsAfterPtAsInt == 0) {
      // Case 1: round() has only one argument or the second argument is 0.
      return new JsExpr("Math.round(" + value.getText() + ")", Integer.MAX_VALUE);

    } else if ((numDigitsAfterPtAsInt >= 0 && numDigitsAfterPtAsInt <= 12)
        || numDigitsAfterPtAsInt == Integer.MIN_VALUE) {
      String shiftExprText;
      if (numDigitsAfterPtAsInt >= 0 && numDigitsAfterPtAsInt <= 12) {
        shiftExprText = "1" + "000000000000".substring(0, numDigitsAfterPtAsInt);
      } else {
        shiftExprText = "Math.pow(10, " + numDigitsAfterPt.getText() + ")";
      }
      JsExpr shift = new JsExpr(shiftExprText, Integer.MAX_VALUE);
      JsExpr valueTimesShift =
          SoyJsPluginUtils.genJsExprUsingSoySyntax(
              Operator.TIMES, Lists.newArrayList(value, shift));
      return new JsExpr(
          "Math.round(" + valueTimesShift.getText() + ") / " + shift.getText(),
          Operator.DIVIDE_BY.getPrecedence());

    } else if (numDigitsAfterPtAsInt < 0 && numDigitsAfterPtAsInt >= -12) {
      String shiftExprText = "1" + "000000000000".substring(0, -numDigitsAfterPtAsInt);
      JsExpr shift = new JsExpr(shiftExprText, Integer.MAX_VALUE);
      JsExpr valueDivideByShift =
          SoyJsPluginUtils.genJsExprUsingSoySyntax(
              Operator.DIVIDE_BY, Lists.newArrayList(value, shift));
      return new JsExpr(
          "Math.round(" + valueDivideByShift.getText() + ") * " + shift.getText(),
          Operator.TIMES.getPrecedence());

    } else {
      throw new IllegalArgumentException(
          "Second argument to round() function is "
              + numDigitsAfterPtAsInt
              + ", which is too large in magnitude.");
    }
  }

  @Override
  public PyExpr computeForPySrc(List<PyExpr> args) {
    PyExpr value = args.get(0);
    PyExpr precision = (args.size() == 2) ? args.get(1) : null;

    int precisionAsInt = convertNumDigits(precision);
    boolean isLiteral = precisionAsInt != Integer.MIN_VALUE;

    if ((precisionAsInt >= -12 && precisionAsInt <= 12) || !isLiteral) {
      // Python rounds ties away from 0 instead of towards infinity as JS and Java do. So to make
      // the behavior consistent, we add the smallest possible float amount to break ties towards
      // infinity.
      String floatBreakdown = "math.frexp(" + value.getText() + ")";
      String precisionValue = isLiteral ? precisionAsInt + "" : precision.getText();
      StringBuilder roundedValue =
          new StringBuilder("round(")
              .append('(')
              .append(floatBreakdown)
              .append("[0]")
              .append(" + sys.float_info.epsilon)*2**")
              .append(floatBreakdown)
              .append("[1]")
              .append(", ")
              .append(precisionValue)
              .append(")");
      // The precision is less than 1. Convert to an int to prevent extraneous decimals in display.
      return new PyExpr(
          "runtime.simplify_num(" + roundedValue + ", " + precisionValue + ")", Integer.MAX_VALUE);
    } else {
      throw new IllegalArgumentException(
          "Second argument to round() function is "
              + precisionAsInt
              + ", which is too large in magnitude.");
    }
  }

  /**
   * Convert the number of digits after the point from an expression to an int.
   *
   * @param numDigitsAfterPt The number of digits after the point as an expression
   * @return The number of digits after the point and an int.
   */
  private static int convertNumDigits(TargetExpr numDigitsAfterPt) {
    int numDigitsAfterPtAsInt = 0;
    if (numDigitsAfterPt != null) {
      try {
        numDigitsAfterPtAsInt = Integer.parseInt(numDigitsAfterPt.getText());
      } catch (NumberFormatException nfe) {
        numDigitsAfterPtAsInt = Integer.MIN_VALUE; // indicates it's not a simple integer literal
      }
    }
    return numDigitsAfterPtAsInt;
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    static final Method BOXED_ROUND_FN =
        JavaValueFactory.createMethod(BasicFunctionsRuntime.class, "round", SoyValue.class);

    static final Method BOXED_ROUND_WITH_NUM_DIGITS_AFTER_POINT_FN =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class, "round", SoyValue.class, int.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    if (args.size() == 1) {
      return factory.callStaticMethod(Methods.BOXED_ROUND_FN, args.get(0));
    } else {
      return factory.callStaticMethod(
          Methods.BOXED_ROUND_WITH_NUM_DIGITS_AFTER_POINT_FN, args.get(0), args.get(1).asSoyInt());
    }
  }

  /**
   * round() func requires importing Darwin/Glibc
   */
  @Override
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args) {
    SwiftExpr value = args.get(0);
    SwiftExpr numDigitsAfterPt = (args.size() == 2) ? args.get(1) : null;

    int numDigitsAfterPtAsInt = convertNumDigits(numDigitsAfterPt);

    // FIXME implement the whole feature

    return new SwiftExpr("round(" + value.getText() + ")", Integer.MAX_VALUE);
  }
}
