package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author kdm
 * @since 2023-09-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final InteractionReplyMapper replyMapper;
    private final UserClient userClient;
    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;

    @Override
    public void saveQuestion(QuestionFormDTO dto) {
        //1.获取当前用户登录id
        Long user = UserContext.getUser();
        //2.dto转po
        InteractionQuestion interactionQuestion = BeanUtils.copyBean(dto, InteractionQuestion.class);
        interactionQuestion.setUserId(user);
        //3.
        this.save(interactionQuestion);
    }

    @Override
    public void updateQuestion(QuestionFormDTO dto, Long id) {
        if(StringUtils.isBlank(dto.getTitle()) || StringUtils.isBlank(dto.getDescription()) || dto.getAnonymity()==null){
            throw new BadRequestException("非法参数");
        }
        //校验id
        InteractionQuestion question = this.getById(id);
        if(question==null){
            throw new BadRequestException("非法参数");
        }
        //修改只能修改自己的互动问题
        Long user = UserContext.getUser();
        if(!question.getUserId().equals(user)){
            throw new BadRequestException("不能修改别人的问题");
        }

        //dto转po
        question.setTitle(dto.getTitle());
        question.setDescription(dto.getDescription());
        question.setAnonymity(dto.getAnonymity());
        //修改
        this.updateById(question);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        //校验
        if(query.getCourseId()==null){
            throw new BadRequestException("courseId为空");
        }
        //查询用户id
        Long userId = UserContext.getUser();
        //分页查询课程互动问题表 条件：courseId onlyMine为true 才会加userId 小节id不会为空 hidden为false 按提问时间倒叙
        Page<InteractionQuestion> page = this.lambdaQuery()
//                .select(InteractionQuestion.class, tableFieldInfo -> {
////                        tableFieldInfo.getProperty();//获取InteractionQuestion实体类的属性名称
//                    return !tableFieldInfo.getProperty().equals("description");//指定返回不查询的字段
//                })
                .select(InteractionQuestion.class, tableFieldInfo -> !tableFieldInfo.getProperty().equals("description"))
                .eq(InteractionQuestion::getCourseId, query.getCourseId())
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, userId)
                .eq(query.getSectionId() != null, InteractionQuestion::getSectionId, query.getSectionId())
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if(CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        Set<Long> longList=new HashSet<>();
        Set<Long> userIds=new HashSet<>();
        for (InteractionQuestion record : records) {
            if(!record.getAnonymity()){
                userIds.add(record.getUserId());
            }
            if (record.getLatestAnswerId() != null) {
                longList.add(record.getLatestAnswerId());
            }
        }
//        List<Long> longList = records.stream()
//                .map(InteractionQuestion::getLatestAnswerId)
//                .filter(latestAnswerId -> latestAnswerId !=null)
//                .collect(Collectors.toList());
        //根据最新回答id 批量查询回答信息
        Map<Long,InteractionReply> replyMap=new HashMap<>();
        if(CollUtils.isNotEmpty(longList)){
//            List<InteractionReply> interactionReplies = replyService.listByIds(longList);
            List<InteractionReply> interactionReplies = replyMapper.selectList(Wrappers.<InteractionReply>lambdaQuery()
                    .in(InteractionReply::getId, longList)
                    .eq(InteractionReply::getHidden, false));
            for (InteractionReply reply : interactionReplies) {
                if(!reply.getAnonymity()){
                    userIds.add(reply.getUserId());//将最新回答的用户id存入ids
                }
                replyMap.put(reply.getId(),reply);
            }
//            replyMap=interactionReplies.stream().collect(Collectors.toMap(InteractionReply::getId,c ->c));
        }

        //远程调用用户服务，获取用户信息 批量
        List<UserDTO> userByIds = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userDTOMap = userByIds.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));

        //封装vo返回
        List<QuestionVO> list=new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionVO questionVO = BeanUtils.copyBean(record, QuestionVO.class);
            if(!record.getAnonymity()){
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                if(userDTO!=null){
                    questionVO.setUserName(userDTO.getName());
                    questionVO.setUserIcon(userDTO.getIcon());
                }
            }

            InteractionReply reply = replyMap.get(record.getId());
            if(reply!=null){
                if(!reply.getAnonymity()){
                    UserDTO userDTO = userDTOMap.get(reply.getUserId());
                    if(userDTO!=null){
                        questionVO.setLatestReplyContent(userDTO.getName());
                    }
                }
                questionVO.setLatestReplyContent(reply.getContent());
            }
            list.add(questionVO);
        }
        return PageDTO.of(page,list);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        //校验
        if(id==null){
            throw new BadRequestException("非法参数");
        }
        //查询互动问题表，按主键查
        InteractionQuestion question = this.getById(id);
        if(question==null){
            throw new BadRequestException("问题不存在");
        }
        //如果该问题管理员设置了隐藏 返回null
        if(question.getHidden()){
            return null;
        }
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);

        //如果问题是匿名提问，不用查询提问者昵称和头像
        if(!question.getAnonymity()){
            //调用用户服务
            UserDTO userDTO = userClient.queryUserById(question.getUserId());
            if(userDTO!=null){
                vo.setUserName(userDTO.getName());
                vo.setUserIcon(userDTO.getIcon());
            }
        }
        //封装vo返回
        return vo;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionAdmin(QuestionAdminPageQuery query) {
        //如果用户传了课程的名称参数，则从es中获取该名称对应的课程id
        String courseName = query.getCourseName();
        List<Long> cids=null;
        if(StringUtils.isNotBlank(courseName)){
            cids = searchClient.queryCoursesIdByName(courseName);
            if(CollUtils.isEmpty(cids)){
                return PageDTO.empty(0L,0L);
            }
        }
        //查询互动问题 条件前端传条件就添加，不传就查所有 分页 排序按提问时间排序
        Page<InteractionQuestion> page = this.lambdaQuery()
                .in(CollUtils.isNotEmpty(cids),InteractionQuestion::getCourseId, cids)
                .eq(query.getStatus() != null, InteractionQuestion::getStatus, query.getStatus())
                .between(query.getBeginTime() != null && query.getEndTime() != null
                        , InteractionQuestion::getCreateTime, query.getBeginTime(), query.getEndTime())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if(CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        Set<Long> uids=new HashSet<>();//用户id集合
        Set<Long> courseids=new HashSet<>();//课程id集合
        Set<Long> chapterAndSectionIds=new HashSet<>();//章和节的id集合

        for (InteractionQuestion record : records) {
            uids.add(record.getUserId());
            courseids.add(record.getCourseId());
            chapterAndSectionIds.add(record.getChapterId());
            chapterAndSectionIds.add(record.getSectionId());
        }
        //远程调用用户服务，获取用户信息
        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        if(userDTOS==null){
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, UserDTO> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));
        //远程调用课程服务，获取课程信息
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseids);
        if(cinfos==null){
            throw new BizIllegalException("课程不存在");
        }
        Map<Long, CourseSimpleInfoDTO> cinfoMap = cinfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        //远程调用课程服务，获取章节信息
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(chapterAndSectionIds);
        Map<Long, String> cataInfoMap = cataSimpleInfoDTOS.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));

        //po转vo
        List<QuestionAdminVO> list=new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionAdminVO adminVO = BeanUtils.copyBean(record, QuestionAdminVO.class);
            UserDTO userDTO = userDTOMap.get(record.getUserId());
            if(userDTO!=null){
                adminVO.setUserName(userDTO.getName());
            }
            CourseSimpleInfoDTO cinfoDTO = cinfoMap.get(record.getCourseId());
            if(cinfoDTO!=null){
                adminVO.setCourseName(cinfoDTO.getName());
                List<Long> categoryIds = cinfoDTO.getCategoryIds();
                //获取分类信息
                String categoryNames = categoryCache.getCategoryNames(List.of());
                adminVO.setCategoryName(categoryNames);//三级分类，拼接字段
            }
            adminVO.setChapterName(cataInfoMap.get(record.getChapterId())==null ? "":cataInfoMap.get(record.getChapterId()));//章
            adminVO.setSectionName(cataInfoMap.get(record.getSectionId()));//节
            list.add(adminVO);
        }
        return PageDTO.of(page,list);
    }

    @Override
    public void deleteQuestionById(Long id) {
        InteractionQuestion question = this.getById(id);
        if(question==null){
            return;
        }
        Long userId = UserContext.getUser();
        if(!userId.equals(question.getUserId())){
            throw new BizIllegalException("不能删除别人的回答");
        }
        this.removeById(id);
        replyMapper.delete(Wrappers.<InteractionReply>lambdaQuery().eq(InteractionReply::getQuestionId,question.getId()));
    }

    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        // 1.根据id查询问题
        InteractionQuestion question = getById(id);
        if (question == null) {
            return null;
        }
        // 2.转PO为VO
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);
        // 3.查询提问者信息
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        // 4.查询课程信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(
                question.getCourseId(), false, true);
        if (cInfo != null) {
            // 4.1.课程名称信息
            vo.setCourseName(cInfo.getName());
            // 4.2.分类信息
            vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds()));
            // 4.3.教师信息
            List<Long> teacherIds = cInfo.getTeacherIds();
            List<UserDTO> teachers = userClient.queryUserByIds(teacherIds);
            if(CollUtils.isNotEmpty(teachers)) {
                vo.setTeacherName(teachers.stream()
                        .map(UserDTO::getName).collect(Collectors.joining("/")));
            }
        }
        // 5.查询章节信息
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(
                List.of(question.getChapterId(), question.getSectionId()));
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (CollUtils.isNotEmpty(catas)) {
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }
        vo.setChapterName(cataMap.getOrDefault(question.getChapterId(), ""));
        vo.setSectionName(cataMap.getOrDefault(question.getSectionId(), ""));
        // 6.封装VO
        return vo;
    }

    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        // 1.更新问题
        InteractionQuestion question = new InteractionQuestion();
        question.setId(id);
        question.setHidden(hidden);
        updateById(question);
    }

//    @Override
    public void updateQuestion(Long id, QuestionFormDTO questionDTO) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.查询当前问题
        InteractionQuestion q = getById(id);
        if (q == null) {
            throw new BadRequestException("问题不存在");
        }
        // 3.判断是否是当前用户的问题
        if (!q.getUserId().equals(userId)) {
            // 不是，抛出异常
            throw new BadRequestException("无权修改他人的问题");
        }
        // 4.修改问题
        InteractionQuestion question = BeanUtils.toBean(questionDTO, InteractionQuestion.class);
        question.setId(id);
        updateById(question);
    }
}
