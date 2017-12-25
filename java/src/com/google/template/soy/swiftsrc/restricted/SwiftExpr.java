package com.google.template.soy.swiftsrc.restricted;

import com.google.template.soy.internal.targetexpr.TargetExpr;

public class SwiftExpr extends TargetExpr {

  public SwiftExpr(String text, int precedence) {
    super(text, precedence);
  }

  /**
   * Convert the given type to a Swift String expression.
   *
   * @return A SwiftStringExpr representing this expression as a String.
   */
  public SwiftStringExpr toSwiftString() {
    // FIXME
    return new SwiftStringExpr("str(" + getText() + ")", Integer.MAX_VALUE);
  }
}
