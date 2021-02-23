/*
 * Copyright 2019 Google Inc.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.LocalVariable;
import com.google.template.soy.jbcsrc.restricted.Statement;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

@RunWith(JUnit4.class)
public final class SimpleLocalVariableManagerTest {

  @Test
  public void testReserveSlots() throws Exception {
    SimpleLocalVariableManager vars =
        new SimpleLocalVariableManager(
            BytecodeUtils.OBJECT.type(),
            new Method("foo", /*returnType=*/ Type.INT_TYPE, /*arguments=*/ new Type[] {}),
            /* isStatic=*/ true);

    LocalVariableManager.Scope outer = vars.enterScope();
    LocalVariableManager.Scope inner = vars.enterScope();
    LocalVariable foo = inner.createTemporary("foo", Type.INT_TYPE);
    assertThat(foo.index()).isEqualTo(0);
    assertThat(foo.resultType().getSize()).isEqualTo(1);

    LocalVariable bar = outer.createTemporary("bar", Type.INT_TYPE);
    assertThat(bar.index()).isEqualTo(1);
    assertThat(bar.resultType().getSize()).isEqualTo(1);

    Statement unused = inner.exitScope(); // will cause the slot for foo to be released
    // this is too big to fit in the whole left by foo
    LocalVariable baz = outer.createTemporary("bar", Type.LONG_TYPE);
    assertThat(baz.index()).isEqualTo(2);
    assertThat(baz.resultType().getSize()).isEqualTo(2);

    // ditto
    LocalVariable qux = outer.createTemporary("qux", Type.LONG_TYPE);
    assertThat(qux.index()).isEqualTo(4);
    assertThat(qux.resultType().getSize()).isEqualTo(2);

    // but this can fit
    LocalVariable quux = outer.createTemporary("qux", Type.INT_TYPE);
    assertThat(quux.index()).isEqualTo(0);
    assertThat(quux.resultType().getSize()).isEqualTo(1);
  }

  @Test
  public void testParameters() {
    SimpleLocalVariableManager vars =
        new SimpleLocalVariableManager(
            BytecodeUtils.OBJECT.type(),
            new Method(
                "foo",
                /*returnType=*/ Type.INT_TYPE,
                /*arguments=*/ new Type[] {Type.getType(String.class), Type.getType(int.class)}),
            ImmutableList.of("baz", "quux"),
            new Label(),
            new Label(),
            /* isStatic=*/ false);
    LocalVariable thisVar = (LocalVariable) vars.getVariable("this");
    assertThat(thisVar.index()).isEqualTo(0);
    assertThat(thisVar.variableName()).isEqualTo("this");

    LocalVariable baz = (LocalVariable) vars.getVariable("baz");
    assertThat(baz.index()).isEqualTo(1);
    assertThat(baz.variableName()).isEqualTo("baz");

    LocalVariable quux = (LocalVariable) vars.getVariable("quux");
    assertThat(quux.index()).isEqualTo(2);
    assertThat(quux.variableName()).isEqualTo("quux");
  }
}
