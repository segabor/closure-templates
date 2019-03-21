package com.google.template.soy.plugin.swift.restricted;

/** The context for a {@link SoySwiftSourceFunction}. */
public interface SwiftPluginContext {
  /**
   * A value that resolves to the direction at runtime. Will resolve to {@code -1} if the locale is
   * RTL, or {@code 1} if the current locale is LTR.
   *
   * <p>Very few plugins should require this, instead rely on the built-in bidi functions and common
   * localization libraries.
   * 
   * TODO: revise this
   */
  SwiftValue getBidiDir();
}
