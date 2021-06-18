/*
 * Copyright 2012 Google Inc.
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

package com.google.template.soy.base.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.template.soy.base.SourceFilePath;
import java.util.Objects;

/**
 * Abstract base implementation of SoyFileSupplier.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public abstract class AbstractSoyFileSupplier implements SoyFileSupplier {

  /** Returns the file path (used for messages only). */
  protected final SourceFilePath filePath;

  /**
   * @param filePath The path to the Soy file, used for as a unique map/set key and for messages.
   */
  public AbstractSoyFileSupplier(SourceFilePath filePath) {
    this.filePath = checkNotNull(filePath);
  }

  @Override
  public SourceFilePath getFilePath() {
    return filePath;
  }

  /**
   * Tests equality based on the file path. This allows deduping of suppliers that refer to the same
   * underlying file.
   *
   * <p>NOTE: This will consider different file supplier implementations and different file kinds as
   * distinct since they behave differently. The caller may want to explicitly check for filename
   * collisions afterwards in case the same file is used with different supplier subclasses or file
   * kinds.
   */
  @Override
  public boolean equals(Object other) {
    if (other instanceof AbstractSoyFileSupplier && other.getClass() == this.getClass()) {
      AbstractSoyFileSupplier otherSupplier = (AbstractSoyFileSupplier) other;
      return filePath.equals(otherSupplier.filePath);
    }
    return false;
  }

  /** Hashes based on the file path. */
  @Override
  public int hashCode() {
    return Objects.hash(filePath, this.getClass());
  }
}
