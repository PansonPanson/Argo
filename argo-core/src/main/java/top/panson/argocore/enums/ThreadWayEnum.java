package top.panson.argocore.enums;


public enum ThreadWayEnum {
    /**
     * 异步执行
     */
    ASYNC(1, "异步执行"),
    /**
     * 同步执行
     */
    SYNC(2, "同步执行");

    private final Integer code;

    private final String desc;

    ThreadWayEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }


    public String getDesc() {
        return desc;
    }

}
