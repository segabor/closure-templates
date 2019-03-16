package com.google.template.soy.plugin.swift.restricted;

import javax.annotation.Nullable;
import com.google.template.soy.plugin.restricted.SoySourceValue;

/**
 * Basically, it is a copy of PythonValue
 * 
 * TODO: review methods, remove unneeded ones
 * 
 * @author segabor
 */
public interface SwiftValue extends SoySourceValue {
  SwiftValue isNull();

  SwiftValue isNonNull();

  /**
   * FIXME: named parameters are not supported
   * 
   * @param args
   * @return
   */
  SwiftValue call(SwiftValue... args);

  // should generate an expression where variable.property is accessed
  SwiftValue getProp(String ident);

  SwiftValue plus(SwiftValue value);

  SwiftValue coerceToString();

  /** Tests if {@code this in other}. */
  SwiftValue in(SwiftValue other);

  SwiftValue slice(@Nullable SwiftValue start, @Nullable SwiftValue end);

  SwiftValue getItem(SwiftValue key);
}
