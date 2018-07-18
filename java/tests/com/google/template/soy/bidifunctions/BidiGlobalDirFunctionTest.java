/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.bidifunctions;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.plugin.java.restricted.testing.SoyJavaSourceFunctionTester;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
import com.google.template.soy.shared.SharedRestrictedTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for BidiGlobalDirFunction.
 *
 */
@RunWith(JUnit4.class)
public class BidiGlobalDirFunctionTest {

  @Test
  public void testComputeForJava() {
    // the java source version doesn't use the provider
    BidiGlobalDirFunction fn =
        new BidiGlobalDirFunction(
            () -> {
              throw new UnsupportedOperationException();
            });

    SoyJavaSourceFunctionTester tester =
        new SoyJavaSourceFunctionTester.Builder(fn).withBidiGlobalDir(BidiGlobalDir.LTR).build();
    assertThat(tester.callFunction()).isEqualTo(1);

    tester =
        new SoyJavaSourceFunctionTester.Builder(fn).withBidiGlobalDir(BidiGlobalDir.RTL).build();
    assertThat(tester.callFunction()).isEqualTo(-1);
  }

  @Test
  public void testComputeForJsSrc() {
    BidiGlobalDirFunction ltr = new BidiGlobalDirFunction(Suppliers.ofInstance(BidiGlobalDir.LTR));
    BidiGlobalDirFunction rtl = new BidiGlobalDirFunction(Suppliers.ofInstance(BidiGlobalDir.RTL));

    assertThat(ltr.computeForJsSrc(ImmutableList.<JsExpr>of()))
        .isEqualTo(new JsExpr("1", Integer.MAX_VALUE));
    assertThat(rtl.computeForJsSrc(ImmutableList.<JsExpr>of()))
        .isEqualTo(new JsExpr("-1", Integer.MAX_VALUE));

    BidiGlobalDirFunction codeSnippet =
        new BidiGlobalDirFunction(
            SharedRestrictedTestUtils.BIDI_GLOBAL_DIR_FOR_JS_ISRTL_CODE_SNIPPET_SUPPLIER);
    assertThat(codeSnippet.computeForJsSrc(ImmutableList.<JsExpr>of()))
        .isEqualTo(new JsExpr("IS_RTL?-1:1", Operator.CONDITIONAL.getPrecedence()));
  }

  @Test
  public void testComputeForPySrc() {
    BidiGlobalDirFunction codeSnippet =
        new BidiGlobalDirFunction(
            SharedRestrictedTestUtils.BIDI_GLOBAL_DIR_FOR_PY_ISRTL_CODE_SNIPPET_SUPPLIER);

    assertThat(codeSnippet.computeForPySrc(ImmutableList.<PyExpr>of()))
        .isEqualTo(
            new PyExpr(
                "-1 if IS_RTL else 1", PyExprUtils.pyPrecedenceForOperator(Operator.CONDITIONAL)));
  }
}
