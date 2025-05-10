package top.panson.argo.annotation;


import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target(ElementType.TYPE)
@Import({ConsistencyTaskSelector.class})
public @interface EnableTendConsistencyTask {
}
