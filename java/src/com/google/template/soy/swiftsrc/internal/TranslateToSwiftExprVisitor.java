package com.google.template.soy.swiftsrc.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.AbstractReturningExprNodeVisitor;
import com.google.template.soy.exprtree.BooleanNode;
import com.google.template.soy.exprtree.DataAccessNode;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.OperatorNode;
import com.google.template.soy.exprtree.ExprNode.PrimitiveNode;
import com.google.template.soy.exprtree.Operator.Operand;
import com.google.template.soy.exprtree.Operator.SyntaxElement;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.exprtree.ProtoInitNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.EqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NegativeOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.logging.LoggingFunction;
import com.google.template.soy.plugin.swift.restricted.SoySwiftSourceFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import com.google.template.soy.swiftsrc.restricted.SwiftFunctionExprBuilder;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;

public final class TranslateToSwiftExprVisitor extends AbstractReturningExprNodeVisitor<SwiftExpr> {

  /** How a key access should behave if a key is not in the structure. */
  private enum NotFoundBehavior {
    /** Return {@code None} if the key is not in the structure. */
    RETURN_NONE,
    /** Throw an exception if the key is not in the structure. */
    THROW
  }

  /** If a key should be coerced to a string before a key access. */
  private enum CoerceKeyToString {
    /**
     * Coerce the key to a string. This is mostly useful for keys that are the {@link
     * com.google.template.soy.data.UnsanitizedString} type.
     */
    YES,
    /** Do not coerce the key to a string. */
    NO
  }

  protected enum ConditionalEvaluationMode {
	  NORMAL, CONDITIONAL, CONDITIONAL_NOT
  }

  private static final SoyErrorKind PROTO_ACCESS_NOT_SUPPORTED =
      SoyErrorKind.of("Proto accessors are not supported in Swift src.");
  private static final SoyErrorKind PROTO_INIT_NOT_SUPPORTED =
      SoyErrorKind.of("Proto init is not supported in Swift src.");
  private static final SoyErrorKind SOY_PY_SRC_FUNCTION_NOT_FOUND =
      SoyErrorKind.of("Failed to find SoySwiftSrcFunction ''{0}''.");
  private static final SoyErrorKind UNTYPED_BRACKET_ACCESS_NOT_SUPPORTED =
      SoyErrorKind.of(
          "Bracket access on values of unknown type is not supported in Swift src. "
              + "The expression should be declared as a list or map.");
  
  /**
   * Errors in this visitor generate Python source that immediately explodes. Users of Soy are
   * expected to check the error reporter before using the gencode; if they don't, this should
   * apprise them. TODO(brndn): consider changing the visitor to return {@code Optional<PyExpr>} and
   * returning {@link Optional#absent()} on error.
   */
  // FIXME
  private static final SwiftExpr ERROR =
      new SwiftExpr("raise Exception('Soy compilation failed')", Integer.MAX_VALUE);

  private final LocalVariableStack localVarExprs;
  private final ErrorReporter errorReporter;

  /**
   * TBD
   * Normal - visit child nodes as usual
   * Conditional - replace 'data[..]' to 'data[..] != nil'
   * Conditional_Not - replace 'data[..]' to 'data[..] == nil'
   */
  private ConditionalEvaluationMode conditionalEvaluationMode = ConditionalEvaluationMode.NORMAL;
  
  TranslateToSwiftExprVisitor(LocalVariableStack localVarExprs, ErrorReporter errorReporter) {
    this(localVarExprs, errorReporter, ConditionalEvaluationMode.NORMAL);
  }

  TranslateToSwiftExprVisitor(LocalVariableStack localVarExprs, ErrorReporter errorReporter, ConditionalEvaluationMode conditionalEvaluationMode) {
    this.errorReporter = errorReporter;
    this.localVarExprs = localVarExprs;
    this.conditionalEvaluationMode = conditionalEvaluationMode;
  }

  protected void flipConditionalMode() {
    switch (conditionalEvaluationMode) {
      case CONDITIONAL:
        conditionalEvaluationMode = ConditionalEvaluationMode.CONDITIONAL_NOT;
        break;
      case CONDITIONAL_NOT:
        conditionalEvaluationMode = ConditionalEvaluationMode.CONDITIONAL;
        break;
      default:
        // do nothing
    }
  }
  
  // -----------------------------------------------------------------------------------------------
  // Implementation for a dummy root node.

