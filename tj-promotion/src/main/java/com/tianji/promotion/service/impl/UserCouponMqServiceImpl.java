package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.discount.Discount;
import com.tianji.promotion.discount.DiscountStrategy;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.CodeUtil;
import com.tianji.promotion.utils.MyLock;
import com.tianji.promotion.utils.PermuteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponMqServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService codeService;
    private final ICouponScopeService scopeService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;
    private final Executor discountSolutionExecutor;

    @Override
    @MyLock(name = "lock:coupon:uid:#{couponId}")
//    @Transactional
    public void receiveCoupon(Long couponId) {
        // 1.查询优惠券
//        Coupon coupon = couponMapper.selectById(couponId);
        Coupon coupon = queryCouponByCache(couponId);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        // 2.校验发放时间
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("优惠券发放已经结束或尚未开始");
        }
        // 3.校验库存
//        if (coupon.getIssueNum() >= coupon.getTotalNum()) {
        if(coupon.getTotalNum()<=0){
            throw new BadRequestException("优惠券库存不足");
        }
        Long userId = UserContext.getUser();

        //统计已领的数量
        String key=PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX+couponId;
        //代表领取后的已领数量
        Long increment = redisTemplate.opsForHash().increment(key, userId.toString(), 1);
        if(increment>coupon.getUserLimit()){
            throw new BadRequestException("超出限领数量");
        }

        //修改库存-1
        String couponKey=PromotionConstants.COUPON_CACHE_KEY_PREFIX+couponId;
        redisTemplate.opsForHash().increment(couponKey,"totalNum",-1);

        //校验是否超过限领数量
        UserCouponDTO msg = new UserCouponDTO();
        msg.setUserId(userId);
        msg.setCouponId(couponId);

        mqHelper.send(
                MqConstants.Exchange.PROMOTION_EXCHANGE,
                MqConstants.Key.COUPON_RECEIVE,
                msg);
//        IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
//        userCouponServiceProxy.checkAndCreateUserCoupon(coupon, userId, null);

    }

    private Coupon queryCouponByCache(Long couponId) {
        // 1.准备KEY
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;
        // 2.查询
        Map<Object, Object> objMap = redisTemplate.opsForHash().entries(key);
        if (objMap.isEmpty()) {
            return null;
        }
        // 3.数据反序列化
        return BeanUtils.mapToBean(objMap, Coupon.class, false, CopyOptions.create());
    }

    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        // 1.校验并解析兑换码
        long serialNum = CodeUtil.parseCode(code);
        // 2.校验是否已经兑换 SETBIT KEY 4 1 ，这里直接执行setbit，通过返回值来判断是否兑换过
        boolean exchanged = codeService.updateExchangeMark(serialNum, true);
        if (exchanged) {
            throw new BizIllegalException("兑换码已经被兑换过了");
        }
        try {
            // 3.查询兑换码对应的优惠券id
            ExchangeCode exchangeCode = codeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在！");
            }
            // 4.是否过期
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(exchangeCode.getExpiredTime())){
                throw new BizIllegalException("兑换码已经过期");
            }
            // 5.校验并生成用户券
            // 5.1.查询优惠券
            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
            // 5.2.查询用户
            Long userId = UserContext.getUser();
            // 5.3.校验并生成用户券，更新兑换码状态
            checkAndCreateUserCoupon(coupon, userId, serialNum);
        } catch (Exception e) {
            // 重置兑换的标记 0
            codeService.updateExchangeMark(serialNum, false);
            throw e;
        }
    }

    @MyLock(name = "lock:coupon:uid:#{userId}")
    @Override
    @Transactional
    public void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum){
        // 1.校验每人限领数量
        // 1.1.统计当前用户对当前优惠券的已经领取的数量
        Integer count = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, coupon.getId())
                .count();
        // 1.2.校验限领数量
        if(count != null && count >= coupon.getUserLimit()){
            throw new BadRequestException("超出领取数量");
        }
        // 2.更新优惠券的已经发放的数量 + 1
        couponMapper.incrIssueNum(coupon.getId());
        // 3.新增一个用户券
        saveUserCoupon(coupon, userId);
        // 4.更新兑换码状态
        if (serialNum != null) {
            codeService.lambdaUpdate()
                    .set(ExchangeCode::getUserId, userId)
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .eq(ExchangeCode::getId, serialNum)
                    .update();
        }
//        throw new BadRequestException("故意报错");
    }

