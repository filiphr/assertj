/*
 * Copyright 2012-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.assertj.processor.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an Assert class for soft assertion entry point generation.
 * <p>
 * When an Assert class is annotated with this annotation, the annotation processor will generate
 * corresponding {@code assertThat()} and {@code then()} methods in the soft assertion provider interfaces.
 * <p>
 * Example:
 * <pre><code>
 * &#064;SoftAssertionEntryPoint(actualType = String.class)
 * public class StringAssert extends AbstractStringAssert&lt;StringAssert&gt; {
 *   public StringAssert(String actual) {
 *     super(actual, StringAssert.class);
 *   }
 * }
 * </code></pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface SoftAssertionEntryPoint {

  /**
   * The type of the actual value that this Assert class wraps.
   * This is used to generate the parameter type for the {@code assertThat()} method.
   *
   * @return the actual value type
   */
  Class<?> actualType();

  /**
   * The method name for the standard soft assertions entry point.
   * Defaults to "assertThat".
   *
   * @return the entry point method name
   */
  String methodName() default "assertThat";

  /**
   * The method name for the BDD soft assertions entry point.
   * Defaults to "then".
   *
   * @return the BDD entry point method name
   */
  String bddMethodName() default "then";
}
