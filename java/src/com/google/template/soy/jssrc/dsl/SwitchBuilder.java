/*
 * Copyright 2017 Google Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** Builds a {@link Switch} statement. */
public final class SwitchBuilder {
  private final Expression switchOn;
  private final ImmutableList.Builder<Switch.CaseClause> clauses = ImmutableList.builder();
  @Nullable private Statement defaultCaseBody;

  SwitchBuilder(Expression switchOn) {
    this.switchOn = switchOn;
  }

  /**
   * Adds a case clause (one or more {@code case} labels followed by a body) to this switch
   * statement.
   */
  public SwitchBuilder addCase(ImmutableList<Expression> caseLabels, Statement body) {
    Preconditions.checkState(!caseLabels.isEmpty(), "at least one case required");
    clauses.add(new Switch.CaseClause(caseLabels, body));
    return this;
  }

  /** Adds a case clause to this switch statement. */
  public SwitchBuilder addCase(Expression caseLabel, Statement body) {
    clauses.add(new Switch.CaseClause(ImmutableList.of(caseLabel), body));
    return this;
  }

  /** Adds a {@code default} clause to this switch statement. */
  public SwitchBuilder setDefault(Statement body) {
    Preconditions.checkState(defaultCaseBody == null);
    defaultCaseBody = body;
    return this;
  }

  /** Finishes building this switch statement. */
  public Statement build() {
    return Switch.create(switchOn, clauses.build(), defaultCaseBody);
  }
}