//    @MyLock(name = "lock:coupon:uid:#{userId}")
    @Override
    @Transactional
    public void checkAndCreateUserCouponNew(UserCouponDTO uc) {
        // 1.查询优惠券
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null) {
            return;
        }
        // 2.更新优惠券的已经发放的数量 + 1
        int r = couponMapper.incrIssueNum(coupon.getId());
        if (r == 0) {
            return;
        }
        // 3.新增一个用户券
        saveUserCoupon(coupon,uc.getUserId());
        // 4.更新兑换码状态
//        if (uc.getSerialNum()!= null) {
//            codeService.lambdaUpdate()
//                    .set(ExchangeCode::getUserId, uc.getUserId())
//                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
//                    .eq(ExchangeCode::getId, uc.getSerialNum())
//                    .update();
//        }
    }

    private void saveUserCoupon(Coupon coupon, Long userId) {
        // 1.基本信息
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(coupon.getId());
        // 2.有效期信息
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        uc.setTermBeginTime(termBeginTime);
        uc.setTermEndTime(termEndTime);
        // 3.保存
        save(uc);
    }

    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
        //1.查询当前用户可用的优惠卷 coupon和user_coupon表 条件：userId、status=1 查哪些字段 优惠卷的规则 优惠卷id 用户卷id
        List<Coupon> coupons=getBaseMapper().queryMyCoupons(UserContext.getUser());
        if(CollUtils.isEmpty(coupons)){
            return CollUtils.emptyList();
        }
        log.debug("用户的优惠卷公有{}张", coupons.size());
        //2.筛选(初筛)
        //2.1计算订单的总金额 对courses的price累加
        int sum = orderCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        log.debug("订单的总金额{}",sum);
        //2.2校验优惠卷是否可用
        List<Coupon> availableCoupons = coupons.stream()
                .filter(coupon -> DiscountStrategy
                        .getDiscount(coupon.getDiscountType()).canUse(sum, coupon))
                .collect(Collectors.toList());
        log.debug("经过筛选之后，还剩{}张",availableCoupons.size());
        //3.细筛（需要考虑优惠卷的限定范围）排列组合
        Map<Coupon,List<OrderCourseDTO>> avaMap=findAvailableCoupons(coupons,orderCourses);
        if(avaMap.isEmpty()){
            return CollUtils.emptyList();
        }
        availableCoupons=new ArrayList<>(avaMap.keySet());//才是真正可用的优惠卷集合
        log.debug("经过细筛之后的优惠卷{}",availableCoupons);
        //排练组合
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        for (Coupon availableCoupon : availableCoupons) {
            solutions.add(List.of(availableCoupon));//添加单卷到方案中
        }
        //4.计算每一种组合的优惠明细
//        List<CouponDiscountDTO> list =
//                Collections.synchronizedList(new ArrayList<>(solutions.size()));
//        for (List<Coupon> solution : solutions) {
//            list.add(calculateSolutionDiscount(avaMap, orderCourses, solution));
//        }
        //5.使用多线程改造第4 步
        List<CouponDiscountDTO> list = Collections.synchronizedList(new ArrayList<>(solutions.size()));
        // 4.1.定义闭锁
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            // 4.2.异步计算
            CompletableFuture
                    .supplyAsync(
                            () -> calculateSolutionDiscount(avaMap, orderCourses, solution),
                            discountSolutionExecutor
                    ).thenAccept(dto -> {
                        // 4.3.提交任务结果
                        list.add(dto);
                        latch.countDown();
                    });
        }
        // 4.4.等待运算结束
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("优惠方案计算被中断，{}", e.getMessage());
        }
        //6.筛选最优解
        return findBestSolution(list);
