/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.data.restricted;

import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.data.SoyDataException;

/**
 * Undefined data. Used only by Tofu, not jbcsrc.
 *
 * <p>Important: This class may only be used in implementing plugins (e.g. functions, directives).
 */
@Immutable
public final class UndefinedData extends PrimitiveData {

  /** Static singleton instance of UndefinedData. */
  public static final UndefinedData INSTANCE = new UndefinedData();

  private UndefinedData() {}

  @Override
  public String toString() {
    throw new SoyDataException("Attempted to coerce undefined value into a string.");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Undefined is falsy.
   */
  @Override
  public boolean coerceToBoolean() {
    return false;
  }

  @Override
  public String coerceToString() {
    return toString();
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof PrimitiveData) {
      return other == UndefinedData.INSTANCE || other == NullData.INSTANCE;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
