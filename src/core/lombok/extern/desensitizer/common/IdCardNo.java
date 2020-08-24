package lombok.extern.desensitizer.common;

import lombok.Data;
import lombok.extern.desensitizer.Desensitizer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Desensitizer
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface IdCardNo {

    Desensitizer desensitizer() default @Desensitizer(preLen = 6, sufLen = 4);

}
