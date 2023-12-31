package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoard;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 服务类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-24
 */
public interface IPointsBoardService extends IService<PointsBoard> {

    PointsBoardVO queryPointsBoardList(PointsBoardQuery query);

    void createPointsBoardTableBySeason(Integer season);

    //查询当前赛季的排行榜列表 查redis
    public List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize);

//    List<PointsBoard> queryCurrentBoardList(String key, int pageNo, int pageSize);
}
