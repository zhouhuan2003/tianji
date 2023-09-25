package com.tianji.learning.constants;/*
 *@author 周欢
 *@version 1.0
 */

public interface RedisContstants {
    /**
     * 签到记录的key前缀 完整格式为 sign:uid:用户id:年月
     */
    String SIGN_RECORD_KEY_PREFIX="sign:uid:";

    /**
     * 积分排行榜key前缀 完整格式为 boards:年月
     */
    String POINTS_BOARD_KEY_PREFIX="boards:";
}
