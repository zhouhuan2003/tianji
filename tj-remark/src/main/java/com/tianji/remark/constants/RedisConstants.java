package com.tianji.remark.constants;/*
 *@author 周欢
 *@version 1.0
 */

public interface RedisConstants {
    //给业务单招数统计KEY前缀，后缀是业务类型
    String LIKE_BIZ_KEY_PREFIX="likes:set:biz:";
    //业务点赞数统计的KEY前缀，后缀是业务类型
    String LIKE_COUNT_KEY_PREFIX="likes:times:type:";
}
