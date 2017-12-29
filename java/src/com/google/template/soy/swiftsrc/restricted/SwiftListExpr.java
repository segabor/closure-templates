package com.google.template.soy.swiftsrc.restricted;

public final class SwiftListExpr extends SwiftExpr {

  public SwiftListExpr(String text, int precedence) {
    super(text, precedence);
  }

  @Override
  public SwiftStringExpr toSwiftString() {
    // Lists are converted by concatenating all of their values.
    return new SwiftStringExpr("" + getText() + ".joined()", Integer.MAX_VALUE);
  }
}
