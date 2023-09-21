package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.PlanStatus;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author kdm
 * @since 2023-09-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {

    final CourseClient courseClient;

    private final CatalogueClient catalogueClient;

    private final LearningRecordMapper recordMapper;

    @Override
    public void addUserLesson(Long userId, List<Long> courseIds) {
        //1.通过feign远程调用课程服务 得到课程信息
        List<CourseSimpleInfoDTO> infoList = courseClient.getSimpleInfoList(courseIds);

        //封装po实体类，填充过期时间
        List<LearningLesson> list=new ArrayList<>();
        for (CourseSimpleInfoDTO dto : infoList) {
            LearningLesson learningLesson = new LearningLesson();
            learningLesson.setUserId(userId);
            learningLesson.setCourseId(dto.getId());
            Integer validDuration = dto.getValidDuration();//课程有效期，单位月
            if(validDuration!=null){
                LocalDateTime now = LocalDateTime.now();
                learningLesson.setCreateTime(now);
                learningLesson.setExpireTime(now.plusMonths(validDuration));
            }
            list.add(learningLesson);
        }
        //批量保存
        this.saveBatch(list);
    }


    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.分页查询
        // select * from learning_lesson where user_id = #{userId} order by latest_learn_time limit 0, 5
        Page<LearningLesson> page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId) // where user_id = #{userId}
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.查询课程信息
        Map<Long, CourseSimpleInfoDTO> cMap = queryCourseSimpleInfoList(records);

        // 4.封装VO返回
        List<LearningLessonVO> list = new ArrayList<>(records.size());
        // 4.1.循环遍历，把LearningLesson转为VO
        for (LearningLesson r : records) {
            // 4.2.拷贝基础属性到vo
            LearningLessonVO vo = BeanUtils.copyBean(r, LearningLessonVO.class);
            // 4.3.获取课程信息，填充到vo
            CourseSimpleInfoDTO cInfo = cMap.get(r.getCourseId());
            vo.setCourseName(cInfo.getName());
            vo.setCourseCoverUrl(cInfo.getCoverUrl());
            vo.setSections(cInfo.getSectionNum());
            list.add(vo);
        }
        return PageDTO.of(page, list);
    }

    private Map<Long, CourseSimpleInfoDTO> queryCourseSimpleInfoList(List<LearningLesson> records) {
        // 3.1.获取课程id
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        // 3.2.查询课程信息
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);
        if (CollUtils.isEmpty(cInfoList)) {
            // 课程不存在，无法添加
            throw new BadRequestException("课程信息不存在！");
        }
        // 3.3.把课程集合处理成Map，key是courseId，值是course本身
        Map<Long, CourseSimpleInfoDTO> cMap = cInfoList.stream()
                .collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        return cMap;
    }

    @Override
    public LearningLessonVO queryMyCurrentLesson() {
        // 1.获取当前登录的用户
        Long userId = UserContext.getUser();
        // 2.查询正在学习的课程 select * from xx where user_id = #{userId} AND status = 1 order by latest_learn_time limit 1
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")
                .one();
        if (lesson == null) {
            return null;
           }
        // 3.拷贝PO基础属性到VO
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        // 4.查询课程信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cInfo == null) {
            throw new BadRequestException("课程不存在");
        }
        vo.setCourseName(cInfo.getName());
        vo.setCourseCoverUrl(cInfo.getCoverUrl());
        vo.setSections(cInfo.getSectionNum());
        // 5.统计课表中的课程数量 select count(1) from xxx where user_id = #{userId}
        Integer courseAmount = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .count();
        vo.setCourseAmount(courseAmount);
        // 6.查询小节信息
        List<CataSimpleInfoDTO> cataInfos =
                catalogueClient.batchQueryCatalogue(CollUtils.singletonList(lesson.getLatestSectionId()));
        if (!CollUtils.isEmpty(cataInfos)) {
            CataSimpleInfoDTO cataInfo = cataInfos.get(0);
            vo.setLatestSectionName(cataInfo.getName());
            vo.setLatestSectionIndex(cataInfo.getCIndex());
        }
        return vo;
    }

    @Override
    public Long isLessonValid(Long courseId) {
        //获取当前用户id
        Long user = UserContext.getUser();
        //查询课表learning_lesson,
        LearningLesson lesson = this.lambdaQuery().eq(LearningLesson::getUserId, user).
                eq(LearningLesson::getCourseId, courseId).one();
        if(lesson==null){
            return null;
        }
        //交易课程有限状态
        LocalDateTime expireTime = lesson.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        if(now.isAfter(expireTime)){
            return null;
        }
        return lesson.getId();
    }

    @Override
    public LearningLessonVO queryLessonByCourseId(Long courseId) {
        //获取当前用户id
        Long user = UserContext.getUser();
        //查询课表learning_lesson,
        LearningLesson lesson = this.lambdaQuery().eq(LearningLesson::getUserId, user).
                eq(LearningLesson::getCourseId, courseId).one();
        if(lesson==null){
            return null;
        }
        //po转vo
        return BeanUtils.copyBean(lesson, LearningLessonVO.class);
    }

    @Override
    public void createLearningPlan(Long courseId, Integer freq) {
        // 1.获取当前登录的用户
        Long userId = UserContext.getUser();
        // 2.查询课表中的指定课程有关的数据
        LearningLesson lesson = this.lambdaQuery().eq(LearningLesson::getUserId,userId).eq(LearningLesson::getCourseId,courseId).one();
        AssertUtils.isNotNull(lesson, "课程信息不存在！");
        // 3.修改数据
//        LearningLesson l = new LearningLesson();
//        l.setId(lesson.getId());
//        l.setWeekFreq(freq);
//        if(lesson.getPlanStatus() == PlanStatus.NO_PLAN) {
//            l.setPlanStatus(PlanStatus.PLAN_RUNNING);
//        }
//        updateById(l);
        this.lambdaUpdate()
                .set(LearningLesson::getWeekFreq,freq)
                .set(LearningLesson::getPlanStatus,PlanStatus.PLAN_RUNNING)
                .eq(LearningLesson::getId,lesson.getId())
                .update();
    }

    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        LearningPlanPageVO planPageVO = new LearningPlanPageVO();
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        //todo 2.查询积分
        //3.查询本周学习计划总数据
        QueryWrapper<LearningLesson> wrapper = new QueryWrapper<>();
        wrapper.select("sum(week_freq) as plansTotal");//查询哪些列
        wrapper.eq("user_id",userId);
        wrapper.in("status",LessonStatus.NOT_BEGIN,LessonStatus.LEARNING);
        wrapper.eq("plan_status",PlanStatus.PLAN_RUNNING);
        Map<String, Object> map = this.getMap(wrapper);
        if(map!=null && map.get("plansTotal")!=null){
            planPageVO.setWeekTotalPlan(Integer.valueOf(map.get("plansTotal").toString()));
        }

        //4.查询本周 实际 已学习的计划总数
        LocalDate now = LocalDate.now();
        LocalDateTime weekBeginTime = DateUtils.getWeekBeginTime(now);
        LocalDateTime weekEndTime = DateUtils.getWeekEndTime(now);
        Integer weekFinishedPlanNum= recordMapper.selectCount(Wrappers.<LearningRecord>lambdaQuery().eq(LearningRecord::getUserId,userId)
                .eq(LearningRecord::getFinished,true)
                .between(LearningRecord::getFinishTime,weekBeginTime,weekEndTime));

        //5.查询课程数据
        Page<LearningLesson> page = this.lambdaQuery().eq(LearningLesson::getUserId, userId)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if(CollUtils.isEmpty(records)){
            planPageVO.setTotal(0L);
            planPageVO.setPages(0L);
            planPageVO.setList(CollUtils.emptyList());
            return planPageVO;
        }
        Set<Long> set = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        //6.远程调用课程服务 获取课程信息
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(set);
        Map<Long, CourseSimpleInfoDTO> cInfoMap = simpleInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        if(CollUtils.isEmpty(cInfoMap)){
            throw new BizIllegalException("课程不存在");
        }
        //7.查询学习记录表 本周 当前用户下 每一门课下 已学习的小节数量
        QueryWrapper<LearningRecord> rwrapper = new QueryWrapper<>();
        rwrapper.select("lesson_id as lessonId","count(*) as userId");
        rwrapper.eq("user_id",userId);
        rwrapper.eq("finished",true);
        rwrapper.between("finish_time",weekBeginTime,weekEndTime);
        rwrapper.groupBy("lesson_id");
        List<LearningRecord> list = recordMapper.selectList(rwrapper);
        //map中的key是 lessonId，value是当前用户对下已学习的小节数量
        Map<Long, Long> courseWeekFinishNumMap = list.stream().collect(Collectors.toMap(LearningRecord::getLessonId, c -> c.getUserId()));
        //8.封装vo返回
        planPageVO.setWeekFinished(weekFinishedPlanNum);
        List<LearningPlanVO> voList=new ArrayList<>();
        for (LearningLesson record : records) {
            LearningPlanVO learningPlanVO = BeanUtils.copyBean(record, LearningPlanVO.class);
            CourseSimpleInfoDTO courseSimpleInfoDTO = cInfoMap.get(record.getCourseId());
            if(courseSimpleInfoDTO!=null){
                learningPlanVO.setCourseName(courseSimpleInfoDTO.getName());
                learningPlanVO.setSections(courseSimpleInfoDTO.getSectionNum());
            }
//            Long aLong = courseWeekFinishNumMap.get(record.getId());
//            if(aLong!=null){
//                learningPlanVO.setWeekLearnedSections(aLong.intValue());//设置本周已学习章节数
//            }else {
//                learningPlanVO.setWeekLearnedSections(0);
//            }
            learningPlanVO.setWeekLearnedSections(courseWeekFinishNumMap.getOrDefault(record.getId(),0L).intValue());
            voList.add(learningPlanVO);
        }
        planPageVO.setList(voList);
        planPageVO.setTotal(page.getTotal());
        planPageVO.setPages(page.getPages());
        return planPageVO;
    }



}
