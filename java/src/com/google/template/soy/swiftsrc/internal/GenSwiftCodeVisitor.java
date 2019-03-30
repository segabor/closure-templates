package com.google.template.soy.swiftsrc.internal;

import java.util.ArrayList;
import java.util.List;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.DebuggerNode;
import com.google.template.soy.soytree.ForIfemptyNode;
import com.google.template.soy.soytree.ForNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfElseNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.LetValueNode;
import com.google.template.soy.soytree.LogNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SwitchCaseNode;
import com.google.template.soy.soytree.SwitchDefaultNode;
import com.google.template.soy.soytree.SwitchNode;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.VeLogNode;
import com.google.template.soy.swiftsrc.SoySwiftSrcOptions;
import com.google.template.soy.swiftsrc.internal.GenSwiftExprsVisitor.GenSwiftExprsVisitorFactory;
import com.google.template.soy.swiftsrc.internal.TranslateToSwiftExprVisitor.ConditionalEvaluationMode;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import com.google.template.soy.swiftsrc.restricted.SwiftFunctionExprBuilder;
import com.google.template.soy.types.SoyType;

public class GenSwiftCodeVisitor extends AbstractSoyNodeVisitor<List<String>> {
  private static final SoyErrorKind NON_NAMESPACED_TEMPLATE =
      SoyErrorKind.of("Called template does not reside in a namespace.");

  /** The options configuration for this run. */
  private final SoySwiftSrcOptions swiftSrcOptions;

  /** The namespace manifest for all current and dependent sources. */
  private final ImmutableMap<String, String> namespaceManifest;

  @VisibleForTesting protected SwiftCodeBuilder swiftCodeBuilder;

  private final IsComputableAsSwiftExprVisitor isComputableAsSwiftExprVisitor;

  @VisibleForTesting final GenSwiftExprsVisitorFactory genSwiftExprsVisitorFactory;

  @VisibleForTesting protected GenSwiftExprsVisitor genSwiftExprsVisitor;

  private final GenSwiftCallExprVisitor genSwiftCallExprVisitor;
  
  private final SwiftValueFactoryImpl pluginValueFactory;

  /** @see LocalVariableStack */
  @VisibleForTesting protected LocalVariableStack localVarExprs;

  GenSwiftCodeVisitor(
      SoySwiftSrcOptions swiftSrcOptions,
      ImmutableMap<String, String> currentManifest,
      IsComputableAsSwiftExprVisitor isComputableAsSwiftExprVisitor,
      GenSwiftExprsVisitorFactory genSwiftExprsVisitorFactory,
      GenSwiftCallExprVisitor genSwiftCallExprVisitor,
      SwiftValueFactoryImpl pluginValueFactory) {
    this.swiftSrcOptions = swiftSrcOptions;
    this.isComputableAsSwiftExprVisitor = isComputableAsSwiftExprVisitor;
    this.genSwiftExprsVisitorFactory = genSwiftExprsVisitorFactory;
    this.genSwiftCallExprVisitor = genSwiftCallExprVisitor;
    this.pluginValueFactory = pluginValueFactory;

    this.namespaceManifest =
        new ImmutableMap.Builder<String, String>()
            .putAll(swiftSrcOptions.getNamespaceManifest())
            .putAll(currentManifest)
            .build();
  }

  public List<String> gen(SoyFileSetNode node, ErrorReporter errorReporter) {
    // All these fields should move into Impl but are currently exposed for tests.
    swiftCodeBuilder = null;
    genSwiftExprsVisitor = null;
    localVarExprs = null;
    return new Impl(errorReporter).exec(node);
  }

  @VisibleForTesting
  void visitForTesting(SoyNode node, ErrorReporter errorReporter) {
    new Impl(errorReporter).exec(node);
  }

  private final class Impl extends AbstractSoyNodeVisitor<List<String>> {
    /** The contents of the generated Python files. */
    private List<String> swiftFilesContents;

    final ErrorReporter errorReporter;

    Impl(ErrorReporter reporter) {
      this.errorReporter = reporter;
    }

