/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.template.soy.jbcsrc.StandardNames.IJ_FIELD;
import static com.google.template.soy.jbcsrc.StandardNames.PARAMS_FIELD;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.LOGGING_ADVISING_APPENDABLE_TYPE;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.NULLARY_INIT;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.OBJECT;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.RENDER_CONTEXT_TYPE;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.SOY_RECORD_TYPE;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.constantSanitizedContentKindAsContentKind;
import static com.google.template.soy.jbcsrc.restricted.LocalVariable.createLocal;
import static com.google.template.soy.jbcsrc.restricted.LocalVariable.createThisVar;
import static com.google.template.soy.soytree.SoyTreeUtils.getAllNodesOfType;

import com.google.auto.value.AutoAnnotation;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.AbstractLocalVarDefn;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.exprtree.VarDefn.Kind;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.exprtree.VeLiteralNode;
import com.google.template.soy.jbcsrc.ExpressionCompiler.BasicExpressionCompiler;
import com.google.template.soy.jbcsrc.SoyNodeCompiler.CompiledMethodBody;
import com.google.template.soy.jbcsrc.internal.ClassData;
import com.google.template.soy.jbcsrc.internal.InnerClasses;
import com.google.template.soy.jbcsrc.internal.SoyClassWriter;
import com.google.template.soy.jbcsrc.restricted.AnnotationRef;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.FieldRef;
import com.google.template.soy.jbcsrc.restricted.LocalVariable;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.SoyExpression;
import com.google.template.soy.jbcsrc.restricted.SoyRuntimeType;
import com.google.template.soy.jbcsrc.restricted.Statement;
import com.google.template.soy.jbcsrc.restricted.TypeInfo;
import com.google.template.soy.jbcsrc.shared.CompiledTemplate;
import com.google.template.soy.jbcsrc.shared.TemplateMetadata;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamValueNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.LetValueNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.TemplateHeaderVarDefn;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.SoyTypes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

/**
 * Compiles the top level {@link CompiledTemplate} class for a single template and all related
 * classes.
 */
final class TemplateCompiler {
  private static final AnnotationRef<TemplateMetadata> TEMPLATE_METADATA_REF =
      AnnotationRef.forType(TemplateMetadata.class);
  private static final TypeInfo TEMPLATE_TYPE = TypeInfo.create(CompiledTemplate.class);

  private final CompiledTemplateRegistry registry;
  // lazily allocated
  private FieldRef paramsField;
  // lazily allocated
  private FieldRef ijField;
  private final FieldManager fields;
  private final ImmutableMap<String, FieldRef> paramFields;
  private final CompiledTemplateMetadata template;
  private final TemplateNode templateNode;
  private final InnerClasses innerClasses;
  private final ErrorReporter reporter;
  private final SoyTypeRegistry soyTypeRegistry;
  private SoyClassWriter writer;

  TemplateCompiler(
      CompiledTemplateRegistry registry,
      CompiledTemplateMetadata template,
      TemplateNode templateNode,
      ErrorReporter reporter,
      SoyTypeRegistry soyTypeRegistry) {
    this.registry = registry;
    this.template = template;
    this.templateNode = templateNode;
    this.innerClasses = new InnerClasses(template.typeInfo());
    this.fields = new FieldManager(template.typeInfo());

    ImmutableMap.Builder<String, FieldRef> builder = ImmutableMap.builder();
    for (TemplateParam param : templateNode.getAllParams()) {
      String name = param.name();
      builder.put(
          name,
          shouldResolveParamValueInConstructor(param)
              ? fields.addFinalField(name, BytecodeUtils.SOY_VALUE_PROVIDER_TYPE).asNonNull()
              : fields.addField(name, BytecodeUtils.SOY_VALUE_PROVIDER_TYPE).asNonNull());
    }
    this.paramFields = builder.build();
    this.reporter = reporter;
    this.soyTypeRegistry = soyTypeRegistry;
  }

  private static boolean shouldResolveParamValueInConstructor(TemplateParam param) {
    // Template-type and VE literal (with metadata) params with defaults need access to a
    // RenderContext to resolve, so we resolve them in the render method, not the constructor.
    return !(param.hasDefault()
        && (SoyTypes.transitivelyContainsKind(param.type(), SoyType.Kind.TEMPLATE)
            || hasVeMetadataDefault(param)));
  }

