package com.google.template.soy.swiftsrc.restricted;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.exprtree.Operator.Associativity;
import com.google.template.soy.exprtree.Operator.Operand;
import com.google.template.soy.exprtree.Operator.Spacer;
import com.google.template.soy.exprtree.Operator.SyntaxElement;
import com.google.template.soy.exprtree.Operator.Token;
import com.google.template.soy.internal.targetexpr.TargetExpr;

public final class SwiftExprUtils {
  /** The variable name used to reference the current translator instance. */
  public static final String TRANSLATOR_NAME = "translator_impl";

  /** Expression constant for empty string. */
  private static final SwiftExpr EMPTY_STRING = new SwiftStringExpr("\"\"");


  /**
   * Map used to provide operator precedences in Python.
   *
   * @see <a href="https://docs.python.org/2/reference/expressions.html#operator-precedence">Python
   *     operator precedence.</a>
   *     
   * FIXME https://stackoverflow.com/questions/24316882/operator-precedence-overloading-in-swift
   */
  private static final ImmutableMap<Operator, Integer> SWIFT_PRECEDENCES =
      new ImmutableMap.Builder<Operator, Integer>()
          .put(Operator.NEGATIVE, 8)
          .put(Operator.TIMES, 7)
          .put(Operator.DIVIDE_BY, 7)
          .put(Operator.MOD, 7)
          .put(Operator.PLUS, 6)
          .put(Operator.MINUS, 6)
          .put(Operator.LESS_THAN, 5)
          .put(Operator.GREATER_THAN, 5)
          .put(Operator.LESS_THAN_OR_EQUAL, 5)
          .put(Operator.GREATER_THAN_OR_EQUAL, 5)
          .put(Operator.EQUAL, 5)
          .put(Operator.NOT_EQUAL, 5)
          .put(Operator.NOT, 4)
          .put(Operator.AND, 3)
          .put(Operator.OR, 2)
          .put(Operator.NULL_COALESCING, 1)
          .put(Operator.CONDITIONAL, 1)
          .build();

  private SwiftExprUtils() {}

  /**
   * Builds one Python expression that computes the concatenation of the given Python expressions.
   *
   * <p>Python doesn't allow arbitrary concatenation between types, so to ensure type safety and
   * consistent behavior, coerce all expressions to Strings before joining them. Python's array
   * joining mechanism is used in place of traditional concatenation to improve performance.
   *
   * @param pyExprs The Python expressions to concatenate.
   * @return One Python expression that computes the concatenation of the given Python expressions.
   */
  public static SwiftExpr concatSwiftExprs(List<? extends SwiftExpr> swiftExprs) {

    if (swiftExprs.isEmpty()) {
      return EMPTY_STRING;
    }

    if (swiftExprs.size() == 1) {
      // If there's only one element, simply return the expression as a String.
      return swiftExprs.get(0).toSwiftString();
    }

    StringBuilder resultSb = new StringBuilder();

    // Use Python's list joining mechanism to speed up concatenation.
    resultSb.append("[");

    boolean isFirst = true;
    for (SwiftExpr pyExpr : swiftExprs) {

      if (isFirst) {
        isFirst = false;
      } else {
        resultSb.append(", ");
      }

      resultSb.append(pyExpr.toSwiftString().getText());
    }

    resultSb.append("]");
    return new SwiftListExpr(resultSb.toString(), Integer.MAX_VALUE);
  }

  /**
   * Provide the Python operator precedence for a given operator.
   *
   * @param op The operator.
   * @return THe python precedence as an integer.
   */
  public static int swiftPrecedenceForOperator(Operator op) {
    return SWIFT_PRECEDENCES.get(op);
  }

  /**
   * Convert a java Iterable object to valid SwiftExpr as array.
   *
   * @param iterable Iterable of Objects to be converted to SwiftExpr, it must be Number, SwiftExpr
   *     or String.
   */
  public static SwiftExpr convertIterableToSwfitListExpr(Iterable<?> iterable) {
    List<String> values = new ArrayList<>();
    String leftDelimiter = "[";
    String rightDelimiter = "]";

    for (Object elem : iterable) {
      if (!(elem instanceof Number || elem instanceof String || elem instanceof SwiftExpr)) {
        throw new UnsupportedOperationException("Only Number, String and SwiftExpr is allowed");
      } else if (elem instanceof Number) {
        values.add(String.valueOf(elem));
      } else if (elem instanceof SwiftExpr) {
        values.add(((SwiftExpr) elem).getText());
      } else if (elem instanceof String) {
        values.add("\"" + elem + "\"");
      }
    }

    String contents = Joiner.on(", ").join(values);

    return new SwiftListExpr(leftDelimiter + contents + rightDelimiter, Integer.MAX_VALUE);
  }