    @Override
    public List<String> exec(SoyNode node) {
      swiftFilesContents = new ArrayList<>();
      visit(node);
      return swiftFilesContents;
    }
    /**
     * Visit all the children of a provided node and combine the results into one expression where
     * possible. This will let us avoid some {@code output.append} calls and save a bit of time.
     */
    @Override
    protected void visitChildren(ParentSoyNode<?> node) {
      // If the first child cannot be written as an expression, we need to init the output variable
      // first or face potential scoping issues with the output variable being initialized too late.
      if (node.numChildren() > 0 && !isComputableAsSwiftExprVisitor.exec(node.getChild(0))) {
        swiftCodeBuilder.initOutputVarIfNecessary();
      }

      List<SwiftExpr> childPyExprs = new ArrayList<>();

      for (SoyNode child : node.getChildren()) {
        if (isComputableAsSwiftExprVisitor.exec(child)) {
          childPyExprs.addAll(genSwiftExprsVisitor.exec(child));
        } else {
          // We've reached a child that is not computable as a Python expression.
          // First add the PyExprs from preceding consecutive siblings that are computable as Python
          // expressions (if any).
          if (!childPyExprs.isEmpty()) {
            swiftCodeBuilder.addToOutputVar(childPyExprs);
            childPyExprs.clear();
          }
          // Now append the code for this child.
          visit(child);
        }
      }

      // Add the PyExprs from the last few children (if any).
      if (!childPyExprs.isEmpty()) {
        swiftCodeBuilder.addToOutputVar(childPyExprs);
        childPyExprs.clear();
      }
    }

    // ---------------------------------------------------------------------------------------------
    // Implementations for specific nodes.

    @Override
    protected void visitSoyFileSetNode(SoyFileSetNode node) {
      for (SoyFileNode soyFile : node.getChildren()) {
        visit(soyFile);
      }
    }

    /**
     * Visit a SoyFileNode and generate it's Python output.
     *
     * <p>This visitor generates the necessary imports and configuration needed for all Python
     * output files. This includes imports of runtime libraries, external templates called from
     * within this file, and namespacing configuration.
     *
     * <p>Template generation is deferred to other visitors.
     *
     * <p>Example Output:
     *
     * <pre>
     * # coding=utf-8
     * """ This file was automatically generated from my-templates.soy.
     * Please don't edit this file by hand.
     * """
     *
     * ...
     * </pre>
     */
    @Override
    protected void visitSoyFileNode(SoyFileNode node) {
      swiftCodeBuilder = new SwiftCodeBuilder();

      // Encode all source files in utf-8 to allow for special unicode characters in the generated
      // literals.

      swiftCodeBuilder.appendLine(
          "//This file was automatically generated from ", node.getFileName(), ".");
      swiftCodeBuilder.appendLine("//Please don't edit this file by hand.");
      swiftCodeBuilder.appendLine("");

      // Add code to define Python namespaces and add import calls for libraries.
      swiftCodeBuilder.appendLine();
      addCodeToRequireGeneralDeps();
      //addCodeToRequireSoyNamespaces(node);

      // Add debug support
      /* if (SoyTreeUtils.hasNodesOfType(node, DebuggerNode.class)) {
        swiftCodeBuilder.appendLine("import pdb");
      } */

      swiftCodeBuilder.appendLine("fileprivate let SOY_SOURCE = \"" + node.getFileName() + "\"");
      swiftCodeBuilder.appendLine("fileprivate let SOY_NAMESPACE = \"" + node.getNamespace() + "\"");
      swiftCodeBuilder.appendLine("");

      // Add code for each template.
      for (TemplateNode template : node.getChildren()) {
        swiftCodeBuilder.appendLine().appendLine();
        visit(template);
      }
      
      swiftFilesContents.add(swiftCodeBuilder.getCode());
      swiftCodeBuilder = null;
    }

