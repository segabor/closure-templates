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
 * Unit tests for {@link StrLenFunction}.
 *
 */
@RunWith(JUnit4.class)
public class StrLenFunctionTest {

  @Test
  public void testComputeForJavaSource_containsString() {
    SoyJavaSourceFunctionTester tester = new SoyJavaSourceFunctionTester(new StrLenFunction());
    assertThat(tester.callFunction("foobarfoo")).isEqualTo(9);
  }

  @Test
  public void testComputeForJavaSource_containsSanitizedContent() {
    SoyJavaSourceFunctionTester tester = new SoyJavaSourceFunctionTester(new StrLenFunction());
    assertThat(tester.callFunction(ordainAsSafe("foobarfoo", ContentKind.TEXT))).isEqualTo(9);
  }

  @Test
  public void testComputeForJsSrc() {
    StrLenFunction strLen = new StrLenFunction();
    JsExpr arg0 = new JsExpr("'foo' + 'bar'", Operator.PLUS.getPrecedence());
    assertThat(strLen.computeForJsSrc(ImmutableList.of(arg0)))
        .isEqualTo(new JsExpr("('' + ('foo' + 'bar')).length", Integer.MAX_VALUE));
  }

  @Test
  public void testComputeForPySrc() {
    StrLenFunction strLen = new StrLenFunction();

    PyExpr string = new PyStringExpr("'data'");
    assertThat(strLen.computeForPySrc(ImmutableList.of(string)))
        .isEqualTo(new PyExpr("len('data')", Integer.MAX_VALUE));

    PyExpr data = new PyExpr("data", Integer.MAX_VALUE);
    assertThat(strLen.computeForPySrc(ImmutableList.of(data)))
        .isEqualTo(new PyExpr("len(str(data))", Integer.MAX_VALUE));
  }
}
