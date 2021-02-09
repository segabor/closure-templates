package com.google.template.soy.swiftsrc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.AbstractReturningExprNodeVisitor;
import com.google.template.soy.exprtree.BooleanNode;
import com.google.template.soy.exprtree.DataAccessNode;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.AccessChainComponentNode;
import com.google.template.soy.exprtree.ExprNode.OperatorNode;
import com.google.template.soy.exprtree.ExprNode.PrimitiveNode;
import com.google.template.soy.exprtree.Operator.Operand;
import com.google.template.soy.exprtree.Operator.SyntaxElement;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListComprehensionNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.MethodCallNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.NullSafeAccessNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.exprtree.OperatorNodes.AssertNonNullOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.EqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.logging.LoggingFunction;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.plugin.swift.restricted.SoySwiftSourceFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.shared.internal.BuiltinMethod;
import com.google.template.soy.shared.restricted.SoyMethod;
import com.google.template.soy.shared.restricted.SoySourceFunctionMethod;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import com.google.template.soy.swiftsrc.restricted.SwiftFunctionExprBuilder;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;

public final class TranslateToSwiftExprVisitor extends AbstractReturningExprNodeVisitor<SwiftExpr> {

  public static final String DATA_INTERNAL_VAR_NAME = "__data";
  
  public static final String IJDATA_INTERNAL_VAR_NAME = "__ijData";

  public static final SwiftExpr DATA = new SwiftExpr(DATA_INTERNAL_VAR_NAME, Integer.MAX_VALUE);
  
  public static final SwiftExpr IJDATA = new SwiftExpr(IJDATA_INTERNAL_VAR_NAME, Integer.MAX_VALUE);

  private static class NotFoundBehavior {
    private static final NotFoundBehavior RETURN_NONE = new NotFoundBehavior(Type.RETURN_NONE);
    private static final NotFoundBehavior THROW = new NotFoundBehavior(Type.THROW);

    /** Return {@code None} if the key is not in the structure. */
    private static NotFoundBehavior returnNone() {
      return RETURN_NONE;
    }

    /** Throw an exception if the key is not in the structure. */
    private static NotFoundBehavior throwException() {
      return THROW;
    }

    /** Default to the given value if the key is not in the structure. */
    private static NotFoundBehavior defaultValue(SwiftExpr defaultValue) {
      return new NotFoundBehavior(defaultValue);
    }

    private enum Type {
      RETURN_NONE,
      THROW,
      DEFAULT_VALUE,
    }

    private final Type type;
    @Nullable private final SwiftExpr defaultValue;

    private NotFoundBehavior(Type type) {
      this.type = type;
      this.defaultValue = null;
    }

    private NotFoundBehavior(SwiftExpr defaultValue) {
      this.type = Type.DEFAULT_VALUE;
      this.defaultValue = checkNotNull(defaultValue);
    }

    private Type getType() {
      return type;
    }

    private SwiftExpr getDefaultValue() {
      return defaultValue;
    }
  }

  protected enum ConditionalEvaluationMode {
	  NORMAL, CONDITIONAL, CONDITIONAL_NOT
  }

  private static final SoyErrorKind PROTO_ACCESS_NOT_SUPPORTED =
      SoyErrorKind.of("Proto accessors are not supported in Swift src.");
  private static final SoyErrorKind PROTO_INIT_NOT_SUPPORTED =
      SoyErrorKind.of("Proto init is not supported in Swift src.");
  private static final SoyErrorKind SOY_SWIFT_SRC_FUNCTION_NOT_FOUND =
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

  private static final SwiftExpr NONE = new SwiftExpr("nil", Integer.MAX_VALUE);

  private final LocalVariableStack localVarExprs;

  private final ErrorReporter errorReporter;
  private final SwiftValueFactoryImpl pluginValueFactory;

  /**
   * TBD
   * Normal - visit child nodes as usual
   * Conditional - replace 'data[..]' to 'data[..] != nil'
   * Conditional_Not - replace 'data[..]' to 'data[..] == nil'
   */
  private ConditionalEvaluationMode conditionalEvaluationMode = ConditionalEvaluationMode.NORMAL;
  