    /**
     * Visit a TemplateNode and generate a corresponding function.
     *
     * <p>Example:
     *
     * <pre>
     * def myfunc(data, ijData):
     *   output = ''
     *   ...
     *   ...
     *   return output
     * </pre>
     */
    @Override
    protected void visitTemplateNode(TemplateNode node) {
      localVarExprs = new LocalVariableStack();
      genSwiftExprsVisitor = genSwiftExprsVisitorFactory.create(localVarExprs, errorReporter);

      final String swiftFuncName = GenSoyTemplateRendererName.makeFuncName(node);

      // append doc
      if (node.getSoyDoc() != null) {
        swiftCodeBuilder.appendLine(node.getSoyDoc());
      }
      
      swiftCodeBuilder.appendLine(
          node.getVisibility().getAttributeValue() + " ",
          "func ",
          swiftFuncName,
          "(_ data: SoyValue = .map([:]), _ ijData: SoyValue = .map([:])) -> String {");
      swiftCodeBuilder.increaseIndent();

      generatePreconditions(node);
      
      swiftCodeBuilder.appendLine("");
      swiftCodeBuilder.appendLine("");

      generateFunctionBody(node);

      // Dedent to end the function.
      swiftCodeBuilder.decreaseIndent();
      
      swiftCodeBuilder.appendLine("}");
    }

    /**
     * Visit a TemplateDelegateNode and generate the corresponding function along with the delegate
     * registration.
     *
     * <p>Example:
     *
     * <pre>
     * def myfunc(data=None, ijData=None):
     *   ...
     * runtime.register_delegate_fn('delname', 'delvariant', 0, myfunc, 'myfunc')
     * </pre>
     */
    // FIXME
    @Override
    protected void visitTemplateDelegateNode(TemplateDelegateNode node) {
      // Generate the template first, before registering the delegate function.
      visitTemplateNode(node);

      // Register the function as a delegate function.
      String delTemplateIdExprText = "'" + node.getDelTemplateName() + "'";
      String delTemplateVariantExprText = "'" + node.getDelTemplateVariant() + "'";
      swiftCodeBuilder.appendLine(
          "runtime.register_delegate_fn(",
          delTemplateIdExprText,
          ", ",
          delTemplateVariantExprText,
          ", ",
          node.getDelPriority().toString(),
          ", ",
          node.getPartialTemplateName().substring(1),
          ", '",
          node.getPartialTemplateName().substring(1),
          "')");
    }

    @Override
    protected void visitPrintNode(PrintNode node) {
      swiftCodeBuilder.addToOutputVar(genSwiftExprsVisitor.exec(node));
    }

    /**
     * Visit an IfNode and generate a full conditional statement, or an inline ternary conditional
     * expression if all the children are computable as expressions.
     *
     * <p>Example:
     *
     * <pre>
     *   {if $boo > 0}
     *     ...
     *   {/if}
     * </pre>
     *
     * might generate
     *
     * <pre>
     *   if data.get('boo') > 0:
     *     ...
     * </pre>
     */
    @Override
    protected void visitIfNode(IfNode node) {
      if (isComputableAsSwiftExprVisitor.exec(node)) {
        // FIXME: it generates ugly result!
        // This flag has been also turned off: IsComputableAsSwiftExprVisitor#visitIfCondNode
        swiftCodeBuilder.addToOutputVar(genSwiftExprsVisitor.exec(node));
        return;
      }

      // Not computable as Python expressions, so generate full code.
      TranslateToSwiftExprVisitor translator = new TranslateToSwiftExprVisitor(localVarExprs, errorReporter, pluginValueFactory, ConditionalEvaluationMode.CONDITIONAL);
      for (SoyNode child : node.getChildren()) {
        if (child instanceof IfCondNode) {
          IfCondNode icn = (IfCondNode) child;

          translator.flipConditionalMode();
          SwiftExpr condSwiftExpr = translator.exec(icn.getExpr());
          translator.flipConditionalMode();

          if (icn.getCommandName().equals("if")) {
            swiftCodeBuilder.appendLine("if ", condSwiftExpr.getText(), " {");
          } else {
            swiftCodeBuilder.appendLine("} else if ", condSwiftExpr.getText(), " {");
          }

          swiftCodeBuilder.increaseIndent();
          visitChildren(icn);
          swiftCodeBuilder.decreaseIndent();

        } else if (child instanceof IfElseNode) {
          swiftCodeBuilder.appendLine("} else {");
          swiftCodeBuilder.increaseIndent();
          visitChildren((IfElseNode) child);
          swiftCodeBuilder.decreaseIndent();
        } else {
          throw new AssertionError("Unexpected if child node type. Child: " + child);
        }
      }

      swiftCodeBuilder.appendLine("}");
    }

