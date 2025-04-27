package top.panson.argocore.annotation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;


@Configuration
@ComponentScan(value = {"top.panson.argocore"}) // 作用是让spring去扫描框架各个包下的bean
@MapperScan(basePackages = {"top.panson.argocore.mapper"})
public class ComponentScanConfig {
}
