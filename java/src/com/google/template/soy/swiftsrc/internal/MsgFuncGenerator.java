package com.google.template.soy.swiftsrc.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.msgs.internal.IcuSyntaxUtils;
import com.google.template.soy.msgs.internal.MsgUtils;
import com.google.template.soy.msgs.internal.MsgUtils.MsgPartsAndIds;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPart.Case;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralCaseSpec;
import com.google.template.soy.msgs.restricted.SoyMsgPluralPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.soytree.AbstractParentSoyNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.MsgSelectNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.MsgPlaceholderInitialNode;
import com.google.template.soy.soytree.SoyNode.MsgSubstUnitNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.swiftsrc.internal.GenSwiftExprsVisitor.GenSwiftExprsVisitorFactory;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftExprUtils;
import com.google.template.soy.swiftsrc.restricted.SwiftFunctionExprBuilder;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;

/**
 * Class to generate python code for one {@link MsgNode}.
 *
 */
public final class MsgFuncGenerator {
  /** The msg node to generate the function calls from. */
  private final MsgNode msgNode;

  /** The generated msg id with the same algorithm for translation service. */
  private final long msgId;

  private final ImmutableList<SoyMsgPart> msgParts;

  private final GenSwiftExprsVisitor genSwiftExprsVisitor;

  /** The function builder for the prepare_*() method */
  private final SwiftFunctionExprBuilder prepareFunc;

  /** The function builder for the render_*() method */
  private final SwiftFunctionExprBuilder renderFunc;

  private final TranslateToSwiftExprVisitor translateToSwiftExprVisitor;

  MsgFuncGenerator(
      GenSwiftExprsVisitorFactory genSwiftExprsVisitorFactory,
      MsgNode msgNode,
      LocalVariableStack localVarExprs,
      ErrorReporter errorReporter) {
    this.msgNode = msgNode;
    this.genSwiftExprsVisitor = genSwiftExprsVisitorFactory.create(localVarExprs, errorReporter);
    this.translateToSwiftExprVisitor = new TranslateToSwiftExprVisitor(localVarExprs, errorReporter);
    String translator = SwiftExprUtils.TRANSLATOR_NAME;

    if (this.msgNode.isPlrselMsg()) {
      if (this.msgNode.isPluralMsg()) {
        this.prepareFunc = new SwiftFunctionExprBuilder(translator + ".prepare_plural");
        this.renderFunc = new SwiftFunctionExprBuilder(translator + ".render_plural");
      } else {
        this.prepareFunc = new SwiftFunctionExprBuilder(translator + ".prepare_icu");
        this.renderFunc = new SwiftFunctionExprBuilder(translator + ".render_icu");
      }
    } else if (this.msgNode.isRawTextMsg()) {
      this.prepareFunc = new SwiftFunctionExprBuilder(translator + ".prepare_literal");
      this.renderFunc = new SwiftFunctionExprBuilder(translator + ".render_literal");
    } else {
      this.prepareFunc = new SwiftFunctionExprBuilder(translator + ".prepare");
      this.renderFunc = new SwiftFunctionExprBuilder(translator + ".render");
    }

    MsgPartsAndIds msgPartsAndIds = MsgUtils.buildMsgPartsAndComputeMsgIdForDualFormat(msgNode);
    Preconditions.checkNotNull(msgPartsAndIds);

    this.msgId = msgPartsAndIds.id;
    this.msgParts = msgPartsAndIds.parts;

    Preconditions.checkState(!msgParts.isEmpty());
  }

  /**
   * Return the SwiftStringExpr for the render function call, because we know render always return a
   * string in Python runtime.
   */
  SwiftStringExpr getSwiftExpr() {
    if (this.msgNode.isPlrselMsg()) {
      return this.msgNode.isPluralMsg() ? swiftFuncForPluralMsg() : pyFuncForSelectMsg();
    } else {
      return this.msgNode.isRawTextMsg() ? swiftFuncForRawTextMsg() : swiftFuncForGeneralMsg();
    }
  }