    /**
     * Python does not support switch statements, so just replace with if: ... elif: ... else: ...
     * As some expressions may generate different results each time, the expression is stored before
     * conditionals (which prevents expression inlining).
     *
     * <p>Example:
     *
     * <pre>
     *   {switch $boo}
     *     {case 0}
     *       ...
     *     {case 1, 2}
     *       ...
     *     {default}
     *       ...
     *   {/switch}
     * </pre>
     *
     * might generate
     *
     * <pre>
     *   switchValue = data.get('boo')
     *   if switchValue == 0:
     *     ...
     *   elif switchValue == 1:
     *     ...
     *   elif switchValue == 2:
     *     ...
     *   else:
     *     ...
     * </pre>
     */
    // FIXME
    @Override
    protected void visitSwitchNode(SwitchNode node) {
      // Run the switch value creation first to ensure side effects always occur.
      TranslateToSwiftExprVisitor translator = new TranslateToSwiftExprVisitor(localVarExprs, pluginValueFactory, errorReporter);
      String switchValueVarName = "switchValue";
      SwiftExpr switchValuePyExpr = translator.exec(node.getExpr());
      swiftCodeBuilder.appendLine(switchValueVarName, " = ", switchValuePyExpr.getText());

      // If a Switch with only a default is provided (no case statements), just execute the inner
      // code directly.
      if (node.getChildren().size() == 1 && node.getChild(0) instanceof SwitchDefaultNode) {
        visitChildren(node.getChild(0));
        return;
      }

      boolean isFirstCase = true;
      for (SoyNode child : node.getChildren()) {
        if (child instanceof SwitchCaseNode) {
          SwitchCaseNode scn = (SwitchCaseNode) child;

          for (ExprNode caseExpr : scn.getExprList()) {
            SwiftExpr casePyExpr = translator.exec(caseExpr);
            SwiftExpr conditionFn =
                new SwiftFunctionExprBuilder("runtime.type_safe_eq")
                    .addArg(new SwiftExpr(switchValueVarName, Integer.MAX_VALUE))
                    .addArg(casePyExpr)
                    .asSwiftExpr();

            if (isFirstCase) {
              swiftCodeBuilder
                  .appendLineStart("if ")
                  .append(conditionFn.getText())
                  .appendLineEnd(":");
              isFirstCase = false;
            } else {
              swiftCodeBuilder
                  .appendLineStart("elif ")
                  .append(conditionFn.getText())
                  .appendLineEnd(":");
            }

            swiftCodeBuilder.increaseIndent();
            visitChildren(scn);
            swiftCodeBuilder.decreaseIndent();
          }
        } else if (child instanceof SwitchDefaultNode) {
          SwitchDefaultNode sdn = (SwitchDefaultNode) child;

          swiftCodeBuilder.appendLine("else:");
          swiftCodeBuilder.increaseIndent();
          visitChildren(sdn);
          swiftCodeBuilder.decreaseIndent();
        } else {
          throw new AssertionError("Unexpected switch child node type. Child: " + child);
        }
      }
    }

