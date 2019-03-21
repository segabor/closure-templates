package com.google.template.soy.swiftsrc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.template.soy.plugin.swift.restricted.SwiftValue;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;

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
    // TODO Auto-generated method stub
    return null;
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
