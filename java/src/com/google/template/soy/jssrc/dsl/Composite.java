/*
 * Copyright 2016 Google Inc.
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

package com.google.template.soy.jssrc.dsl;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.jssrc.restricted.JsExpr;

/** Represents an expression preceded by one or more initial statements. */
@AutoValue
@Immutable
abstract class Composite extends Expression {

  abstract Expression value();

  static Composite create(ImmutableList<Statement> initialStatements, Expression value) {
    Preconditions.checkState(!initialStatements.isEmpty());
    return new AutoValue_Composite(
        ImmutableList.<Statement>builder()
            .addAll(initialStatements)
            .addAll(value.initialStatements())
            .build(),
        value);
  }

  @Override
  public final boolean isCheap() {
    return value().isCheap();
  }

  /**
   * {@link CodeChunk#getCode} serializes both the chunk's initial statements and its output
   * expression. When a composite is the only chunk being serialized, and its value is a variable
   * reference, this leads to a redundant trailing expression (the variable name). Override the
   * superclass implementation to omit it.
   *
   * <p>This heuristic appears to only be depended on by unit tests
   */
  @Override
  String getCode(int startingIndent) {
    return value() instanceof VariableReference
        ? new FormattingContext(startingIndent).appendInitialStatements(this).toString()
        : super.getCode(startingIndent);
  }

  @Override
  void doFormatInitialStatements(FormattingContext ctx) {
    for (CodeChunk stmt : initialStatements()) {
      ctx.appendAll(stmt);
    }
    ctx.appendInitialStatements(value());
  }

  @Override
  public void collectRequires(RequiresCollector collector) {
    for (Statement stmt : initialStatements()) {
      stmt.collectRequires(collector);
    }
    value().collectRequires(collector);
  }

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    ctx.appendOutputExpression(value());
  }

  @Override
  public JsExpr singleExprOrName() {
    return value().singleExprOrName();
  }
}