    /**
     * The top level ForNode primarily serves to test for the ifempty case. If present, the loop is
     * wrapped in an if statement which checks for data in the list before iterating.
     *
     * FIXME
     * 
     * <p>Example:
     *
     * <pre>
     *   {for $foo in $boo}
     *     ...
     *   {ifempty}
     *     ...
     *   {/for}
     * </pre>
     *
     * might generate
     *
     * <pre>
     *   fooList2 = data.get('boo')
     *   if fooList2:
     *     ...loop...
     *   else:
     *     ...
     * </pre>
     */
    @Override
    protected void visitForNode(ForNode node) {
      ForNonemptyNode nonEmptyNode = (ForNonemptyNode) node.getChild(0);
      String baseVarName = nonEmptyNode.getVarName();
      String listVarName = String.format("%sList%d", baseVarName, node.getId());

      // Define list variable
      TranslateToSwiftExprVisitor translator =
          new TranslateToSwiftExprVisitor(localVarExprs, pluginValueFactory, errorReporter);
      
      SwiftExpr dataRefPyExpr = translator.exec(node.getExpr());
      // swiftCodeBuilder.appendLine(listVarName, " = ", dataRefPyExpr.getText());

      // If has 'ifempty' node, add the wrapper 'if' statement.
      boolean hasIfemptyNode = node.numChildren() == 2;

      // HACK - figure out what's being iterated
      // TODO: infer correct Swift type from SoyType
      ForNonemptyNode loopNode = (ForNonemptyNode) node.getChild(0);
      String loopVarTypeSwift = "CustomStringConvertible"; // fall-back
      SoyType loopVarType = loopNode.getVar().type();
      if (loopVarType.getKind().isKnownStringOrSanitizedContent()) {
        // Only string types are supported
        loopVarTypeSwift = "String";
      }
      
      swiftCodeBuilder.appendLine("if let ", listVarName, " = " + dataRefPyExpr.getText() + " as? ["+loopVarTypeSwift+"] {");
      swiftCodeBuilder.increaseIndent();

      // Generate code for nonempty case.
      visit(nonEmptyNode);

      // If has 'ifempty' node, add the 'else' block of the wrapper 'if' statement.
      if (hasIfemptyNode) {
        swiftCodeBuilder.decreaseIndent();
        swiftCodeBuilder.appendLine("} else {");
        swiftCodeBuilder.increaseIndent();

        // Generate code for empty case.
        visit(node.getChild(1));
      }

      swiftCodeBuilder.decreaseIndent();
      swiftCodeBuilder.appendLine("}");
    }

    /**
     * The ForNonemptyNode performs the actual looping. We use a standard {@code for} loop, except
     * that instead of looping directly over the list, we loop over an enumeration to have easy
     * access to the index along with the data.
     *
     * FIXME
     * 
     * <p>Example:
     *
     * <pre>
     *   {for $foo in $boo}
     *     ...
     *   {/for}
     * </pre>
     *
     * might generate
     *
     * <pre>
     *   fooList2 = data.get('boo')
     *   for fooIndex2, fooData2 in enumerate(fooList2):
     *     ...
     * </pre>
     */
    @Override
    protected void visitForNonemptyNode(ForNonemptyNode node) {
      // Build the local variable names.
      String baseVarName = node.getVarName();
      String forNodeId = Integer.toString(node.getForNodeId());
      String listVarName = baseVarName + "List" + forNodeId;
      String indexVarName = baseVarName + "Index" + forNodeId;
      String dataVarName = baseVarName + "Data" + forNodeId;

      // Create the loop with an enumeration.
      swiftCodeBuilder.appendLine(
          "for (", indexVarName, ", ", dataVarName, ") in ", listVarName, ".enumerated() {");
      swiftCodeBuilder.increaseIndent();

      // Add a new localVarExprs frame and populate it with the translations from this loop.
      int eqPrecedence = SwiftExprUtils.swiftPrecedenceForOperator(Operator.EQUAL);
      localVarExprs.pushFrame();
      localVarExprs
          .addVariable(baseVarName, new SwiftExpr(dataVarName, Integer.MAX_VALUE))
          .addVariable(baseVarName + "__isFirst", new SwiftExpr(indexVarName + " == " + listVarName + ".startIndex", eqPrecedence))
          .addVariable(
              baseVarName + "__isLast",
              new SwiftExpr(indexVarName + " == " + listVarName + ".endIndex-1", eqPrecedence))
          .addVariable(baseVarName + "__index", new SwiftExpr(indexVarName, Integer.MAX_VALUE));

      // Generate the code for the loop body.
      visitChildren(node);

      // Remove the localVarExprs frame that we added above.
      localVarExprs.popFrame();

      // The end of the Python 'for' loop.
      swiftCodeBuilder.decreaseIndent();

      // close for loop
      swiftCodeBuilder.appendLine("}");
    }

    @Override
    protected void visitForIfemptyNode(ForIfemptyNode node) {
      visitChildren(node);
    }

