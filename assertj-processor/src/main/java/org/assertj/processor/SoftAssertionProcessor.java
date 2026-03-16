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
package org.assertj.processor;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import org.assertj.processor.api.SoftAssertionEntryPoint;

/**
 * Annotation processor that generates soft assertion entry point interfaces.
 * <p>
 * Scans for {@link SoftAssertionEntryPoint} annotations on Assert classes and generates:
 * <ul>
 *   <li>{@code GeneratedStandardSoftAssertionsProvider} - interface with {@code assertThat()} methods</li>
 *   <li>{@code GeneratedBDDSoftAssertionsProvider} - interface with {@code then()} methods</li>
 * </ul>
 */
@SupportedAnnotationTypes("org.assertj.processor.api.SoftAssertionEntryPoint")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class SoftAssertionProcessor extends AbstractProcessor {

  private static final String PACKAGE = "org.assertj.core.api";
  private static final String STANDARD_PROVIDER = "GeneratedStandardSoftAssertionsProvider";
  private static final String BDD_PROVIDER = "GeneratedBDDSoftAssertionsProvider";

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (annotations.isEmpty()) return false;

    List<EntryPointInfo> entryPoints = new ArrayList<>();

    for (Element element : roundEnv.getElementsAnnotatedWith(SoftAssertionEntryPoint.class)) {
      if (!(element instanceof TypeElement typeElement)) continue;

      SoftAssertionEntryPoint annotation = typeElement.getAnnotation(SoftAssertionEntryPoint.class);
      String assertClassName = typeElement.getQualifiedName().toString();
      String simpleAssertName = typeElement.getSimpleName().toString();

      // Get the actual type via MirroredTypeException pattern
      TypeMirror actualTypeMirror = getActualTypeMirror(annotation);
      String actualTypeName = actualTypeMirror.toString();

      entryPoints.add(new EntryPointInfo(
          assertClassName, simpleAssertName, actualTypeName,
          annotation.methodName(), annotation.bddMethodName()));
    }

    if (!entryPoints.isEmpty()) {
      generateProvider(STANDARD_PROVIDER, entryPoints, false);
      generateProvider(BDD_PROVIDER, entryPoints, true);
    }

    return true;
  }

  private TypeMirror getActualTypeMirror(SoftAssertionEntryPoint annotation) {
    try {
      annotation.actualType();
      throw new IllegalStateException("Expected MirroredTypeException");
    } catch (MirroredTypeException e) {
      return e.getTypeMirror();
    }
  }

  private void generateProvider(String className, List<EntryPointInfo> entryPoints, boolean bdd) {
    String qualifiedName = PACKAGE + "." + className;

    try {
      JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName);
      try (PrintWriter out = new PrintWriter(file.openWriter())) {
        out.println("/*");
        out.println(" * Licensed under the Apache License, Version 2.0 (the \"License\");");
        out.println(" * you may not use this file except in compliance with the License.");
        out.println(" * You may obtain a copy of the License at");
        out.println(" *");
        out.println(" * https://www.apache.org/licenses/LICENSE-2.0");
        out.println(" */");
        out.println("package " + PACKAGE + ";");
        out.println();
        out.println("import javax.annotation.processing.Generated;");
        out.println();
        out.println("import org.assertj.core.annotation.CheckReturnValue;");
        out.println();
        out.println("/**");
        out.println(" * Generated soft assertions provider interface.");
        if (bdd) {
          out.println(" * Provides {@code then()} entry point methods for BDD-style soft assertions.");
        } else {
          out.println(" * Provides {@code assertThat()} entry point methods for soft assertions.");
        }
        out.println(" */");
        out.println("@Generated(\"org.assertj.processor.SoftAssertionProcessor\")");
        out.println("@CheckReturnValue");
        out.println("public interface " + className + " extends SoftAssertionsProvider {");
        out.println();

        for (EntryPointInfo ep : entryPoints) {
          String methodName = bdd ? ep.bddMethodName : ep.methodName;
          out.println("  /**");
          out.println("   * Creates a new soft assertion instance of {@link " + ep.simpleAssertName + "}.");
          out.println("   *");
          out.println("   * @param actual the actual value.");
          out.println("   * @return the created assertion object.");
          out.println("   */");
          out.println("  default " + ep.simpleAssertName + " " + methodName + "(" + ep.actualTypeName + " actual) {");
          out.println("    " + ep.simpleAssertName + " assertion = new " + ep.simpleAssertName + "(actual);");
          out.println("    assertion.softAssertionCollector = this;");
          out.println("    return assertion;");
          out.println("  }");
          out.println();
        }

        out.println("}");
      }
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed to generate " + qualifiedName + ": " + e.getMessage());
    }
  }

  private record EntryPointInfo(
      String assertClassName,
      String simpleAssertName,
      String actualTypeName,
      String methodName,
      String bddMethodName) {
  }
}
