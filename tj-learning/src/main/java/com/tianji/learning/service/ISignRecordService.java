package com.tianji.learning.service;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.learning.domain.vo.SignResultVO;

public interface ISignRecordService {
    SignResultVO addSignRecords();

    Byte[] querySignRecords();
}