    /**
     * Visits a let node which accepts a value and stores it as a unique variable. The unique
     * variable name is stored in the LocalVariableStack for use by any subsequent code.
     *
     * <p>Example:
     *
     * <pre>
     *   {let $boo: $foo[$moo] /}
     * </pre>
     *
     * might generate
     *
     * <pre>
     *   boo3 = data.get('foo')['moo']
     * </pre>
     */
    @Override
    protected void visitLetValueNode(LetValueNode node) {
      String generatedVarName = node.getUniqueVarName();

      // Generate code to define the local var.
      TranslateToSwiftExprVisitor translator = new TranslateToSwiftExprVisitor(localVarExprs, pluginValueFactory, errorReporter);
      SwiftExpr valuePyExpr = translator.exec(node.getExpr());
      swiftCodeBuilder.appendLine("let ", generatedVarName, ": SoyValue = ", valuePyExpr.getText());

      // Add a mapping for generating future references to this local var.
      localVarExprs.addVariable(
          node.getVarName(), new SwiftExpr(generatedVarName, Integer.MAX_VALUE));
    }

    /**
     * Visits a let node which contains a content section and stores it as a unique variable. The
     * unique variable name is stored in the LocalVariableStack for use by any subsequent code.
     *
     * <p>Note, this is one of the location where Strict mode is enforced in Python templates. As
     * such, all LetContentNodes must have a contentKind specified.
     *
     * <p>Example:
     *
     * <pre>
     *   {let $boo kind="html"}
     *     Hello {$name}
     *   {/let}
     * </pre>
     *
     * might generate
     *
     * <pre>
     *   boo3 = sanitize.SanitizedHtml(''.join(['Hello ', sanitize.escape_html(data.get('name'))])
     * </pre>
     */
    // FIXME: type of sanitized content is undecided whether it is SoyValue or StringConvertible
    @Override
    protected void visitLetContentNode(LetContentNode node) {
      String generatedVarName = node.getUniqueVarName();

      // Traverse the children and push them onto the generated variable.
      localVarExprs.pushFrame();
      swiftCodeBuilder.pushOutputVar(generatedVarName);

      visitChildren(node);

      SwiftExpr generatedContent = swiftCodeBuilder.getOutputAsString();
      swiftCodeBuilder.popOutputVar();
      localVarExprs.popFrame();

      // Mark the result as being escaped to the appropriate kind (e.g., "sanitize.SanitizedHtml").
      swiftCodeBuilder.appendLine(
          "let ",
          generatedVarName,
          ": SoyValue = ",
          InternalSwiftExprUtils.wrapAsSanitizedContent(node.getContentKind(), generatedContent)
              .getText());

      // Add a mapping for generating future references to this local var.
      localVarExprs.addVariable(
          node.getVarName(), new SwiftExpr(generatedVarName, Integer.MAX_VALUE));
    }

    /**
     * Visits a call node and generates the syntax needed to call another template. If all of the
     * children can be represented as expressions, this is built as an expression itself. If not,
     * the non-expression params are saved as {@code param<n>} variables before the function call.
     */
    @Override
    protected void visitCallNode(CallNode node) {
      // If this node has any param children whose contents are not computable as Python
      // expressions, visit them to generate code to define their respective 'param<n>' variables.
      for (CallParamNode child : node.getChildren()) {
        if (child instanceof CallParamContentNode && !isComputableAsSwiftExprVisitor.exec(child)) {
          visit(child);
        }
      }

      swiftCodeBuilder.addToOutputVar(
          genSwiftCallExprVisitor.exec(node, localVarExprs, errorReporter).toSwiftString());
    }

    /**
     * Visits a call param content node which isn't computable as a SwiftExpr and stores its content
     * in a variable with the name {@code param<n>} where n is the node's id.
     */
    @Override
    protected void visitCallParamContentNode(CallParamContentNode node) {
      // This node should only be visited when it's not computable as Python expressions.
      Preconditions.checkArgument(
          !isComputableAsSwiftExprVisitor.exec(node),
          "Should only define 'param<n>' when not computable as Python expressions.");

      swiftCodeBuilder.pushOutputVar("param" + node.getId());
      swiftCodeBuilder.initOutputVarIfNecessary();
      visitChildren(node);
      swiftCodeBuilder.popOutputVar();
    }

