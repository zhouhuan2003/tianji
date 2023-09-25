package com.tianji.learning.controller;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.service.ISignRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "签到相关接口")
@RestController
@RequestMapping("sign-records")
@RequiredArgsConstructor
public class SignRecordsController {

    private final ISignRecordService recordService;

    @ApiOperation("签到")
    @PostMapping
    public SignResultVO addSignRecords(){
        return recordService.addSignRecords();
    }


    @ApiOperation("查询签到记录")
    @GetMapping
    public Byte[] querySignRecords(){
        return recordService.querySignRecords();
    }

}
