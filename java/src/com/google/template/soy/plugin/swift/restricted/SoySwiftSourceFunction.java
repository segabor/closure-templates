package com.google.template.soy.plugin.swift.restricted;

import java.util.List;
import com.google.template.soy.plugin.restricted.SoySourceFunction;

public interface SoySwiftSourceFunction extends SoySourceFunction {
  /**
   * Instructs Soy as to how to implement the function when compiling to Python.
   *
   * <p>The {@code args} can only represent the types that can represent all values of the type
   * listed in the function signature. Additionally, the return value must represent a type
   * compatible with the returnType of the function signature.
   */
  SwiftValue applyForSwiftSource(
      SwiftValueFactory factory, List<SwiftValue> args, SwiftPluginContext context);

}
