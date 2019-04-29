package com.google.template.soy.plugin.swift.restricted;

/** A factory for instructing soy how to implement a {@link SoySWiftSourceFunction}. */
public abstract class SwiftValueFactory {
  
  public enum RuntimeNamespace {
    Math, Lists, Util, Strings
  }
  
  /** Creates an integer constant. */
  public abstract SwiftValue constant(long num);

  /** Creates a floating point constant. */
  public abstract SwiftValue constant(double num);

  /** Creates a String constant. */
  public abstract SwiftValue constant(String str);

  /** Creates a boolean constant. */
  public abstract SwiftValue constant(boolean bool);

  /** Creates a null constant. */
  public abstract SwiftValue constantNull();

  /** Creates a reference to a global symbol, e.g. {@code Math}. */
  public abstract SwiftValue global(String globalSymbol);

  /** Creates a reference to a function defined in SoyKit runtime. */
  public abstract SwiftValue runtime(RuntimeNamespace namespace, String functionName, boolean throwing);
}
