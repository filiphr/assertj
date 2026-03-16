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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;

import org.assertj.processor.api.GenerateSoftAssertions;

/**
 * Annotation processor that generates soft assertion entry point interfaces by scanning
 * the {@code Assertions} class annotated with {@link GenerateSoftAssertions}.
 * <p>
 * For each {@code public static} method returning an {@code AbstractAssert} subtype, generates
 * corresponding {@code default} methods in:
 * <ul>
 *   <li>{@code GeneratedStandardSoftAssertionsProvider} — same method names</li>
 *   <li>{@code GeneratedBDDSoftAssertionsProvider} — with {@code then}/{@code thenCode}/{@code thenThrownBy} names</li>
 * </ul>
 */
@SupportedAnnotationTypes("org.assertj.processor.api.GenerateSoftAssertions")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class SoftAssertionProcessor extends AbstractProcessor {

  private static final String API_PACKAGE = "org.assertj.core.api";
  private static final ClassName SOFT_ASSERTIONS_PROVIDER = ClassName.get(API_PACKAGE, "SoftAssertionsProvider");
  private static final ClassName CHECK_RETURN_VALUE = ClassName.get("org.assertj.core.annotation", "CheckReturnValue");

  private static final String LICENSE_HEADER = """
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
      """;


  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (annotations.isEmpty()) return false;

    for (Element element : roundEnv.getElementsAnnotatedWith(GenerateSoftAssertions.class)) {
      if (!(element instanceof TypeElement assertionsClass)) continue;
      processAssertionsClass(assertionsClass);
    }

    return true;
  }

  private void processAssertionsClass(TypeElement assertionsClass) {
    List<ExecutableElement> entryPoints = new ArrayList<>();

    for (ExecutableElement method : ElementFilter.methodsIn(assertionsClass.getEnclosedElements())) {
      if (!method.getModifiers().contains(Modifier.PUBLIC)) continue;
      if (!method.getModifiers().contains(Modifier.STATIC)) continue;
      if (!returnsAbstractAssertSubtype(method)) continue;

      entryPoints.add(method);
    }

    if (!entryPoints.isEmpty()) {
      writeInterface("GeneratedStandardSoftAssertionsProvider", entryPoints, false);
      writeInterface("GeneratedBDDSoftAssertionsProvider", entryPoints, true);
    }
  }

  private boolean returnsAbstractAssertSubtype(ExecutableElement method) {
    return isAbstractAssertSubtype(method.getReturnType());
  }

  private boolean isAbstractAssertSubtype(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) return false;
    TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
    String name = element.getSimpleName().toString();
    if (name.equals("AbstractAssert")) return true;
    if (name.equals("Object")) return false;
    TypeMirror superType = element.getSuperclass();
    if (superType.getKind() == TypeKind.DECLARED) {
      return isAbstractAssertSubtype(superType);
    }
    return false;
  }

  private void writeInterface(String interfaceName, List<ExecutableElement> methods, boolean bdd) {
    TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
        .addModifiers(Modifier.PUBLIC)
        .addSuperinterface(SOFT_ASSERTIONS_PROVIDER)
        .addAnnotation(CHECK_RETURN_VALUE);

    for (ExecutableElement method : methods) {
      MethodSpec generated = generateMethod(method, bdd);
      if (generated != null) {
        interfaceBuilder.addMethod(generated);
      }
    }

    JavaFile javaFile = JavaFile.builder(API_PACKAGE, interfaceBuilder.build())
        .indent("  ")
        .build();

    try {
      var fileObject = processingEnv.getFiler().createSourceFile(API_PACKAGE + "." + interfaceName);
      try (java.io.Writer writer = fileObject.openWriter()) {
        writer.write(LICENSE_HEADER);
        javaFile.writeTo(writer);
      }
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed to generate " + interfaceName + ": " + e.getMessage());
    }
  }

  private MethodSpec generateMethod(ExecutableElement sourceMethod, boolean bdd) {
    String methodName = bdd
        ? toBddName(sourceMethod.getSimpleName().toString())
        : sourceMethod.getSimpleName().toString();

    MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
        .returns(TypeName.get(sourceMethod.getReturnType()));

    // Inherit javadoc from the source method
    String docComment = processingEnv.getElementUtils().getDocComment(sourceMethod);
    if (docComment != null) {
      builder.addJavadoc(sanitizeJavadoc(docComment));
    }

    // Copy type parameters
    for (var typeParam : sourceMethod.getTypeParameters()) {
      builder.addTypeVariable(TypeVariableName.get(typeParam));
    }

    // Copy parameters
    StringBuilder proxyArgs = new StringBuilder();
    for (var param : sourceMethod.getParameters()) {
      builder.addParameter(ParameterSpec.get(param));
      if (!proxyArgs.isEmpty()) proxyArgs.append(", ");
      proxyArgs.append(param.getSimpleName());
    }

    // Generate body: delegate to the static Assertions method, then set the collector
    builder.addStatement("$T __result = $L.$L($L)",
        TypeName.get(sourceMethod.getReturnType()),
        "Assertions",
        sourceMethod.getSimpleName(),
        proxyArgs.toString());
    builder.addStatement("(($T) __result).softAssertionCollector = this",
        ClassName.get(API_PACKAGE, "AbstractAssert"));
    builder.addStatement("return __result");

    return builder.build();
  }

  /**
   * Sanitize javadoc for use with JavaPoet's {@code addJavadoc}.
   * JavaPoet treats {@code $} as a format specifier, so we must escape it.
   */
  private static String sanitizeJavadoc(String docComment) {
    return docComment.replace("$", "$$");
  }

  private static String toBddName(String assertThatName) {
    if (assertThatName.equals("assertThat")) return "then";
    if (assertThatName.equals("assertThatThrownBy")) return "thenThrownBy";
    if (assertThatName.equals("assertThatCode")) return "thenCode";
    if (assertThatName.equals("assertThatObject")) return "thenObject";
    if (assertThatName.equals("assertThatCollection")) return "thenCollection";
    if (assertThatName.equals("assertThatList")) return "thenList";
    if (assertThatName.equals("assertThatIterable")) return "thenIterable";
    if (assertThatName.equals("assertThatStream")) return "thenStream";
    if (assertThatName.startsWith("assertThat")) {
      String suffix = assertThatName.substring("assertThat".length());
      return "then" + suffix;
    }
    return assertThatName.replace("assertThat", "then");
  }
}
