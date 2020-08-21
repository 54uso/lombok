package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface Desensitizer {

    /**
     * 前缀明文长度
     */
    int preLen() default 0;

    /**
     * 后缀明文长度
     */
    int sufLen() default 0;

    /**
     * 替换字符.
     */
    String cipher() default "*";

}
