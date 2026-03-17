# Plan: Remove ByteBuddy Proxying from Soft Assertions

## Context

AssertJ's soft assertions currently use ByteBuddy to generate proxy subclasses at runtime. This adds:
- A heavyweight runtime dependency (ByteBuddy)
- Runtime class generation overhead
- Complex proxy infrastructure (`SoftProxies`, `ErrorCollector`, `ProxifyMethodChangingTheObjectUnderTest`, `AssertJProxySetup`, `ClassLoadingStrategyFactory`)
- Manually maintained method exclusion lists (`METHODS_NOT_TO_PROXY`, `METHODS_CHANGING_THE_OBJECT_UNDER_TEST`)
- Stack trace scanning for nested call detection
- ClassLoader/OSGi complexity

The goal is to replace this with compile-time soft assertion support built directly into the `*Assert` classes, plus an annotation processor to generate entry point classes.

**Note:** ByteBuddy cannot be fully removed from `pom.xml` because `Assumptions.java` still uses it independently for assumption proxying.

## Architecture

### How it works

1. `AbstractAssert` gains a `softAssertionCollector` field (null = normal mode, non-null = soft mode)
2. Every assertion method wraps its body in `runSoftly(() -> { body })` which catches `AssertionError` and collects it instead of throwing
3. A `ThreadLocal<Integer>` depth counter handles nested calls (e.g., `isTrue()` calling `isEqualTo(true)`) — only the outermost `runSoftly` catches/collects
4. Navigation methods propagate the collector to new assert instances via `withAssertionState(myself)`
5. `SoftAssertionsProvider.proxy()` becomes a default method using reflection + `setAccessible(true)` to create soft-aware asserts

### Three helper methods on `AbstractAssert`

- `runSoftly(Runnable)` → for methods returning `SELF`
- `runSoftlyVoid(Runnable)` → for void methods (`isNull`, `isEmpty`, etc.)
- `runSoftlyNavigation(Callable<T>)` → for navigation methods with assertion guards (`first()`, `last()`, `element()`, `singleElement()`) — returns `null` on soft failure

---

## Completed Work

### 1. Core infrastructure in `AbstractAssert.java` ✅
- `softAssertionCollector` field
- `SOFT_CALL_DEPTH` ThreadLocal
- `runSoftly()`, `runSoftlyVoid()`, `runSoftlyNavigation()`
- `withAssertionState()` propagates `softAssertionCollector`

### 2. All Abstract*Assert classes modified ✅ (68 files)
- Every assertion method wrapped with `runSoftly()`
- Void methods wrapped with `runSoftlyVoid()`
- ForProxy methods wrapped with `runSoftly()`
- Configuration methods (as, describedAs, usingComparator, etc.) left unchanged
- Navigation methods left unchanged (propagation via withAssertionState)

### 3. All concrete *Assert classes modified ✅ (~20 files)
- `RecursiveComparisonAssert`, `RecursiveAssertionAssert`, `ThrowableAssertAlternative`
- All `Atomic*Assert` classes
- All `*2DArrayAssert` classes
- `ListAssert`, `HashSetAssert`

### 4. Proxy infrastructure removed ✅
- Deleted: `SoftProxies.java`, `ErrorCollector.java`, `ProxifyMethodChangingTheObjectUnderTest.java`, `AssertJProxySetup.java`
- `METHODS_NOT_TO_PROXY` moved into `Assumptions.java` (only remaining consumer)
- `ClassLoadingStrategyFactory` kept (used by `Assumptions`)

### 5. Soft assertion providers updated ✅
- `AbstractSoftAssertions` — removed `SoftProxies` field and proxy constructor
- `SoftAssertionsProvider.proxy()` — now a default method using `getDeclaredConstructor` + `setAccessible(true)`
- `StandardSoftAssertionsProvider` / `BDDSoftAssertionsProvider` — work via `proxy()` calls unchanged

### 6. Navigation method propagation ✅
- `AbstractIterableAssert`: `size()`, `internalFirst/Last/Element/SingleElement`, `first()`, `last()`, `element()`, `singleElement()` (with InstanceOfAssertFactory overloads), `elements()`
- `AbstractMapAssert`: `size()`
- `AbstractFileAssert`: `size()`
- `AbstractBigDecimalAssert`: `scale()`
- `AbstractThrowableAssert`: `message()`
- `AbstractOptionalAssert`: `map()`, `flatMap()`
- `AbstractObjectAssert`: `newObjectAssert()`
- `AbstractObjectArrayAssert`: `newListAssertInstance()`, `toAssert()`
- `AbstractAssert`: `newListAssertInstance()`

### 7. Annotation processor module created ✅
- `assertj-processor/pom.xml`
- `SoftAssertionEntryPoint` annotation
- `SoftAssertionProcessor` skeleton
- Added to root `pom.xml` modules

### Test results: 68/73 SoftAssertionsTest pass (93%)

---

## Remaining Work

### Phase 1: Fix 5 remaining SoftAssertionsTest failures

