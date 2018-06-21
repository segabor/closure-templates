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

package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.basicfunctions.ConcatListsFunction;
import com.google.template.soy.basicfunctions.LegacyObjectMapToMapFunction;
import com.google.template.soy.basicfunctions.MapKeysFunction;
import com.google.template.soy.basicfunctions.MapToLegacyObjectMapFunction;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.ErrorReporter.Checkpoint;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.exprtree.AbstractExprNodeVisitor;
import com.google.template.soy.exprtree.AbstractOperatorNode;
import com.google.template.soy.exprtree.AbstractParentExprNode;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.ParentExprNode;
import com.google.template.soy.exprtree.ExprNode.PrimitiveNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.OperatorNodes.AndOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.DivideByOpNode;
import com.google.template.soy.exprtree.OperatorNodes.EqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.GreaterThanOpNode;
import com.google.template.soy.exprtree.OperatorNodes.GreaterThanOrEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.LessThanOpNode;
import com.google.template.soy.exprtree.OperatorNodes.LessThanOrEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.MinusOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ModOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NegativeOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.exprtree.OperatorNodes.OrOpNode;
import com.google.template.soy.exprtree.OperatorNodes.PlusOpNode;
import com.google.template.soy.exprtree.OperatorNodes.TimesOpNode;
import com.google.template.soy.exprtree.ProtoInitNode;
import com.google.template.soy.exprtree.RecordLiteralNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.shared.internal.ResolvedSignature;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.TypedSoyFunction;
import com.google.template.soy.soyparse.SoyFileParser;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfElseNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.LetValueNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.LoopVar;
import com.google.template.soy.types.AbstractMapType;
import com.google.template.soy.types.BoolType;
import com.google.template.soy.types.ErrorType;
import com.google.template.soy.types.FloatType;
import com.google.template.soy.types.IntType;
import com.google.template.soy.types.LegacyObjectMapType;
import com.google.template.soy.types.ListType;
import com.google.template.soy.types.MapType;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.RecordType;
import com.google.template.soy.types.SanitizedType;
import com.google.template.soy.types.SoyProtoType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.SoyTypes;
import com.google.template.soy.types.StringType;
import com.google.template.soy.types.UnionType;
import com.google.template.soy.types.UnknownType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Visitor which resolves all expression types.
 *
 */
final class ResolveExpressionTypesPass extends CompilerFilePass {

  // Keep in alphabetical order.
  private static final SoyErrorKind BAD_FOREACH_TYPE =
      SoyErrorKind.of("Cannot iterate over {0} of type {1}.");
  private static final SoyErrorKind BAD_INDEX_TYPE = SoyErrorKind.of("Bad index type {0} for {1}.");
  private static final SoyErrorKind BAD_KEY_TYPE = SoyErrorKind.of("Bad key type {0} for {1}.");
  private static final SoyErrorKind BRACKET_ACCESS_NOT_SUPPORTED =
      SoyErrorKind.of("Type {0} does not support bracket access.");
  private static final SoyErrorKind BRACKET_ACCESS_NULLABLE_UNION =
      SoyErrorKind.of(
          "Union type that is nullable cannot use bracket access. To access this value, "
              + "first check for null or use null-safe (\"?[\") operations.");
  private static final SoyErrorKind CHECK_NOT_NULL_ON_COMPILE_TIME_NULL =
      SoyErrorKind.of("Cannot call checkNotNull on a parameter with a static type of ''null''.");
  private static final SoyErrorKind DOT_ACCESS_NOT_SUPPORTED =
      SoyErrorKind.of("Type {0} does not support dot access.");
  private static final SoyErrorKind DOT_ACCESS_NOT_SUPPORTED_CONSIDER_RECORD =
      SoyErrorKind.of("Type {0} does not support dot access (consider record instead of map).");
  private static final SoyErrorKind DUPLICATE_KEY_IN_MAP_LITERAL =
      SoyErrorKind.of("Map literals with duplicate keys are not allowed.  Duplicate key: ''{0}''");
  private static final SoyErrorKind ILLEGAL_MAP_RESOLVED_KEY_TYPE =
      SoyErrorKind.of(
          "A map''s keys must all be the same type. This map has keys of multiple types "
              + "(''{0}'').");
  private static final SoyErrorKind EMPTY_LIST_ACCESS =
      SoyErrorKind.of("Accessing item in empty list.");
  private static final SoyErrorKind EMPTY_LIST_FOREACH =
      SoyErrorKind.of("Cannot iterate over empty list.");
  private static final SoyErrorKind EMPTY_MAP_ACCESS =
      SoyErrorKind.of("Accessing item in empty map.");
  private static final SoyErrorKind INVALID_TYPE_SUBSTITUTION =
      SoyErrorKind.of("Expected expression of type ''{0}'', found ''{1}''.");
  private static final SoyErrorKind LIST_LENGTH_ERROR =
      SoyErrorKind.of(
          "Soy lists do not have a ''length'' field. Use function length(...) instead.");
  private static final SoyErrorKind MISSING_SOY_TYPE =
      SoyErrorKind.of("Missing Soy type for node {0}.");
  private static final SoyErrorKind NOT_A_PROTO_TYPE =
      SoyErrorKind.of("''{0}'' is a ''{1}'', expected a protocol buffer.");
  private static final SoyErrorKind OR_OPERATOR_HAS_CONSTANT_OPERAND =
      SoyErrorKind.of(
          "Constant operand ''{0}'' used with ''or'' operator. "
              + "Consider simplifying or using the ?: operator, see "
              + "go/soy/reference/expressions.md#logical-operators",
          StyleAllowance.NO_PUNCTUATION);
  private static final SoyErrorKind STRING_LENGTH_ERROR =
      SoyErrorKind.of(
          "Soy strings do not have a ''length'' field. Use function strLen(...) instead.");
  private static final SoyErrorKind UNDEFINED_FIELD_FOR_PROTO_TYPE =
      SoyErrorKind.of(
          "Undefined field ''{0}'' for proto type {1}.{2}", StyleAllowance.NO_PUNCTUATION);
  private static final SoyErrorKind UNDEFINED_FIELD_FOR_RECORD_TYPE =
      SoyErrorKind.of(
          "Undefined field ''{0}'' for record type {1}.{2}", StyleAllowance.NO_PUNCTUATION);
  private static final SoyErrorKind UNKNOWN_PROTO_TYPE =
      SoyErrorKind.of("Unknown proto type ''{0}''.");
  private static final SoyErrorKind VAR_REF_MISSING_SOY_TYPE =
      SoyErrorKind.of("Missing Soy type for variable.");
  private static final SoyErrorKind TYPE_MISMATCH =
      SoyErrorKind.of("Soy types ''{0}'' and ''{1}'' are not comparable.");
  private static final SoyErrorKind INCOMPATIBLE_ARITHMETIC_OP =
      SoyErrorKind.of("Using arithmetic operators on Soy types ''{0}'' and ''{1}'' is illegal.");
  private static final SoyErrorKind INCOMPATIBLE_ARITHMETIC_OP_UNARY =
      SoyErrorKind.of("Using arithmetic operators on the Soy type ''{0}'' is illegal.");
  private static final SoyErrorKind INCORRECT_ARG_TYPE =
      SoyErrorKind.of("Function ''{0}'' called with incorrect arg type {1} (expected {2}).");
  private static final SoyErrorKind LOOP_VARIABLE_NOT_IN_SCOPE =
      SoyErrorKind.of("Function ''{0}'' must have a loop variable as its argument.");
  private static final SoyErrorKind STRING_LITERAL_REQUIRED =
      SoyErrorKind.of("Argument to function ''{0}'' must be a string literal.");

