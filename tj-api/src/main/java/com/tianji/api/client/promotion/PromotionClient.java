package com.tianji.api.client.promotion;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.api.client.promotion.fallback.PromotionFallback;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

//促销服务feign客户端
@FeignClient(value = "promotion-service",fallbackFactory = PromotionFallback.class)
public interface PromotionClient {

    @PostMapping("/user-coupons/available")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourses);
}
