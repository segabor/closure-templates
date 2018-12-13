package com.google.template.soy.swiftsrc.internal;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.base.internal.QuoteStyle;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.msgs.internal.MsgUtils;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfElseNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.swiftsrc.internal.TranslateToSwiftExprVisitor.ConditionalEvaluationMode;
import com.google.template.soy.swiftsrc.restricted.SoySwiftSrcPrintDirective;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;

public class GenSwiftExprsVisitor extends AbstractSoyNodeVisitor<List<SwiftExpr>> {
  private static final SoyErrorKind UNKNOWN_SOY_SWIFT_SRC_PRINT_DIRECTIVE =
      SoyErrorKind.of("Unknown SoySwiftSrcPrintDirective ''{0}''.");

  /** Injectable factory for creating an instance of this class. */
  public static final class GenSwiftExprsVisitorFactory {
    private final IsComputableAsSwiftExprVisitor isComputableAsSwiftExprVisitor;
    // depend on a Supplier since there is a circular dependency between genSwiftExprsVisitorFactory
    // and GenPyCallExprVisitor
    private final Supplier<GenSwiftCallExprVisitor> genSwiftCallExprVisitor;

    GenSwiftExprsVisitorFactory(
    		IsComputableAsSwiftExprVisitor isComputableAsSwiftExprVisitor,
    		Supplier<GenSwiftCallExprVisitor> genSwiftCallExprVisitor) {
    	  this.isComputableAsSwiftExprVisitor = isComputableAsSwiftExprVisitor;
      this.genSwiftCallExprVisitor = genSwiftCallExprVisitor;
    }

    public GenSwiftExprsVisitor create(
      LocalVariableStack localVarExprs, ErrorReporter errorReporter) {
      return new GenSwiftExprsVisitor(
          isComputableAsSwiftExprVisitor,
          this,
          genSwiftCallExprVisitor.get(),
          localVarExprs,
          errorReporter);
    }
  }

  private final IsComputableAsSwiftExprVisitor isComputableAsSwiftExprVisitor;

  private final GenSwiftExprsVisitorFactory genSwiftExprsVisitorFactory;

  private final GenSwiftCallExprVisitor genSwiftCallExprVisitor;

  private final LocalVariableStack localVarExprs;

  /** List to collect the results. */
  private List<SwiftExpr> swiftExprs;

  private final ErrorReporter errorReporter;

  GenSwiftExprsVisitor(
      IsComputableAsSwiftExprVisitor isComputableAsSwiftExprVisitor,
      GenSwiftExprsVisitorFactory genSwiftExprsVisitorFactory,
      GenSwiftCallExprVisitor genSwiftCallExprVisitor,
      LocalVariableStack localVarExprs,
      ErrorReporter errorReporter) {
	this.isComputableAsSwiftExprVisitor = isComputableAsSwiftExprVisitor;
    this.genSwiftExprsVisitorFactory = genSwiftExprsVisitorFactory;
    this.genSwiftCallExprVisitor = genSwiftCallExprVisitor;
    this.localVarExprs = localVarExprs;
    this.errorReporter = errorReporter;
  }

  @Override
  public List<SwiftExpr> exec(SoyNode node) {
    Preconditions.checkArgument(isComputableAsSwiftExprVisitor.exec(node));
    swiftExprs = new ArrayList<>();
    visit(node);
    return swiftExprs;
  }

