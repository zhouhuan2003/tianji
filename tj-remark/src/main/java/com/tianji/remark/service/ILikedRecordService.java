package com.tianji.remark.service;

import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 服务类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-23
 */
public interface ILikedRecordService extends IService<LikedRecord> {

    void addLikeRecord(LikeRecordFormDTO dto);

    Set<Long> getLikeStatusByBizIds(List<Long> bizIds);

    void readLikedTimesAndSendMessage(String bizType, int maxBizSize);
}
