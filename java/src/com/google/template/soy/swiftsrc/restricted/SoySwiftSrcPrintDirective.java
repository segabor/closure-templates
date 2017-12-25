package com.google.template.soy.swiftsrc.restricted;

import java.util.List;

import com.google.template.soy.shared.restricted.SoyPrintDirective;

public interface SoySwiftSrcPrintDirective extends SoyPrintDirective {
  public SwiftExpr applyForSwiftSrc(SwiftExpr value, List<SwiftExpr> args);
}