    // FIXME
    @Override
    protected void visitVeLogNode(VeLogNode node) {
      if (node.getLogonlyExpression() != null) {
        TranslateToSwiftExprVisitor translator = new TranslateToSwiftExprVisitor(localVarExprs, pluginValueFactory, errorReporter);
        SwiftExpr isLogonly = translator.exec(node.getLogonlyExpression());
        swiftCodeBuilder.appendLine("if ", isLogonly.getText(), ":");
        swiftCodeBuilder.increaseIndent();
        swiftCodeBuilder.appendLine(
            "raise Exception('Cannot set logonly=\"true\" unless there is a "
                + "logger configured, but pysrc doesn\\'t support loggers')");
        swiftCodeBuilder.decreaseIndent();
      }
      // TODO(lukes): expand implementation
      visitChildren(node);
    }

    @Override
    protected void visitDebuggerNode(DebuggerNode node) {
      // TODO no debug option is designed yet
    }

    @Override
    protected void visitLogNode(LogNode node) {
      // TODO
    }

    // ---------------------------------------------------------------------------------------------
    // Fallback implementation.

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (isComputableAsSwiftExprVisitor.exec(node)) {
        // Generate Python expressions for this node and add them to the current output var.
        swiftCodeBuilder.addToOutputVar(genSwiftExprsVisitor.exec(node));
      } else {
        // Need to implement visit*Node() for the specific case.
        throw new UnsupportedOperationException();
      }
    }

    // ---------------------------------------------------------------------------------------------
    // Utility methods.

    /** Helper for visitSoyFileNode(SoyFileNode) to add code to require general dependencies. */
    private void addCodeToRequireGeneralDeps() {
      swiftCodeBuilder.appendLine("import Foundation");
      swiftCodeBuilder.appendLine("import SoyKit");
      swiftCodeBuilder.appendLine();
    }

    private void generatePreconditions(TemplateNode node) {
      swiftCodeBuilder.appendLine("guard case let .map(_) = data, case let .map(_) = ijData else {");
      swiftCodeBuilder.increaseIndent();
      swiftCodeBuilder.appendLine("// Input type mismatch detected!");
      swiftCodeBuilder.appendLine("// TODO provide feedback");
      swiftCodeBuilder.appendLine("return \"\"");
      swiftCodeBuilder.decreaseIndent();
      swiftCodeBuilder.appendLine("}");
    }

    /** Helper for visitTemplateNode which generates the function body. */
    private void generateFunctionBody(TemplateNode node) {
      // Add a new frame for local variable translations.
      localVarExprs.pushFrame();

      swiftCodeBuilder.pushOutputVar("output");

      visitChildren(node);

      SwiftExpr resultSwiftExpr = swiftCodeBuilder.getOutputAsString();
      swiftCodeBuilder.popOutputVar();

      // Templates with autoescape="strict" return the SanitizedContent wrapper for its kind:
      // - Call sites are wrapped in an escaper. Returning SanitizedContent prevents re-escaping.
      // - The topmost call into Soy returns a SanitizedContent. This will make it easy to take
      // the result of one template and feed it to another, and also to confidently assign sanitized
      // HTML content to innerHTML. This does not use the internal-blocks variant.
      resultSwiftExpr =
          InternalSwiftExprUtils.wrapAsSanitizedContent(node.getContentKind(), resultSwiftExpr);

      swiftCodeBuilder.appendLine("return ", resultSwiftExpr.getText());

      localVarExprs.popFrame();
    }
  }

  @AutoValue
  abstract static class NamespaceAndName {
    static NamespaceAndName fromModule(String moduleName) {
      String namespace = moduleName;
      String name = moduleName;
      int lastDotIndex = moduleName.lastIndexOf('.');
      if (lastDotIndex != -1) {
        namespace = moduleName.substring(0, lastDotIndex);
        name = moduleName.substring(lastDotIndex + 1);
      }
      return new AutoValue_GenSwiftCodeVisitor_NamespaceAndName(namespace, name);
    }

    abstract String namespace();

    abstract String name();
  }
}
