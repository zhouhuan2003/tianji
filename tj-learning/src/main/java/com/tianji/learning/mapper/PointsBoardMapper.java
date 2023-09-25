package com.tianji.learning.mapper;

import com.tianji.learning.domain.po.PointsBoard;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 学霸天梯榜 Mapper 接口
 * </p>
 *
 * @author 周欢
 * @since 2023-09-24
 */
public interface PointsBoardMapper extends BaseMapper<PointsBoard> {

    void createPointsBoardTable(String s);
}
