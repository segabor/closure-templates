/*
 * Copyright 2013 Google Inc.
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

package com.google.template.soy.basicfunctions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.template.soy.data.UnsafeSanitizedContentOrdainer.ordainAsSafe;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.plugin.java.restricted.testing.SoyJavaSourceFunctionTester;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyStringExpr;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link com.google.template.soy.basicfunctions.StrIndexOfFunction}.
 *
 */
@RunWith(JUnit4.class)
public class StrIndexOfFunctionTest {

  @Test
  public void testComputeForJavaSource_containsString() {
    SoyJavaSourceFunctionTester tester = new SoyJavaSourceFunctionTester(new StrIndexOfFunction());
    assertThat(tester.callFunction("foobarfoo", "bar")).isEqualTo(3);
  }

  @Test
  public void testComputeForJavaSource_containsSanitizedContent() {
    SoyJavaSourceFunctionTester tester = new SoyJavaSourceFunctionTester(new StrIndexOfFunction());
    assertThat(
            tester.callFunction(
                ordainAsSafe("foobarfoo", ContentKind.TEXT), ordainAsSafe("bar", ContentKind.TEXT)))
        .isEqualTo(3);
  }

  @Test
  public void testComputeForJavaSource_doesNotContainString() {
    SoyJavaSourceFunctionTester tester = new SoyJavaSourceFunctionTester(new StrIndexOfFunction());
    assertThat(tester.callFunction("foobarfoo", "baz")).isEqualTo(-1);
  }

  @Test
  public void testComputeForJavaSource_doesNotContainSanitizedContent() {
    SoyJavaSourceFunctionTester tester = new SoyJavaSourceFunctionTester(new StrIndexOfFunction());
    assertThat(
            tester.callFunction(
                ordainAsSafe("foobarfoo", ContentKind.TEXT), ordainAsSafe("baz", ContentKind.TEXT)))
        .isEqualTo(-1);
  }

  @Test
  public void testComputeForJsSrc_lowPrecedenceArg() {
    StrIndexOfFunction strIndexOf = new StrIndexOfFunction();
    JsExpr arg0 = new JsExpr("'foo' + 'bar'", Operator.PLUS.getPrecedence());
    JsExpr arg1 = new JsExpr("'ba' + 'r'", Operator.PLUS.getPrecedence());
    assertThat(strIndexOf.computeForJsSrc(ImmutableList.of(arg0, arg1)))
        .isEqualTo(
            new JsExpr("('' + ('foo' + 'bar')).indexOf('' + ('ba' + 'r'))", Integer.MAX_VALUE));
  }

  @Test
  public void testComputeForJsSrc_maxPrecedenceArgs() {
    StrIndexOfFunction strIndexOf = new StrIndexOfFunction();
    JsExpr arg0 = new JsExpr("'foobar'", Integer.MAX_VALUE);
    JsExpr arg1 = new JsExpr("'bar'", Integer.MAX_VALUE);
    assertThat(strIndexOf.computeForJsSrc(ImmutableList.of(arg0, arg1)))
        .isEqualTo(new JsExpr("('foobar').indexOf('bar')", Integer.MAX_VALUE));
  }

  @Test
  public void testComputeForPySrc_stringInput() {
    StrIndexOfFunction strIndexOf = new StrIndexOfFunction();
    PyExpr base = new PyStringExpr("'foobar'", Integer.MAX_VALUE);
    PyExpr substring = new PyStringExpr("'bar'", Integer.MAX_VALUE);
    assertThat(strIndexOf.computeForPySrc(ImmutableList.of(base, substring)))
        .isEqualTo(new PyExpr("('foobar').find('bar')", Integer.MAX_VALUE));
  }

  @Test
  public void testComputeForPySrc_nonStringInput() {
    StrIndexOfFunction strIndexOf = new StrIndexOfFunction();
    PyExpr base = new PyExpr("foobar", Integer.MAX_VALUE);
    PyExpr substring = new PyExpr("bar", Integer.MAX_VALUE);
    assertThat(strIndexOf.computeForPySrc(ImmutableList.of(base, substring)))
        .isEqualTo(new PyExpr("(str(foobar)).find(str(bar))", Integer.MAX_VALUE));
  }
}
