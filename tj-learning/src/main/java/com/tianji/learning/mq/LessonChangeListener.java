package com.tianji.learning.mq;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor //使用构造器,lombok在编译期生成构造器
public class LessonChangeListener {

    final ILearningLessonService learningLessonService;

//    public LessonChangeListener(ILearningLessonService learningLessonService) {
//        this.learningLessonService = learningLessonService;
//    }

    @RabbitListener(bindings = @QueueBinding(value = @Queue(value="learning.lesson.pay.queue",durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.ORDER_EXCHANGE,type = ExchangeTypes.TOPIC),
            key=MqConstants.Key.ORDER_PAY_KEY))
    public void onMsg(OrderBasicDTO dto){
        log.info("LessonChangeListener 接收到了信息 用户{}，添加课程{}",dto.getUserId(),dto.getCourseIds());
        //1.校验
        if(dto.getUserId()==null
                || dto.getOrderId()==null
                || CollUtils.isEmpty(dto.getCourseIds())){
            //不要抛异常，否则重试
            return;
        }
        //2.调service,保存课程到课表
        learningLessonService.addUserLesson(dto.getUserId(),dto.getCourseIds());
    }
}
