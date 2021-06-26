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

package com.google.template.soy.exprtree;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.types.SoyType;
import javax.annotation.Nullable;

/**
 * Interface for the definition of a variable, i.e. a value that can be referred to by name.
 * Variables include params, injected params, and local vars.
 *
 * <p>This object is appropriate for use as a unique hash key for referring to variables and should
 * always be used as opposed to using a variable name.
 *
 * <p>TODO(lukes): All variables have declaring nodes, we should add that field to this interface.
 */
public interface VarDefn {

  /** Enum used to distinguish subtypes. */
  enum Kind {
    // Explicitly declared parameter.
    PARAM,
    // Local variable
    LOCAL_VAR,
    // File-wide exportable constant.
    CONST,
    // File-wide exportable extern.
    EXTERN,

    // imported symbol
    IMPORT_VAR,

    // List/map comprehension var.
    COMPREHENSION_VAR,

    // State variable
    STATE,
    // A local template name.
    TEMPLATE,
    // Undeclared variable reference (for legacy templates).
    UNDECLARED,
  }

  /** What kind of variable this is (param, local var, etc). */
  Kind kind();

  /**
   * The name of this variable.
   *
   * <p><em>Not including the {@code $}.</em>
   */
  String name();

  /** The name by which this variable should be referenced in expressions. */
  default String refName() {
    return name();
  }

  /** The source location of the variable name. */
  @Nullable
  SourceLocation nameLocation();

  /**
   * Returns the data type of this variable. This throws an error if the var def does not have a
   * type yet. {@link hasType} should be called first.
   */
  SoyType type();

  default SoyType typeOrDefault(@Nullable SoyType defaultValue) {
    return hasType() ? type() : defaultValue;
  }

  /** Whether a type has been set yet for this variable (in early passes, this may not be true). */
  boolean hasType();

  /** Returns true if this is an {@code @inject} param. */
  boolean isInjected();
}
