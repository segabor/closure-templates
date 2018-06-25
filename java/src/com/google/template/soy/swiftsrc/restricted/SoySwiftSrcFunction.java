package com.google.template.soy.swiftsrc.restricted;

import java.util.List;

import com.google.template.soy.shared.restricted.SoyFunction;

public interface SoySwiftSrcFunction extends SoyFunction {

  /**
   * Computes this function on the given arguments for the Python Source backend.
   *
   * @param args The function arguments.
   * @return The computed result of this function.
   */
  public SwiftExpr computeForSwiftSrc(List<SwiftExpr> args);
}
