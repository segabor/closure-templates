package com.google.template.soy.swiftsrc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.template.soy.swiftsrc.internal.SwiftValueFactoryImpl.unwrap;
import static com.google.template.soy.swiftsrc.restricted.SwiftExprUtils.maybeProtect;
import com.google.template.soy.plugin.swift.restricted.SwiftValue;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;

public final class SwiftValueImpl implements SwiftValue {

  final SwiftExpr expr;
  
  public SwiftValueImpl(SwiftExpr expr) {
    this.expr = checkNotNull(expr);
  }

  @Override
  public SwiftValue isNull() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue isNonNull() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue call(SwiftValue... args) {
    StringBuilder sb =
        new StringBuilder()
            .append(maybeProtect(expr, SwiftExprUtils.CALL_PRECEDENCE).getText())
            .append("(");
    boolean isFirst = true;
    for (SwiftValue arg : args) {
      if (isFirst) {
        isFirst = false;
      } else {
        sb.append(", ");
      }
      sb.append(unwrap(arg).getText());
    }
    sb.append(")");
    return new SwiftValueImpl(new SwiftExpr(sb.toString(), SwiftExprUtils.CALL_PRECEDENCE));
  }

  @Override
  public SwiftValue getProp(String ident) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue plus(SwiftValue value) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue coerceToString() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue in(SwiftValue other) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue slice(SwiftValue start, SwiftValue end) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue getItem(SwiftValue key) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public String toString() {
    return expr.getText();
  }
  
}
