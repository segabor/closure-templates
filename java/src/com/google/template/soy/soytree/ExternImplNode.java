/*
 * Copyright 2021 Google Inc.
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

package com.google.template.soy.soytree;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.soytree.CommandTagAttribute.CommandTagAttributesHolder;

/** A node that specifies some language-specific implementation of an extern. */
public abstract class ExternImplNode extends AbstractCommandNode
    implements CommandTagAttributesHolder {
  /**
   * @param id The id for this node.
   * @param sourceLocation The node's source location.
   * @param commandName The name of the Soy command.
   */
  protected ExternImplNode(int id, SourceLocation sourceLocation, String commandName) {
    super(id, sourceLocation, commandName);
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  protected ExternImplNode(ExternImplNode orig, CopyState copyState) {
    super(orig, copyState);
  }
}
