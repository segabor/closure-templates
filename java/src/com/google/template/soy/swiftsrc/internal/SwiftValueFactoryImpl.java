package com.google.template.soy.swiftsrc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import java.util.ArrayList;
import java.util.List;
import com.google.common.base.Throwables;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.plugin.swift.restricted.SoySwiftSourceFunction;
import com.google.template.soy.plugin.swift.restricted.SwiftPluginContext;
import com.google.template.soy.plugin.swift.restricted.SwiftValue;
import com.google.template.soy.plugin.swift.restricted.SwiftValueFactory;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;

/** A {@link SwiftValueFactory} implementation that can also manage invoking the plugins. */
public class SwiftValueFactoryImpl extends SwiftValueFactory {

  private static final SwiftValueImpl ERROR_VALUE =
      new SwiftValueImpl(
          new SwiftStringExpr(
              "uh oh, if you see this the soy compiler has swallowed an error", Integer.MIN_VALUE));

  private static final SoyErrorKind NULL_RETURN =
      SoyErrorKind.of(
          formatPlain("{2}.applyForPythonSource returned null."), StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind UNEXPECTED_ERROR =
      SoyErrorKind.of(formatPlain("{2}"), StyleAllowance.NO_PUNCTUATION);

  private static String formatPlain(String innerFmt) {
    return "Error in plugin implementation for function ''{0}''."
        + "\n"
        + innerFmt
        + "\nPlugin implementation: {1}";
  }

  private final SwiftPluginContext context;
  private final ErrorReporter reporter;
  
  SwiftValueFactoryImpl(ErrorReporter reporter, final BidiGlobalDir bidiDir) {
    this.reporter = checkNotNull(reporter);
    checkNotNull(bidiDir);
    this.context =
        new SwiftPluginContext() {
          @Override
          public SwiftValue getBidiDir() {
            return new SwiftValueImpl(new SwiftExpr(bidiDir.getCodeSnippet(), Integer.MIN_VALUE));
          }
        };
  }

  SwiftExpr applyFunction(
      SourceLocation location, String name, SoySwiftSourceFunction fn, List<SwiftExpr> args) {
    SwiftValueImpl result;
    try {
      result = (SwiftValueImpl) fn.applyForSwiftSource(this, wrapParams(args), context);
      if (result == null) {
        report(location, name, fn, NULL_RETURN, fn.getClass().getSimpleName());
        result = ERROR_VALUE;
      }
    } catch (Throwable t) {
      BaseUtils.trimStackTraceTo(t, getClass());
      report(location, name, fn, UNEXPECTED_ERROR, Throwables.getStackTraceAsString(t));
      result = ERROR_VALUE;
    }
    return result.expr;
  }

  private void report(
      SourceLocation location,
      String name,
      SoySwiftSourceFunction fn,
      SoyErrorKind error,
      Object... additionalArgs) {
    Object[] args = new Object[additionalArgs.length + 2];
    args[0] = name;
    args[1] = fn.getClass().getName();
    System.arraycopy(additionalArgs, 0, args, 2, additionalArgs.length);
    reporter.report(location, error, args);
  }

  private static List<SwiftValue> wrapParams(List<SwiftExpr> params) {
    List<SwiftValue> exprs = new ArrayList<>(params.size());
    for (SwiftExpr e : params) {
      exprs.add(new SwiftValueImpl(e));
    }
    return exprs;
  }

  @Override
  public SwiftValue constant(long num) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue constant(double num) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue constant(String str) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue constant(boolean bool) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue constantNull() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue global(String globalSymbol) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SwiftValue systemFunction(String functionName) {
    return new SwiftValueImpl(new SwiftStringExpr(functionName));
  }

  public static SwiftExpr unwrap(SwiftValue start) {
    return ((SwiftValueImpl) start).expr;
  }

}
