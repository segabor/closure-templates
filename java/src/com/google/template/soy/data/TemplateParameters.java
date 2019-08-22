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

package com.google.template.soy.data;

import java.util.Map;

/**
 * An invocation of a Soy template, encapsulating both the template name and all the data parameters
 * passed to the template.
 */
public interface TemplateParameters {

  /** Returns the name of the Soy template that this params object renders. */
  String getTemplateName();

  /**
   * Returns the parameters as a map. This method is only intended to be called by the Soy
   * framework.
   */
  Map<String, SoyValueProvider> getParamsAsMap();
}
