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
 * Placed on the {@code Assertions} entry point class to trigger generation of soft assertion provider interfaces.
 * <p>
 * The processor scans all {@code public static} methods in the annotated class that return an
 * {@code AbstractAssert} subtype, and generates corresponding {@code default} methods in:
 * <ul>
 *   <li>{@code GeneratedStandardSoftAssertionsProvider} — same method names (typically {@code assertThat})</li>
 *   <li>{@code GeneratedBDDSoftAssertionsProvider} — with {@code then} method names</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateSoftAssertions {
}
