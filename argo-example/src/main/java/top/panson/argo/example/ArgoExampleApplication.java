package top.panson.argo.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.panson.argo.annotation.EnableTendConsistencyTask;


/**
 *  现在一般来说引入一个框架之后，pom里引入jar包依赖，resources里放置框架配置
 *  框架在系统启动的时候可能会去做一些初始化的操作，@EnableTendConsistencyTask，spring boot自动装配机制
 *  一般来说就是在代码里引入这个框架来进行使用就可以了
 *  启动起来跑起来了以后，最核心的一定是会去启动spring框架容器，spring为核心，就会接管一切的事情了
 **/
@EnableTendConsistencyTask
@EnableScheduling
@SpringBootApplication
public class ArgoExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgoExampleApplication.class, args);
    }

}