  private SwiftStringExpr swiftFuncForRawTextMsg() {
    String pyMsgText = processMsgPartsHelper(msgParts, escaperForPyFormatString);

    prepareFunc.addArg(msgId).addArg(pyMsgText);
    return renderFunc.addArg(prepareFunc.asSwiftExpr()).asSwiftStringExpr();
  }

  private SwiftStringExpr swiftFuncForGeneralMsg() {
    String pyMsgText = processMsgPartsHelper(msgParts, escaperForPyFormatString);
    Map<SwiftExpr, SwiftExpr> nodePyVarToPyExprMap = collectVarNameListAndToPyExprMap();

    prepareFunc
        .addArg(msgId)
        .addArg(pyMsgText)
        .addArg(SwiftExprUtils.convertIterableToSwfitListExpr(nodePyVarToPyExprMap.keySet()));

    return renderFunc
        .addArg(prepareFunc.asSwiftExpr())
        .addArg(SwiftExprUtils.convertMapToSwiftExpr(nodePyVarToPyExprMap))
        .asSwiftStringExpr();
  }

  private SwiftStringExpr swiftFuncForPluralMsg() {
    SoyMsgPluralPart pluralPart = (SoyMsgPluralPart) msgParts.get(0);
    MsgPluralNode pluralNode = msgNode.getRepPluralNode(pluralPart.getPluralVarName());
    Map<SwiftExpr, SwiftExpr> nodePyVarToPyExprMap = collectVarNameListAndToPyExprMap();
    Map<SwiftExpr, SwiftExpr> caseSpecStrToMsgTexts = new LinkedHashMap<>();

    for (Case<SoyMsgPluralCaseSpec> pluralCase : pluralPart.getCases()) {
      caseSpecStrToMsgTexts.put(
          new SwiftStringExpr("'" + pluralCase.spec() + "'"),
          new SwiftStringExpr("'" + processMsgPartsHelper(pluralCase.parts(), nullEscaper) + "'"));
    }

    prepareFunc
        .addArg(msgId)
        .addArg(SwiftExprUtils.convertMapToSwiftExpr(caseSpecStrToMsgTexts))
        .addArg(SwiftExprUtils.convertIterableToSwfitListExpr(nodePyVarToPyExprMap.keySet()));

    // Translates {@link MsgPluralNode#pluralExpr} into a Python lookup expression.
    // Note that pluralExpr represent the Soy expression inside the attributes of a plural tag.
    SwiftExpr pluralSwiftExpr = translateToSwiftExprVisitor.exec(pluralNode.getExpr());

    return renderFunc
        .addArg(prepareFunc.asSwiftExpr())
        .addArg(pluralSwiftExpr)
        .addArg(SwiftExprUtils.convertMapToSwiftExpr(nodePyVarToPyExprMap))
        .asSwiftStringExpr();
  }

  private SwiftStringExpr pyFuncForSelectMsg() {
    Map<SwiftExpr, SwiftExpr> nodeSwiftVarToPyExprMap = collectVarNameListAndToPyExprMap();

    ImmutableList<SoyMsgPart> msgPartsInIcuSyntax =
        IcuSyntaxUtils.convertMsgPartsToEmbeddedIcuSyntax(msgParts, true);
    String swiftMsgText = processMsgPartsHelper(msgPartsInIcuSyntax, nullEscaper);

    prepareFunc
        .addArg(msgId)
        .addArg(swiftMsgText)
        .addArg(SwiftExprUtils.convertIterableToSwfitListExpr(nodeSwiftVarToPyExprMap.keySet()));

    return renderFunc
        .addArg(prepareFunc.asSwiftExpr())
        .addArg(SwiftExprUtils.convertMapToSwiftExpr(nodeSwiftVarToPyExprMap))
        .asSwiftStringExpr();
  }

