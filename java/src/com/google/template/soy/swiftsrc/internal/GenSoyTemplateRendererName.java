package com.google.template.soy.swiftsrc.internal;

import javax.annotation.Nonnull;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.TemplateNode;

/**
 * Utility class to generate template renderer function names
 * 
 * Input:
 * - namespace: soy.examples.simple
 * - template: .helloWorld
 * 
 * Output:
 * - r__soy_examples_simple__helloWorld
 * 
 * @author segabor
 *
 */
public final class GenSoyTemplateRendererName {
  private static final String FN_SEP = "__";

  /**
   * This method called when renderer function is being generated
   */
  @Nonnull
  public static String makeFuncName(TemplateNode node) {
    final String templateName = GenSwiftCallExprVisitor.getLocalTemplateName(node);

    return makeFuncName(node.getParent().getNamespace(), templateName);
  }

  /**
   * This method is typically invoked to generate function name called from
   * another renderer function
   */
  @Nonnull
  public static String makeFuncName(CallBasicNode node) {
    String calleeName = node.getSourceCalleeName();
    String calleeExprText = null;
    
    if (calleeName.startsWith(".")) {
      // callee contains just a local template name
      // ie. call .helloWorld
      String localCalleeName = node.getSourceCalleeName().replaceFirst("^\\.", "");

      SoyFileNode soyModule = node.getNearestAncestor(SoyFileNode.class);
      calleeExprText = GenSoyTemplateRendererName.makeFuncName(soyModule.getNamespace(), localCalleeName);
    } else {
      // callee contains namespace for remote call
      // ie. call soy.examples.simple.helloWorld
      int periodIndex = calleeName.lastIndexOf(".");
      calleeExprText = GenSoyTemplateRendererName.makeFuncName(calleeName.substring(0, periodIndex), calleeName.substring(periodIndex+1));      
    }
    
    return calleeExprText;
  }

  @Nonnull
  public static String makeFuncName(String namespace, String soyFuncName) {
    StringBuilder funcNameBuilder = new StringBuilder();
    funcNameBuilder
      .append("r")
      .append(FN_SEP);
    if (namespace != null) {
      funcNameBuilder
        .append(namespace.replaceAll("\\.", "_") )
        .append(FN_SEP);
    }
    funcNameBuilder.append(soyFuncName);

    return funcNameBuilder.toString();
  }
}
