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

package com.google.template.soy.exprtree;

import static com.google.common.base.Preconditions.checkState;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.restricted.SoyFunction;

/**
 * A node representing a function (with args as children).
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class FunctionNode extends AbstractParentExprNode {

  private final String name;

  /**
   * Either a {@link SoyFunction} or a {@link SoySourceFunction}. TODO(b/19252021): use
   * SoySourceFunction everywhere.
   */
  private final Object soyFunction;

  /** Convenience constructor for SoyFunctions. */
  public FunctionNode(SoyFunction soyFunction, SourceLocation sourceLocation) {
    this(soyFunction.getName(), soyFunction, sourceLocation);
  }

  /**
   * @param soyFunction The SoyFunction.
   * @param sourceLocation The node's source location.
   */
  public FunctionNode(String name, Object soyFunction, SourceLocation sourceLocation) {
    super(sourceLocation);
    this.name = name;
    checkState(soyFunction instanceof SoyFunction || soyFunction instanceof SoySourceFunction);
    this.soyFunction = soyFunction;
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private FunctionNode(FunctionNode orig, CopyState copyState) {
    super(orig, copyState);
    this.name = orig.name;
    this.soyFunction = orig.soyFunction;
  }

  @Override
  public Kind getKind() {
    return Kind.FUNCTION_NODE;
  }

  /** Returns the function name. */
  public String getFunctionName() {
    return name;
  }

  public Object getSoyFunction() {
    return soyFunction;
  }

  @Override
  public String toSourceString() {

    StringBuilder sourceSb = new StringBuilder();
    sourceSb.append(getFunctionName()).append('(');

    boolean isFirst = true;
    for (ExprNode child : getChildren()) {
      if (isFirst) {
        isFirst = false;
      } else {
        sourceSb.append(", ");
      }
      sourceSb.append(child.toSourceString());
    }

    sourceSb.append(')');
    return sourceSb.toString();
  }

  @Override
  public FunctionNode copy(CopyState copyState) {
    return new FunctionNode(this, copyState);
  }
}
