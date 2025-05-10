package top.panson.argo.enums;

/**
 * 一致性任务状态枚举类
 *
 **/
public enum ConsistencyTaskStatusEnum {

    /**
     * 0:初始化 1:开始执行 2:执行失败 3:执行成功
     */
    INIT(0),
    START(1), // 为什么会处于这种模式呢，有一个任务执行到了一半儿就系统崩了 -> 没有进入fail或者success
    FAIL(2), // 执行了，但是fail失败掉了
    SUCCESS(3);

    private final int code;

    ConsistencyTaskStatusEnum(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