  TranslateToSwiftExprVisitor(
      LocalVariableStack localVarExprs,
      SwiftValueFactoryImpl pluginValueFactory,
      ErrorReporter errorReporter) {
    this(localVarExprs, pluginValueFactory, errorReporter, ConditionalEvaluationMode.NORMAL);
  }

  TranslateToSwiftExprVisitor(
      LocalVariableStack localVarExprs,
      SwiftValueFactoryImpl pluginValueFactory,
      ErrorReporter errorReporter,
      ConditionalEvaluationMode conditionalEvaluationMode) {
    this.localVarExprs = localVarExprs;
    this.pluginValueFactory = pluginValueFactory;
    this.errorReporter = errorReporter;
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
  protected SwiftExpr visitListComprehensionNode(ListComprehensionNode node) {

    // Visit the originalListExpr in: [transformExpr for $foo in originalListExpr if filterExpr].
    SwiftExpr originalListExpr = visit(node.getListExpr());

    // Build a unique name for the iterator variable ($foo in this example), and push a local var
    // frame for its scope.
    String baseListIterVarName = node.getListIterVar().name();
    String uniqueListIterVarName =
        String.format("%sListComprehensions%d", baseListIterVarName, node.getNodeId());
    localVarExprs.pushFrame();
    localVarExprs.addVariable(
        baseListIterVarName, new SwiftExpr(uniqueListIterVarName, Integer.MAX_VALUE));
    String uniqueIndexVarName = null;
    if (node.getIndexVar() != null) {
      String baseIndexVarName = node.getIndexVar().name();
      uniqueIndexVarName =
          String.format("%sListComprehensions%d", baseIndexVarName, node.getNodeId());
      localVarExprs.addVariable(
          baseIndexVarName, new SwiftExpr(uniqueIndexVarName, Integer.MAX_VALUE));
    }

    // Now we can visit the transformExpr and filterExpr (if present).
    SwiftExpr itemTransformExpr = visit(node.getListItemTransformExpr());
    SwiftExpr filterExpr = node.getFilterExpr() == null ? null : visit(node.getFilterExpr());

    // Build the full list comprehension expr.
    SwiftExpr comprehensionExpr =
        SwiftExprUtils.genSwiftListComprehensionExpr(
            originalListExpr,
            itemTransformExpr,
            filterExpr,
            uniqueListIterVarName,
            uniqueIndexVarName);

    localVarExprs.popFrame();

    return comprehensionExpr;
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
    if (node.getDefnDecl().kind() == VarDefn.Kind.STATE) {
      throw new AssertionError(); // should have been desugared
    } else if (node.isInjected()) {
      // Case 1: Injected data reference.
      return new SwiftExpr(
          genCodeForLiteralKeyAccess(IJDATA, node.getNameWithoutLeadingDollar()),
          Integer.MAX_VALUE);
    } else {
      SwiftExpr translation = localVarExprs.getVariableExpression(node.getNameWithoutLeadingDollar());
      if (translation != null) {
        // Case 2: In-scope local var.
        return new SwiftExpr(translation.getText(), Integer.MAX_VALUE);
      } else {
        // Case 3: Data reference.
        NotFoundBehavior notFoundBehavior = NotFoundBehavior.throwException();
        if (node.getDefnDecl().kind() == VarDefn.Kind.PARAM
            && ((TemplateParam) node.getDefnDecl()).hasDefault()) {
          // This evaluates the default value at every access of a parameter with a default
          // value. This could be made more performant by only evaluating the default value
          // once at the beginning of the template. But the Swift backend is minimally
          // supported so this is fine.
          SwiftExpr defaultValue = visit(((TemplateParam) node.getDefnDecl()).defaultValue());
          notFoundBehavior = NotFoundBehavior.defaultValue(defaultValue);
        }
        return new SwiftExpr(
            genCodeForLiteralKeyAccess(DATA, node.getNameWithoutLeadingDollar(), notFoundBehavior),
            Integer.MAX_VALUE);
      }
    }
  }

  @Override
  protected SwiftExpr visitDataAccessNode(DataAccessNode node) {
    // All null safe accesses should've already been converted to NullSafeAccessNodes.
    checkArgument(!node.isNullSafe());
    // First recursively visit base expression.
    SwiftExpr base = visit(node.getBaseExprChild());
    return new SwiftExpr(visitDataAccessNode(node, base), Integer.MAX_VALUE);
  }

  private SwiftExpr visitDataAccessNode(
      DataAccessNode dataAccess,
      StringBuilder nullSafetyPrefix,
      SwiftExpr base,
      boolean nullSafe,
      boolean hasAssertNonNull) {
    // Generate null safety check for base expression.
    if (nullSafe) {
      // FIXME: change it to 'if let' expression
      nullSafetyPrefix.append("None if ").append(base.getText()).append(" is None else ");
    }
    SwiftExpr result = new SwiftExpr(visitDataAccessNode(dataAccess, base), Integer.MAX_VALUE);
    if (hasAssertNonNull) {
      result = assertNotNull(result);
    }
    return result;
  }

  private String visitDataAccessNode(DataAccessNode dataAccess, SwiftExpr base) {
    // Generate access to field
    if (dataAccess.getKind() == ExprNode.Kind.FIELD_ACCESS_NODE) {
      FieldAccessNode fieldAccess = (FieldAccessNode) dataAccess;
      return genCodeForFieldAccess(
          fieldAccess, fieldAccess.getBaseExprChild().getType(), base, fieldAccess.getFieldName());
    } else if (dataAccess.getKind() == ExprNode.Kind.METHOD_CALL_NODE) {
      MethodCallNode methodCall = (MethodCallNode) dataAccess;
      return genCodeForMethodCall(methodCall, base);
    } else {
      ItemAccessNode itemAccess = (ItemAccessNode) dataAccess;
      Kind baseKind = itemAccess.getBaseExprChild().getType().getKind();
      SwiftExpr keyExpr = visit(itemAccess.getKeyExprChild());
      switch (baseKind) {
        case LIST:
          return genCodeForKeyAccess(base, keyExpr, NotFoundBehavior.returnNone());
        case UNKNOWN:
          errorReporter.report(
              itemAccess.getKeyExprChild().getSourceLocation(),
              UNTYPED_BRACKET_ACCESS_NOT_SUPPORTED);
          // fall through
        case MAP:
        case UNION:
          return genCodeForKeyAccess(base, keyExpr, NotFoundBehavior.returnNone());
        case LEGACY_OBJECT_MAP:
        case RECORD:
          return genCodeForKeyAccess(base, keyExpr, NotFoundBehavior.throwException());
        default:
          throw new AssertionError("illegal item access on " + baseKind);
      }
    }
  }

  @Override
  protected SwiftExpr visitNullSafeAccessNode(NullSafeAccessNode nullSafeAccessNode) {
    StringBuilder nullSafetyPrefix = new StringBuilder();

    SwiftExpr access = visit(nullSafeAccessNode.getBase());
    ExprNode dataAccess = nullSafeAccessNode.getDataAccess();
    while (dataAccess.getKind() == ExprNode.Kind.NULL_SAFE_ACCESS_NODE) {
      NullSafeAccessNode node = (NullSafeAccessNode) dataAccess;
      access =
          accumulateDataAccess(
              (DataAccessNode) node.getBase(),
              access,
              nullSafetyPrefix,
              /* hasAssertNonNull= */ false);
      dataAccess = node.getDataAccess();
    }
    access =
        accumulateDataAccessTail((AccessChainComponentNode) dataAccess, access, nullSafetyPrefix);

    if (nullSafetyPrefix.length() == 0) {
      return access;
    } else {
      return new SwiftExpr(
          nullSafetyPrefix + access.getText(),
          SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL));
    }
  }