  /**
   * Private helper to process and collect all variables used within this msg node for code
   * generation.
   *
   * @return A Map populated with all the variables used with in this message node, using {@link
   *     MsgPlaceholderInitialNode#genBasePhName}.
   */
  private Map<SwiftExpr, SwiftExpr> collectVarNameListAndToPyExprMap() {
    Map<SwiftExpr, SwiftExpr> nodePyVarToPyExprMap = new LinkedHashMap<>();
    for (Map.Entry<String, MsgSubstUnitNode> entry : msgNode.getVarNameToRepNodeMap().entrySet()) {
      MsgSubstUnitNode substUnitNode = entry.getValue();
      SwiftExpr substPyExpr = null;

      if (substUnitNode instanceof MsgPlaceholderNode) {
        SoyNode phInitialNode = ((AbstractParentSoyNode<?>) substUnitNode).getChild(0);

        if (phInitialNode instanceof PrintNode
            || phInitialNode instanceof CallNode
            || phInitialNode instanceof RawTextNode) {
          substPyExpr =
              SwiftExprUtils.concatSwiftExprs(genSwiftExprsVisitor.exec(phInitialNode)).toSwiftString();
        }

        // when the placeholder is generated by HTML tags
        if (phInitialNode instanceof MsgHtmlTagNode) {
          substPyExpr =
              SwiftExprUtils.concatSwiftExprs(
            		  genSwiftExprsVisitor.execOnChildren((ParentSoyNode<?>) phInitialNode))
                  .toSwiftString();
        }
      } else if (substUnitNode instanceof MsgPluralNode) {
        // Translates {@link MsgPluralNode#pluralExpr} into a Python lookup expression.
        // Note that {@code pluralExpr} represents the soy expression of the {@code plural} attr,
        // i.e. the {@code $numDrafts} in {@code {plural $numDrafts}...{/plural}}.
        substPyExpr = translateToSwiftExprVisitor.exec(((MsgPluralNode) substUnitNode).getExpr());
      } else if (substUnitNode instanceof MsgSelectNode) {
        substPyExpr = translateToSwiftExprVisitor.exec(((MsgSelectNode) substUnitNode).getExpr());
      }

      if (substPyExpr != null) {
        nodePyVarToPyExprMap.put(new SwiftStringExpr("'" + entry.getKey() + "'"), substPyExpr);
      }
    }

    return nodePyVarToPyExprMap;
  }

  /**
   * Private helper to build valid Python string for a list of {@link SoyMsgPart}s.
   *
   * <p>It only processes {@link SoyMsgRawTextPart} and {@link SoyMsgPlaceholderPart} and ignores
   * others, because we didn't generate a direct string for plural and select nodes.
   *
   * <p>For {@link SoyMsgRawTextPart}, it appends the raw text and applies necessary escaping; For
   * {@link SoyMsgPlaceholderPart}, it turns the placeholder's variable name into Python replace
   * format.
   *
   * @param parts The SoyMsgPart parts to convert.
   * @param escaper A Function which provides escaping for raw text.
   * @return A String representing all the {@code parts} in Python.
   */
  private static String processMsgPartsHelper(
      ImmutableList<SoyMsgPart> parts, Function<String, String> escaper) {
    StringBuilder rawMsgTextSb = new StringBuilder();
    for (SoyMsgPart part : parts) {
      if (part instanceof SoyMsgRawTextPart) {
        rawMsgTextSb.append(escaper.apply(((SoyMsgRawTextPart) part).getRawText()));
      }

      if (part instanceof SoyMsgPlaceholderPart) {
        String phName = ((SoyMsgPlaceholderPart) part).getPlaceholderName();
        rawMsgTextSb.append("{" + phName + "}");
      }
    }
    return rawMsgTextSb.toString();
  }

  /**
   * A mapper to apply escaping for python format string.
   *
   * <p>It escapes '{' and '}' to '{{' and '}}' in the String.
   *
   * @see "https://docs.python.org/2/library/string.html#formatstrings"
   */
  private static final Function<String, String> escaperForPyFormatString =
      new Function<String, String>() {
        @Override
        public String apply(String str) {
          return str.replaceAll("\\{", "{{").replaceAll("\\}", "}}").replace("'", "\\\'");
        }
      };

  /** A mapper which does nothing. */
  private static final Function<String, String> nullEscaper =
      new Function<String, String>() {
        @Override
        public String apply(String str) {
          return str;
        }
      };
}