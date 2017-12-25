package com.google.template.soy.swiftsrc.internal;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static com.google.template.soy.shared.SharedTestUtils.untypedTemplateBodyForExpression;

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.SoyModule;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.pysrc.internal.SoyExprForPySubject;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
import com.google.template.soy.shared.SharedTestUtils;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;

public class SoyExprForSwiftSubject extends Subject<SoyExprForSwiftSubject, String> {
  // disable optimizer for backwards compatibility
  private final SoyGeneralOptions opts = new SoyGeneralOptions().disableOptimizer();

  private final LocalVariableStack localVarExprs;

  private final Injector injector;

  private SoyExprForSwiftSubject(FailureMetadata failureMetadata, String expr) {
    super(failureMetadata, expr);
    localVarExprs = new LocalVariableStack();
    injector = Guice.createInjector(new SoyModule());
  }

  /**
   * Adds a frame of local variables to the top of the {@link LocalVariableStack}.
   *
   * @param localVarFrame one frame of local variables
   * @return the current subject for chaining
   */
  public SoyExprForSwiftSubject with(Map<String, SwiftExpr> localVarFrame) {
    localVarExprs.pushFrame();
    for (Map.Entry<String, SwiftExpr> entry : localVarFrame.entrySet()) {
      localVarExprs.addVariable(entry.getKey(), entry.getValue());
    }
    return this;
  }

  /**
   * Sets a map of key to {@link com.google.template.soy.data.restricted.PrimitiveData} values as
   * the current globally available data. Any compilation step will use these globals to replace
   * unrecognized variables.
   *
   * @param globals a map of keys to PrimitiveData values
   * @return the current subject for chaining
   */
  public SoyExprForSwiftSubject withGlobals(ImmutableMap<String, ?> globals) {
    opts.setCompileTimeGlobals(globals);
    return this;
  }
  /**
   * Asserts the subject translates to the expected SwiftExpr.
   *
   * @param expectedPyExpr the expected result of translation
   */
  public void translatesTo(SwiftExpr expectedPyExpr) {
    translatesTo(expectedPyExpr, null);
  }

  public void translatesTo(String expr, Operator precedence) {
    translatesTo(new SwiftExpr(expr, PyExprUtils.pyPrecedenceForOperator(precedence)));
  }

  public void translatesTo(String expr, int precedence) {
    translatesTo(new SwiftExpr(expr, precedence));
  }

  /**
   * Asserts the subject translates to the expected SwiftExpr including verification of the exact
   * SwiftExpr class (e.g. {@code PyStringExpr.class}).
   *
   * @param expectedPyExpr the expected result of translation
   * @param expectedClass the expected class of the resulting SwiftExpr
   */
  public void translatesTo(SwiftExpr expectedPyExpr, Class<? extends SwiftExpr> expectedClass) {

    SoyFileSetNode soyTree =
        SoyFileSetParserBuilder.forTemplateContents(untypedTemplateBodyForExpression(getSubject()))
            .options(opts)
            .parse()
            .fileSet();
    PrintNode node = (PrintNode) SharedTestUtils.getNode(soyTree, 0);
    ExprNode exprNode = node.getExpr();

    SwiftExpr actualSwiftExpr =
        new TranslateToSwiftExprVisitor(localVarExprs, ErrorReporter.exploding()).exec(exprNode);
    assertThat(actualSwiftExpr.getText()).isEqualTo(expectedPyExpr.getText());
    assertThat(actualSwiftExpr.getPrecedence()).isEqualTo(expectedPyExpr.getPrecedence());

    if (expectedClass != null) {
      assertThat(actualSwiftExpr.getClass()).isEqualTo(expectedClass);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Public static functions for starting a SoyExprForPySubject test.

  private static final Subject.Factory<SoyExprForSwiftSubject, String> SOYEXPR =
      SoyExprForSwiftSubject::new;

  public static SoyExprForSwiftSubject assertThatSoyExpr(String expr) {
    return assertAbout(SOYEXPR).that(expr);
  }
}
