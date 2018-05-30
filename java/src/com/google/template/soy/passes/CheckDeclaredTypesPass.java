/*
 * Copyright 2018 Google Inc.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.HeaderParam;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.soytree.defn.TemplateParam.DeclLoc;
import com.google.template.soy.types.MapType;
import com.google.template.soy.types.ast.GenericTypeNode;
import com.google.template.soy.types.ast.NamedTypeNode;
import com.google.template.soy.types.ast.RecordTypeNode;
import com.google.template.soy.types.ast.RecordTypeNode.Property;
import com.google.template.soy.types.ast.TypeNode;
import com.google.template.soy.types.ast.TypeNodeVisitor;
import com.google.template.soy.types.ast.UnionTypeNode;

/**
 * Checks type declarations to make sure they're legal. For now, this only checks that legal map
 * keys are used.
 *
 * <p>This class determines if explicit type declarations are legal, whereas {@link
 * ResolveExpressionTypesPass} calculates implicit types and determines if they're legal.
 */
final class CheckDeclaredTypesPass extends CompilerFilePass {

  private final ErrorReporter errorReporter;

  CheckDeclaredTypesPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (TemplateNode templateNode : file.getChildren()) {
      for (TemplateParam param : templateNode.getAllParams()) {
        if (param.declLoc() == DeclLoc.HEADER) {
          ((HeaderParam) param).getTypeNode().accept(new MapKeyTypeChecker());
        }
      }
    }
  }

  private final class MapKeyTypeChecker implements TypeNodeVisitor<Void> {

    @Override
    public Void visit(NamedTypeNode node) {
      return null; // Not a map. Nothing to do.
    }

    @Override
    public Void visit(GenericTypeNode node) {
      switch (node.getResolvedType().getKind()) {
        case MAP:
          checkArgument(node.arguments().size() == 2);
          TypeNode key = node.arguments().get(0);
          if (!MapType.isAllowedKeyType(key.getResolvedType())) {
            errorReporter.report(
                key.sourceLocation(), MapType.BAD_MAP_KEY_TYPE, key.getResolvedType());
          }
          node.arguments().get(1).accept(this);
          break;
        case LIST:
          checkArgument(node.arguments().size() == 1);
          node.arguments().get(0).accept(this);
          break;
        case LEGACY_OBJECT_MAP:
          checkArgument(node.arguments().size() == 2);
          for (TypeNode child : node.arguments()) {
            child.accept(this);
          }
          break;
        default:
          throw new AssertionError("unexpected generic type: " + node.getResolvedType().getKind());
      }
      return null;
    }

    @Override
    public Void visit(UnionTypeNode node) {
      for (TypeNode child : node.candidates()) {
        child.accept(this);
      }
      return null;
    }

    @Override
    public Void visit(RecordTypeNode node) {
      for (Property property : node.properties()) {
        property.type().accept(this);
      }
      return null;
    }
  }
}
