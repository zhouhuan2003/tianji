package com.tianji.api.client.promotion.fallback;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.api.client.promotion.PromotionClient;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

@Slf4j
public class PromotionFallback implements FallbackFactory {
    @Override
    public Object create(Throwable cause) {
        log.debug("远程调用promotion服务报错了",cause);
        return new PromotionClient() {
            @Override
            public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
                return null;
            }
        };
    }
}