  private SwiftExpr accumulateDataAccess(
      DataAccessNode dataAccessNode,
      SwiftExpr base,
      StringBuilder nullSafetyPrefix,
      boolean hasAssertNonNull) {
    boolean nullSafe = true;
    if (dataAccessNode.getBaseExprChild() instanceof DataAccessNode) {
      base =
          accumulateDataAccess(
              (DataAccessNode) dataAccessNode.getBaseExprChild(),
              base,
              nullSafetyPrefix,
              /* hasAssertNonNull= */ false);
      nullSafe = false;
    }
    return visitDataAccessNode(dataAccessNode, nullSafetyPrefix, base, nullSafe, hasAssertNonNull);
  }

  private SwiftExpr accumulateDataAccessTail(
      AccessChainComponentNode dataAccessNode, SwiftExpr base, StringBuilder nullSafetyPrefix) {
    boolean hasAssertNonNull = false;
    if (dataAccessNode.getKind() == ExprNode.Kind.ASSERT_NON_NULL_OP_NODE) {
      AssertNonNullOpNode assertNonNull = (AssertNonNullOpNode) dataAccessNode;
      dataAccessNode = (AccessChainComponentNode) assertNonNull.getChild(0);
      hasAssertNonNull = true;
    }
    return accumulateDataAccess(
        (DataAccessNode) dataAccessNode, base, nullSafetyPrefix, hasAssertNonNull);
  }

