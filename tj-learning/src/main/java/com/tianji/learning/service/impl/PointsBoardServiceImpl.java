package com.tianji.learning.service.impl;

import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-24
 */
@Service
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

}
