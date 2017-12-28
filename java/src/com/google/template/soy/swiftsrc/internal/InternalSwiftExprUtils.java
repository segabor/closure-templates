package com.google.template.soy.swiftsrc.internal;

import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.data.internalutils.NodeContentKinds;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;

public final class InternalSwiftExprUtils {

  /**
   * Wraps an expression with the proper SanitizedContent constructor.
   * 
   * TODO: complete implementation for Swift
   *
   * @see NodeContentKinds#toPySanitizedContentOrdainer(SanitizedContentKind)
   *
   * @param contentKind The kind of sanitized content.
   * @param swiftExpr The expression to wrap.
   */
  static SwiftExpr wrapAsSanitizedContent(SanitizedContentKind contentKind, SwiftExpr swiftExpr) {
	return swiftExpr;
  }

  private InternalSwiftExprUtils() {}
}
