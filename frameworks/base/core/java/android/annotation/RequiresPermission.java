package android.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.CLASS;

@Retention(CLASS)
@Target({ANNOTATION_TYPE, METHOD, CONSTRUCTOR, FIELD, PARAMETER})
public @interface RequiresPermission {
    String value() default "";
    String[] allOf() default {};
    String[] anyOf() default {};
    boolean conditional() default false;
}
