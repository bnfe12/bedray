package io.github.andrexarlay.vulkancompat.shaderhost;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the shader-host SPI as internal and experimental (ADR-0004: a provider kind is published only when it has at least two
 * real implementations; ShaderHost has one working host, native, and one host in construction, iris). No compatibility promise:
 * signatures may change between commits, other mods must not implement or call these types.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
    /** Why the element is experimental / which delivery stage makes it stable. */
    String value() default "";
}