  private static boolean hasVeMetadataDefault(TemplateParam param) {
    if (param.hasDefault()) {
      List<VeLiteralNode> veChildren =
          SoyTreeUtils.getAllNodesOfType(param.defaultValue(), VeLiteralNode.class);
      for (VeLiteralNode ve : veChildren) {
        if (ve.getLoggableElement().hasMetadata()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns the list of classes needed to implement this template.
   *
   * <p>For each template, we generate:
   *
   * <ul>
   *   <li>A {@link com.google.template.soy.jbcsrc.shared.CompiledTemplate.Factory}
   *   <li>A {@link CompiledTemplate}
   *   <li>A DetachableSoyValueProvider subclass for each {@link LetValueNode} and {@link
   *       CallParamValueNode}
   *   <li>A DetachableContentProvider subclass for each {@link LetContentNode} and {@link
   *       CallParamContentNode}
   *       <p>Note: This will <em>not</em> generate classes for other templates, only the template
   *       configured in the constructor. But it will generate classes that <em>reference</em> the
   *       classes that are generated for other templates. It is the callers responsibility to
   *       ensure that all referenced templates are generated and available in the classloader that
   *       ultimately loads the returned classes.
   */
  Iterable<ClassData> compile() {
    List<ClassData> classes = new ArrayList<>();

    // first generate the factory
    // TODO(lukes): consider conditionally generating the factory only if it is referenced as a
    // template literal; this would reduce the amount of generated code.
    new TemplateFactoryCompiler(template, templateNode, innerClasses, templateNode.getVisibility())
        .compile();

    // TODO(lukes): change the flow of this method so these methods return method bodies and we only
    // write the methods to the writer after generating everything.
    // this should make the order of operations clearer and limit access to the writer.
    writer =
        SoyClassWriter.builder(template.typeInfo())
            .setAccess(Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL)
            .implementing(TEMPLATE_TYPE)
            .sourceFileName(templateNode.getSourceLocation().getFileName())
            .build();
    BasicExpressionCompiler constantCompiler =
        ExpressionCompiler.createConstantCompiler(
            new SimpleLocalVariableManager(BytecodeUtils.CLASS_INIT, /* isStatic=*/ true),
            fields,
            reporter,
            soyTypeRegistry,
            registry);
    generateTemplateMetadata();
    generateKindMethod();

    generateRenderMethod(constantCompiler);

    generateConstructor(constantCompiler);

    innerClasses.registerAllInnerClasses(writer);
    fields.defineFields(writer);
    fields.defineStaticInitializer(writer);

    writer.visitEnd();

    classes.add(writer.toClassData());
    classes.addAll(innerClasses.getInnerClassData());
    writer = null;
    return classes;
  }

  private void generateKindMethod() {
    Statement.returnExpression(
            constantSanitizedContentKindAsContentKind(templateNode.getContentKind()))
        .writeMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, template.kindMethod().method(), writer);
  }

  /** Writes a {@link TemplateMetadata} to the generated class. */
  private void generateTemplateMetadata() {
    String kind = templateNode.getContentKind().name();

    // using linked hash sets below for determinism
    Set<String> uniqueIjs = new LinkedHashSet<>();
    for (VarRefNode var : getAllNodesOfType(templateNode, VarRefNode.class)) {
      if (var.isInjected()) {
        uniqueIjs.add(var.getName());
      }
    }

    Set<String> callees = new LinkedHashSet<>();
    for (TemplateLiteralNode templateLiteralNode :
        getAllNodesOfType(templateNode, TemplateLiteralNode.class)) {
      callees.add(Preconditions.checkNotNull(templateLiteralNode.getResolvedName()));
    }

    Set<String> delCallees = new LinkedHashSet<>();
    for (CallDelegateNode call : getAllNodesOfType(templateNode, CallDelegateNode.class)) {
      delCallees.add(Preconditions.checkNotNull(call.getDelCalleeName()));
    }

    TemplateMetadata.DelTemplateMetadata deltemplateMetadata;
    if (templateNode.getKind() == SoyNode.Kind.TEMPLATE_DELEGATE_NODE) {
      TemplateDelegateNode delegateNode = (TemplateDelegateNode) templateNode;
      deltemplateMetadata =
          createDelTemplateMetadata(
              nullToEmpty(delegateNode.getDelPackageName()),
              delegateNode.getDelTemplateName(),
              delegateNode.getDelTemplateVariant());
    } else {
      deltemplateMetadata = createDefaultDelTemplateMetadata();
    }
    Set<String> namespaces = Sets.newLinkedHashSet();
    // This ordering is critical to preserve css hierarchy.
    namespaces.addAll(templateNode.getParent().getRequiredCssNamespaces());
    namespaces.addAll(templateNode.getRequiredCssNamespaces());
    TemplateMetadata metadata =
        createTemplateMetadata(
            kind, namespaces, uniqueIjs, callees, delCallees, deltemplateMetadata);
    TEMPLATE_METADATA_REF.write(metadata, writer);
  }

  @AutoAnnotation
  static TemplateMetadata createTemplateMetadata(
      String contentKind,
      Set<String> requiredCssNames,
      Set<String> injectedParams,
      Set<String> callees,
      Set<String> delCallees,
      TemplateMetadata.DelTemplateMetadata deltemplateMetadata) {
    return new AutoAnnotation_TemplateCompiler_createTemplateMetadata(
        contentKind, requiredCssNames, injectedParams, callees, delCallees, deltemplateMetadata);
  }

  @AutoAnnotation
  static TemplateMetadata.DelTemplateMetadata createDefaultDelTemplateMetadata() {
    return new AutoAnnotation_TemplateCompiler_createDefaultDelTemplateMetadata();
  }

  @AutoAnnotation
  static TemplateMetadata.DelTemplateMetadata createDelTemplateMetadata(
      String delPackage, String name, String variant) {
    return new AutoAnnotation_TemplateCompiler_createDelTemplateMetadata(delPackage, name, variant);
  }

  private void generateRenderMethod(BasicExpressionCompiler constantCompiler) {
    final Label start = new Label();
    final Label end = new Label();
    final LocalVariable thisVar = createThisVar(template.typeInfo(), start, end);
    final LocalVariable appendableVar =
        createLocal("appendable", 1, LOGGING_ADVISING_APPENDABLE_TYPE, start, end).asNonNullable();
    final LocalVariable contextVar =
        createLocal("context", 2, RENDER_CONTEXT_TYPE, start, end).asNonNullable();
    final TemplateVariableManager variableSet =
        new TemplateVariableManager(fields, thisVar, template.renderMethod().method());
    TemplateVariables variables =
        new TemplateVariables(variableSet, thisVar, new RenderContextExpression(contextVar));
    // We skipped resolving default values for template-type parameters earlier, but now we have
    // access to the RenderContext and can do so.
    ExtraCodeCompiler resolveDefaultValuesForTemplateParams =
        (expressionCompiler, appendable) -> {
          List<Statement> statements = new ArrayList<>();
          for (TemplateParam param : templateNode.getAllParams()) {
            if (!shouldResolveParamValueInConstructor(param)) {
              Optional<SoyExpression> defaultExpression =
                  expressionCompiler.compileWithNoDetaches(param.defaultValue());
              checkState(
                  defaultExpression.isPresent(),
                  "Default expression unexpectedly required detachment");
              Expression paramProvider =
                  getParam(
                      variables.getParamsRecordField().accessor(thisVar),
                      variables.getIjRecordField().accessor(thisVar),
                      param,
                      defaultExpression.get());
              statements.add(
                  paramFields.get(param.name()).putInstanceField(thisVar, paramProvider));
            }
          }
          return Statement.concat(statements);
        };
    final CompiledMethodBody methodBody =
        SoyNodeCompiler.create(
                registry,
                innerClasses,
                thisVar,
                AppendableExpression.forLocal(appendableVar),
                variableSet,
                variables,
                fields,
                constantCompiler,
                reporter,
                soyTypeRegistry)
            .compile(
                templateNode,
                /* prefix= */ resolveDefaultValuesForTemplateParams,
                /* suffix= */ ExtraCodeCompiler.NO_OP);
    final Statement returnDone = Statement.returnExpression(MethodRef.RENDER_RESULT_DONE.invoke());
    new Statement() {
      @Override
      protected void doGen(CodeBuilder adapter) {
        adapter.mark(start);
        methodBody.body().gen(adapter);
        adapter.mark(end);
        returnDone.gen(adapter);

        thisVar.tableEntry(adapter);
        appendableVar.tableEntry(adapter);
        contextVar.tableEntry(adapter);
        variableSet.generateTableEntries(adapter);
      }
    }.writeIOExceptionMethod(Opcodes.ACC_PUBLIC, template.renderMethod().method(), writer);
    writer.setNumDetachStates(methodBody.numberOfDetachStates());
  }

  private SoyExpression getDefaultValueVarRef(
      TemplateHeaderVarDefn headerVar, BasicExpressionCompiler constantCompiler) {
    SoyExpression varRef;
    if (headerVar.defaultValue().getType() == NullType.getInstance()) {
      // a special case for null to avoid poor handling elsewhere in the compiler.
      varRef =
          SoyExpression.forSoyValue(
              headerVar.type(),
              BytecodeUtils.constantNull(
                  SoyRuntimeType.getBoxedType(headerVar.type()).runtimeType()));
    } else {
      varRef = constantCompiler.compile(headerVar.defaultValue());
    }
    if (!varRef.isCheap()) {
      FieldRef ref;
      if (headerVar.kind() == Kind.STATE) {
        // State fields are package private so that lazy closures can access them directly.
        ref = fields.addPackagePrivateStaticField(headerVar.name(), varRef);
      } else {
        ref = fields.addStaticField("default$" + headerVar.name(), varRef);
      }
      varRef = varRef.withSource(ref.accessor());
    }
    return varRef;
  }

  /**
   * Generate a public constructor that assigns our final field and checks for missing required
   * params.
   *
   * <p>This constructor is called by the generated factory classes.
   */
  private void generateConstructor(BasicExpressionCompiler constantCompiler) {
    final Label start = new Label();
    final Label end = new Label();
    final LocalVariable thisVar = createThisVar(template.typeInfo(), start, end);
    final LocalVariable paramsVar = createLocal("params", 1, SOY_RECORD_TYPE, start, end);
    final LocalVariable ijVar = createLocal("ij", 2, SOY_RECORD_TYPE, start, end);
    final List<Statement> assignments = new ArrayList<>();
    // these aren't always needed, so we lazily allocate the fields
    if (paramsField != null) {
      assignments.add(paramsField.putInstanceField(thisVar, paramsVar));
    }
    if (ijField != null) {
      assignments.add(ijField.putInstanceField(thisVar, ijVar));
    }
    for (TemplateParam param : templateNode.getAllParams()) {
      // Constructing template types requires a render context, so skip all template types here.
      if (shouldResolveParamValueInConstructor(param)) {
        Expression paramProvider =
            getParam(
                paramsVar,
                ijVar,
                param,
                /* defaultValue=*/ param.hasDefault()
                    ? getDefaultValueVarRef(param, constantCompiler)
                    : null);
        assignments.add(paramFields.get(param.name()).putInstanceField(thisVar, paramProvider));
      }
    }
    Statement constructorBody =
        new Statement() {
          @Override
          protected void doGen(CodeBuilder ga) {
            ga.mark(start);
            // call super()
            thisVar.gen(ga);
            ga.invokeConstructor(OBJECT.type(), NULLARY_INIT);
            for (Statement assignment : assignments) {
              assignment.gen(ga);
            }
            ga.visitInsn(Opcodes.RETURN);
            ga.visitLabel(end);
            thisVar.tableEntry(ga);
            paramsVar.tableEntry(ga);
            ijVar.tableEntry(ga);
          }
        };
    constructorBody.writeMethod(Opcodes.ACC_PUBLIC, template.constructor().method(), writer);
  }

  /**
   * Returns an expression that fetches the given param from the params record or the ij record and
   * enforces the {@link TemplateParam#isRequired()} flag, throwing SoyDataException if a required
   * parameter is missing.
   */
  private static Expression getParam(
      Expression paramsVar,
      Expression ijVar,
      TemplateParam param,
      @Nullable SoyExpression defaultValue) {
    Expression fieldName = BytecodeUtils.constant(param.name());
    Expression record = param.isInjected() ? ijVar : paramsVar;
    // NOTE: for compatibility with Tofu and jssrc we do not check for missing required parameters
    // here instead they will just turn into null.  Existing templates depend on this.
    if (defaultValue == null) {
      return MethodRef.RUNTIME_GET_FIELD_PROVIDER.invoke(record, fieldName);
    } else {
      return MethodRef.RUNTIME_GET_FIELD_PROVIDER_DEFAULT.invoke(
          record, fieldName, defaultValue.box());
    }
  }

  private final class TemplateVariables extends AbstractTemplateParameterLookup {
    private final TemplateVariableManager variableSet;
    private final Expression thisRef;
    private final RenderContextExpression renderContext;

    TemplateVariables(
        TemplateVariableManager variableSet,
        Expression thisRef,
        RenderContextExpression renderContext) {
      this.variableSet = variableSet;
      this.thisRef = thisRef;
      this.renderContext = renderContext;
    }

    @Override
    FieldRef getParamField(TemplateParam param) {
      return paramFields.get(param.name());
    }

    // allocate these lazily since they are only needed in certain cases (ij is needed for all
    // calls, but not every template has a call, params is needed for data="all" style calls, but
    // not all templates have those)
    @Override
    FieldRef getParamsRecordField() {
      if (paramsField == null) {
        paramsField = fields.addFinalField(PARAMS_FIELD, BytecodeUtils.SOY_RECORD_TYPE).asNonNull();
      }
      return paramsField;
    }

    @Override
    FieldRef getIjRecordField() {
      if (ijField == null) {
        ijField = fields.addFinalField(IJ_FIELD, BytecodeUtils.SOY_RECORD_TYPE).asNonNull();
      }
      return ijField;
    }

    @Override
    public Expression getLocal(AbstractLocalVarDefn<?> local) {
      return variableSet.getVariable(local.name());
    }

    @Override
    public Expression getLocal(SyntheticVarName varName) {
      return variableSet.getVariable(varName);
    }

    @Override
    public RenderContextExpression getRenderContext() {
      return renderContext;
    }

    @Override
    Expression getCompiledTemplate() {
      return thisRef;
    }
  }
}