  @Override
  protected SwiftExpr visitGlobalNode(GlobalNode node) {
    return visit(node.getValue());
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for operators.

  @Override
  protected SwiftExpr visitOperatorNode(OperatorNode node) {
    String opToOverride = null;
    
    switch (node.getOperator()) {
      case OR:
        opToOverride = "||";
        break;
      case AND:
        opToOverride = "&&";
        break;
      default:
        break;
    }

    return genSwiftExprUsingSoySyntax(node, opToOverride);
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
    } else if (soyFunction instanceof SoySwiftSourceFunction) {
      return pluginValueFactory.applyFunction(
          node.getSourceLocation(),
          node.getFunctionName(),
          (SoySwiftSourceFunction) soyFunction,
          visitChildren(node));
    } else if (soyFunction instanceof LoggingFunction) {
      // trivial logging function support
      return new SwiftStringExpr("\"" + ((LoggingFunction) soyFunction).getPlaceholder() + "\"");
    } else {
      errorReporter.report(
          node.getSourceLocation(), SOY_SWIFT_SRC_FUNCTION_NOT_FOUND, node.getFunctionName());
      return ERROR;
    }
  }

  // FIXME
  private SwiftExpr visitNonPluginFunction(FunctionNode node, BuiltinFunction nonpluginFn) {
    switch (nonpluginFn) {
      case IS_PARAM_SET:
        return visitIsSetFunction(node);
      case IS_FIRST:
        return visitForEachFunction(node, "__isFirst");
      case IS_LAST:
        return visitForEachFunction(node, "__isLast");
      case INDEX:
        return visitForEachFunction(node, "__index");
      case CHECK_NOT_NULL:
        return assertNotNull(node.getChild(0));
      case CSS:
        return visitCssFunction(node);
      case XID:
        return visitXidFunction(node);
      case SOY_SERVER_KEY:
        return visitSoyServerKeyFunction(node);
      case IS_PRIMARY_MSG_IN_USE:
        return visitIsPrimaryMsgInUseFunction(node);
      case TO_FLOAT:
        // this is a no-op in python
        return visit(node.getChild(0));
      case DEBUG_SOY_TEMPLATE_INFO:
        // 'debugSoyTemplateInfo' is used for inpsecting soy template info from rendered pages.
        // Always resolve to false since there is no plan to support this feature in PySrc.
        return new SwiftExpr("false", Integer.MAX_VALUE);
      case LEGACY_DYNAMIC_TAG:
      case UNKNOWN_JS_GLOBAL:
        throw new UnsupportedOperationException(
            "the "
                + nonpluginFn.getName()
                + " function can't be used in templates compiled to Swift");
      case VE_DATA:
        return NONE;
      case MSG_WITH_ID:
      case REMAINDER:
      case TEMPLATE:
        // should have been removed earlier in the compiler
        throw new AssertionError();
      case PROTO_INIT:
        errorReporter.report(node.getSourceLocation(), PROTO_INIT_NOT_SUPPORTED);
        return ERROR;
    }
    throw new AssertionError();
  }

  private SwiftExpr visitForEachFunction(FunctionNode node, String suffix) {
    String varName = ((VarRefNode) node.getChild(0)).getNameWithoutLeadingDollar();
    return localVarExprs.getVariableExpression(varName + suffix);
  }

