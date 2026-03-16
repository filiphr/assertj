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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import org.assertj.processor.api.SoftAssertionEntryPoint;

/**
 * Annotation processor that generates soft assertion entry point interfaces.
 * <p>
 * Scans for {@link SoftAssertionEntryPoint} annotations on Assert classes and generates:
 * <ul>
 *   <li>{@code GeneratedStandardSoftAssertionsProvider} with {@code assertThat()} methods</li>
 *   <li>{@code GeneratedBDDSoftAssertionsProvider} with {@code then()} methods</li>
 * </ul>
 */
@SupportedAnnotationTypes("org.assertj.processor.api.SoftAssertionEntryPoint")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class SoftAssertionProcessor extends AbstractProcessor {

  private static final String API_PACKAGE = "org.assertj.core.api";
  private static final ClassName SOFT_ASSERTIONS_PROVIDER = ClassName.get(API_PACKAGE, "SoftAssertionsProvider");
  private static final ClassName CHECK_RETURN_VALUE = ClassName.get("org.assertj.core.annotation", "CheckReturnValue");

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (annotations.isEmpty()) return false;

    List<EntryPointInfo> entryPoints = new ArrayList<>();

    for (Element element : roundEnv.getElementsAnnotatedWith(SoftAssertionEntryPoint.class)) {
      if (!(element instanceof TypeElement typeElement)) continue;

      SoftAssertionEntryPoint annotation = typeElement.getAnnotation(SoftAssertionEntryPoint.class);
      ClassName assertClass = ClassName.get(typeElement);
      TypeName actualType = TypeName.get(getActualTypeMirror(annotation));

      entryPoints.add(new EntryPointInfo(assertClass, actualType, annotation.methodName(), annotation.bddMethodName()));
    }

    if (!entryPoints.isEmpty()) {
      writeInterface("GeneratedStandardSoftAssertionsProvider", entryPoints, false);
      writeInterface("GeneratedBDDSoftAssertionsProvider", entryPoints, true);
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

  private void writeInterface(String interfaceName, List<EntryPointInfo> entryPoints, boolean bdd) {
    TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
        .addModifiers(Modifier.PUBLIC)
        .addSuperinterface(SOFT_ASSERTIONS_PROVIDER)
        .addAnnotation(CHECK_RETURN_VALUE);

    for (EntryPointInfo ep : entryPoints) {
      String methodName = bdd ? ep.bddMethodName : ep.methodName;
      MethodSpec method = MethodSpec.methodBuilder(methodName)
          .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
          .returns(ep.assertClass)
          .addParameter(ep.actualType, "actual")
          .addStatement("return proxy($T.class, $T.class, actual)", ep.assertClass, ep.actualType)
          .addJavadoc("Creates a new soft assertion instance of {@link $T}.\n", ep.assertClass)
          .addJavadoc("\n")
          .addJavadoc("@param actual the actual value.\n")
          .addJavadoc("@return the created assertion object.\n")
          .build();
      interfaceBuilder.addMethod(method);
    }

    JavaFile javaFile = JavaFile.builder(API_PACKAGE, interfaceBuilder.build())
        .indent("  ")
        .build();

    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed to generate " + interfaceName + ": " + e.getMessage());
    }
  }

  private record EntryPointInfo(ClassName assertClass, TypeName actualType, String methodName, String bddMethodName) {}
}
