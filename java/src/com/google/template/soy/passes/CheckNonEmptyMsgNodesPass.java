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

package com.google.template.soy.passes;

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;

/**
 * A compiler pass that disallows empty {@code msg} commands.
 *
 * <p>In theory an empty {@code msg} command would be trivial to support, but it most likely points
 * to some user confusion or stray debugging code.
 */
final class CheckNonEmptyMsgNodesPass implements CompilerFilePass {

  private static final SoyErrorKind EMPTY_MSG_ERROR =
      SoyErrorKind.of("Empty messages are forbidden.");
  private final ErrorReporter errorReporter;

  CheckNonEmptyMsgNodesPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (MsgFallbackGroupNode groupNode :
        SoyTreeUtils.getAllNodesOfType(file, MsgFallbackGroupNode.class)) {
      for (MsgNode msg : groupNode.getChildren()) {
        if (isEmpty(msg)) {
          errorReporter.report(msg.getSourceLocation(), EMPTY_MSG_ERROR);
          // remove the whole group.
          // a number of msgnode methods throw if there are no children and having a fallback group
          // with 0 children is also unexpected.
          groupNode.getParent().removeChild(groupNode);
          break;
        }
      }
    }
  }

  /**
   * If the only children are empty raw text nodes, then the node is empty.
   *
   * <p>Empty raw text nodes are inserted by the parser to keep track of trimmed whitespace for the
   * html parser and removed later in the compiler.
   */
  private static boolean isEmpty(MsgNode msg) {
    for (SoyNode child : msg.getChildren()) {
      if (child instanceof RawTextNode && ((RawTextNode) child).getRawText().isEmpty()) {
        continue;
      }
      return false;
    }
    return true;
  }
}
