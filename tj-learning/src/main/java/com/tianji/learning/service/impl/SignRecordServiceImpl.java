package com.tianji.learning.service.impl;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisContstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;

    @Override
    public SignResultVO addSignRecords() {
        //获取用户id
        Long userId = UserContext.getUser();
        //拼接key
//        SimpleDateFormat format = new SimpleDateFormat("yyyyMM");
//        format.format(new Date());
        LocalDate date = LocalDate.now();
        String format = date.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key= RedisContstants.SIGN_RECORD_KEY_PREFIX+userId.toString()+format;
        //利用bitset命令 将签到记录保存到redis的bitmap结构中，需要判断返回值(是否已签到)
        int offset = date.getDayOfMonth() - 1;
        Boolean setBit = redisTemplate.opsForValue().setBit(key, offset, true);
        if(Boolean.TRUE.equals(setBit)){
            throw new BizIllegalException("不能连续签到");
        }
        //计算连续签到的天数
        int days= countSignDays(key,date.getDayOfMonth());
        //计算连续签到的奖励积分
        int rewardPoints=0;
        switch (days){
            case 7:
                rewardPoints=10;
                break;
            case 14:
                rewardPoints=20;
                break;
            case 28:
                rewardPoints=40;
                break;

        }
        //todo 保存积分
        mqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId,rewardPoints+1));
        //封装vo返回
        SignResultVO signResultVO = new SignResultVO();
        signResultVO.setSignDays(days);
        signResultVO.setRewardPoints(rewardPoints);
        return signResultVO;
    }

    @Override
    public Byte[] querySignRecords() {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.获取日期
        LocalDate now = LocalDate.now();
        int dayOfMonth = now.getDayOfMonth();
        // 3.拼接key
        String key = RedisContstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        // 4.读取
        List<Long> result = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            return new Byte[0];
        }
        int num = result.get(0).intValue();

        Byte[] arr = new Byte[dayOfMonth];
        int pos = dayOfMonth - 1;
        while (pos >= 0){
            arr[pos--] = (byte)(num & 1);
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return arr;
    }

    /**
     * 计算连续签到多少天
     * @param key 缓存中的key
     * @param dayOfMonth 本月第一天到今天的天数
     * @return
     */
    private int countSignDays(String key, int dayOfMonth) {
        List<Long> bitField = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(CollUtils.isEmpty(bitField)){
            return 0;
        }
        Long num = bitField.get(0);//本月第一天到今天的签到数 拿的十进制
        log.debug("num {}",num);
        //num转2进制 从后往前推共有多少个1 与运算 右移
        int count=0;
        while ((num & 1)==1 ){
            count++;
            num=num>>>1;
        }
        return count;
    }
}
