package top.panson.argo.manager;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import top.panson.argo.exceptions.ConsistencyException;
import top.panson.argo.model.ConsistencyTaskInstance;
import top.panson.argo.service.TaskStoreService;
import top.panson.argo.utils.ReflectTools;
import top.panson.argo.utils.SpringUtil;
import top.panson.argo.utils.ThreadLocalUtil;

import javax.annotation.Resource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * 任务调度管理器
 *
 **/
@Slf4j
@Component
public class TaskScheduleManager {

    /**
     * 任务存储mapper组件
     */
    @Autowired
    private TaskStoreService taskStoreService;
    /**
     * 并行任务线程池
     */
    @Autowired
    private CompletionService<ConsistencyTaskInstance> consistencyTaskPool;

    @Resource
    private TaskEngineExecutor taskEngineExecutor;

    /**
     * 该方法在业务服务中的定时任务中进行调度
     * 查询并执行未完成的一致性任务
     */
    public void performanceTask() throws InterruptedException {
        // 第一步，先去查询未完成的任务实例list列表
        // 查询待执行任务实例集合的时候，数据库故障了，这是第二个故障点
        List<ConsistencyTaskInstance> consistencyTaskInstances = taskStoreService.listByUnFinishTask();
        if (CollectionUtils.isEmpty(consistencyTaskInstances)) {
            return;
        }

        // 过滤出需要被执行的任务
        // 每个任务都可以来运行吗？不是的，要根据你的execute time来的，execute time是根据delay time来的
        consistencyTaskInstances = consistencyTaskInstances.stream()
                // 要针对我们查询出来的任务实例执行一次过滤，根据execute time来过滤
                // 每个任务实例的execute time是根据你的运行模式和delay time运算出来的，直接运行模式，execute time就是当时实例化任务的now
                // 如果是调度运行模式，是now + delay time = execute time，延迟过的时间
                // schedule运行模式，delay time = 10s，now1 = 22:56:30，execute time = 22:56:40，now2 = 22:56:45，减法 = 正数，能运行吗？不能运行的
                // execute time - now2 = 0，刚好达到了我预期最快运行的时间，此时任务就可以运行了
                // execute time - now2 < -5，负数，必须赶紧运行了，此时超过了我预期运行的时间了，就不对了，赶紧跑吧
                .filter(e -> e.getExecuteTime() - System.currentTimeMillis() <= 0) // 如果execute  time - now是小于等于0，
                .collect(Collectors.toList()); // 拿到的任务实例，都是execute time <= now

                // 第一次运行失败了，第一次运行时间 executeTime = 22:50:30
                // 第二次运行时间，executeTime(22:50:30) + (1 + 1) * 20 = 22:51:10
                // 此时另外一个线程来调度运行，now = 22:50:50，此时根本就不会运行第二次，第二次预期的时间还没到
                // 等待后续后台线程调度的时候，now = 22:51:20，<0，负数10秒钟，此时就可以对这个任务进行第二次运行

        if (CollectionUtils.isEmpty(consistencyTaskInstances)) {
            return;
        }

        // 多线程并发运行任务
        // 如果说查出来了比如说很多个任务实例，1000个，往你的线程池里提交，5个线程+100 size queue
        // 如果说出现了这个问题的话，会导致线程的reject提交任务
        CountDownLatch latch = new CountDownLatch(consistencyTaskInstances.size());
        for (ConsistencyTaskInstance instance : consistencyTaskInstances) {
            consistencyTaskPool.submit(() -> {
                try {
                    // 执行任务
                    taskEngineExecutor.executeTaskInstance(instance);
                    return instance;
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        log.info("[一致性任务框架] 执行完成");
    }

    /**
     * 执行指定任务
     *
     * @param taskInstance 任务实例信息
     */
    public void performanceTask(ConsistencyTaskInstance taskInstance) {
        // 任务实例数据里，封装了目标方法（AOP切入点，JoinPoint） + 注解（@ConsistencyTask）
        // 都完成了一个封装，此时我们就可以用到所有的数据

        // 获取方法签名 格式：类路径#方法名(参数1的类型,参数2的类型,...参数N的类型)
        String methodSignName = taskInstance.getMethodSignName();
        // 获取方法所在的类，包含了类全限定名，就通过字符串操作，可以拿到你的方法所属的类
        Class<?> clazz = getTaskMethodClass(methodSignName.split("#")[0]);
        if (ObjectUtils.isEmpty(clazz)) {
            return;
        }
        // 通过你的类class类型，从spring容器里获取到这个class类型的bean
        Object bean = SpringUtil.getBean(clazz);
        if (ObjectUtils.isEmpty(bean)) {
            return;
        }

        // 后面把methodName独立出一个字段
        String methodName = taskInstance.getMethodName();
        // 获取参数类型的字符串字符串 多个用逗号分隔
        String[] parameterTypes = taskInstance.getParameterTypes().split(",");
        // 构造参数类数组
        Class<?>[] parameterClasses = ReflectTools.buildTypeClassArray(parameterTypes);
        // 获取目标方法
        Method targetMethod = getTargetMethod(methodName, parameterClasses, clazz);
        if (ObjectUtils.isEmpty(targetMethod)) {
            return;
        }

        // 已经获取到了目标类 -> spring容器里的bean实例对象，有了这个bean实例对象之后，就可以去进行反射调用
        // 目标类里指定的方法名称+入参类型 -> Method方法
        // 如果说我们要通过反射技术，去调用bean实例对象的method方法，传入参数对象
        // 要把入参参数对象搞出来

        // 构造方法入参
        // task parameter就是入参对象数组转为的json字符串
        Object[] args = ReflectTools.buildArgs(taskInstance.getTaskParameter(), parameterClasses);
        try {
            // 执行目标方法调用
            ThreadLocalUtil.setFlag(true); // 基于thread local设置一个flag，true
            // 基于反射技术进行方法调用，调用的是谁，bean，调用的是bean的哪个方法，给方法调用传入的是哪些参数
            // bean=spring容器里获取的bean，方法就是类的方法，args入参对象就是本次方法调用传入的入参对象
            // 结论：我们在进行方法调用的时候，其实也是会进入AOP增强逻辑的，完成了AOP增强逻辑了之后，才会推进到目标方法的执行
            targetMethod.invoke(bean, args); // 基于反射技术，method对象完成针对bean实例的传入入参对象数组的调用
            ThreadLocalUtil.setFlag(false); // 跑完了以后，会设置为false
        } catch (InvocationTargetException e) {
            log.error("调用目标方法时，发生异常", e);
            Throwable target = e.getTargetException();
            throw new ConsistencyException((Exception) target);
        } catch (Exception ex) {
            throw new ConsistencyException(ex);
        }
    }

    /**
     * 获取目标方法
     *
     * @param methodName              方法名称
     * @param parameterTypeClassArray 入参类数组
     * @param clazz                   方法所在类的Class对象
     * @return 目标方法
     */
    private Method getTargetMethod(String methodName, Class<?>[] parameterTypeClassArray, Class<?> clazz) {
        try {
            // 基于反射技术，从指定的目标类里拿到了类的方法对应的Method
            return clazz.getMethod(methodName, parameterTypeClassArray);
        } catch (NoSuchMethodException e) {
            log.error("获取目标方法失败", e);
            return null;
        }
    }

    /**
     * 构造任务方法所在的类对象
     *
     * @param className 类名称
     * @return 类对象
     */
    private Class<?> getTaskMethodClass(String className) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className);
            return clazz;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

}