  private final ErrorReporter errorReporter;
  /** Type registry. */
  private final SoyTypeRegistry typeRegistry;
  /** Cached map that converts a string representation of types to actual soy types. */
  private final Map<Signature, ResolvedSignature> signatureMap = new HashMap<>();

  /** Current set of type substitutions. */
  private TypeSubstitution substitutions;

  ResolveExpressionTypesPass(SoyTypeRegistry typeRegistry, ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
    this.typeRegistry = typeRegistry;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    substitutions = null; // make sure substitutions don't leak across files
    new TypeAssignmentSoyVisitor().exec(file);
  }

  private final class TypeAssignmentSoyVisitor extends AbstractSoyNodeVisitor<Void> {

    @Override
    protected void visitTemplateNode(TemplateNode node) {
      visitSoyNode(node);
    }

    @Override
    protected void visitPrintNode(PrintNode node) {
      visitSoyNode(node);
    }

    @Override
    protected void visitLetValueNode(LetValueNode node) {
      visitSoyNode(node);
      node.getVar().setType(node.getExpr().getType());
    }

    @Override
    protected void visitLetContentNode(LetContentNode node) {
      visitSoyNode(node);
      node.getVar()
          .setType(
              node.getContentKind() != null
                  ? SanitizedType.getTypeForContentKind(node.getContentKind())
                  : StringType.getInstance());
    }

    @Override
    protected void visitIfNode(IfNode node) {
      // TODO(user): Also support switch / case.
      TypeSubstitution savedSubstitutionState = substitutions;
      for (SoyNode child : node.getChildren()) {
        if (child instanceof IfCondNode) {
          IfCondNode icn = (IfCondNode) child;
          visitExpressions(icn);

          // Visit the conditional expression to compute which types can be narrowed.
          TypeNarrowingConditionVisitor visitor = new TypeNarrowingConditionVisitor();
          visitor.exec(icn.getExpr());

          // Save the state of substitutions from the previous if block.
          TypeSubstitution previousSubstitutionState = substitutions;

          // Modify the current set of type substitutions for the 'true' branch
          // of the if statement.
          addTypeSubstitutions(visitor.positiveTypeConstraints);
          visitChildren(icn);

          // Rewind the substitutions back to the state before the if-condition.
          // Add in the negative substitutions, which will affect subsequent blocks
          // of the if statement.
          // So for example if we have a variable whose type is (A|B|C) and the
          // first if-block tests whether that variable is type A, then in the
          // 'else' block it must be of type (B|C); If a subsequent 'elseif'
          // statement tests whether it's type B, then in the following else block
          // it can only be of type C.
          substitutions = previousSubstitutionState;
          addTypeSubstitutions(visitor.negativeTypeConstraints);
        } else if (child instanceof IfElseNode) {
          // For the else node, we simply inherit the previous set of subsitutions.
          IfElseNode ien = (IfElseNode) child;
          visitChildren(ien);
        }
      }
      substitutions = savedSubstitutionState;
    }

    @Override
    protected void visitForNonemptyNode(ForNonemptyNode node) {
      // Visit the foreach iterator expression
      visitExpressions(node.getParent());
      // Set the inferred type of the loop variable.
      node.getVar().setType(getElementType(node.getExpr().getType(), node));
      // Visit the node body
      visitChildren(node);
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ExprHolderNode) {
        visitExpressions((ExprHolderNode) node);
      }

