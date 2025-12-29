package com.tyron.nanoj.testFramework;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test (typically a class) as requiring fresh, local indices.
 *
 * Tests not annotated with {@link FreshIndices} may mount and use shared indexes
 * to speed up execution.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FreshIndices {
}
