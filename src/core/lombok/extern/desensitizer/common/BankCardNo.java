package lombok.extern.desensitizer.common;

import lombok.extern.desensitizer.Desensitizer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface BankCardNo {

    Desensitizer desensitizer() default @Desensitizer(preLen = 4, sufLen = 4);

}