      if (node instanceof ParentSoyNode<?>) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }
  }

  // Given a map of type subsitutions, add all the entries to the current set of
  // active substitutions.
  private void addTypeSubstitutions(Map<Wrapper<ExprNode>, SoyType> substitutionsToAdd) {
    for (Map.Entry<Wrapper<ExprNode>, SoyType> entry : substitutionsToAdd.entrySet()) {
      ExprNode expr = entry.getKey().get();
      // Get the existing type
      SoyType previousType = expr.getType();
      for (TypeSubstitution subst = substitutions; subst != null; subst = subst.parent) {
        if (ExprEquivalence.get().equivalent(subst.expression, expr)) {
          previousType = subst.type;
          break;
        }
      }

      // If the new type is different than the current type, then add a new type substitution.
      if (!entry.getValue().equals(previousType)) {
        substitutions = new TypeSubstitution(substitutions, expr, entry.getValue());
      }
    }
  }

  private void visitExpressions(ExprHolderNode node) {
    ResolveTypesExprVisitor exprVisitor = new ResolveTypesExprVisitor();
    for (ExprRootNode expr : node.getExprList()) {
      exprVisitor.exec(expr);
    }
  }

  /**
   * Given a collection type, compute the element type.
   *
   * @param collectionType The base type.
   * @param node The ForNonemptyNode being iterated.
   * @return The type of the elements of the collection.
   */
  private SoyType getElementType(SoyType collectionType, ForNonemptyNode node) {
    Preconditions.checkNotNull(collectionType);
    switch (collectionType.getKind()) {
      case UNKNOWN:
        // If we don't know anything about the base type, then make no assumptions
        // about the field type.
        return UnknownType.getInstance();

      case LIST:
        if (collectionType == ListType.EMPTY_LIST) {
          errorReporter.report(node.getParent().getSourceLocation(), EMPTY_LIST_FOREACH);
          return ErrorType.getInstance();
        }
        return ((ListType) collectionType).getElementType();

      case UNION:
        {
          // If it's a union, then do the field type calculation for each member of
          // the union and combine the result.
          UnionType unionType = (UnionType) collectionType;
          List<SoyType> fieldTypes = new ArrayList<>(unionType.getMembers().size());
          for (SoyType unionMember : unionType.getMembers()) {
            SoyType elementType = getElementType(unionMember, node);
            if (elementType.getKind() == SoyType.Kind.ERROR) {
              return ErrorType.getInstance();
            }
            fieldTypes.add(elementType);
          }
          return SoyTypes.computeLowestCommonType(typeRegistry, fieldTypes);
        }

      default:
        errorReporter.report(
            node.getParent().getSourceLocation(),
            BAD_FOREACH_TYPE,
            node.getExpr().toSourceString(),
            node.getExpr().getType()); // Report the outermost union type in the error.
        return ErrorType.getInstance();
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Expr visitor.

  /**
   * Visitor which resolves all variable and parameter references in expressions to point to the
   * corresponding declaration object.
   */
  private final class ResolveTypesExprVisitor extends AbstractExprNodeVisitor<Void> {

    private final AbstractExprNodeVisitor<Void> checkAllTypesAssignedVisitor =
        new AbstractExprNodeVisitor<Void>() {
          @Override
          protected void visitExprNode(ExprNode node) {
            if (node instanceof ParentExprNode) {
              visitChildren((ParentExprNode) node);
            }
            requireNodeType(node);
          }
        };

    @Override
    public Void exec(ExprNode node) {
      Preconditions.checkArgument(node instanceof ExprRootNode);
      visit(node);
      // Check that every node in the tree had a type assigned
      checkAllTypesAssignedVisitor.exec(node);
      return null;
    }

    @Override
    protected void visitExprRootNode(ExprRootNode node) {
      visitChildren(node);
      ExprNode expr = node.getRoot();
      node.setType(expr.getType());
      tryApplySubstitution(node);
    }

    @Override
    protected void visitPrimitiveNode(PrimitiveNode node) {
      // We don't do anything here because primitive nodes already have type information.
    }

    @Override
    protected void visitListLiteralNode(ListLiteralNode node) {
      visitChildren(node);
      List<SoyType> elementTypes = new ArrayList<>(node.getChildren().size());
      for (ExprNode child : node.getChildren()) {
        requireNodeType(child);
        elementTypes.add(child.getType());
      }
      // Special case for empty list.
      if (elementTypes.isEmpty()) {
        node.setType(ListType.EMPTY_LIST);
      } else {
        node.setType(
            typeRegistry.getOrCreateListType(
                SoyTypes.computeLowestCommonType(typeRegistry, elementTypes)));
      }
      tryApplySubstitution(node);
    }

    @Override
    protected void visitRecordLiteralNode(RecordLiteralNode node) {
      visitChildren(node);

      int numChildren = node.numChildren();
      checkState(numChildren == node.getKeys().size());
      if (numChildren == 0) {
        // TODO(b/79869432): Remove support for the empty record.
        node.setType(RecordType.EMPTY_RECORD);
        return;
      }

      Map<String, SoyType> fieldTypes = Maps.newHashMapWithExpectedSize(numChildren);
      for (int i = 0; i < numChildren; i++) {
        fieldTypes.put(node.getKey(i).identifier(), node.getChild(i).getType());
      }
      node.setType(typeRegistry.getOrCreateRecordType(fieldTypes));

      tryApplySubstitution(node);
    }

    @Override
    protected void visitMapLiteralNode(MapLiteralNode node) {
      visitChildren(node);

      int numChildren = node.numChildren();
      checkState(numChildren % 2 == 0);
      if (numChildren == 0) {
        node.setType(MapType.EMPTY_MAP);
        return;
      }

      Set<String> duplicateKeyErrors = new HashSet<>();
      Map<String, SoyType> recordFieldTypes = new LinkedHashMap<>();
      List<SoyType> keyTypes = new ArrayList<>(numChildren / 2);
      List<SoyType> valueTypes = new ArrayList<>(numChildren / 2);
      Checkpoint checkpoint = errorReporter.checkpoint();
      for (int i = 0; i < numChildren; i += 2) {
        ExprNode key = node.getChild(i);
        ExprNode value = node.getChild(i + 1);
        // TODO: consider using ExprEquivalence to detect duplicate map keys
        if (key.getKind() == ExprNode.Kind.STRING_NODE) {
          String fieldName = ((StringNode) key).getValue();
          SoyType prev = recordFieldTypes.put(fieldName, value.getType());
          if (prev != null && duplicateKeyErrors.add(fieldName)) {
            errorReporter.report(key.getSourceLocation(), DUPLICATE_KEY_IN_MAP_LITERAL, fieldName);
          }
        }
        keyTypes.add(key.getType());
        if (!MapType.isAllowedKeyType(key.getType())) {
          errorReporter.report(key.getSourceLocation(), MapType.BAD_MAP_KEY_TYPE, key.getType());
        }
        valueTypes.add(value.getType());
      }
      SoyType commonKeyType = SoyTypes.computeLowestCommonType(typeRegistry, keyTypes);
      if (!errorReporter.errorsSince(checkpoint) && !MapType.isAllowedKeyType(commonKeyType)) {
        errorReporter.report(
            node.getSourceLocation(), ILLEGAL_MAP_RESOLVED_KEY_TYPE, commonKeyType);
      }
      SoyType commonValueType = SoyTypes.computeLowestCommonType(typeRegistry, valueTypes);
      node.setType(typeRegistry.getOrCreateMapType(commonKeyType, commonValueType));

      tryApplySubstitution(node);
    }

    @Override
    protected void visitVarRefNode(VarRefNode varRef) {
      if (varRef.getType() == null) {
        errorReporter.report(varRef.getSourceLocation(), VAR_REF_MISSING_SOY_TYPE);
      }
      SoyType newType = getTypeSubstitution(varRef);
      if (newType != null) {
        varRef.setSubstituteType(newType);
      }
    }

    @Override
    protected void visitFieldAccessNode(FieldAccessNode node) {
      visit(node.getBaseExprChild());
      node.setType(
          getFieldType(
              node.getBaseExprChild().getType(), node.getFieldName(), node.getSourceLocation()));
      tryApplySubstitution(node);
    }

    @Override
    protected void visitItemAccessNode(ItemAccessNode node) {
      visit(node.getBaseExprChild());
      visit(node.getKeyExprChild());
      SoyType itemType =
          getItemType(
              node.getBaseExprChild().getType(),
              node.getKeyExprChild().getType(),
              node.isNullSafe(),
              node.getSourceLocation(),
              node.getKeyExprChild().getSourceLocation());
      node.setType(itemType);
      tryApplySubstitution(node);
    }

    @Override
    protected void visitGlobalNode(GlobalNode node) {
      // Do nothing, global nodes already have type information.
    }

    @Override
    protected void visitNegativeOpNode(NegativeOpNode node) {
      visitChildren(node);
      SoyType childType = node.getChild(0).getType();
      if (SoyTypes.isNumericOrUnknown(childType)) {
        node.setType(childType);
      } else {
        errorReporter.report(node.getSourceLocation(), INCOMPATIBLE_ARITHMETIC_OP_UNARY, childType);
        node.setType(UnknownType.getInstance());
      }
      tryApplySubstitution(node);
    }

    @Override
    protected void visitNotOpNode(NotOpNode node) {
      visitChildren(node);
      node.setType(BoolType.getInstance());
    }

    @Override
    protected void visitTimesOpNode(TimesOpNode node) {
      visitArithmeticOpNode(node);
    }

    @Override
    protected void visitDivideByOpNode(DivideByOpNode node) {
      visitArithmeticOpNode(node);
    }

    @Override
    protected void visitModOpNode(ModOpNode node) {
      visitArithmeticOpNode(node);
    }

    @Override
    protected void visitPlusOpNode(PlusOpNode node) {
      visitChildren(node);
      SoyType left = node.getChild(0).getType();
      SoyType right = node.getChild(1).getType();
      SoyType result =
          SoyTypes.getSoyTypeForBinaryOperator(left, right, new SoyTypes.SoyTypePlusOperator());
      if (result == null) {
        errorReporter.report(node.getSourceLocation(), INCOMPATIBLE_ARITHMETIC_OP, left, right);
        result = UnknownType.getInstance();
      }
      node.setType(result);
      tryApplySubstitution(node);
    }

    @Override
    protected void visitMinusOpNode(MinusOpNode node) {
      visitArithmeticOpNode(node);
    }

    @Override
    protected void visitLessThanOpNode(LessThanOpNode node) {
      visitComparisonOpNode(node);
    }

    @Override
    protected void visitGreaterThanOpNode(GreaterThanOpNode node) {
      visitComparisonOpNode(node);
    }

    @Override
    protected void visitLessThanOrEqualOpNode(LessThanOrEqualOpNode node) {
      visitComparisonOpNode(node);
    }

    @Override
    protected void visitGreaterThanOrEqualOpNode(GreaterThanOrEqualOpNode node) {
      visitComparisonOpNode(node);
    }

    @Override
    protected void visitEqualOpNode(EqualOpNode node) {
      visitEqualComparisonOpNode(node);
    }

    @Override
    protected void visitNotEqualOpNode(NotEqualOpNode node) {
      visitEqualComparisonOpNode(node);
    }

    @Override
    protected void visitAndOpNode(AndOpNode node) {
      visit(node.getChild(0)); // Assign normal types to left child

      // Save the state of substitutions.
      TypeSubstitution savedSubstitutionState = substitutions;

      // Visit the left hand side to help narrow types used on the right hand side.
      TypeNarrowingConditionVisitor visitor = new TypeNarrowingConditionVisitor();
      visitor.visitAndImplicitlyCastToBoolean(node.getChild(0));

      // For 'and' the second child only gets evaluated if node 0 is truthy.  So apply the positive
      // assertions.
      addTypeSubstitutions(visitor.positiveTypeConstraints);
      visit(node.getChild(1));

      // Restore substitutions to previous state
      substitutions = savedSubstitutionState;

      node.setType(BoolType.getInstance());
    }

    @Override
    protected void visitOrOpNode(OrOpNode node) {
      ExprNode lhs = node.getChild(0);
      if (SoyTreeUtils.isConstantExpr(lhs)) {
        errorReporter.warn(
            node.getSourceLocation(), OR_OPERATOR_HAS_CONSTANT_OPERAND, lhs.toSourceString());
      }
      visit(lhs); // Assign normal types to left child

      // Save the state of substitutions.
      TypeSubstitution savedSubstitutionState = substitutions;

      // Visit the left hand side to help narrow types used on the right hand side.
      TypeNarrowingConditionVisitor visitor = new TypeNarrowingConditionVisitor();
      visitor.visitAndImplicitlyCastToBoolean(node.getChild(0));

      // For 'or' the second child only gets evaluated if node 0 is falsy.  So apply the negative
      // assertions.
      addTypeSubstitutions(visitor.negativeTypeConstraints);
      ExprNode rhs = node.getChild(1);
      visit(rhs);
      if (SoyTreeUtils.isConstantExpr(rhs)) {
        errorReporter.warn(
            node.getSourceLocation(), OR_OPERATOR_HAS_CONSTANT_OPERAND, rhs.toSourceString());
      }

      // Restore substitutions to previous state
      substitutions = savedSubstitutionState;

      node.setType(BoolType.getInstance());
    }

    @Override
    protected void visitNullCoalescingOpNode(NullCoalescingOpNode node) {
      visit(node.getChild(0));

      // Save the state of substitutions.
      TypeSubstitution savedSubstitutionState = substitutions;

      // Visit the conditional expression to compute which types can be narrowed.
      TypeNarrowingConditionVisitor visitor = new TypeNarrowingConditionVisitor();
      visitor.visitAndImplicitlyCastToBoolean(node.getChild(0));

      // Now, re-visit the first node but with substitutions. The reason is because
      // the value of node 0 is what will be returned if node 0 is truthy.
      addTypeSubstitutions(visitor.positiveTypeConstraints);
      visit(node.getChild(0));

      // For the null-coalescing operator, the node 1 only gets evaluated
      // if node 0 is falsey. Use the negative substitutions for this case.
      addTypeSubstitutions(visitor.negativeTypeConstraints);
      visit(node.getChild(1));

      // Restore substitutions to previous state
      substitutions = savedSubstitutionState;

      node.setType(
          SoyTypes.computeLowestCommonType(
              typeRegistry, node.getChild(0).getType(), node.getChild(1).getType()));
      tryApplySubstitution(node);
    }

    @Override
    protected void visitConditionalOpNode(ConditionalOpNode node) {
      visit(node.getChild(0));

      // Save the state of substitutions.
      TypeSubstitution savedSubstitutionState = substitutions;

      // Visit the conditional expression to compute which types can be narrowed.
      TypeNarrowingConditionVisitor visitor = new TypeNarrowingConditionVisitor();
      visitor.visitAndImplicitlyCastToBoolean(node.getChild(0));

      // Modify the current set of type substitutions for the 'true' branch
      // of the conditional.
      addTypeSubstitutions(visitor.positiveTypeConstraints);
      visit(node.getChild(1));

      // Rewind the substitutions back to the state before the expression.
      // Add in the negative substitutions, which will affect the 'false'
      // branch.
      substitutions = savedSubstitutionState;
      addTypeSubstitutions(visitor.negativeTypeConstraints);
      visit(node.getChild(2));

      // Restore substitutions to previous state
      substitutions = savedSubstitutionState;

      // For a conditional node, it will return either child 1 or 2.
      node.setType(
          SoyTypes.computeLowestCommonType(
              typeRegistry, node.getChild(1).getType(), node.getChild(2).getType()));
      tryApplySubstitution(node);
    }

    /**
     * Converts a signature annotation (string representation of type information) to a resolved
     * signature (actual Soy type).
     *
     * @param signature The input signature.
     * @param className The class name of the Soy function that has the signature annotation. This
     *     is also used as a fake file path in the reported error.
     * @param errorReporter The Soy error reporter.
     */
    private @Nullable ResolvedSignature getOrCreateFunctionSignature(
        Signature signature, String className, ErrorReporter errorReporter) {
      ResolvedSignature resolvedSignature = signatureMap.get(signature);
      if (resolvedSignature != null) {
        return resolvedSignature;
      }
      ImmutableList.Builder<SoyType> paramTypes = ImmutableList.builder();
      for (String paramTypeString : signature.parameterTypes()) {
        SoyType paramType =
            SoyFileParser.parseType(paramTypeString, typeRegistry, className, errorReporter);
        if (paramType == null) {
          return null;
        }
        paramTypes.add(paramType);
      }
      SoyType returnType =
          SoyFileParser.parseType(signature.returnType(), typeRegistry, className, errorReporter);
      if (returnType == null) {
        return null;
      }
      resolvedSignature = ResolvedSignature.create(paramTypes.build(), returnType);
      signatureMap.put(signature, resolvedSignature);
      return resolvedSignature;
    }

    @Override
    protected void visitFunctionNode(FunctionNode node) {
      visitChildren(node);
      Object knownFunction = node.getSoyFunction();
      if (knownFunction.getClass().isAnnotationPresent(SoyFunctionSignature.class)) {
        checkState(
            knownFunction instanceof TypedSoyFunction || knownFunction instanceof SoySourceFunction,
            "Classes annotated with @SoyFunctionSignature must either extend "
                + "TypedSoyFunction or implement SoySourceFunction.");
        visitSoyFunctionWithSignature(
            knownFunction.getClass().getAnnotation(SoyFunctionSignature.class),
            knownFunction.getClass().getCanonicalName(),
            node);
      } else if (knownFunction instanceof BuiltinFunction) {
        visitBuiltinFunction((BuiltinFunction) knownFunction, node);
      } else {
        visitSoyFunction(knownFunction, node);
      }
      tryApplySubstitution(node);
    }

    /**
     * For soy functions with type annotation, perform the strict type checking and set the return
     * type.
     */
    private void visitSoyFunctionWithSignature(
        SoyFunctionSignature fnSignature, String className, FunctionNode node) {
      ResolvedSignature matchedSignature = null;
      // Found the matched signature for the current function call.
      for (Signature signature : fnSignature.value()) {
        if (signature.parameterTypes().length == node.numChildren()) {
          matchedSignature = getOrCreateFunctionSignature(signature, className, errorReporter);
          break;
        }
      }
      // TODO(b/71386491): Maybe we should set this to ErrorType
      if (matchedSignature == null) {
        node.setType(UnknownType.getInstance());
        return;
      }
      for (int i = 0; i < node.numChildren(); ++i) {
        checkArgType(node.getChild(i), matchedSignature.parameterTypes().get(i), node);
      }
      node.setType(matchedSignature.returnType());
    }

    private void visitMapKeysFunction(FunctionNode node) {
      SoyType argType = node.getChild(0).getType();
      if (argType.equals(MapType.EMPTY_MAP)) {
        node.setType(ListType.EMPTY_LIST);
      } else {
        node.setType(typeRegistry.getOrCreateListType(((MapType) argType).getKeyType()));
      }
    }

    private void visitLegacyObjectMapToMapFunction(FunctionNode node) {
      SoyType argType = node.getChild(0).getType();
      if (argType.equals(LegacyObjectMapType.EMPTY_MAP)) {
        node.setType(MapType.EMPTY_MAP);
      } else if (argType.isAssignableFrom(UnknownType.getInstance())) {
        // Allow the type of the arg to be unknown as legacy_object_map functionality on unknown
        // types is allowed (i.e. bracket access on a variable with an unknown type).
        node.setType(
            typeRegistry.getOrCreateMapType(StringType.getInstance(), UnknownType.getInstance()));
      } else {
        LegacyObjectMapType actualArgType = (LegacyObjectMapType) argType;
        node.setType(
            typeRegistry.getOrCreateMapType(
                // Converting a legacy_object_map<K,V> to a map creates a value of type
                // map<string, V>, not map<K, V>. legacy_object_map<K, ...> is misleading:
                // although Soy will type check K consistently, the runtime implementation of
                // legacy_object_map just coerces the key to a string.
                // b/69051605 will change many Soy params to have a type of map<string, ...>,
                // so legacyObjectMapToMap() needs to have this return type too.
                StringType.getInstance(), actualArgType.getValueType()));
      }
    }

    private void visitMapToLegacyObjectMapFunction(FunctionNode node) {
      SoyType argType = node.getChild(0).getType();
      if (argType.equals(MapType.EMPTY_MAP)) {
        node.setType(LegacyObjectMapType.EMPTY_MAP);
      } else {
        MapType actualArgType = (MapType) argType;
        node.setType(
            typeRegistry.getOrCreateLegacyObjectMapType(
                // Converting a map to a legacy object map coerces all the keys to strings
                StringType.getInstance(), actualArgType.getValueType()));
      }
    }

    @Override
    protected void visitProtoInitNode(ProtoInitNode node) {
      visitChildren(node);

      String protoName = node.getProtoName();
      SoyType type = typeRegistry.getType(protoName);

      if (type == null) {
        errorReporter.report(node.getSourceLocation(), UNKNOWN_PROTO_TYPE, protoName);
        node.setType(ErrorType.getInstance());
      } else if (!(type.getKind() == SoyType.Kind.PROTO
          || type.getKind() == SoyType.Kind.PROTO_ENUM)) {
        errorReporter.report(node.getSourceLocation(), NOT_A_PROTO_TYPE, protoName, type);
        node.setType(ErrorType.getInstance());
      } else {
        node.setType(type);
      }
    }

    private void visitComparisonOpNode(AbstractOperatorNode node) {
      visitChildren(node);
      SoyType left = node.getChild(0).getType();
      SoyType right = node.getChild(1).getType();
      SoyType result =
          SoyTypes.getSoyTypeForBinaryOperator(left, right, new SoyTypes.SoyTypeComparisonOp());
      if (result == null) {
        errorReporter.report(node.getSourceLocation(), TYPE_MISMATCH, left, right);
      }
      node.setType(BoolType.getInstance());
    }

    private void visitEqualComparisonOpNode(AbstractOperatorNode node) {
      visitChildren(node);
      SoyType left = node.getChild(0).getType();
      SoyType right = node.getChild(1).getType();
      SoyType result =
          SoyTypes.getSoyTypeForBinaryOperator(
              left, right, new SoyTypes.SoyTypeEqualComparisonOp());
      if (result == null) {
        errorReporter.report(node.getSourceLocation(), TYPE_MISMATCH, left, right);
      }
      node.setType(BoolType.getInstance());
    }

    private void visitArithmeticOpNode(AbstractOperatorNode node) {
      visitChildren(node);
      boolean isDivide = node instanceof DivideByOpNode;
      SoyType left = node.getChild(0).getType();
      SoyType right = node.getChild(1).getType();
      SoyType result =
          SoyTypes.getSoyTypeForBinaryOperator(
              left, right, new SoyTypes.SoyTypeArithmeticOperator());
      if (result == null) {
        errorReporter.report(node.getSourceLocation(), INCOMPATIBLE_ARITHMETIC_OP, left, right);
        result = UnknownType.getInstance();
      }
      // Division is special. it is always coerced to a float. For other operators, use the value
      // returned by getSoyTypeForBinaryOperator.
      // TODO(b/64098780): Should we add nullability to divide operator? Probably not, but we should
      // also consolidate the behaviors when we divide something by 0 or null.
      node.setType(isDivide ? FloatType.getInstance() : result);
      tryApplySubstitution(node);
    }

    /**
     * Helper function that reports an error if a node's type field is {@code null}. The error will
     * show what kind of node it was, and where in the template it was found.
     */
    private void requireNodeType(ExprNode node) {
      if (node.getType() == null) {
        errorReporter.report(node.getSourceLocation(), MISSING_SOY_TYPE, node.getClass().getName());
      }
    }

    /**
     * Given a base type and a field name, compute the field type.
     *
     * @param baseType The base type.
     * @param fieldName The name of the field.
     * @param sourceLocation The source location of the expression
     * @return The type of the field.
     */
    private SoyType getFieldType(
        SoyType baseType, String fieldName, SourceLocation sourceLocation) {
      Preconditions.checkNotNull(baseType);
      switch (baseType.getKind()) {
        case UNKNOWN:
          // If we don't know anything about the base type, then make no assumptions
          // about the field type.
          return UnknownType.getInstance();

        case RECORD:
          {
            RecordType recordType = (RecordType) baseType;
            SoyType fieldType = recordType.getFieldType(fieldName);
            if (fieldType != null) {
              return fieldType;
            } else {
              String extraErrorMessage =
                  SoyErrors.getDidYouMeanMessage(recordType.getFieldNames(), fieldName);
              errorReporter.report(
                  sourceLocation,
                  UNDEFINED_FIELD_FOR_RECORD_TYPE,
                  fieldName,
                  baseType,
                  extraErrorMessage);
              return ErrorType.getInstance();
            }
          }

        case PROTO:
          {
            SoyProtoType protoType = (SoyProtoType) baseType;
            SoyType fieldType = protoType.getFieldType(fieldName);
            if (fieldType != null) {
              return fieldType;
            } else {
              String extraErrorMessage =
                  SoyErrors.getDidYouMeanMessageForProtoFields(
                      protoType.getFieldNames(), protoType.getDescriptor(), fieldName);
              errorReporter.report(
                  sourceLocation,
                  UNDEFINED_FIELD_FOR_PROTO_TYPE,
                  fieldName,
                  baseType,
                  extraErrorMessage);
              return ErrorType.getInstance();
            }
          }

        case LEGACY_OBJECT_MAP:
          {
            errorReporter.report(
                sourceLocation, DOT_ACCESS_NOT_SUPPORTED_CONSIDER_RECORD, baseType);
            return ErrorType.getInstance();
          }

        case UNION:
          {
            // If it's a union, then do the field type calculation for each member of
            // the union and combine the result.
            UnionType unionType = (UnionType) baseType;
            List<SoyType> fieldTypes = new ArrayList<>(unionType.getMembers().size());
            for (SoyType unionMember : unionType.getMembers()) {
              // TODO: Only exclude nulls when FieldAccessNode is null-safe.
              if (unionMember.getKind() == SoyType.Kind.NULL) {
                continue;
              }
              SoyType fieldType = getFieldType(unionMember, fieldName, sourceLocation);
              // If this member's field type resolved to an error, bail out to avoid spamming
              // the user with multiple error messages for the same line.
              if (fieldType == ErrorType.getInstance()) {
                return fieldType;
              }
              fieldTypes.add(fieldType);
            }
            return SoyTypes.computeLowestCommonType(typeRegistry, fieldTypes);
          }

        case ERROR:
          // report no additional errors
          return ErrorType.getInstance();

          // calling .length on strings/lists is common in v1 templates. So provide better error
          // messages for when users are migrating.
        case STRING:
        case CSS:
        case JS:
        case ATTRIBUTES:
        case HTML:
        case URI:
          if (fieldName.equals("length")) {
            errorReporter.report(sourceLocation, STRING_LENGTH_ERROR);
            return ErrorType.getInstance();
          }
          // else fall through

        case LIST:
          if (fieldName.equals("length")) {
            errorReporter.report(sourceLocation, LIST_LENGTH_ERROR);
            return ErrorType.getInstance();
          }
          // else fall through

        case ANY:
        case NULL:
        case BOOL:
        case INT:
        case FLOAT:
        case TRUSTED_RESOURCE_URI:
        case MAP:
        case PROTO_ENUM:
          errorReporter.report(sourceLocation, DOT_ACCESS_NOT_SUPPORTED, baseType);
          return ErrorType.getInstance();
      }
      throw new AssertionError("unhandled kind: " + baseType.getKind());
    }

    /** Given a base type and an item key type, compute the item value type. */
    private SoyType getItemType(
        SoyType baseType,
        SoyType keyType,
        boolean isNullSafe,
        SourceLocation baseLocation,
        SourceLocation keyLocation) {
      Preconditions.checkNotNull(baseType);
      Preconditions.checkNotNull(keyType);
      switch (baseType.getKind()) {
        case UNKNOWN:
          // If we don't know anything about the base type, then make no assumptions
          // about the item type.
          return UnknownType.getInstance();

        case LIST:
          ListType listType = (ListType) baseType;
          if (listType.equals(ListType.EMPTY_LIST)) {
            errorReporter.report(baseLocation, EMPTY_LIST_ACCESS);
            return ErrorType.getInstance();
          }

          // For lists, the key type must either be unknown or assignable to integer.
          if (keyType.getKind() != SoyType.Kind.UNKNOWN
              && !IntType.getInstance().isAssignableFrom(keyType)) {
            errorReporter.report(keyLocation, BAD_INDEX_TYPE, keyType, baseType);
            // fall through and report the element type.  This will allow more later type checks to
            // be evaluated.
          }
          return listType.getElementType();

        case LEGACY_OBJECT_MAP:
        case MAP:
          {
            AbstractMapType mapType = (AbstractMapType) baseType;
            if (mapType.equals(LegacyObjectMapType.EMPTY_MAP)
                || mapType.equals(MapType.EMPTY_MAP)) {
              errorReporter.report(baseLocation, EMPTY_MAP_ACCESS);
              return ErrorType.getInstance();
            }

            // For maps, the key type must either be unknown or assignable to the declared key type.
            if (keyType.getKind() != SoyType.Kind.UNKNOWN
                && !mapType.getKeyType().isAssignableFrom(keyType)) {
              errorReporter.report(keyLocation, BAD_KEY_TYPE, keyType, baseType);
              // fall through and report the value type.  This will allow more later type checks to
              // be evaluated.
            }
            return mapType.getValueType();
          }
        case UNION:
          {
            // If it's a union, then do the item type calculation for each member of
            // the union and combine the result.
            UnionType unionType = (UnionType) baseType;
            List<SoyType> itemTypes = new ArrayList<>(unionType.getMembers().size());
            for (SoyType unionMember : unionType.getMembers()) {
              // Skips null types for now.
              if (unionMember.equals(NullType.getInstance())) {
                continue;
              }
              SoyType itemType =
                  getItemType(unionMember, keyType, isNullSafe, baseLocation, keyLocation);
              // If this member's item type resolved to an error, bail out to avoid spamming
              // the user with multiple error messages for the same line.
              if (itemType == ErrorType.getInstance()) {
                return itemType;
              }
              itemTypes.add(itemType);
            }
            // If this is a nullable union type but the operation is not null-safe, report an error.
            if (unionType.isNullable() && !isNullSafe) {
              errorReporter.report(baseLocation, BRACKET_ACCESS_NULLABLE_UNION);
              return ErrorType.getInstance();
            }
            return SoyTypes.computeLowestCommonType(typeRegistry, itemTypes);
          }

        case ERROR:
          return ErrorType.getInstance();

        case ANY:
        case NULL:
        case BOOL:
        case INT:
        case FLOAT:
        case STRING:
        case HTML:
        case ATTRIBUTES:
        case JS:
        case CSS:
        case URI:
        case TRUSTED_RESOURCE_URI:
        case RECORD:
        case PROTO:
        case PROTO_ENUM:
          errorReporter.report(baseLocation, BRACKET_ACCESS_NOT_SUPPORTED, baseType);
          return ErrorType.getInstance();
      }
      throw new AssertionError("unhandled kind: " + baseType.getKind());
    }

    private void tryApplySubstitution(AbstractParentExprNode parentNode) {
      SoyType newType = getTypeSubstitution(parentNode);
      if (newType != null) {
        if (!parentNode.getType().isAssignableFrom(newType)) {
          errorReporter.report(
              parentNode.getSourceLocation(),
              INVALID_TYPE_SUBSTITUTION,
              parentNode.getType(),
              newType);
        }
        parentNode.setType(newType);
      }
    }

    @Nullable
    private SoyType getTypeSubstitution(ExprNode expr) {
      // If there's a type substitution in effect for this expression, then change
      // the type of the variable reference to the substituted type.
      for (TypeSubstitution subst = substitutions; subst != null; subst = subst.parent) {
        if (ExprEquivalence.get().equivalent(subst.expression, expr)) {
          return subst.type;
        }
      }
      return null;
    }

    /**
     * Private helper that checks types of the arguments and tries to set the return type for some
     * built-in functions.
     */
    private void visitBuiltinFunction(BuiltinFunction builtinFunction, FunctionNode node) {
      // Most non-plugin functions have exactly 1 arg
      ExprNode arg1 = node.getChild(0);
      switch (builtinFunction) {
        case CHECK_NOT_NULL:
          SoyType type = node.getChild(0).getType();
          if (type.equals(NullType.getInstance())) {
            errorReporter.report(node.getSourceLocation(), CHECK_NOT_NULL_ON_COMPILE_TIME_NULL);
          } else {
            // Same type as its child but with nulls removed
            node.setType(SoyTypes.removeNull(type));
          }
          break;
        case INDEX:
          requireLoopVariableInScope(node, arg1);
          node.setType(IntType.getInstance());
          break;
        case IS_PRIMARY_MSG_IN_USE:
          // don't bother checking the args, they are only ever set by the MsgIdFunctionPass
          node.setType(BoolType.getInstance());
          break;
        case IS_FIRST:
        case IS_LAST:
          requireLoopVariableInScope(node, arg1);
          node.setType(BoolType.getInstance());
          break;
        case CSS:
          checkArgIsStringLiteral(node.getChild(node.numChildren() - 1), "css");
          node.setType(StringType.getInstance());
          break;
        case XID:
          // arg validation is already handled by the XidPass
          node.setType(StringType.getInstance());
          break;
        case V1_EXPRESSION:
          checkArgIsStringLiteral(arg1, "v1Expression");
          node.setType(UnknownType.getInstance());
          break;
        case REMAINDER:
        case MSG_WITH_ID: // should have already been removed from the tree
          throw new AssertionError();
      }
    }

    /** Private helper that reports an error if the argument is not a string literal. */
    private void checkArgIsStringLiteral(ExprNode arg, String funcName) {
      if (!(arg instanceof StringNode)) {
        errorReporter.report(arg.getSourceLocation(), STRING_LITERAL_REQUIRED, funcName);
      }
    }

    /**
     * Private helper that checks types of the arguments and tries to set the return type for some
     * basic functions provided by Soy.
     */
    private void visitSoyFunction(Object fn, FunctionNode node) {
      // Here we have special handling for a variety of 'generic' function.
      if (fn instanceof LegacyObjectMapToMapFunction) {
        // If argument type is incorrect, do not try to create a return type. Instead, set the
        // return type to unknown.
        if (checkArgType(node.getChild(0), LegacyObjectMapType.ANY_MAP, node)) {
          visitLegacyObjectMapToMapFunction(node);
        } else {
          node.setType(UnknownType.getInstance());
        }
      } else if (fn instanceof MapToLegacyObjectMapFunction) {
        // If argument type is incorrect, do not try to create a return type. Instead, set the
        // return type to unknown.
        // We disallow unknown for this function in order to ensure that maps remain strongly typed
        if (checkArgType(node.getChild(0), MapType.ANY_MAP, node, UnknownPolicy.DISALLOWED)) {
          visitMapToLegacyObjectMapFunction(node);
        } else {
          node.setType(UnknownType.getInstance());
        }
      } else if (fn instanceof MapKeysFunction) {
        // We disallow unknown for this function in order to ensure that maps remain strongly typed
        if (checkArgType(node.getChild(0), MapType.ANY_MAP, node, UnknownPolicy.DISALLOWED)) {
          visitMapKeysFunction(node);
        } else {
          node.setType(UnknownType.getInstance());
        }
      } else if (fn instanceof ConcatListsFunction) {
        boolean allTypesValid = true;
        ImmutableSet.Builder<SoyType> elementTypesBuilder = ImmutableSet.builder();
        for (ExprNode childNode : node.getChildren()) {
          allTypesValid =
              checkArgType(childNode, ListType.ANY_LIST, node, UnknownPolicy.DISALLOWED);
          if (!allTypesValid) {
            break;
          }
          SoyType elementType = ((ListType) childNode.getType()).getElementType();
          if (elementType != null) { // Empty lists have no element type
            elementTypesBuilder.add(elementType);
          }
        }

        if (allTypesValid) {
          ImmutableSet<SoyType> elementTypes = elementTypesBuilder.build();
          node.setType(
              elementTypes.isEmpty()
                  ? ListType.EMPTY_LIST
                  : typeRegistry.getOrCreateListType(
                      typeRegistry.getOrCreateUnionType(elementTypes)));
        } else {
          node.setType(UnknownType.getInstance());
        }

      } else {
        // We have no way of knowing the return type of a function.
        // TODO: think about adding function type declarations.
        // TODO(b/70946095): at the very least we could hard code types for standard functions for
        // example, everything in the BasicFunctionsModule.
        // TODO(b/70946095): Maybe we should set to ErrorType if checkArgType failed.
        node.setType(UnknownType.getInstance());
      }
    }

    /** @param fn The function that must take a loop variable. */
    private void requireLoopVariableInScope(FunctionNode fn, ExprNode loopVariable) {
      if (!(loopVariable instanceof VarRefNode
          && ((VarRefNode) loopVariable).getDefnDecl() instanceof LoopVar)) {
        errorReporter.report(
            fn.getSourceLocation(), LOOP_VARIABLE_NOT_IN_SCOPE, fn.getFunctionName());
      }
    }

    /** Checks the argument type. Returns false if an incorrect arg type error was reported. */
    private boolean checkArgType(ExprNode arg, SoyType expectedType, FunctionNode node) {
      return checkArgType(arg, expectedType, node, UnknownPolicy.ALLOWED);
    }

    /** Checks the argument type. Returns false if an incorrect arg type error was reported. */
    private boolean checkArgType(
        ExprNode arg, SoyType expectedType, FunctionNode node, UnknownPolicy policy) {
      SoyType.Kind argTypeKind = arg.getType().getKind();
      if (argTypeKind == SoyType.Kind.ERROR || expectedType.getKind() == SoyType.Kind.ERROR) {
        return false;
      }
      if (policy == UnknownPolicy.ALLOWED && argTypeKind == SoyType.Kind.UNKNOWN) {
        return true;
      }
      if (!expectedType.isAssignableFrom(arg.getType())) {
        errorReporter.report(
            arg.getSourceLocation(),
            INCORRECT_ARG_TYPE,
            node.getFunctionName(),
            arg.getType(),
            expectedType);
        return false;
      }
      return true;
    }
  }

  /**
   * Visitor which analyzes a boolean expression and determines if any of the variables involved in
   * the expression can have their types narrowed depending on the outcome of the condition.
   *
   * <p>For example, if the condition is "$var != null", then we know that if the condition is true,
   * then $var cannot be of null type. We also know that if the condition is false, then $var must
   * be of the null type.
   *
   * <p>The result of the analysis is two "constraint maps", which map variables to types,
   * indicating that the variable satisfies the criteria of being of that type.
   *
   * <p>The "positiveConstraints" map is the set of constraints that will be satisfied if the
   * condition is true, the "negativeConstraints" is the set of constraints that will be satisfied
   * if the condition is false.
   *
   * <p>TODO(user) - support instanceof tests. Right now the only type tests supported are
   * comparisons with null. If we added an 'instanceof' operator to Soy, the combination of
   * instanceof + flow-based type analysis would effectively allow template authors to do typecasts,
   * without having to add a cast operator to the language.
   */
  private final class TypeNarrowingConditionVisitor extends AbstractExprNodeVisitor<Void> {
    // Type constraints that are valid if the condition is true.
    Map<Wrapper<ExprNode>, SoyType> positiveTypeConstraints = new LinkedHashMap<>();

    // Type constraints that are valid if the condition is false.
    Map<Wrapper<ExprNode>, SoyType> negativeTypeConstraints = new LinkedHashMap<>();

    @Override
    public Void exec(ExprNode node) {
      visit(node);
      return null;
    }

    @Override
    protected void visitExprRootNode(ExprRootNode node) {
      visitAndImplicitlyCastToBoolean(node.getRoot());
    }

    void visitAndImplicitlyCastToBoolean(ExprNode node) {
      // In places where the expression is implicitly cast to a boolean, treat
      // a reference to a variable as a comparison of that variable with null.
      // So for example an expression like {if $var} should be treated as
      // {if $var != null} but something like {if $var > 0} should not be changed.
      visit(node);
      Wrapper<ExprNode> wrapped = ExprEquivalence.get().wrap(node);
      positiveTypeConstraints.put(wrapped, SoyTypes.tryRemoveNull(node.getType()));
      // TODO(lukes): The 'negative' type constraint here is not optimal.  What we really know is
      // that the value of the expression is 'falsy' we could use that to inform later checks but
      // for now we just assume it has its normal type.
      negativeTypeConstraints.put(wrapped, node.getType());
    }

    @Override
    protected void visitAndOpNode(AndOpNode node) {
      Preconditions.checkArgument(node.getChildren().size() == 2);
      // Create two separate visitors to analyze each side of the expression.
      TypeNarrowingConditionVisitor leftVisitor = new TypeNarrowingConditionVisitor();
      TypeNarrowingConditionVisitor rightVisitor = new TypeNarrowingConditionVisitor();
      leftVisitor.visitAndImplicitlyCastToBoolean(node.getChild(0));
      rightVisitor.visitAndImplicitlyCastToBoolean(node.getChild(1));

      // Both the left side and right side constraints will be valid if the condition is true.
      positiveTypeConstraints.putAll(
          computeConstraintUnion(
              leftVisitor.positiveTypeConstraints, rightVisitor.positiveTypeConstraints));
      // If the condition is false, then the overall constraint is the intersection of
      // the complements of the true constraints.
      negativeTypeConstraints.putAll(
          computeConstraintIntersection(
              leftVisitor.negativeTypeConstraints, rightVisitor.negativeTypeConstraints));
    }

    @Override
    protected void visitOrOpNode(OrOpNode node) {
      Preconditions.checkArgument(node.getChildren().size() == 2);
      // Create two separate visitors to analyze each side of the expression.
      TypeNarrowingConditionVisitor leftVisitor = new TypeNarrowingConditionVisitor();
      TypeNarrowingConditionVisitor rightVisitor = new TypeNarrowingConditionVisitor();
      leftVisitor.visitAndImplicitlyCastToBoolean(node.getChild(0));
      rightVisitor.visitAndImplicitlyCastToBoolean(node.getChild(1));

      // If the condition is true, then only constraints that appear on both sides of the
      // operator will be valid.
      positiveTypeConstraints.putAll(
          computeConstraintIntersection(
              leftVisitor.positiveTypeConstraints, rightVisitor.positiveTypeConstraints));
      // If the condition is false, then both sides must be false, so the overall constraint
      // is the union of the complements of the constraints on each side.
      negativeTypeConstraints.putAll(
          computeConstraintUnion(
              leftVisitor.negativeTypeConstraints, rightVisitor.negativeTypeConstraints));
    }

    @Override
    protected void visitNotOpNode(NotOpNode node) {
      // For a logical not node, compute the positive and negative constraints of the
      // operand and then simply swap them.
      TypeNarrowingConditionVisitor childVisitor = new TypeNarrowingConditionVisitor();
      childVisitor.visitAndImplicitlyCastToBoolean(node.getChild(0));
      positiveTypeConstraints.putAll(childVisitor.negativeTypeConstraints);
      negativeTypeConstraints.putAll(childVisitor.positiveTypeConstraints);
    }

    @Override
    protected void visitEqualOpNode(EqualOpNode node) {
      if (node.getChild(1).getKind() == ExprNode.Kind.NULL_NODE) {
        Wrapper<ExprNode> wrappedExpr = ExprEquivalence.get().wrap(node.getChild(0));
        positiveTypeConstraints.put(wrappedExpr, NullType.getInstance());
        negativeTypeConstraints.put(
            wrappedExpr, SoyTypes.tryRemoveNull(wrappedExpr.get().getType()));
      } else if (node.getChild(0).getKind() == ExprNode.Kind.NULL_NODE) {
        Wrapper<ExprNode> wrappedExpr = ExprEquivalence.get().wrap(node.getChild(1));
        positiveTypeConstraints.put(wrappedExpr, NullType.getInstance());
        negativeTypeConstraints.put(
            wrappedExpr, SoyTypes.tryRemoveNull(wrappedExpr.get().getType()));
      }
      // Otherwise don't make any inferences (don't visit children).
    }

    @Override
    protected void visitNotEqualOpNode(NotEqualOpNode node) {
      if (node.getChild(1).getKind() == ExprNode.Kind.NULL_NODE) {
        Wrapper<ExprNode> wrappedExpr = ExprEquivalence.get().wrap(node.getChild(0));
        positiveTypeConstraints.put(
            wrappedExpr, SoyTypes.tryRemoveNull(wrappedExpr.get().getType()));
        negativeTypeConstraints.put(wrappedExpr, NullType.getInstance());
      } else if (node.getChild(0).getKind() == ExprNode.Kind.NULL_NODE) {
        Wrapper<ExprNode> wrappedExpr = ExprEquivalence.get().wrap(node.getChild(1));
        positiveTypeConstraints.put(
            wrappedExpr, SoyTypes.tryRemoveNull(wrappedExpr.get().getType()));
        negativeTypeConstraints.put(wrappedExpr, NullType.getInstance());
      }
      // Otherwise don't make any inferences (don't visit children).
    }

    @Override
    protected void visitNullCoalescingOpNode(NullCoalescingOpNode node) {
      // Don't make any inferences (don't visit children).
      // Note: It would be possible to support this case by expanding it into
      // if-statements.
    }

    @Override
    protected void visitConditionalOpNode(ConditionalOpNode node) {
      // Don't make any inferences (don't visit children).
      // Note: It would be possible to support this case by expanding it into
      // if-statements.
    }

    @Override
    protected void visitFunctionNode(FunctionNode node) {
      // Handle 'isNull(<expr>)' and 'isNonnull(<expr>)'.
      if (node.numChildren() != 1) {
        return;
      } else if (node.getFunctionName().equals("isNonnull")) {
        Wrapper<ExprNode> wrappedExpr = ExprEquivalence.get().wrap(node.getChild(0));
        positiveTypeConstraints.put(
            wrappedExpr, SoyTypes.tryRemoveNull(wrappedExpr.get().getType()));
        negativeTypeConstraints.put(wrappedExpr, NullType.getInstance());
      } else if (node.getFunctionName().equals("isNull")) {
        Wrapper<ExprNode> wrappedExpr = ExprEquivalence.get().wrap(node.getChild(0));
        positiveTypeConstraints.put(wrappedExpr, NullType.getInstance());
        negativeTypeConstraints.put(
            wrappedExpr, SoyTypes.tryRemoveNull(wrappedExpr.get().getType()));
      }
    }

    @Override
    protected void visitExprNode(ExprNode node) {
      if (node instanceof ParentExprNode) {
        visitChildren((ParentExprNode) node);
      }
    }

    /**
     * Compute a map which combines the constraints from both the left and right side of an
     * expression. The result should be a set of constraints which satisfy <strong>both</strong>
     * sides.
     *
     * @param left Constraints from the left side.
     * @param right Constraints from the right side.
     * @return The combined constraint.
     */
    private <T> Map<T, SoyType> computeConstraintUnion(
        Map<T, SoyType> left, Map<T, SoyType> right) {
      if (left.isEmpty()) {
        return right;
      }
      if (right.isEmpty()) {
        return left;
      }
      Map<T, SoyType> result = new LinkedHashMap<>(left);
      for (Map.Entry<T, SoyType> entry : right.entrySet()) {
        // The union of two constraints is a *stricter* constraint.
        // Thus "((a instanceof any) AND (a instanceof bool)) == (a instanceof bool)"
        if (left.containsKey(entry.getKey())) {
          // For now, it's sufficient that the map contains an entry for the variable
          // (since we're only testing for nullability). Once we add support for more
          // complex type tests, we'll need to add code here that combines the two
          // constraints.
        } else {
          result.put(entry.getKey(), entry.getValue());
        }
      }
      return result;
    }

    /**
     * Compute a map which combines the constraints from both the left and right side of an
     * expression. The result should be a set of constraints which satisfy <strong>either</strong>
     * sides.
     *
     * @param left Constraints from the left side.
     * @param right Constraints from the right side.
     * @return The combined constraint.
     */
    private Map<Wrapper<ExprNode>, SoyType> computeConstraintIntersection(
        Map<Wrapper<ExprNode>, SoyType> left, Map<Wrapper<ExprNode>, SoyType> right) {
      if (left.isEmpty()) {
        return left;
      }
      if (right.isEmpty()) {
        return right;
      }
      Map<Wrapper<ExprNode>, SoyType> result = Maps.newHashMapWithExpectedSize(left.size());
      for (Map.Entry<Wrapper<ExprNode>, SoyType> entry : left.entrySet()) {
        // A variable must be present in both the left and right sides in order to be
        // included in the output.
        if (right.containsKey(entry.getKey())) {
          // The intersection of two constraints is a *looser* constraint.
          // Thus "((a instanceof any) OR (a instanceof bool)) == (a instanceof any)"
          SoyType rightSideType = right.get(entry.getKey());
          result.put(
              entry.getKey(),
              SoyTypes.computeLowestCommonType(typeRegistry, entry.getValue(), rightSideType));
        }
      }
      return result;
    }
  }

  /** Whether or not we allow unknown values to be accepted implicitly. */
  private enum UnknownPolicy {
    ALLOWED,
    DISALLOWED;
  }

  /**
   * Class that is used to temporarily substitute the type of a variable.
   *
   * <p>Type substitution preferences are implemented via a custom stack in order for new
   * substitutions to override old ones. This means that lookups for type substitutions are linear
   * in the number of active substitutions. This should be fine because the stack depth is unlikely
   * to be >10. If we end up observing large stacks (100s of active substitutions), then we should
   * rewrite to a hashed data structure to make it faster to do negative lookups.
   */
  private static final class TypeSubstitution {
    /** Parent substitution. */
    @Nullable final TypeSubstitution parent;

    /** The expression whose type we are overriding. */
    final ExprNode expression;

    /** The new type of the variable. */
    final SoyType type;

    TypeSubstitution(@Nullable TypeSubstitution parent, ExprNode expression, SoyType type) {
      this.parent = parent;
      this.expression = expression;
      this.type = type;
    }
  }
}
