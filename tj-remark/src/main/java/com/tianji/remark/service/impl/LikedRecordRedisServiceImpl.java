package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.dto.msg.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constants.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikedRecordRedisServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper rabbitMqHelper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        //获取当前用户
        Long userId = UserContext.getUser();
        //判断是否点赞 dto.liked 为true则是点赞
//        boolean flag=true;
//        if(dto.getLiked()){
//            flag= liked(dto);
//        }else{
//            flag=unliked(dto);
//        }
        boolean flag=dto.getLiked() ? liked(dto,userId):unliked(dto,userId);
        if(!flag){//说明点赞或者取消赞失败
            return;
        }
        //统计该业务id的总点赞数
//        Integer count = this.lambdaQuery()
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .count();

        //拼接key
        var key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        Long totalLikesNum = redisTemplate.opsForSet().size(key);
        if(totalLikesNum==null){
            return;
        }

        //采用zset缓存点赞的总数
        String bizTypeTotalLikeKey=RedisConstants.LIKE_COUNT_KEY_PREFIX+dto.getBizType();
        redisTemplate.opsForZSet().add(bizTypeTotalLikeKey,dto.getBizId().toString(),totalLikesNum);

//        //发送消息到mq
//        // 5.发送MQ消息，通知报名成功
//        log.debug("发送点赞消息,");
//        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, dto.getBizType());
//        LikedTimesDTO msg = new LikedTimesDTO();
//        msg.setBizId(dto.getBizId());
//        msg.setLikedTimes(count);
//        rabbitMqHelper.send(
//                MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
//                routingKey,
//                msg
//        );
    }

    @Override
    public Set<Long> getLikeStatusByBizIds(List<Long> bizIds) {
        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.查询点赞状态  短时间 执行大量的redis命令
        List<Object> objects = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId;
                src.sIsMember(key, userId.toString());
            }
            return null;
        });
        // 3.返回结果
        return IntStream.range(0, objects.size()) // 创建从0到集合size的流
                .filter(i -> (boolean) objects.get(i)) // 遍历每个元素，保留结果为true的角标i
                .mapToObj(bizIds::get)// 用角标i取bizIds中的对应数据，就是点赞过的id
                .collect(Collectors.toSet());// 收集


//        //1.获取用户
//        Long userId = UserContext.getUser();
//        if(CollUtils.isEmpty(bizIds)){
//            return CollUtils.emptySet();
//        }
//        //2循环bizIds
//        Set<Long> retSet=new HashSet<>();
//        for (Long bizId : bizIds) {
//            //判断该业务id 的点赞用户集合中是否包含当前用户
//            Boolean member = redisTemplate.opsForSet().isMember(RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId, userId.toString());
//            if (Boolean.TRUE.equals(member)){
//                retSet.add(bizId);
//            }
//        }
//        return retSet;


//        //获取用户id
//        Long userId = UserContext.getUser();
//        //查点赞记录表 in bizIds
//        List<LikedRecord> recordList = this.lambdaQuery().in(LikedRecord::getBizId, bizIds).eq(LikedRecord::getUserId, userId).list();
//        //将查询到的bizid转集合
//        return recordList.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        // 1.读取并移除Redis中缓存的点赞总数
        String key = RedisConstants.LIKE_COUNT_KEY_PREFIX + bizType;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().popMin(key, maxBizSize);
        if (CollUtils.isEmpty(tuples)) {
            return;
        }
        // 2.数据转换
        List<LikedTimesDTO> list = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String bizId = tuple.getValue();
            Double likedTimes = tuple.getScore();
            if (StringUtils.isBlank(bizId) || likedTimes == null) {
                continue;
            }
            list.add(LikedTimesDTO.of(Long.valueOf(bizId), likedTimes.intValue()));
        }
        // 3.发送MQ消息
        if(CollUtils.isNotEmpty(list)){
            log.debug("发送点赞消息,{}",list);
            String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, bizType);
            rabbitMqHelper.send(
                    MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                    routingKey,
                    list
            );
        }
    }

    //点赞
    private boolean unliked(LikeRecordFormDTO dto,Long userId) {
        //基于redis做点赞
        //拼接key
        var key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        //从set结构删除 当前userId
        Long result = redisTemplate.opsForSet().remove(key, userId.toString());
        return result!=null && result>0;
        //redisTemplate 往
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if(record==null){
//            //说明没有点过赞
//            return false;
//        }
//        //删除
//        return this.removeById(record.getId());
    }
    //取消赞
    private boolean liked(LikeRecordFormDTO dto,Long userId) {
        //基于redis做点赞
        //拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        Long result = redisTemplate.opsForSet().add(key, userId.toString());
        return result!=null && result>0;
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if(record!=null){
//            //说明之前点过赞
//            return false;
//        }
//        //保存点赞记录到表中
//        LikedRecord likedRecord=new LikedRecord();
//        likedRecord.setUserId(userId);
//        likedRecord.setBizId(dto.getBizId());
//        likedRecord.setBizType(dto.getBizType());
//        return this.save(likedRecord);
    }
}
