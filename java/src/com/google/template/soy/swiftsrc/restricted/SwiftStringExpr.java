package com.google.template.soy.swiftsrc.restricted;

public class SwiftStringExpr extends SwiftExpr {

  public SwiftStringExpr(String text) {
    super(text, Integer.MAX_VALUE);
  }

  public SwiftStringExpr(String text, int precedence) {
    super(text, precedence);
  }

  @Override
  public SwiftStringExpr toSwiftString() {
    // This expression is already a String. No conversion needed.
    return this;
  }
}