The 5 failures all involve extracting/filtering methods where `overridingErrorMessage` from a parent assert gets combined with a description set by the extracting method (e.g., `[Extracted: first] overridingErrorMessage`). In the old proxy approach, `withAssertionState` was called AFTER the method returned, overwriting descriptions. Now it's called inside, so `.as(description)` runs after.

**Failing tests:**
- `should_pass_when_using_extracting_with_list`
- `should_collect_all_errors_when_using_extracting`
- `should_collect_all_errors_when_using_extracting_on_object`
- `should_collect_all_errors_when_using_filtering`
- `check_477_bugfix`

**Fix approach:** Modify `newListAssertInstanceForMethodsChangingElementType` in `AbstractIterableAssert` to clear `overridingErrorMessage` after `withAssertionState(myself)` propagates it, since extracting methods set their own description context. Alternatively, update the tests to expect the new (more informative) behavior.

**Files:**
- `assertj-core/src/main/java/org/assertj/core/api/AbstractIterableAssert.java` (line ~1350)
- Similar pattern in `AbstractObjectAssert.java` extracting methods
- `assertj-core/src/test/java/org/assertj/core/api/SoftAssertionsTest.java` (if updating tests)

### Phase 2: Fix BDDSoftAssertionsTest failures

BDDSoftAssertionsTest mirrors SoftAssertionsTest. Expect ~13 analogous failures (same navigation/extracting patterns).

**Files:**
- Same source fixes as Phase 1 will resolve most of these
- `assertj-core/src/test/java/org/assertj/core/api/BDDSoftAssertionsTest.java` (if updating tests)

### Phase 3: Fix SoftAssertions_wasSuccess_Test

Verify that `wasSuccess()` works correctly with the depth counter. Run:
```
./mvnw test -pl assertj-core -Dtest="SoftAssertions_wasSuccess_Test"
```

### Phase 4: Run full assertj-core test suite

```bash
./mvnw test -pl assertj-core
```

Fix any remaining failures. Common categories:
- Tests that reference deleted classes (`SoftProxies`, `ErrorCollector`, etc.)
- Tests that depend on proxy-specific behavior
- Navigation methods in lesser-used Assert classes missing collector propagation

### Phase 5: Handle assertj-guava module

The `assertj-guava` module has 6 Assert classes with assertion methods:
- `RangeMapAssert`, `RangeSetAssert`, `TableAssert`, `MultimapAssert`, `MultisetAssert`, `RangeAssert`

Apply same `runSoftly()` wrapping pattern. Run:
```bash
./mvnw test -pl assertj-guava
```

### Phase 6: Run integration tests

```bash
./mvnw test -pl assertj-tests/assertj-integration-tests/assertj-core-tests
```

Key tests:
- `SoftAssertionsExtension_PER_CLASS_Concurrency_Test` — thread safety
- `SoftAssertionsExtensionAPIIntegrationTest` — JUnit 5 extension
- `CustomSoftAssertionsLineNumberTest` — stack trace line numbers
- OSGi soft assertion tests

### Phase 7: Wire annotation processor into build

Currently `StandardSoftAssertionsProvider` and `BDDSoftAssertionsProvider` have ~100 manually maintained `assertThat()`/`then()` methods. The processor should generate these.

1. Add `@SoftAssertionEntryPoint(actualType = X.class)` to each concrete `*Assert` class
2. Configure `assertj-core/pom.xml` to use `assertj-processor` on annotation processor path
3. Generate `GeneratedStandardSoftAssertionsProvider` and `GeneratedBDDSoftAssertionsProvider`
4. Update `SoftAssertions` and `BDDSoftAssertions` to implement generated interfaces
5. Delete hand-written `StandardSoftAssertionsProvider` and `BDDSoftAssertionsProvider`

### Phase 8: Final verification

```bash
./mvnw clean verify
```

---

## Verification

After each phase, run the relevant tests:
```bash
# Phase 1-3: Soft assertion unit tests
./mvnw test -pl assertj-core -Dtest="SoftAssertionsTest,BDDSoftAssertionsTest,SoftAssertions_wasSuccess_Test"

# Phase 4: Full assertj-core
./mvnw test -pl assertj-core

# Phase 5: Guava module
./mvnw test -pl assertj-guava

# Phase 6: Integration tests
./mvnw test -pl assertj-tests/assertj-integration-tests/assertj-core-tests

# Phase 8: Full build
./mvnw clean verify
```

## Key files reference

| File | Role |
|------|------|
| `AbstractAssert.java` | Core: `softAssertionCollector`, `runSoftly()`, `runSoftlyVoid()`, `runSoftlyNavigation()` |
| `AbstractSoftAssertions.java` | Base class for SoftAssertions (simplified, no more SoftProxies) |
| `SoftAssertionsProvider.java` | `proxy()` default method using reflection |
| `Assumptions.java` | Still uses ByteBuddy; owns `METHODS_NOT_TO_PROXY` now |
| `assertj-processor/` | Annotation processor module (skeleton) |
| `SoftAssertionEntryPoint.java` | Marker annotation for processor |
| `SoftAssertionProcessor.java` | Processor implementation |