  /**
   * Executes this visitor on the children of the given node, without visiting the given node
   * itself.
   */
  List<SwiftExpr> execOnChildren(ParentSoyNode<?> node) {
    // Preconditions.checkArgument(isComputableAsPyExprVisitor.execOnChildren(node));
	  swiftExprs = new ArrayList<>();
    visitChildren(node);
    return swiftExprs;
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.
  
  /**
   * Example:
   *
   * <pre>
   *   I'm feeling lucky!
   * </pre>
   *
   * generates
   *
   * <pre>
   *   "I\'m feeling lucky!"
   * </pre>
   */
  @Override
  protected void visitRawTextNode(RawTextNode node) {
    // Escape special characters in the text before writing as a string.
    String exprText = BaseUtils.escapeToSoyString(node.getRawText(), false, QuoteStyle.DOUBLE);
    swiftExprs.add(new SwiftStringExpr(exprText));
  }

  /**
   * Visiting a print node accomplishes 3 basic tasks. It loads data, it performs any operations
   * needed, and it executes the appropriate print directives.
   *
   * <p>TODO(dcphillips): Add support for local variables once LetNode are supported.
   *
   * <p>Example:
   *
   * <pre>
   *   {$boo |changeNewlineToBr}
   *   {$goo + 5}
   * </pre>
   *
   * might generate
   *
   * <pre>
   *   sanitize.change_newline_to_br(data.get('boo'))
   *   data.get('goo') + 5
   * </pre>
   */
  @Override
  protected void visitPrintNode(PrintNode node) {
	  TranslateToSwiftExprVisitor translator =
        new TranslateToSwiftExprVisitor(localVarExprs, errorReporter);

    SwiftExpr swiftExpr = translator.exec(node.getExpr());

    // Process directives.
    for (PrintDirectiveNode directiveNode : node.getChildren()) {

      // Get directive.
      SoyPrintDirective directive = directiveNode.getPrintDirective();
      if (!(directive instanceof SoySwiftSrcPrintDirective)) {
        errorReporter.report(
            directiveNode.getSourceLocation(),
            UNKNOWN_SOY_SWIFT_SRC_PRINT_DIRECTIVE,
            directiveNode.getName());
        continue;
      }

      // Get directive args.
      List<ExprRootNode> args = directiveNode.getArgs();
      // Translate directive args.
      List<SwiftExpr> argsSwiftExprs = new ArrayList<>(args.size());
      for (ExprRootNode arg : args) {
        argsSwiftExprs.add(translator.exec(arg));
      }

      // Apply directive.
      swiftExpr = ((SoySwiftSrcPrintDirective) directive).applyForSwiftSrc(swiftExpr, argsSwiftExprs);
    }

    swiftExprs.add(swiftExpr);
  }

  @Override
  protected void visitMsgFallbackGroupNode(MsgFallbackGroupNode node) {
    SwiftExpr msg = generateMsgFunc(node.getMsg());

    // MsgFallbackGroupNode could only have one child or two children. See MsgFallbackGroupNode.
    if (node.hasFallbackMsg()) {
      StringBuilder pyExprTextSb = new StringBuilder();
      SwiftExpr fallbackMsg = generateMsgFunc(node.getFallbackMsg());

      // Build Python ternary expression: a if cond else c
      // FIXME transform python expression to swift analogue
      pyExprTextSb.append(msg.getText()).append(" if ");

      // The fallback message is only used if the first message is not available, but the fallback
      // is. So availability of both messages must be tested.
      long firstId = MsgUtils.computeMsgIdForDualFormat(node.getMsg());
      long secondId = MsgUtils.computeMsgIdForDualFormat(node.getFallbackMsg());
      pyExprTextSb
          .append(SwiftExprUtils.TRANSLATOR_NAME)
          .append(".is_msg_available(")
          .append(firstId)
          .append(")")
          .append(" or not ")
          .append(SwiftExprUtils.TRANSLATOR_NAME)
          .append(".is_msg_available(")
          .append(secondId)
          .append(")");

      pyExprTextSb.append(" else ").append(fallbackMsg.getText());
      msg =
          new SwiftStringExpr(
              pyExprTextSb.toString(), SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL));
    }

    // Escaping directives apply to messages, especially in attribute context.
    for (SoyPrintDirective directive : node.getEscapingDirectives()) {
      Preconditions.checkState(
          directive instanceof SoySwiftSrcPrintDirective,
          "Contextual autoescaping produced a bogus directive: %s",
          directive.getName());
      msg = ((SoySwiftSrcPrintDirective) directive).applyForSwiftSrc(msg, ImmutableList.<SwiftExpr>of());
    }
    swiftExprs.add(msg);
  }

  private SwiftStringExpr generateMsgFunc(MsgNode msg) {
    return new MsgFuncGenerator(genSwiftExprsVisitorFactory, msg, localVarExprs, errorReporter)
        .getSwiftExpr();
  }

  /**
   * If all the children are computable as expressions, the IfNode can be written as a ternary
   * conditional expression.
   */
  @Override
  protected void visitIfNode(IfNode node) {
    // Create another instance of this visitor for generating Python expressions from children.
    GenSwiftExprsVisitor genSwiftExprsVisitor =
        genSwiftExprsVisitorFactory.create(localVarExprs, errorReporter);
    TranslateToSwiftExprVisitor translator =
        new TranslateToSwiftExprVisitor(localVarExprs, errorReporter, ConditionalEvaluationMode.CONDITIONAL);

    StringBuilder swiftExprTextSb = new StringBuilder();

    boolean hasElse = false;
    for (SoyNode child : node.getChildren()) {

      if (child instanceof IfCondNode) {
        IfCondNode icn = (IfCondNode) child;

        // Python ternary conditional expressions modify the order of the conditional from
        // <conditional> ? <true> : <false> to
        // <true> if <conditional> else <false>
        SwiftExpr condBlock = SwiftExprUtils.concatSwiftExprs(genSwiftExprsVisitor.exec(icn)).toSwiftString();
        SwiftExpr condPyExpr = translator.exec(icn.getExpr());

//            SwiftExprUtils.maybeProtect(
//                condBlock, SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL));
        // swiftExprTextSb.append(condBlock.getText());

        swiftExprTextSb.append("(" + condPyExpr.getText() + ") ? ");
        
        // Append the conditional and if/else syntax.
        // swiftExprTextSb.append(" if ").append(condPyExpr.getText()).append(" else ");
        swiftExprTextSb.append(condBlock.getText());

      } else if (child instanceof IfElseNode) {
        hasElse = true;
        IfElseNode ien = (IfElseNode) child;

        swiftExprTextSb.append(" : ");
        
        SwiftExpr elseBlock = SwiftExprUtils.concatSwiftExprs(genSwiftExprsVisitor.exec(ien)).toSwiftString();
        swiftExprTextSb.append(elseBlock.getText());
      } else {
        throw new AssertionError("Unexpected if child node type. Child: " + child);
      }
    }

    // By their nature, inline'd conditionals can only contain output strings, so they can be
    // treated as a string type with a conditional precedence.
    swiftExprs.add(
        new SwiftStringExpr(
            swiftExprTextSb.toString(), SwiftExprUtils.swiftPrecedenceForOperator(Operator.CONDITIONAL)));
  }

  @Override
  protected void visitIfCondNode(IfCondNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitIfElseNode(IfElseNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitCallNode(CallNode node) {
    swiftExprs.add(genSwiftCallExprVisitor.exec(node, localVarExprs, errorReporter));
  }

  @Override
  protected void visitCallParamContentNode(CallParamContentNode node) {
    visitChildren(node);
  }
}
