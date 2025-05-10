package top.panson.argo.annotation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;


@Configuration
@ComponentScan(value = {"top.panson.argo"}) // 作用是让spring去扫描框架各个包下的bean
@MapperScan(basePackages = {"top.panson.argo.mapper"})
public class ComponentScanConfig {
}