  @Override
  protected SwiftExpr visitExprRootNode(ExprRootNode node) {
    return visit(node.getRoot());
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for primitives.

  @Override
  protected SwiftExpr visitPrimitiveNode(PrimitiveNode node) {
    // Note: ExprNode.toSourceString() technically returns a Soy expression. In the case of
    // primitives, the result is usually also the correct Python expression.
    return new SwiftExpr(node.toSourceString(), Integer.MAX_VALUE);
  }

  @Override
  protected SwiftExpr visitStringNode(StringNode node) {
	String swiftString = "\"" + node.getValue() + "\"";
    return new SwiftStringExpr(swiftString);
  }

  @Override
  protected SwiftExpr visitNullNode(NullNode node) {
    // Nulls are represented as 'nil' in Swift.
    return new SwiftExpr("nil", Integer.MAX_VALUE);
  }

  @Override
  protected SwiftExpr visitBooleanNode(BooleanNode node) {
    // Specifically set booleans to 'True' and 'False' given python's strict naming for booleans.
    return new SwiftExpr(node.getValue() ? "true" : "false", Integer.MAX_VALUE);
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for collections.

  @Override
  protected SwiftExpr visitListLiteralNode(ListLiteralNode node) {
    return SwiftExprUtils.convertIterableToSwfitListExpr(
        Iterables.transform(
            node.getChildren(),
            new Function<ExprNode, SwiftExpr>() {
              @Override
              public SwiftExpr apply(ExprNode node) {
                return visit(node);
              }
            }));
  }

  @Override
  protected SwiftExpr visitMapLiteralNode(MapLiteralNode node) {
    Preconditions.checkArgument(node.numChildren() % 2 == 0);
    Map<SwiftExpr, SwiftExpr> dict = new LinkedHashMap<>();

    for (int i = 0, n = node.numChildren(); i < n; i += 2) {
      ExprNode keyNode = node.getChild(i);
      ExprNode valueNode = node.getChild(i + 1);
      dict.put(visit(keyNode), visit(valueNode));
    }

    return SwiftExprUtils.convertMapToSwiftExpr(dict);
  }

  @Override
  protected SwiftExpr visitVarRefNode(VarRefNode node) {
    SwiftExpr expr = visitNullSafeNode(node);

    OperatorNode opNode = node.getNearestAncestor(OperatorNode.class);
    // FIXME should ternary operator be included
    if (opNode == null || (opNode.getOperator() == Operator.NOT || opNode.getOperator() == Operator.AND || opNode.getOperator() == Operator.OR )) {
      switch (conditionalEvaluationMode) {
        case CONDITIONAL:
          expr = new SwiftExpr(expr.getText() + ".notNull", expr.getPrecedence());
          break;
        case CONDITIONAL_NOT:
          expr = new SwiftExpr(expr.getText() + ".notNull", expr.getPrecedence());
          break;
        default:
      }
    }

    return expr;
  }

  @Override
  protected SwiftExpr visitDataAccessNode(DataAccessNode node) {
    SwiftExpr expr = visitNullSafeNode(node);
    return expr;
  }

  private SwiftExpr visitNullSafeNode(ExprNode node) {
    StringBuilder nullSafetyPrefix = new StringBuilder();
    String refText = visitNullSafeNodeRecurse(node, nullSafetyPrefix);

    if (nullSafetyPrefix.length() == 0) {
      return new SwiftExpr(refText, Integer.MAX_VALUE);
    } else {
      return new SwiftExpr(
          nullSafetyPrefix + refText, SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL));
    }
  }

  private String visitNullSafeNodeRecurse(ExprNode node, StringBuilder nullSafetyPrefix) {
    switch (node.getKind()) {
      case VAR_REF_NODE:
        {
        	// FIXME fix returning string to '!= nil' or '== nil' in case of conditional evaluation
        	// also check if NOT operator is a parent
          VarRefNode varRef = (VarRefNode) node;
          if (varRef.isInjected()) {
            // Case 1: Injected data reference.
            return genCodeForLiteralKeyAccess("ijData", varRef.getName());
          } else {
        	  	// FIXME take care of proper handling of local vars if needed
            SwiftExpr translation = localVarExprs.getVariableExpression(varRef.getName());
            if (translation != null) {
              // Case 2: In-scope local var.
              return translation.getText();
            } else {
              // Case 3: Data reference.
              return genCodeForLiteralKeyAccess("data", varRef.getName());
            }
          }
        }

      case FIELD_ACCESS_NODE:
      case ITEM_ACCESS_NODE:
        {
          DataAccessNode dataAccess = (DataAccessNode) node;
          // First recursively visit base expression.
          String refText =
              visitNullSafeNodeRecurse(dataAccess.getBaseExprChild(), nullSafetyPrefix);

          // Generate null safety check for base expression.
          if (dataAccess.isNullSafe()) {
        	  	// FIXME
            nullSafetyPrefix.append("None if ").append(refText).append(" is None else ");
          }

          // Generate access to field
          if (node.getKind() == ExprNode.Kind.FIELD_ACCESS_NODE) {
            FieldAccessNode fieldAccess = (FieldAccessNode) node;
            return genCodeForFieldAccess(
                fieldAccess,
                fieldAccess.getBaseExprChild().getType(),
                refText,
                fieldAccess.getFieldName());
          } else {
            ItemAccessNode itemAccess = (ItemAccessNode) node;
            Kind baseKind = itemAccess.getBaseExprChild().getType().getKind();
            SwiftExpr keySwiftExpr = visit(itemAccess.getKeyExprChild());
            switch (baseKind) {
              case LIST:
                return genCodeForKeyAccess(
                    refText, keySwiftExpr, NotFoundBehavior.RETURN_NONE, CoerceKeyToString.NO);
              case UNKNOWN:
                errorReporter.report(
                    itemAccess.getKeyExprChild().getSourceLocation(),
                    UNTYPED_BRACKET_ACCESS_NOT_SUPPORTED);
                // fall through
              case MAP:
              case UNION:
                return genCodeForKeyAccess(
                    refText, keySwiftExpr, NotFoundBehavior.RETURN_NONE, CoerceKeyToString.YES);
              case LEGACY_OBJECT_MAP:
              case RECORD:
                return genCodeForKeyAccess(
                    refText, keySwiftExpr, NotFoundBehavior.THROW, CoerceKeyToString.YES);
              default:
                throw new AssertionError("illegal item access on " + baseKind);
            }
          }
        }

      default:
        {
          SwiftExpr value = visit(node);
          return SwiftExprUtils.maybeProtect(value, Integer.MAX_VALUE).getText();
        }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for operators.

  @Override
  protected SwiftExpr visitOperatorNode(OperatorNode node) {
    return genSwiftExprUsingSoySyntax(node, null);
  }

  @Override
  protected SwiftExpr visitNullCoalescingOpNode(NullCoalescingOpNode node) {
    List<SwiftExpr> children = visitChildren(node);
    int conditionalPrecedence = SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL);

    return new SwiftExpr(children.get(0).getText() + " ?? " + children.get(1).getText(), conditionalPrecedence);
  }

  @Override
  protected SwiftExpr visitConditionalOpNode(ConditionalOpNode node) {
    // Retrieve the operands.
    Operator op = Operator.CONDITIONAL;
    List<SyntaxElement> syntax = op.getSyntax();
    
    conditionalEvaluationMode = ConditionalEvaluationMode.CONDITIONAL;
    List<SwiftExpr> operandExprs = visitChildren(node);
    conditionalEvaluationMode = ConditionalEvaluationMode.NORMAL;

    // FIXME mark visitor scope to evaluate in 'conditional' mode
    // FIXME if conditionalOperand is a VarRefNode (like "x") replace it with "x != nil" expression
    // FIXME if conditionalOperand is a NOT VarRefNode (like "x") replace it with "x == nil" expression
    Operand conditionalOperand = ((Operand) syntax.get(0));
    SwiftExpr conditionalExpr = operandExprs.get(conditionalOperand.getIndex());
    Operand trueOperand = ((Operand) syntax.get(4));
    SwiftExpr trueExpr = operandExprs.get(trueOperand.getIndex());
    Operand falseOperand = ((Operand) syntax.get(8));
    SwiftExpr falseExpr = operandExprs.get(falseOperand.getIndex());

    return genTernaryConditional(conditionalExpr, trueExpr, falseExpr);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The source of available functions is a look-up map provided by Guice in {@link
   * SharedModule#provideSoyFunctionsMap}.
   *
   * FIXME fix code to utilize {@link SoySwiftSourceFunction}
   * 
   * @see BuiltinFunction
   * @see SoySwiftSourceFunction
   */
  @Override
  protected SwiftExpr visitFunctionNode(FunctionNode node) {
    Object soyFunction = node.getSoyFunction();
    if (soyFunction instanceof BuiltinFunction) {
      return visitNonPluginFunction(node, (BuiltinFunction) soyFunction);
    /* } else if (soyFunction instanceof SoySwiftSrcFunction) {
      List<SwiftExpr> args = visitChildren(node);
      return ((SoySwiftSrcFunction) soyFunction).computeForSwiftSrc(args); */
    } else if (soyFunction instanceof LoggingFunction) {
      // trivial logging function support
      return new SwiftStringExpr("\"" + ((LoggingFunction) soyFunction).getPlaceholder() + "\"");
    } else {
      errorReporter.report(
          node.getSourceLocation(), SOY_PY_SRC_FUNCTION_NOT_FOUND, node.getFunctionName());
      return ERROR;
    }
  }

  // FIXME
  private SwiftExpr visitNonPluginFunction(FunctionNode node, BuiltinFunction nonpluginFn) {
    switch (nonpluginFn) {
      case IS_FIRST:
        return visitForEachFunction(node, "__isFirst");
      case IS_LAST:
        return visitForEachFunction(node, "__isLast");
      case INDEX:
        return visitForEachFunction(node, "__index");
      case CHECK_NOT_NULL:
        return visitCheckNotNullFunction(node);
      case CSS:
        return visitCssFunction(node);
      case XID:
        return visitXidFunction(node);
      case IS_PRIMARY_MSG_IN_USE:
        return visitIsPrimaryMsgInUseFunction(node);
      case V1_EXPRESSION:
        throw new UnsupportedOperationException(
            "the v1Expression function can't be used in templates compiled to Python");
      case MSG_WITH_ID:
      case REMAINDER:
        // should have been removed earlier in the compiler
        throw new AssertionError();
    }
    throw new AssertionError();
  }

  private SwiftExpr visitForEachFunction(FunctionNode node, String suffix) {
    String varName = ((VarRefNode) node.getChild(0)).getName();
    return localVarExprs.getVariableExpression(varName + suffix);
  }

  // FIXME
  private SwiftExpr visitCheckNotNullFunction(FunctionNode node) {
    SwiftExpr childExpr = visit(node.getChild(0));
    return childExpr;
  }

  // FIXME
  private SwiftExpr visitCssFunction(FunctionNode node) {
    return new SwiftFunctionExprBuilder("runtime.getCSSName")
        .addArgs(visitChildren(node))
        .asSwiftExpr();
  }

  // FIXME
  private SwiftExpr visitXidFunction(FunctionNode node) {
    return new SwiftFunctionExprBuilder("runtime.getXIDName")
        .addArg(visit(node.getChild(0)))
        .asSwiftExpr();
  }

  // FIXME
  private SwiftExpr visitIsPrimaryMsgInUseFunction(FunctionNode node) {
    long primaryMsgId = ((IntegerNode) node.getChild(1)).getValue();
    long fallbackMsgId = ((IntegerNode) node.getChild(2)).getValue();
    return new SwiftExpr(
        SwiftExprUtils.TRANSLATOR_NAME
            + ".is_msg_available("
            + primaryMsgId
            + ") or not "
            + SwiftExprUtils.TRANSLATOR_NAME
            + ".is_msg_available("
            + fallbackMsgId
            + ")",
        SwiftExprUtils.swiftPrecedenceForOperator(Operator.OR));
  }

  @Override
  protected SwiftExpr visitEqualOpNode(EqualOpNode node) {
    List<SwiftExpr> operandPyExprs = visitChildren(node);

    return new SwiftExpr(
            operandPyExprs.get(0).getText()
            + " == "
            + operandPyExprs.get(1).getText(),
        Integer.MAX_VALUE);
  }

  @Override
  protected SwiftExpr visitNotEqualOpNode(NotEqualOpNode node) {
    List<SwiftExpr> operandPyExprs = visitChildren(node);

    return new SwiftExpr(
            operandPyExprs.get(0).getText()
            + " != "
            + operandPyExprs.get(1).getText(),
        SwiftExprUtils.swiftPrecedenceForOperator(Operator.NOT));
  }
  
  /**
   * Generates the code for key access given a key literal, e.g. {@code .get('key')}.
   *
   * @param key the String literal value to be used as a key
   */
  private static String genCodeForLiteralKeyAccess(String containerExpr, String key) {
    return genCodeForKeyAccess(
        containerExpr,
        new SwiftStringExpr("\"" + key + "\""),
        NotFoundBehavior.THROW,
        CoerceKeyToString.NO);
  }


  /**
   * Generates the code for key access given the name of a variable to be used as a key, e.g. {@code
   * .get(key)}.
   *
   * @param key an expression to be used as a key
   * @param notFoundBehavior What should happen if the key is not in the structure.
   * @param coerceKeyToString Whether or not the key should be coerced to a string.
   */
  private static String genCodeForKeyAccess(
      String containerExpr,
      SwiftExpr key,
      NotFoundBehavior notFoundBehavior,
      CoerceKeyToString coerceKeyToString) {
    if (coerceKeyToString == CoerceKeyToString.YES) {
      key = new SwiftFunctionExprBuilder("runtime.maybe_coerce_key_to_string").addArg(key).asSwiftExpr();
    }
    if (notFoundBehavior == NotFoundBehavior.RETURN_NONE) {
      return new SwiftFunctionExprBuilder("runtime.key_safe_data_access")
          .addArg(new SwiftExpr(containerExpr, Integer.MAX_VALUE))
          .addArg(key)
          .build();
    } else {
      return new SwiftFunctionExprBuilder(containerExpr + ".get").addArg(key).build();
    }
  }

  /**
   * Generates the code for a field name access, e.g. ".foo" or "['bar']".
   *
   * @param node the field access source node
   * @param baseType the type of the object that contains the field
   * @param containerExpr an expression that evaluates to the container of the named field. This
   *     expression may have any operator precedence that binds more tightly than exponentiation.
   * @param fieldName the field name
   */
  private String genCodeForFieldAccess(
      ExprNode node, SoyType baseType, String containerExpr, String fieldName) {
    if (baseType != null && baseType.getKind() == SoyType.Kind.PROTO) {
    	  // TODO - optional protobuf support
      errorReporter.report(node.getSourceLocation(), PROTO_ACCESS_NOT_SUPPORTED);
      return ".ERROR";
    }
    return genCodeForLiteralKeyAccess(containerExpr, fieldName);
  }

  /**
   * Generates a Swift expression for the given OperatorNode's subtree assuming that the Swift
   * expression for the operator uses the same syntax format as the Soy operator.
   *
   * @param opNode the OperatorNode whose subtree to generate a Python expression for
   * @return the generated Swift expression
   */
  private SwiftExpr genSwiftExprUsingSoySyntax(OperatorNode opNode, String tokenToOverride) {
    List<SwiftExpr> operandSwiftExprs = visitChildren(opNode);
    String newExpr = SwiftExprUtils.genExprWithNewToken(opNode.getOperator(), operandSwiftExprs, tokenToOverride);

    return new SwiftExpr(newExpr, SwiftExprUtils.swiftPrecedenceForOperator(opNode.getOperator()));
  }

  /**
   * Generates a ternary conditional Python expression given the conditional and true/false
   * expressions.
   *
   * @param conditionalExpr the conditional expression
   * @param trueExpr the expression to execute if the conditional executes to true
   * @param falseExpr the expression to execute if the conditional executes to false
   * @return a ternary conditional expression
   */
  private SwiftExpr genTernaryConditional(SwiftExpr conditionalExpr, SwiftExpr trueExpr, SwiftExpr falseExpr) {
    int conditionalPrecedence = SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL);
    StringBuilder exprSb =
        new StringBuilder()
            .append(SwiftExprUtils.maybeProtect(conditionalExpr, conditionalPrecedence).getText())
            .append(" ? ")
            .append(SwiftExprUtils.maybeProtect(trueExpr, conditionalPrecedence).getText())
            .append(" : ")
            .append(SwiftExprUtils.maybeProtect(falseExpr, conditionalPrecedence).getText());

    return new SwiftExpr(exprSb.toString(), conditionalPrecedence);
  }

  @Override
  protected SwiftExpr visitProtoInitNode(ProtoInitNode node) {
    errorReporter.report(node.getSourceLocation(), PROTO_INIT_NOT_SUPPORTED);
    return ERROR;
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for operator nodes.

  @Override
  protected SwiftExpr visitNotOpNode(NotOpNode node) {
	flipConditionalMode();

    SwiftExpr expr = genSwiftExprUsingSoySyntax(node, "!");

	flipConditionalMode();
	return expr;
  }
}
