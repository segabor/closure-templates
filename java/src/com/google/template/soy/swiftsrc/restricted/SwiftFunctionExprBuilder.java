package com.google.template.soy.swiftsrc.restricted;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;

public final class SwiftFunctionExprBuilder {

  private static final Function<SwiftExpr, String> LIST_ARG_MAPPER =
      new Function<SwiftExpr, String>() {
        @Override
        public String apply(SwiftExpr arg) {
          return arg.getText();
        }
      };

  private final String funcName;
  private final Deque<SwiftExpr> argList;

  public SwiftFunctionExprBuilder(String funcName) {
    this.funcName = funcName;
    this.argList = new ArrayDeque<>();
  }

  public SwiftFunctionExprBuilder addArg(SwiftExpr arg) {
    this.argList.add(arg);
    return this;
  }

  public SwiftFunctionExprBuilder addArgs(List<SwiftExpr> argList) {
    this.argList.addAll(argList);
    return this;
  }

  public SwiftFunctionExprBuilder addArg(String str) {
    this.argList.add(new SwiftStringExpr("\"" + str + "\""));
    return this;
  }

  public SwiftFunctionExprBuilder addArg(boolean b) {
    this.argList.add(new SwiftExpr(b ? "true" : "false", Integer.MAX_VALUE));
    return this;
  }

  public SwiftFunctionExprBuilder addArg(int i) {
    this.argList.add(new SwiftExpr(String.valueOf(i), Integer.MAX_VALUE));
    return this;
  }

  public SwiftFunctionExprBuilder addArg(double i) {
    this.argList.add(new SwiftExpr(String.valueOf(i), Integer.MAX_VALUE));
    return this;
  }

  public SwiftFunctionExprBuilder addArg(long i) {
    this.argList.add(new SwiftExpr(String.valueOf(i), Integer.MAX_VALUE));
    return this;
  }

  public String getFuncName() {
    return this.funcName;
  }

  /** Returns a valid Python function call as a String. */
  public String build() {
    StringBuilder sb = new StringBuilder(funcName + "(");

    Joiner joiner = Joiner.on(", ").skipNulls();

    // Join args and kwargs into simple strings.
    String args = joiner.join(Iterables.transform(argList, LIST_ARG_MAPPER));

    // Strip empty strings.
    args = Strings.emptyToNull(args);

    // Join all pieces together.
    sb.append(args);

    sb.append(")");
    return sb.toString();
  }

  /**
   * Use when the output function is unknown in Python runtime.
   *
   * @return A PyExpr represents the function code.
   */
  public SwiftExpr asSwiftExpr() {
    return new SwiftExpr(build(), Integer.MAX_VALUE);
  }

  /**
   * Use when the output function is known to be a String in Python runtime.
   *
   * @return A PyStringExpr represents the function code.
   */
  public SwiftStringExpr asSwiftStringExpr() {
    return new SwiftStringExpr(build());
  }
}
