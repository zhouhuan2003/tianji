package com.tianji.learning.service.impl;

import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisContstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tianji.learning.constants.LearningContstants.POINTS_BOARD_TABLE_PREFIX;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author 周欢
 * @since 2023-09-24
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;

    @Override
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        //1.获取当前登录用户id
        Long userId = UserContext.getUser();
        //2.判断是查当前赛季还是历史赛季
        boolean isCurrent=query.getSeason()==null || query.getSeason()==0;//该直段为true 则查当前赛季 redis
        LocalDate localDate = LocalDate.now();
        String format = localDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key= RedisContstants.POINTS_BOARD_KEY_PREFIX +format;
        Long season = query.getSeason();
        //3.查询我的排名和积分
        PointsBoard board=isCurrent ? queryMyCurrentBoard(key) : queryMyHistoryBoard(season);
//        PointsBoard board=null;
//        if(isCurrent){
//             board= queryMyCurrentBoard(key);
//        }else {
//             board= queryMyHistoryBoard(season);
//        }
        //4.分页查询赛季列表
        List<PointsBoard> list= isCurrent?
                queryCurrentBoard(key,query.getPageNo(),query.getPageSize())
                : queryHistoryBoard(query);

        //封装用户id集合，远程调用用户服务 ，获取用户信息
        Set<Long> uids = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        if(CollUtils.isEmpty(userDTOS)){
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, String> userMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, UserDTO::getName));
        //封装vo返回
        PointsBoardVO vo = new PointsBoardVO();
        assert board != null;
        vo.setRank(board.getRank());
        vo.setPoints(board.getPoints());

        List<PointsBoardItemVO> voList=new ArrayList<>();
        for (PointsBoard pointsBoard : list) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            itemVO.setName(userMap.get(pointsBoard.getUserId()));
            itemVO.setPoints(pointsBoard.getPoints());
            itemVO.setRank(pointsBoard.getRank());
            voList.add(itemVO);
        }
        vo.setBoardList(voList);
        return vo;
    }

    @Override
    public void createPointsBoardTableBySeason(Integer season) {
        getBaseMapper().createPointsBoardTable(POINTS_BOARD_TABLE_PREFIX + season);
    }

    //查询历史赛季列表 db
    private List<PointsBoard> queryHistoryBoard(PointsBoardQuery query) {
        return null;
    }

    //查询当前赛季的排行榜列表 查redis
    private List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize) {
        //1.计算start 和 end 分页值
        int start = (pageNo - 1) * pageSize;
        int end =start+pageSize-1;
        //2.利用zrevrange命令 会按分数倒序 分页查询
        Set<ZSetOperations.TypedTuple<String>> set = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if(CollUtils.isEmpty(set)){
            return CollUtils.emptyList();
        }
        //3. 封装结果返回
        int rank=start+1;
        List<PointsBoard>  list=new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> typedTuple : set) {
            String value = typedTuple.getValue();//用户id
            Double score = typedTuple.getScore();//总积分值
            if(StringUtils.isBlank(value)|| score==null){
                continue;
            }
            PointsBoard board = new PointsBoard();
            board.setUserId(Long.valueOf(value));
            board.setPoints(score.intValue());
            board.setRank(rank++);
            list.add(board);
        }
        return list;
    }

    //查询历史赛季我的积分排名 db
    private PointsBoard queryMyHistoryBoard(Long season) {
        //todo
        return null;
    }

    //查询当前赛季我的积分 redis
    private PointsBoard queryMyCurrentBoard(String key) {
        Long userId = UserContext.getUser();
        //获取分值
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        //获取排名 从0开始 得+1
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        PointsBoard board = new PointsBoard();
        board.setRank(rank==null? 0 :rank.intValue()+1);
        board.setPoints(score==null? 0 : score.intValue());
        return board;
    }
}
