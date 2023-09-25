package com.tianji.learning.controller;


import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.service.IPointsRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 学霸天梯榜 前端控制器
 * </p>
 *
 * @author 周欢
 * @since 2023-09-24
 */
@Api(tags = "学霸天梯榜")
@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class PointsBoardController {

    private final IPointsBoardService pointsBoardService;

    @ApiOperation("查询学霸积分榜-当前赛季和历史赛季都可由")
    @GetMapping
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query){
        return pointsBoardService.queryPointsBoardList(query);
    }

}
