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

package com.google.template.soy.plugin.java.restricted;

/** The context for {@link SoyJavaSourceFunction}s and {@link SoyJavaSourcePrintDirective}s. */
public interface JavaPluginContext {
  /** A value that resolves the locale string at runtime. */
  JavaValue getLocaleString();

  /**
   * A value that resolves to the BidiGlobalDir at runtime. Only useful for internal Soy functions
   * or directives.
   */
  JavaValue getBidiDir();
}