//        return null;
    }

    /**
     * 求最优解
     * - 用卷相同时，优惠金额最高的方案
     * - 优惠金额相同时，用卷最少的方案
     * @param list
     * @return
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> list) {
        // 1.准备Map记录最优解
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();
        // 2.遍历，筛选最优解
        for (CouponDiscountDTO solution : list) {
            // 2.1.计算当前方案的id组合
            String ids = solution.getIds().stream()
                    .sorted(Long::compare).map(String::valueOf).collect(Collectors.joining(","));
            // 2.2.比较用券相同时，优惠金额是否最大
            CouponDiscountDTO best = moreDiscountMap.get(ids);
            if (best != null && best.getDiscountAmount() >= solution.getDiscountAmount()) {
                // 当前方案优惠金额少，跳过
                continue;
            }
            // 2.3.比较金额相同时，用券数量是否最少
            best = lessCouponMap.get(solution.getDiscountAmount());
            int size = solution.getIds().size();
            if (size > 1 && best != null && best.getIds().size() <= size) {
                // 当前方案用券更多，放弃
                continue;
            }
            // 2.4.更新最优解
            moreDiscountMap.put(ids, solution);
            lessCouponMap.put(solution.getDiscountAmount(), solution);
        }
        // 3.求交集
        Collection<CouponDiscountDTO> bestSolutions = CollUtils
                .intersection(moreDiscountMap.values(), lessCouponMap.values());
        // 4.排序，按优惠金额降序
        return bestSolutions.stream()
                .sorted(Comparator.comparingInt(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 计算每一个方案的 优惠信息
     * @param couponMap 优惠卷和可用课程的映射集合
     * @param courses 订单中所有的课程
     * @param solution 方案
     * @return
     */
    private CouponDiscountDTO calculateSolutionDiscount(
            Map<Coupon, List<OrderCourseDTO>> couponMap, List<OrderCourseDTO> courses, List<Coupon> solution) {
        // 1.初始化DTO
        CouponDiscountDTO dto = new CouponDiscountDTO();
        // 2.初始化折扣明细的映射
        Map<Long, Integer> detailMap = courses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, oc -> 0));
        // 3.计算折扣
        for (Coupon coupon : solution) {
            // 3.1.获取优惠券限定范围对应的课程
            List<OrderCourseDTO> availableCourses = couponMap.get(coupon);
            // 3.2.计算课程总价(课程原价 - 折扣明细)
            int totalAmount = availableCourses.stream()
                    .mapToInt(oc -> oc.getPrice() - detailMap.get(oc.getId())).sum();
            // 3.3.判断是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(totalAmount, coupon)) {
                // 券不可用，跳过
                continue;
            }
            // 3.4.计算优惠金额
            int discountAmount = discount.calculateDiscount(totalAmount, coupon);
            // 3.5.计算优惠明细
            calculateDiscountDetails(detailMap, availableCourses, totalAmount, discountAmount);
            // 3.6.更新DTO数据
            dto.getIds().add(coupon.getCreater());
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount());
        }
        return dto;
    }

    /**
     * 计算商品 折扣明细
     * @param detailMap 商品id和商品的优惠卷明细 映射
     * @param courses 当前优惠卷可用的课程集合
     * @param totalAmount 可用的课程的总金额
     * @param discountAmount 当前优惠卷能优惠的金额
     */
    private void calculateDiscountDetails(Map<Long, Integer> detailMap, List<OrderCourseDTO> courses,
                                          int totalAmount, int discountAmount) {
        int times = 0;
        int remainDiscount = discountAmount;
        for (OrderCourseDTO course : courses) {
            // 更新课程已计算数量
            times++;
            int discount = 0;
            // 判断是否是最后一个课程
            if (times == courses.size()) {
                // 是最后一个课程，总折扣金额 - 之前所有商品的折扣金额之和
                discount = remainDiscount;
            } else {
                // 计算折扣明细（课程价格在总价中占的比例，乘以总的折扣）
                discount = discountAmount * course.getPrice() / totalAmount;
                remainDiscount -= discount;
            }
            // 更新折扣明细
            detailMap.put(course.getId(), discount + detailMap.get(course.getId()));
        }
    }

    /**
     * 细筛 查询每个优惠卷 对应的可用的课程
     * @param coupons 初筛之后的优惠卷集合
     * @param courses 订单中的课程集合
     * @return
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupons(
            List<Coupon> coupons, List<OrderCourseDTO> courses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>(coupons.size());
        for (Coupon coupon : coupons) {
            // 1.找出优惠券的可用的课程
            List<OrderCourseDTO> availableCourses = courses;
            if (coupon.getSpecific()) {
                // 1.1.限定了范围，查询券的可用范围
                List<CouponScope> scopes = scopeService.lambdaQuery().eq(CouponScope::getCouponId, coupon.getId()).list();
                // 1.2.获取范围对应的分类id
                Set<Long> scopeIds = scopes.stream().map(CouponScope::getBizId).collect(Collectors.toSet());
                // 1.3.筛选课程
                availableCourses = courses.stream()
                        .filter(c -> scopeIds.contains(c.getCateId())).collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) {
                // 没有任何可用课程，抛弃
                continue;
            }
            // 2.计算课程总价
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            // 3.判断是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount.canUse(totalAmount, coupon)) {
                map.put(coupon, availableCourses);
            }
        }
        return map;
    }
}
