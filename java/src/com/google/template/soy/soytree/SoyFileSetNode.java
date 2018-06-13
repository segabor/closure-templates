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

package com.google.template.soy.soytree;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.soytree.SoyNode.SplitLevelTopNode;

/**
 * Node representing a Soy file set (the root of the Soy parse tree).
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class SoyFileSetNode extends AbstractParentSoyNode<SoyFileNode>
    implements SplitLevelTopNode<SoyFileNode> {


  /** The node id generator for this parse tree. */
  private final IdGenerator nodeIdGen;

  /**
   * @param id The id for this node.
   * @param nodeIdGen The node id generator for this parse tree.
   */
  public SoyFileSetNode(int id, IdGenerator nodeIdGen) {
    super(id, null /* there is no source location. */);
    this.nodeIdGen = nodeIdGen;
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private SoyFileSetNode(SoyFileSetNode orig, CopyState copyState) {
    super(orig, copyState);
    this.nodeIdGen = orig.nodeIdGen.copy();
  }

  @Override
  public Kind getKind() {
    return Kind.SOY_FILE_SET_NODE;
  }

  /** Returns the node id generator for this parse tree. */
  public IdGenerator getNodeIdGenerator() {
    return nodeIdGen;
  }

  /** Returns all child {@link SoyFileNode} that have {@link SoyFileKind#SRC}. */
  public ImmutableList<SoyFileNode> getSourceFiles() {
    return ImmutableList.copyOf(Iterables.filter(getChildren(), SoyFileNode.MATCH_SRC_FILENODE));
  }

  @Deprecated
  @Override
  public String toSourceString() {
    throw new UnsupportedOperationException("SoyFileSets don't have source locations");
  }

  /** @deprecated SoyFileSetNodes don't have source locations. */
  @Deprecated
  @Override
  public SourceLocation getSourceLocation() {
    return super.getSourceLocation();
  }

  @Override
  public SoyFileSetNode copy(CopyState copyState) {
    return new SoyFileSetNode(this, copyState);
  }
}