  /**
   * Convert a java Map to valid SwiftExpr as dict.
   *
   * @param dict A Map to be converted to SwiftExpr as a dictionary, both key and value should be
   *     SwiftExpr.
   */
  public static SwiftExpr convertMapToSwiftExpr(Map<SwiftExpr, SwiftExpr> dict) {
    List<String> values = new ArrayList<>();

    for (Map.Entry<SwiftExpr, SwiftExpr> entry : dict.entrySet()) {
      values.add(entry.getKey().getText() + ": " + entry.getValue().getText());
    }

    Joiner joiner = Joiner.on(", ");
    return new SwiftExpr("[" + joiner.join(values) + "]", Integer.MAX_VALUE);
  }

  /** Generates a Python not null (None) check expression for the given {@link SwiftExpr}. */
  public static SwiftExpr genSwiftNotNullCheck(SwiftExpr pyExpr) {
    ImmutableList<SwiftExpr> exprs = ImmutableList.of(pyExpr, new SwiftExpr("nil", Integer.MAX_VALUE));
    // Note: is/is not is Python's identity comparison. It's used for None checks for performance.
    String conditionalExpr = genExprWithNewToken(Operator.NOT_EQUAL, exprs, "!=");
    return new SwiftExpr(conditionalExpr, SwiftExprUtils.swiftPrecedenceForOperator(Operator.NOT_EQUAL));
  }

  /** Generates a Python null (None) check expression for the given {@link SwiftExpr}. */
  public static SwiftExpr genSwiftNullCheck(SwiftExpr expr) {
    ImmutableList<SwiftExpr> exprs = ImmutableList.of(expr, new SwiftExpr("nil", Integer.MAX_VALUE));
    // Note: is/is not is Python's identity comparison. It's used for None checks for performance.
    String conditionalExpr = genExprWithNewToken(Operator.EQUAL, exprs, "==");
    return new SwiftExpr(conditionalExpr, SwiftExprUtils.swiftPrecedenceForOperator(Operator.EQUAL));
  }

  /**
   * Wraps an expression with parenthesis if it's not above the minimum safe precedence.
   *
   * <p>NOTE: For the sake of brevity, this implementation loses typing information in the
   * expressions.
   *
   * @param expr The expression to wrap.
   * @param minSafePrecedence The minimum safe precedence (not inclusive).
   * @return The PyExpr potentially wrapped in parenthesis.
   */
  // FIXME is it needed for Swift?
  public static SwiftExpr maybeProtect(SwiftExpr expr, int minSafePrecedence) {
    if (expr.getPrecedence() > minSafePrecedence) {
      return expr;
    } else {
      return new SwiftExpr("(" + expr.getText() + ")", Integer.MAX_VALUE);
    }
  }

  /**
   * Generates an expression for the given operator and operands assuming that the expression for
   * the operator uses the same syntax format as the Soy operator, with the exception that the of a
   * different token. Associativity, spacing, and precedence are maintained from the original
   * operator.
   *
   * <p>Examples:
   *
   * <pre>
   * NOT, ["$a"], "!" -> "! $a"
   * AND, ["$a", "$b"], "&&" -> "$a && $b"
   * NOT, ["$a * $b"], "!"; -> "! ($a * $b)"
   * </pre>
   *
   * @param op The operator.
   * @param operandExprs The operands.
   * @param newToken The language specific token equivalent to the operator's original token.
   * @return The generated expression with a new token.
   */
  public static String genExprWithNewToken(
      Operator op, List<? extends TargetExpr> operandExprs, String newToken) {

    int opPrec = op.getPrecedence();
    boolean isLeftAssociative = op.getAssociativity() == Associativity.LEFT;

    StringBuilder exprSb = new StringBuilder();

    // Iterate through the operator's syntax elements.
    List<SyntaxElement> syntax = op.getSyntax();
    for (int i = 0, n = syntax.size(); i < n; i++) {
      SyntaxElement syntaxEl = syntax.get(i);

      if (syntaxEl instanceof Operand) {
        // Retrieve the operand's subexpression.
        int operandIndex = ((Operand) syntaxEl).getIndex();
        TargetExpr operandExpr = operandExprs.get(operandIndex);
        // If left (right) associative, first (last) operand doesn't need protection if it's an
        // operator of equal precedence to this one.
        boolean needsProtection;
        if (i == (isLeftAssociative ? 0 : n - 1)) {
          needsProtection = operandExpr.getPrecedence() < opPrec;
        } else {
          needsProtection = operandExpr.getPrecedence() <= opPrec;
        }
        // Append the operand's subexpression to the expression we're building (if necessary,
        // protected using parentheses).
        String subexpr =
            needsProtection ? "(" + operandExpr.getText() + ")" : operandExpr.getText();
        exprSb.append(subexpr);

      } else if (syntaxEl instanceof Token) {
        // If a newToken is supplied, then use it, else use the token defined by Soy syntax.
        if (newToken != null) {
          exprSb.append(newToken);
        } else {
          exprSb.append(((Token) syntaxEl).getValue());
        }

      } else if (syntaxEl instanceof Spacer) {
        // Spacer is just one space.
        exprSb.append(' ');

      } else {
        throw new AssertionError();
      }
    }

    return exprSb.toString();
  }
}
