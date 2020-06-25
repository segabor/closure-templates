/*
 * Copyright 2020 Google Inc.
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

package com.google.template.soy.types;

import com.google.template.soy.types.RecordType.Member;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * Implementation of {@link SoyTypeRegistry} that delegates all calls to another instance of {@link
 * SoyTypeRegistry}. Used for building chains of registries.
 */
public abstract class DelegatingSoyTypeRegistry implements SoyTypeRegistry {

  private final SoyTypeRegistry delegate;

  protected DelegatingSoyTypeRegistry(SoyTypeRegistry delegate) {
    this.delegate = delegate;
  }

  @Override
  public ListType getOrCreateListType(SoyType elementType) {
    return delegate.getOrCreateListType(elementType);
  }

  @Override
  public LegacyObjectMapType getOrCreateLegacyObjectMapType(SoyType keyType, SoyType valueType) {
    return delegate.getOrCreateLegacyObjectMapType(keyType, valueType);
  }

  @Override
  public MapType getOrCreateMapType(SoyType keyType, SoyType valueType) {
    return delegate.getOrCreateMapType(keyType, valueType);
  }

  @Override
  public SoyType getOrCreateUnionType(Collection<SoyType> members) {
    return delegate.getOrCreateUnionType(members);
  }

  @Override
  public SoyType getOrCreateUnionType(SoyType... members) {
    return delegate.getOrCreateUnionType(members);
  }

  @Override
  public RecordType getOrCreateRecordType(Iterable<Member> members) {
    return delegate.getOrCreateRecordType(members);
  }

  @Override
  public TemplateType internTemplateType(TemplateType typeToIntern) {
    return delegate.internTemplateType(typeToIntern);
  }

  @Override
  public VeType getOrCreateVeType(String dataType) {
    return delegate.getOrCreateVeType(dataType);
  }

  @Override
  @Nullable
  public SoyType getType(String typeName) {
    return delegate.getType(typeName);
  }

  @Override
  public String findTypeWithMatchingNamespace(String prefix) {
    return delegate.findTypeWithMatchingNamespace(prefix);
  }

  @Override
  public Iterable<String> getAllSortedTypeNames() {
    return delegate.getAllSortedTypeNames();
  }
}