  // FIXME: runtime.is_set is a non existent runtime function in SoyKit
  // It should check if SoyValue has member with varName, like dict.keys.contains(varName)
  private SwiftExpr visitIsSetFunction(FunctionNode node) {
    String varName = ((VarRefNode) node.getChild(0)).getNameWithoutLeadingDollar();
    return new SwiftFunctionExprBuilder("runtime.is_set").addArg(varName).addArg(DATA_INTERNAL_VAR_NAME).asSwiftExpr();
  }

  private SwiftExpr assertNotNull(ExprNode node) {
    return assertNotNull(visit(node));
  }

  // FIXME: runtime.check_not_null is a non existent runtime function in SoyKit
  private static SwiftExpr assertNotNull(SwiftExpr expr) {
    return new SwiftFunctionExprBuilder("runtime.check_not_null").addArg(expr).asSwiftExpr();
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

  private SwiftExpr visitSoyServerKeyFunction(FunctionNode node) {
    return visit(node.getChild(0));
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
  private static String genCodeForLiteralKeyAccess(SwiftExpr containerExpr, String key) {
    return genCodeForLiteralKeyAccess(containerExpr, key, NotFoundBehavior.throwException());
  }

  private static String genCodeForLiteralKeyAccess(
      SwiftExpr containerExpr, String key, NotFoundBehavior notFoundBehavior) {
    return genCodeForKeyAccess(containerExpr, new SwiftStringExpr("\"" + key + "\""), notFoundBehavior);
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
      SwiftExpr containerExpr,
      SwiftExpr key,
      NotFoundBehavior notFoundBehavior) {
    switch (notFoundBehavior.getType()) {
      case RETURN_NONE:
        return new SwiftFunctionExprBuilder("runtime.key_safe_data_access")
            .addArg(containerExpr)
            .addArg(key)
            .build();
      case THROW:
        return new SwiftFunctionExprBuilder(containerExpr.getText() + ".get").addArg(key).build();
      case DEFAULT_VALUE:
        return new SwiftFunctionExprBuilder(containerExpr.getText() + ".get")
            .addArg(key)
            .addArg(notFoundBehavior.getDefaultValue())
            .build();
    }
    throw new AssertionError(notFoundBehavior.getType());
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
      ExprNode node, SoyType baseType, SwiftExpr containerExpr, String fieldName) {
    if (baseType != null && baseType.getKind() == SoyType.Kind.PROTO) {
      errorReporter.report(node.getSourceLocation(), PROTO_ACCESS_NOT_SUPPORTED);
      return ".ERROR";
    }
    return genCodeForLiteralKeyAccess(containerExpr, fieldName);
  }

  private String genCodeForMethodCall(MethodCallNode methodCallNode, SwiftExpr containerExpr) {
    Preconditions.checkArgument(methodCallNode.isMethodResolved());
    SoyMethod method = methodCallNode.getSoyMethod();

    // Never allow a null method receiver.
    containerExpr = assertNotNull(containerExpr);

    if (method instanceof BuiltinMethod) {
      switch ((BuiltinMethod) method) {
        case BIND:
          return new SwiftFunctionExprBuilder("runtime.bind_template_params")
              .addArg(containerExpr)
              .addArg(visit(methodCallNode.getChild(1)))
              .asSwiftExpr()
              .getText();
        case GET_EXTENSION:
        case HAS_PROTO_FIELD:
          errorReporter.report(
              methodCallNode.getAccessSourceLocation(),
              SOY_SWIFT_SRC_FUNCTION_NOT_FOUND,
              methodCallNode.getMethodName());
          return ".ERROR";
      }
    } else if (method instanceof SoySourceFunctionMethod) {
      SoySourceFunction function = ((SoySourceFunctionMethod) method).getImpl();
      if (function instanceof SoySwiftSourceFunction) {
        List<SwiftExpr> args = new ArrayList<>();
        args.add(containerExpr);
        methodCallNode.getParams().forEach(n -> args.add(visit(n)));
        return pluginValueFactory
            .applyFunction(
                methodCallNode.getSourceLocation(),
                methodCallNode.getMethodName().identifier(),
                (SoySwiftSourceFunction) function,
                args)
            .getText();
      } else {
        errorReporter.report(
            methodCallNode.getAccessSourceLocation(),
            SOY_SWIFT_SRC_FUNCTION_NOT_FOUND,
            methodCallNode.getMethodName());
        return ".ERROR";
      }
    }
    throw new AssertionError();
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
