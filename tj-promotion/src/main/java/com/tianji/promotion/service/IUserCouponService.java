package com.tianji.promotion.service;

import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-27
 */
public interface IUserCouponService extends IService<UserCoupon> {

    void receiveCoupon(Long couponId);

    void exchangeCoupon(String code);

    public void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum);

    void checkAndCreateUserCouponNew(UserCouponDTO uc);

    List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses);
}
