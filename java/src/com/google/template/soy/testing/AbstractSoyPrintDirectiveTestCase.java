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

package com.google.template.soy.testing;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueConverter;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.shared.restricted.SoyJavaPrintDirective;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 * Utilities for testing Soy print directives.
 *
 * <p>TODO(lukes): this should be changed to be a {@code @Rule}
 */
@ParametersAreNonnullByDefault
public abstract class AbstractSoyPrintDirectiveTestCase {
  @Rule public final TestName testName = new TestName();

  /**
   * @param expectedOutput The expected result of applying directive to value with args.
   * @param value The test input.
   * @param directive The directive whose {@link SoyJavaPrintDirective#applyForJava} is under test.
   * @param args Arguments to the Soy directive.
   */
  protected void assertTofuOutput(
      String expectedOutput,
      @Nullable Object value,
      SoyJavaPrintDirective directive,
      Object... args) {
    assertTofuOutput(StringData.forValue(expectedOutput), value, directive, args);
  }

  /**
   * @param expectedOutput The expected result of applying directive to value with args.
   * @param value The test input.
   * @param directive The directive whose {@link SoyJavaPrintDirective#applyForJava} is under test.
   * @param args Arguments to the Soy directive.
   */
  protected void assertTofuOutput(
      SoyValue expectedOutput, Object value, SoyJavaPrintDirective directive, Object... args) {
    ImmutableList.Builder<SoyValue> argsData = ImmutableList.builder();
    for (Object arg : args) {
      argsData.add(SoyValueConverter.INSTANCE.convert(arg).resolve());
    }
    assertThat(
            directive
                .applyForJava(SoyValueConverter.INSTANCE.convert(value).resolve(), argsData.build())
                .toString())
        .isEqualTo(expectedOutput.toString());
  }
}
