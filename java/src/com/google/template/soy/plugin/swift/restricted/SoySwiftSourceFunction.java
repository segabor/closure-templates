package com.google.template.soy.plugin.swift.restricted;

import java.util.List;
import com.google.template.soy.plugin.restricted.SoySourceFunction;

/**
 * A {@link SoySourceFunction} that generates code to be called at Swift render time. All
 * SoySwiftFunction implementations must be annotated with {@literal @}{@link
 * com.google.template.soy.shared.restricted.SoyFunctionSignature}.
 */
public interface SoySwiftSourceFunction extends SoySourceFunction {
  /**
   * Instructs Soy as to how to implement the function when compiling to Swift.
   *
   * <p>The {@code args} can only represent the types that can represent all values of the type
   * listed in the function signature. Additionally, the return value must represent a type
   * compatible with the returnType of the function signature.
   */
  SwiftValue applyForSwiftSource(
      SwiftValueFactory factory, List<SwiftValue> args, SwiftPluginContext context);

}
