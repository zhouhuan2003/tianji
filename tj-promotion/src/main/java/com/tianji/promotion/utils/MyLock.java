package com.tianji.promotion.utils;/*
 *@author 周欢
 *@version 1.0
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MyLock {
    String name();

    long waitTime() default 1;

    long leaseTime() default -1;

    TimeUnit unit() default TimeUnit.SECONDS;

    MyLockType lockType()default MyLockType.RE_ENTRANT_LOCK;//代表锁的类型 默认可从如锁

    MyLockStrategy lockStrategy()default MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT; //代表获取锁失败的策略
}
