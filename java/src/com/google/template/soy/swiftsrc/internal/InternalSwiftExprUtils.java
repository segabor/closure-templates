package com.google.template.soy.swiftsrc.internal;

import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.data.internalutils.NodeContentKinds;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;

public final class InternalSwiftExprUtils {

  /**
   * Wraps an expression with the proper SanitizedContent constructor.
   *
   * @param contentKind The kind of sanitized content.
   * @param pyExpr The expression to wrap.
   */
  static SwiftExpr wrapAsSanitizedContent(SanitizedContentKind contentKind, SwiftExpr pyExpr) {
    String sanitizer = NodeContentKinds.toPySanitizedContentOrdainer(contentKind);
    String approval =
        "sanitize.IActuallyUnderstandSoyTypeSafetyAndHaveSecurityApproval("
            + "'Internally created Sanitization.')";
    return new SwiftExpr(
        sanitizer + "(" + pyExpr.getText() + ", approval=" + approval + ")", Integer.MAX_VALUE);
  }

  private InternalSwiftExprUtils() {}
}
