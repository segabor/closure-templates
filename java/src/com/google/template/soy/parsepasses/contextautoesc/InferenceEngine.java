/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.parsepasses.contextautoesc;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.parsepasses.contextautoesc.Context.UriPart;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.AutoescapeMode;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.EscapingMode;
import com.google.template.soy.soytree.ForIfemptyNode;
import com.google.template.soy.soytree.ForNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlCommentNode;
import com.google.template.soy.soytree.HtmlContext;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.IfElseNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.CommandNode;
import com.google.template.soy.soytree.SoyNode.Kind;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.RenderUnitNode;
import com.google.template.soy.soytree.SwitchDefaultNode;
import com.google.template.soy.soytree.SwitchNode;
import com.google.template.soy.soytree.TemplateNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Chooses appropriate escaping modes for <code>{print}</code> commands and derives templates as
 * necessary.
 *
 * <p>For each template with {@code autoescape="contextual"}, assume that the template is used to
 * produce an HTML fragment. Start walking the body with the {@link Context context} provided by the
 * caller (typically {@link HtmlContext#HTML_PCDATA}).
 *
 * <ul>
 *   <li>For RawTextNodes, update the context based on the fragment, so seeing "&lt;script&gt;" will
 *       move us into a JavaScript context while "&lt;!--" would move us into an HTML comment
 *       context.
 *   <li>For {@link PrintNode}s, choose an escaping convention appropriate to the current context.
 *   <li>For {@link IfNode}s, {@link SwitchNode}s, and looping constructs, propagate context
 *       separately along each path, and make sure they converge on a consistent context.
 *   <li>For {@link CallBasicNode}s, maybe derive the target based on current context, recursively
 *       propagate contexts through the derived template to compute an end context for the template.
 *       See fixed-point typing below for a discussion of reentrant templates and templates used in
 *       different contexts.
 * </ul>
 *
 */
final class InferenceEngine {
  // States in which it is illegal to recontextualize a template.
  // This is because the autoescaper relies on AST nodes produced by the parser for
  // kind="attributes" and kind="html" templates.  So if we recontextualize a template into an
  // analagous state escaping will fail since the html nodes will not be present.
  private static final ImmutableSet<HtmlContext> ILLEGAL_RECONTEXTUALIZATIONS =
      Sets.immutableEnumSet(
          HtmlContext.HTML_TAG,
          HtmlContext.HTML_TAG_NAME,
          HtmlContext.HTML_PCDATA,
          HtmlContext.HTML_COMMENT,
          HtmlContext.HTML_ATTRIBUTE_NAME,
          HtmlContext.HTML_BEFORE_OPEN_TAG_NAME,
          HtmlContext.HTML_BEFORE_CLOSE_TAG_NAME);

  /**
   * Infer an end context for the given template and, if requested, choose escaping directives for
   * any <code>{print}</code>.
   *
   * @param templateNode A template that is visited in {@code startContext} and no other. If a
   *     template can be reached from multiple contexts, then it should be cloned. This class
   *     automatically does that for called templates.
   * @param inferences Receives all suggested changes and inferences to tn.
   * @return The end context when the given template is reached from {@code startContext}.
   */
  public static Context inferTemplateEndContext(
      TemplateNode templateNode,
      Context startContext,
      Inferences inferences,
      ErrorReporter errorReporter) {
    Context endContext;
    AutoescapeMode autoescapeMode = templateNode.getAutoescapeMode();
    InferenceEngine inferenceEngine =
        new InferenceEngine(autoescapeMode, autoescapeMode, inferences, errorReporter);
    // Context started off as startContext and we have propagated context through all of
    // template's children, so now context is the template's end context.
    endContext = inferenceEngine.infer(templateNode, startContext);
    inferences.recordTemplateEndContext(templateNode.getTemplateName(), endContext);
    return endContext;
  }

  /**
   * Checks that the end context of a strict block is compatible with its start context.
   *
   * @throws SoyAutoescapeException if they mismatch.
   */
  private static void checkStrictBlockEndContext(RenderUnitNode node, Context endContext) {
    if (!endContext.isValidEndContextForContentKind(node.getContentKind())) {
      String msg =
          String.format(
              "A strict block of kind=\"%s\" cannot end in context %s. Likely cause is %s.",
              node.getContentKind().asAttributeValue(),
              endContext,
              endContext.getLikelyEndContextMismatchCause(node.getContentKind()));
      throw SoyAutoescapeException.createWithNode(msg, node);
    }
  }

  /**
   * Applies strict contextual autoescaping to the given node's children.
   *
   * <p>The start context is the given node's declared {@link ContentKind}, and it is enforced that
   * the block's inferred end context matches the start context.
   *
   * <p>This method is used to visit the content of {let} and {param} nodes with a {@code kind}
   * attribute.
   */
  static void inferStrictRenderUnitNode(
      AutoescapeMode templateAutoescapeMode,
      RenderUnitNode node,
      Inferences inferences,
      ErrorReporter errorReporter) {
    InferenceEngine inferenceEngine =
        new InferenceEngine(
            AutoescapeMode.STRICT,
            templateAutoescapeMode,
            inferences,
            errorReporter);
    // Context started off as startContext and we have propagated context through all of
    // node's children, so now context is the node's end context.
    Context endContext =
        inferenceEngine.inferChildren(
            node, Context.getStartContextForContentKind(node.getContentKind()));
    // Checking that start and end context is same.
    checkStrictBlockEndContext(node, endContext);
  }

  /** The autoescaping mode in this current context. */
  private final AutoescapeMode autoescapeMode;

  /** The autoescape mode of the surrounding {template}. */
  private final AutoescapeMode templateAutoescapeMode;

  /** Receives modifications and typing inferences. */
  private final Inferences inferences;

  /** The escaping mode to assume when none is specified. */
  private final EscapingMode defaultEscapingMode;

  /** For reporting errors. */
  private final ErrorReporter errorReporter;

  private InferenceEngine(
      AutoescapeMode autoescapeMode,
      AutoescapeMode templateAutoescapeMode,
      Inferences inferences,
      ErrorReporter errorReporter) {
    this.autoescapeMode = autoescapeMode;
    this.templateAutoescapeMode = templateAutoescapeMode;
    this.inferences = inferences;
    this.defaultEscapingMode = EscapingMode.ESCAPE_HTML;
    this.errorReporter = errorReporter;
  }

  private Context infer(SoyNode node, Context context) {
    return new ContextPropagatingVisitor(context).exec(node);
  }

  private Context inferChildren(SoyNode node, Context context) {
    ContextPropagatingVisitor contextPropagatingVisitor = new ContextPropagatingVisitor(context);
    return contextPropagatingVisitor.execChildren(node);
  }

  /**
   * A visitor that propagates context across a Soy AST to determine its end context. The end
   * context of an AST is the one that would be reached by applying the {@link
   * RawTextContextUpdater}'s HTML/CSS/JS grammar to any output of the template (where print
   * commands produce innocuous strings). An innocuous string is one that is non-empty and that
   * contains no special characters in HTML/CSS/JS. The string 'z' is a good example of an innocuous
   * string.
   */
  private final class ContextPropagatingVisitor extends AbstractSoyNodeVisitor<Context> {

    private Context context;

    private RawTextNode uriStart = null;

    public ContextPropagatingVisitor(Context context) {
      this.context = context;
    }

    @Override
    public Context exec(SoyNode node) {
      visit(node);
      return context;
    }

    /** Like {@link #exec(SoyNode)}, but only visits the current node's children, if any. */
    public Context execChildren(SoyNode node) {
      if (node instanceof ParentSoyNode<?>) {
        visitChildren((ParentSoyNode<?>) node);
      }
      return context;
    }

    @Override
    protected void visitTemplateNode(TemplateNode templateNode) {
      Preconditions.checkState(
          templateNode.getAutoescapeMode() == autoescapeMode,
          "Same ContextPropagatingVisitor cannot be reused for multiple escaping modes.");
      if (autoescapeMode == AutoescapeMode.STRICT) {
        Preconditions.checkState(
            context.isValidStartContextForContentKind(templateNode.getContentKind()),
            "Strict templates may only be visited in the context for their declared content kind.");
        // Normalize to the canonical context, even if we started in a similar but allowable
        // context (e.g.  single versus double quotes).
        context = Context.getStartContextForContentKind(templateNode.getContentKind());
      }
      visitChildren(templateNode);
      if (autoescapeMode == AutoescapeMode.STRICT) {
        checkStrictBlockEndContext(templateNode, context);
      }
    }

    /** Propagates context across raw chunks of HTML text. */
    @Override
    protected void visitRawTextNode(RawTextNode rawTextNode) {
      context = RawTextContextUpdater.processRawText(rawTextNode, context);
      if (context.uriPart == UriPart.TRUSTED_RESOURCE_URI_END) {
        uriStart = rawTextNode;
      }
    }

    @Override
    protected void visitMsgFallbackGroupNode(MsgFallbackGroupNode node) {
      checkUriEnd();

      if (autoescapeMode == AutoescapeMode.STRICT || autoescapeMode == AutoescapeMode.CONTEXTUAL) {
        // (1) Determine the escaping we should do on the node itself, and the context we should
        // parse the children in.
        Optional<Context.MsgEscapingStrategy> maybeStrategy = context.getMsgEscapingStrategy(node);
        if (!maybeStrategy.isPresent()) {
          throw SoyAutoescapeException.createWithNode(
              "Messages are not supported in this context, because it would mean asking "
                  + "translators to write source code; if this is desired, try factoring the "
                  + "message into a {let} block: "
                  + context,
              node);
        }
        Context.MsgEscapingStrategy strategy = maybeStrategy.get();
        inferences.setEscapingDirectives(node, context, strategy.escapingModesForFullMessage);

        // (2) Run the inference engine on the parts of the message in that context.
        Context msgEndContext =
            new InferenceEngine(
                    autoescapeMode,
                    templateAutoescapeMode,
                    inferences,
                    errorReporter)
                .inferChildren(node, strategy.childContext);

        // (3) Make sure the message didn't itself change context.
        if (!msgEndContext.equals(strategy.childContext)) {
          throw SoyAutoescapeException.createWithNode(
              "Message text should not alter the escaping context. "
                  + context
                  + " != "
                  + strategy.childContext,
              node);
        }
      } else {
        // In a non-contextual mode, we just descend into the children.
        visitChildren(node);
      }
    }

    /**
     * {@link DerivedTemplateUtils Derive} a template from the given call's target if necessary, and
     * figure out the template's end context.
     */
    @Override
    protected void visitCallNode(CallNode callNode) {
      checkUriEnd();

      callNode.setHtmlContext(context.state);

      String calleeName;
      if (callNode instanceof CallBasicNode) {
        calleeName = ((CallBasicNode) callNode).getCalleeName();
      } else {
        calleeName = ((CallDelegateNode) callNode).getDelCalleeName();
      }

      DerivedNameAndContext derivedNameAndContext =
          inferCallSite(callNode, context, calleeName, inferences);
      String derivedCalleeName = derivedNameAndContext.derivedName();
      if (!calleeName.equals(derivedCalleeName)) {
        inferences.retargetCall(callNode, derivedCalleeName);
      }
      context = derivedNameAndContext.context();

      visitChildren(callNode);
    }

    @Override
    protected void visitCallParamContentNode(CallParamContentNode node) {
      visitRenderUnitNode(node);
    }

    @Override
    protected void visitLetContentNode(LetContentNode node) {
      visitRenderUnitNode(node);
    }

    private void visitRenderUnitNode(RenderUnitNode node) {
      switch (autoescapeMode) {
        case CONTEXTUAL:
          // if there is a kind and we are contextual, respect it, otherwise, html it is!
          if (node.getContentKind() == null) {
            inferInContextualModeForHtml(node);
          } else {
            inferInStrictMode(node);
          }
          break;
        case STRICT:
          // The CheckEscapingSanityVisitor ensures that node.getContentKind is non-null
          inferInStrictMode(node);
          break;
        case NONCONTEXTUAL:
          // do nothing.  If the let content nodes with a {@code kind} attribute is in
          // non-contextual template it is handled by another visitor:
          // ContextualAutoescaper.NonContextualTypedRenderUnitNodesVisitor called from
          // ContextualAutoescaper.
          break;
      }
    }

    @Override
    protected void visitIfNode(IfNode ifNode) {
      propagateAcrossDisjunction(ifNode);
    }

    @Override
    protected void visitSwitchNode(SwitchNode switchNode) {
      propagateAcrossDisjunction(switchNode);
    }

    /**
     * Do multiple inferences so we can make sure we get to a consistent context regardless of how
     * many times the loop is entered.
     */
    @Override
    protected void visitForNode(ForNode forNode) {
      List<BlockNode> foreachChildren = forNode.getChildren();
      ForNonemptyNode neNode = (ForNonemptyNode) foreachChildren.get(0);
      ForIfemptyNode ieNode;
      if (foreachChildren.size() == 2) {
        ieNode = (ForIfemptyNode) foreachChildren.get(1);
      } else if (foreachChildren.size() == 1) {
        ieNode = null;
      } else {
        throw new AssertionError();
      }
      Context afterBody = context;
      if (neNode != null) {
        afterBody = infer(neNode, context);
        // Make sure that repeated invocations of the body end up in the same state.
        Context elseContext = infer(neNode, afterBody);
        Optional<Context> combined = Context.union(elseContext, afterBody);
        if (!combined.isPresent()) {
          throw SoyAutoescapeException.createWithNode(
              "{"
                  + forNode.getCommandName()
                  + "} body does not end in the same context after repeated entries.",
              forNode);
        }
        afterBody = combined.get();
      }
      Context ifemptyContext;
      if (ieNode != null) {
        ifemptyContext = infer(ieNode, context);
      } else {
        ifemptyContext = context;
      }
      Optional<Context> combined = Context.union(ifemptyContext, afterBody);
      if (!combined.isPresent()) {
        throw SoyAutoescapeException.createWithNode(
            "{"
                + forNode.getCommandName()
                + "} body "
                + (ieNode == null
                    ? "changes context."
                    : "does not end in the same context as {ifempty}."),
            ieNode == null ? forNode : ieNode);
      }
      context = combined.get();
    }

    /**
     * Pick an escaping mode for the print node if this is in an {@code autoescape="contextual"}
     * template.
     */
    @Override
    protected void visitPrintNode(PrintNode printNode) {
      printNode.setHtmlContext(context.state);
      // It is an error to use autoescape-canceling print directives in strict mode unless in a
      // block of kind text.
      if (autoescapeMode == AutoescapeMode.STRICT && context.state != HtmlContext.TEXT) {
        for (PrintDirectiveNode printDirective : printNode.getChildren()) {
          if (printDirective.getName().equals("|noAutoescape")) {
            // Treat noAutoescape specially:
            // - It is allowed in strict sub-contexts if the surrounding template is non-strict,
            // to help with migration. This does not apply to other escaping directives since
            // they are just as dangerous, but less obvious to auditors.
            // - It deserves a more useful error message.
            if (templateAutoescapeMode == AutoescapeMode.STRICT) {
              // Help the user figure out the best content kind to use, using existing heuristics.
              SanitizedContentKind recommendedKind = context.getMostAppropriateContentKind();
              String recommendedKindStr =
                  (recommendedKind == SanitizedContentKind.TEXT)
                      ? "appropriate kind=\"...\""
                      : ("kind=\"" + recommendedKind.asAttributeValue() + "\"");
              throw SoyAutoescapeException.createWithNode(
                  "noAutoescape is not allowed in strict autoescaping mode. Instead, pass in a "
                      + "{param} with "
                      + recommendedKindStr
                      + " or SanitizedContent.",
                  printNode);
            }
          } else if (printDirective.getPrintDirective() != null
              && printDirective.getPrintDirective().shouldCancelAutoescape()) {
            throw SoyAutoescapeException.createWithNode(
                "Autoescape-cancelling print directives like "
                    + printDirective.getName()
                    + " are only allowed in kind=\"text\" blocks. If you really want to "
                    + "over-escape, try using a let block: "
                    + "{let $foo kind=\"text\"}"
                    + printNode.toSourceString()
                    + "{/let}{$foo}.",
                printNode);
          }
        }
      }

      checkUriEnd();

      List<EscapingMode> escapingModes = inferences.getEscapingMode(printNode);
      Context prev = context;
      if (escapingModes.isEmpty()) { // None specified.
        // The inferences set below specify which nodes to change. In the non-contextual modes,
        // we leave escapingModesToSet null since no changes are to be made to this print node.
        List<EscapingMode> escapingModesToSet = null;
        switch (autoescapeMode) {
          case STRICT:
          case CONTEXTUAL:
            // Infer one.
            escapingModes =
                escapingModesToSet = context.getEscapingModes(printNode, printNode.getChildren());
            break;
          case NONCONTEXTUAL:
            escapingModes = ImmutableList.of(defaultEscapingMode);
            break;
        }
        inferences.setEscapingDirectives(printNode, prev, escapingModesToSet);
      } else if (!context.isCompatibleWith(escapingModes.get(0))) {
        String msg =
            String.format("Escaping modes %s not compatible with %s.", escapingModes, context);
        throw SoyAutoescapeException.createWithNode(msg, printNode);
      }

      // Figure out the context at the end.
      if (!escapingModes.isEmpty()
          || autoescapeMode == AutoescapeMode.CONTEXTUAL
          || autoescapeMode == AutoescapeMode.STRICT) {
        // If we know the escaping mode or we're supposed to choose one, then use that.
        context = context.getContextAfterDynamicValue();
      } else {
        // If we are not in an autoescaping template, assume that the author knows what they're
        // doing and simulate an innocuous value.
        context =
            RawTextContextUpdater.processRawText(
                new RawTextNode(-1, "z", printNode.getSourceLocation()), context);
      }
    }

    private void checkUriEnd() {
      if (context.uriPart == UriPart.TRUSTED_RESOURCE_URI_END) {
        throw SoyAutoescapeException.createWithNode(
            "TrustedResourceUris containing dynamic content must have a fixed scheme (https) and "
                + "host using one of the following formats:\n"
                + "  * https://foo/\n" // NOTYPO
                + "  * //foo/\n"
                + "  * /foo\n"
                + "or move the calculation of this URL outside of the template and use an "
                + "ordaining API.",
            // We switch to UriPart.TRUSTED_RESOURCE_URI_END in RawTextNode where we also store
            // uriStart.
            uriStart);
      }
    }

    @Override
    protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
      visitHtmlTagNode(node);
    }

    @Override
    protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
      visitHtmlTagNode(node);
    }

    @Override
    protected void visitHtmlCommentNode(HtmlCommentNode node) {
      context = context.transitionToState(HtmlContext.HTML_COMMENT);
      visitChildren(node);
      context = context.transitionToState(HtmlContext.HTML_PCDATA);
    }

    private void visitHtmlTagNode(HtmlTagNode tag) {
      context =
          context.transitionToState(
              tag.getKind() == Kind.HTML_OPEN_TAG_NODE
                  ? HtmlContext.HTML_BEFORE_OPEN_TAG_NAME
                  : HtmlContext.HTML_BEFORE_CLOSE_TAG_NAME);
      // if the tag name is a constant, transition to an appropriate tag state
      if (tag.getTagName().isStatic()) {
        context = context.transitionToTagName(tag);
      } else {
        // dynamic tag name
        visit(tag.getChild(0));
      }
      // Make sure the element type was pre-determined when setting the tag name.
      Preconditions.checkArgument(context.elType != Context.ElementType.NONE);
      context = context.transitionToTagBody();
      // 0 is the tag name
      for (int i = 1; i < tag.numChildren(); i++) {
        visit(tag.getChild(i));
      }
      context = context.transitionToAfterTag();
    }

    @Override
    protected void visitHtmlAttributeNode(HtmlAttributeNode node) {
      SoyNode first = node.getChild(0);
      if (first.getKind() == SoyNode.Kind.RAW_TEXT_NODE) {
        context = context.transitionToAttrName(((RawTextNode) first).getRawText());
      } else {
        visit(first);
      }
      if (node.hasValue()) {
        visit(node.getChild(1));
      }
      context = context.transitionToTagBody();
    }

    @Override
    protected void visitHtmlAttributeValueNode(HtmlAttributeValueNode node) {
      Context.AttributeEndDelimiter delim;
      switch (node.getQuotes()) {
        case DOUBLE:
          delim = Context.AttributeEndDelimiter.DOUBLE_QUOTE;
          break;
        case NONE:
          delim = Context.AttributeEndDelimiter.SPACE_OR_TAG_END;
          break;
        case SINGLE:
          delim = Context.AttributeEndDelimiter.SINGLE_QUOTE;
          break;
        default:
          throw new AssertionError();
      }
      context = context.transitionToAttrValue(delim);
      visitChildren(node);
      context = context.transitionToTagBody();
    }

    /** Handle conjunction nodes. */
    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode<?>) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }

    //
    // Helper methods.

    /**
     * Determines the content kind of the templates.
     *
     * <p>This relies on CheckDelegatesPass to print friendly messages if the deltemplates differ in
     * content kind.
     */
    private SanitizedContentKind getCommonContentKindIfStrict(List<TemplateNode> templates) {
      if (templates.isEmpty()) {
        return null;
      }
      SanitizedContentKind contentKind = templates.get(0).getContentKind();
      for (TemplateNode template : templates) {
        Preconditions.checkArgument(template.getContentKind() == contentKind);
      }
      return contentKind;
    }

    /**
     * Derives a template if necessary to compute a consistent end context for a call to the named
     * template.
     *
     * @param callNode The call node.
     * @param startContext The context before the call.
     * @param templateName The name of the template being called.
     * @param inferences Contains a mapping of templates visible to the call site, prior typing
     *     decisions, and derived templates. Will receive any templates successfully derived as a
     *     side-effect of this call.
     * @return The name of the template to call (possibly derived from templateName) and the context
     *     after the call ends.
     */
    private DerivedNameAndContext inferCallSite(
        CallNode callNode, Context startContext, String templateName, Inferences inferences) {
      inferences.recordTemplateChecked(templateName);
      List<TemplateNode> targets = inferences.lookupTemplates(templateName);
      SanitizedContentKind calleeStrictContentKind = getCommonContentKindIfStrict(targets);

      if (autoescapeMode == AutoescapeMode.STRICT) {
        // We're currently in a strict mode template. Check what kind of template is being called.
        if (calleeStrictContentKind != null
            && startContext.isValidStartContextForContentKind(calleeStrictContentKind)) {
          // As an optimization, don't escape the call site if the callee has the right content
          // kind. Since all deltemplates with the same name must be of the same kind (checked
          // elsewhere), we can make this optimization even if we can't see all the deltemplates.
          return DerivedNameAndContext.create(
              templateName, startContext.getContextAfterDynamicValue());
        } else if (calleeStrictContentKind != null || targets.isEmpty()) {
          // If a strict template calls another strict template (or an unknown extern), the result
          // will be escaped, so the call statement behaves effectively like a print statement.
          // No re-contextualization of the callee is done.
          // TODO(gboyer): Throw an exception if the list of escaping modes is empty, which
          // indicates that there's no valid escaper for this context. My plan is to actually have
          // getEscapingModes() itself throw the exception, but this requires some weeding out of
          // bad existing templates.
          inferences.setEscapingDirectives(
              callNode,
              startContext,
              startContext.getEscapingModes(callNode, ImmutableList.<PrintDirectiveNode>of()));
          return DerivedNameAndContext.create(
              templateName, startContext.getContextAfterDynamicValue());
        } else if (startContext.state == HtmlContext.TEXT) {
          // Contextualize the callee in TEXT mode. It's okay to call any template from TEXT mode
          // since TEXT doesn't make any safety guarantees.
          return contextualizeCallee(callNode, startContext, templateName, inferences);
        } else {
          // TODO: We could easily allow this in a future release. We can contextualize the callee
          // and re-escape its output. There are two options. TEXT is nicer because there's no
          // re-escaping in most cases. Markup won't be preserved, but at least there will be zero
          // double-escaping. HTML is more consistent because externs behave the same as interns.
          throw SoyAutoescapeException.createWithNode(
              "Soy strict autoescaping currently forbids calls to non-strict templates, unless "
                  + "the context is kind=\"text\", since there's no guarantee the callee is safe.",
              callNode);
        }

      } else {
        // In a non-strict mode template.
        if (targets.isEmpty()) {
          // External template not visible to compiler -- let's pray for the best! We might end up
          // calling a Javascript-escaping template from HTML or vice versa.
          return DerivedNameAndContext.create(templateName, startContext);
        } else if (calleeStrictContentKind != null) {
          // Non-strict templates may call strict templates, but only if the context is a match.
          // NOTE: While contextual templates *might* do escaping like strict in this context, it
          // would silently break if the template is compiled as an extern. By having this check,
          // teams can do a single monolithic compilation for error checking to prevent this.
          // We're a little loose in this check to allow calling URI templates within URI
          // attributes, even though it's not technically valid HTML, in order to help migration.
          if (!startContext.isValidStartContextForContentKindLoose(calleeStrictContentKind)) {
            String msg =
                String.format(
                    "Cannot call strictly autoescaped template %s of kind=\"%s\" from "
                        + "incompatible context %s. Strict templates generate extra code to safely "
                        + "call templates of other content kinds, but non-strict templates do not.",
                    templateName, calleeStrictContentKind.asAttributeValue(), startContext);
            throw SoyAutoescapeException.createWithNode(msg, callNode);
          }
          return DerivedNameAndContext.create(templateName, startContext);
        } else {
          // Normal contextual-to-contextual propagation.
          return contextualizeCallee(callNode, startContext, templateName, inferences);
        }
      }
    }

    /**
     * Creates a contextual derivative of the specified template and infers the end context.
     *
     * @param callNode The call site.
     * @param startContext The known context to start at.
     * @param calleeName The non-contextualized callee name.
     * @param inferences The inferences to write to.
     * @return A pairing of the new derived name and the end context.
     */
    private DerivedNameAndContext contextualizeCallee(
        CallNode callNode, Context startContext, String calleeName, Inferences inferences) {
      // Propgate the context into the callee contextual template.
      String suffix = DerivedTemplateUtils.getSuffix(startContext);
      String baseName = DerivedTemplateUtils.getBaseName(calleeName);
      // The derived template name.
      String newCalleeName = baseName + suffix;

      // Clone the templates for this new context if needed.
      if (inferences.lookupTemplates(newCalleeName).isEmpty()) {
        if (ILLEGAL_RECONTEXTUALIZATIONS.contains(startContext.state)) {
          throw SoyAutoescapeException.createWithNode(
              "Attempting to call non-strict template '"
                  + baseName
                  + "' in context '"
                  + startContext.state
                  + "'.  This is no longer allowed, please migrate the callee to strict and "
                  + "specify a content kind by adding a kind attribute to the callee",
              callNode);
        }
        inferences.cloneTemplates(baseName, newCalleeName, callNode);
      }

      try {
        Context endContext = determineContextualization(startContext, newCalleeName, inferences);
        return DerivedNameAndContext.create(newCalleeName, endContext);
      } catch (SoyAutoescapeException e) {
        throw SoyAutoescapeException.createCausedWithNode(
            "Error while re-contextualizing template "
                + calleeName
                + " in context "
                + startContext
                + ":",
            e,
            callNode);
      }
    }

    /**
     * Determines the end context and a set of inferences for a template in a particular context.
     *
     * <p>This does not create new cloned templates, but just computes contextualization on existing
     * ones.
     *
     * @param startContext The start context we're calling these templates in.
     * @param calleeName The callee's name, already modified for context.
     * @param inferences The inferences to modify.
     */
    private Context determineContextualization(
        Context startContext, String calleeName, Inferences inferences) {
      Context endContext = inferences.getTemplateEndContext(calleeName);
      if (endContext != null) {
        // We've already computed this; return early.
        return endContext;
      }

      List<TemplateNode> templateNodes = inferences.lookupTemplates(calleeName);
      // Optimistically assume the new callee ends with the same context as it starts, and then
      // verify that's the case.
      InferencesAndContext hypothesis =
          hypothesizeContextualization(
              startContext, startContext, calleeName, templateNodes, inferences);
      endContext = hypothesis.context();
      Inferences subInferences = hypothesis.inferences();
      if (!endContext.equals(startContext) && subInferences.wasTemplateChecked(calleeName)) {
        // Try assuming endContext as the endContext and see if that is a fixed point. If so, it
        // is a valid endContext context since its output is the same regardless of whether
        // recursive calls are properly typed. This allows us to gloss over minor differences in
        // startContexts, e.g. JsFollowingSlash.
        InferencesAndContext secondHypothesis =
            hypothesizeContextualization(
                startContext, endContext, calleeName, templateNodes, inferences);
        Optional<Context> combined = Context.union(secondHypothesis.context(), endContext);
        // See if the first and second hypothesis result in a compatible end context.
        if (!combined.isPresent()) {
          // Cannot identify an end context. Bail.
          throw SoyAutoescapeException.createWithNode(
              "Cannot determine end context for recursive template " + calleeName,
              templateNodes.get(0));
        }
        endContext = combined.get();
      }
      subInferences.recordTemplateEndContext(calleeName, endContext);
      subInferences.foldIntoParent();
      return endContext;
    }

    /**
     * Hypothesizes a particular end context and determines a potential end context, if any.
     *
     * <p>This returns the *actual* end context determined from this hypothesis. Hypotheses are
     * needed to handle recursive templates, where the output context is needed to compute the
     * context within the template.
     *
     * @param startContext The known context to start at.
     * @param hypotheticalEndContext The end context to test.
     * @param calleeName Name of the callee.
     * @param templateNodes The templates and deltemplates of the same name.
     * @param parentInferences The inferences to work from.
     * @return A combination of the end context determined and the inferences that go along with
     *     them.
     */
    private InferencesAndContext hypothesizeContextualization(
        Context startContext,
        Context hypotheticalEndContext,
        String calleeName,
        List<TemplateNode> templateNodes,
        Inferences parentInferences) {
      // Create a hypothetical world of inferences based on this hypothesis. It is up to the caller
      // to fold these into the parent inferences if it chooses to use these.
      Inferences inferences = new Inferences(parentInferences);
      List<Context> endContexts = new ArrayList<Context>();
      inferences.recordTemplateEndContext(calleeName, hypotheticalEndContext);
      for (TemplateNode templateNode : templateNodes) {
        endContexts.add(
            inferTemplateEndContext(
                templateNode,
                startContext,
                inferences,
                errorReporter));
      }
      Optional<Context> combined = Context.union(endContexts);
      if (!combined.isPresent()) {
        throw SoyAutoescapeException.createWithNode(
            "Deltemplates diverge when used with deprecated-contextual autoescaping."
                + " Based on the call site, assuming these templates all start in "
                + startContext
                + ", the different deltemplates end in incompatible contexts: "
                + Joiner.on(", ").join(endContexts),
            templateNodes.get(0));
      }
      return InferencesAndContext.create(inferences, combined.get());
    }

    /** Consider the various branches separately and compute a union context for each branch. */
    private void propagateAcrossDisjunction(ParentSoyNode<?> node) {
      // All the branches of an {if} or {switch} should return compatible contexts, so that we can
      // figure out the end context of the branch as a whole.
      Iterator<? extends SoyNode> childIt = node.getChildren().iterator();
      SoyNode firstBranch = childIt.next();
      Context out = infer(firstBranch, context);
      boolean sawElseOrDefault = false;
      while (childIt.hasNext()) {
        SoyNode branch = childIt.next();
        Context brOut = infer(branch, context);
        Optional<Context> combined = Context.union(out, brOut);
        if (!combined.isPresent()) {
          throw SoyAutoescapeException.createWithNode(
              (node instanceof IfNode
                      ? "{if} command branch ends in a different context than preceding branches:"
                      : "{switch} command case ends in a different context than preceding cases:")
                  + " "
                  + branch.toSourceString(),
              branch);
        }
        out = combined.get();
        if (branch instanceof IfElseNode || branch instanceof SwitchDefaultNode) {
          sawElseOrDefault = true;
        }
      }

      // If there is no else or default, then the end context has to be the compatible with the
      // start context.
      if (!sawElseOrDefault) {
        Optional<Context> combined = Context.union(context, out);
        if (!combined.isPresent()) {
          throw SoyAutoescapeException.createWithNode(
              (node instanceof IfNode
                  ? "{if} command without {else} changes context."
                  : "{switch} command without {default} changes context."),
              node);
        }
        out = combined.get();
      }

      context = out;
    }

    private void inferInStrictMode(RenderUnitNode node) {
      inferStrictRenderUnitNode(
          templateAutoescapeMode,
          node,
          inferences,
          errorReporter);
    }

    /** Applies HTML contextual autoescaping on a legacy contextual parameter block. */
    private void inferInContextualModeForHtml(CommandNode node) {
      // NOTE: Previously this wouldn't do any contextual analysis, which resulted in subtle bugs
      // such as the contextual autoescaper not seeing typed parameters in nested calls.
      final Context paramContentNodeEndContext =
          new InferenceEngine(
                  AutoescapeMode.CONTEXTUAL,
                  templateAutoescapeMode,
                  inferences,
                  errorReporter)
              .inferChildren(node, Context.HTML_PCDATA);
      if (!paramContentNodeEndContext.equals(Context.HTML_PCDATA)) {
        throw SoyAutoescapeException.createWithNode(
            "Blocks should start and end in HTML context.", node);
      }
    }
  }

  //
  // Static helper methods (cannot be part of inner class).

  @AutoValue
  abstract static class DerivedNameAndContext {
    static DerivedNameAndContext create(String derivedName, Context context) {
      return new AutoValue_InferenceEngine_DerivedNameAndContext(derivedName, context);
    }

    abstract String derivedName();

    abstract Context context();
  }

  @AutoValue
  abstract static class InferencesAndContext {
    static InferencesAndContext create(Inferences inferences, Context context) {
      return new AutoValue_InferenceEngine_InferencesAndContext(inferences, context);
    }

    abstract Inferences inferences();

    abstract Context context();
  }
}
