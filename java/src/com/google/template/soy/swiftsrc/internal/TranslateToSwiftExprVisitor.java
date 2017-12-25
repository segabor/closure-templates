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
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import com.google.template.soy.swiftsrc.restricted.SwiftFunctionExprBuilder;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;

public final class TranslateToSwiftExprVisitor extends AbstractReturningExprNodeVisitor<SwiftExpr> {

  private static final SoyErrorKind PROTO_ACCESS_NOT_SUPPORTED =
      SoyErrorKind.of("Proto accessors are not supported in pysrc.");
  private static final SoyErrorKind PROTO_INIT_NOT_SUPPORTED =
      SoyErrorKind.of("Proto init is not supported in pysrc.");
  
  private final LocalVariableStack localVarExprs;
  private final ErrorReporter errorReporter;

  TranslateToSwiftExprVisitor(LocalVariableStack localVarExprs, ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
    this.localVarExprs = localVarExprs;
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
    return visitNullSafeNode(node);
  }

  @Override
  protected SwiftExpr visitDataAccessNode(DataAccessNode node) {
    return visitNullSafeNode(node);
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
          VarRefNode varRef = (VarRefNode) node;
          if (varRef.isInjected()) {
            // Case 1: Injected data reference.
            return genCodeForLiteralKeyAccess("ijData", varRef.getName());
          } else {
        	  	// FIXME take care of proper handling of local vars if needed
            SwiftExpr translation = null /* localVarExprs.getVariableExpression(varRef.getName()) */;
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
            if (baseKind == Kind.LEGACY_OBJECT_MAP || baseKind == Kind.RECORD) {
              return genCodeForKeyAccess(refText, keySwiftExpr.getText());
            } else {
              return new SwiftFunctionExprBuilder("runtime.key_safe_data_access")
                  .addArg(new SwiftExpr(refText, Integer.MAX_VALUE))
                  .addArg(keySwiftExpr)
                  .build();
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
    return genSwiftExprUsingSoySyntax(node);
  }

  @Override
  protected SwiftExpr visitNullCoalescingOpNode(NullCoalescingOpNode node) {
    List<SwiftExpr> children = visitChildren(node);
    int conditionalPrecedence = SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL);

    return new SwiftExpr(children.get(0).getText() + " ?? " + children.get(1).getText(), conditionalPrecedence);
  }

  @Override
  protected SwiftExpr visitConditionalOpNode(ConditionalOpNode node) {
    // TODO Auto-generated method stub
    return super.visitConditionalOpNode(node);
  }
  
  /**
   * Generates the code for key access given a key literal, e.g. {@code .get('key')}.
   *
   * @param key the String literal value to be used as a key
   */
  private static String genCodeForLiteralKeyAccess(String containerExpr, String key) {
	// TODO review this snippet
    return genCodeForKeyAccess(containerExpr, "\"" + key + "\"");
  }

  /**
   * Generates the code for key access given the name of a variable to be used as a key, e.g. {@code
   * .get(key)}.
   *
   * @param keyName the variable name to be used as a key
   */
  private static String genCodeForKeyAccess(String containerExpr, String keyName) {
	// TODO review this snippet
    return containerExpr + ".get(" + keyName + ")";
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
   * Generates a Python expression for the given OperatorNode's subtree assuming that the Python
   * expression for the operator uses the same syntax format as the Soy operator.
   *
   * @param opNode the OperatorNode whose subtree to generate a Python expression for
   * @return the generated Python expression
   */
  private SwiftExpr genSwiftExprUsingSoySyntax(OperatorNode opNode) {
    List<SwiftExpr> operandSwiftExprs = visitChildren(opNode);
    String newExpr = SwiftExprUtils.genExprWithNewToken(opNode.getOperator(), operandSwiftExprs, null);

    // FIXME
    return new SwiftExpr(newExpr, SwiftExprUtils.swiftPrecedenceForOperator(opNode.getOperator()));
  }
}
